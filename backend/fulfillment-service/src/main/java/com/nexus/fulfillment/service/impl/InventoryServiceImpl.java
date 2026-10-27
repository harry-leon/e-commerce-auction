package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationItemResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.event.InventoryCheckedEvent;
import com.nexus.fulfillment.event.InventoryCommittedEvent;
import com.nexus.fulfillment.event.InventoryReleasedEvent;
import com.nexus.fulfillment.event.InventoryReservationExpiredEvent;
import com.nexus.fulfillment.event.InventoryReservedEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ quản trị tồn kho và giữ hàng Checkout với Concurrency Control (SRS §3.6.1).
 *
 * Chức năng:
 * - Quản lý 4 trạng thái tồn kho chính xác theo SKU và kho hàng (total_qty, available_qty, reserved_qty, unavailable_qty).
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với Pessimistic Locking để chống Overselling (ALLOW_OVERSELL = false).
 * - Tự động giải phóng các phiếu giữ hàng quá 30 phút (INVENTORY_RESERVATION_TIMEOUT_MINUTES = 30).
 * - Ghi lại mọi biến động vào Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 * - Bắn các sự kiện miền (InventoryChecked, InventoryReserved, InventoryReleased, InventoryReservationExpired, InventoryCommitted).
 *
 * Quy tắc và lý do:
 * - Khi giữ hàng nhiều SKU cùng lúc, danh sách được sắp xếp tăng dần theo skuId trước khi khóa dòng dữ liệu để tránh deadlock.
 * - Sổ cái tồn kho chỉ thêm mới (INSERT) và đọc (SELECT), tuyệt đối cấm sửa đổi hoặc xóa.
 */
@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryLedgerRepository ledgerRepository;
    private final WarehouseRepository warehouseRepository;
    private final FulfillmentProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InventoryLedgerRepository ledgerRepository,
            WarehouseRepository warehouseRepository,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher
    ) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.warehouseRepository = warehouseRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Tra cứu thông tin 4 trạng thái tồn kho của SKU tại một kho hàng cụ thể (hoặc kho mặc định WH_001).
     *
     * Luồng xử lý: Xác định kho → tìm bản ghi tồn kho → trả về DTO chứa total_qty, available_qty, reserved_qty, unavailable_qty.
     */
    @Override
    @Transactional(readOnly = true)
    public InventoryStockResponse getStockBySku(UUID skuId, UUID warehouseId) {
        Warehouse warehouse = resolveWarehouse(warehouseId);
        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId())
                .orElse(null);

        if (inventory == null) {
            return new InventoryStockResponse(
                    skuId,
                    warehouse.getId(),
                    warehouse.getCode(),
                    0,
                    0,
                    0,
                    0
            );
        }

        return toStockResponse(inventory);
    }

    /**
     * Tra cứu danh sách tồn kho của SKU trên tất cả các kho đang lưu trữ.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryStockResponse> getAllStockBySku(UUID skuId) {
        List<Inventory> list = inventoryRepository.findBySkuId(skuId);
        List<InventoryStockResponse> result = new ArrayList<>();
        for (Inventory inv : list) {
            result.add(toStockResponse(inv));
        }
        return result;
    }

    /**
     * Kiểm tra nhanh số lượng tồn khả dụng cho danh sách SKU (dùng cho Cart và Checkout validation).
     * Bắn sự kiện InventoryCheckedEvent cho từng SKU được kiểm tra.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SkuAvailabilityResponse> checkAvailability(SkuAvailabilityCheckRequest request) {
        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        List<SkuAvailabilityResponse> result = new ArrayList<>();

        for (UUID skuId : request.skuIds()) {
            Inventory inv = inventoryRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId()).orElse(null);
            int available = (inv != null) ? inv.getAvailableQty() : 0;
            result.add(new SkuAvailabilityResponse(skuId, warehouse.getId(), available, available > 0));

            // Bắn sự kiện InventoryCheckedEvent theo SRS §2.8
            eventPublisher.publishEvent(InventoryCheckedEvent.of(skuId, warehouse.getId(), available));
        }

        return result;
    }

    /**
     * Khóa giữ hàng tồn kho khi người dùng Checkout (Atomic Reservation).
     *
     * Đầu vào: CreateReservationRequest chứa refType (ORDER/AUCTION), refId (order_id), danh sách SKU + số lượng.
     * Kết quả: ReservationResponse chứa danh sách phiếu giữ hàng PENDING cùng thời điểm hết hạn.
     *
     * Luồng xử lý:
     * 1. Sắp xếp danh sách items tăng dần theo skuId để triệt tiêu nguy cơ Deadlock khi khóa nhiều dòng DB.
     * 2. Khóa ghi (SELECT FOR UPDATE) từng dòng tồn kho.
     * 3. Kiểm tra available_qty: Nếu < số lượng yêu cầu và allowOversell = false → ném INVENTORY_INSUFFICIENT.
     * 4. Tăng reserved_qty của tồn kho (làm giảm available_qty tương ứng).
     * 5. Tạo bản ghi InventoryReservation với trạng thái PENDING và expires_at = now + 30 phút.
     * 6. Ghi nhận biến động RESERVE vào Sổ cái tồn kho bất biến (inventory_ledger).
     * 7. Bắn sự kiện miền InventoryReservedEvent cho từng mặt hàng được giữ.
     */
    @Override
    @Transactional
    public ReservationResponse reserveStock(CreateReservationRequest request) {
        int timeoutMinutes = (request.timeoutMinutes() != null && request.timeoutMinutes() > 0)
                ? request.timeoutMinutes()
                : properties.getReservationTimeoutMinutes();
        Instant expiresAt = Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES);

        // Sắp xếp items theo skuId để đảm bảo thứ tự khóa dòng nhất quán, loại bỏ Deadlock giữa các luồng
        List<ReservationItemRequest> sortedItems = new ArrayList<>(request.items());
        sortedItems.sort(Comparator.comparing(ReservationItemRequest::skuId));

        List<ReservationItemResponse> itemResponses = new ArrayList<>();

        for (ReservationItemRequest item : sortedItems) {
            Warehouse warehouse = resolveWarehouse(item.warehouseId());

            // Áp dụng Pessimistic Write Lock (SELECT ... FOR UPDATE) trên bản ghi tồn kho SKU
            Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(item.skuId(), warehouse.getId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INVENTORY_NOT_FOUND,
                            "Không tìm thấy bản ghi tồn kho cho SKU " + item.skuId() + " tại kho " + warehouse.getCode()
                    ));

            int available = inventory.getAvailableQty();
            int requestedQty = item.quantity();

            // Kiểm tra quy tắc chống bán vượt tồn kho (ALLOW_OVERSELL = false)
            if (available < requestedQty && !Boolean.TRUE.equals(properties.getAllowOversell())) {
                log.warn("Từ chối giữ kho cho SKU {}: yêu cầu={}, khả dụng={}", item.skuId(), requestedQty, available);
                throw new BusinessException(
                        ErrorCode.INVENTORY_INSUFFICIENT,
                        "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua: SKU "
                                + item.skuId() + " yêu cầu " + requestedQty + ", hiện khả dụng " + available
                );
            }

            // Thực hiện tăng số lượng đang giữ (reserved_qty)
            inventory.setReservedQty(inventory.getReservedQty() + requestedQty);
            inventoryRepository.save(inventory);

            // Tạo phiếu giữ kho (Inventory Reservation)
            UUID reservationId = UUID.randomUUID();
            InventoryReservation reservation = new InventoryReservation(
                    reservationId,
                    inventory,
                    requestedQty,
                    request.refType(),
                    request.refId(),
                    ReservationStatus.PENDING,
                    expiresAt
            );
            reservationRepository.save(reservation);

            // Ghi nhận vào Sổ cái tồn kho bất biến (Immutable Inventory Ledger)
            InventoryLedger ledger = new InventoryLedger(
                    UUID.randomUUID(),
                    inventory,
                    LedgerMovementType.RESERVE,
                    requestedQty,
                    request.refType().name(),
                    request.refId(),
                    Instant.now()
            );
            ledgerRepository.save(ledger);

            // Bắn sự kiện miền InventoryReservedEvent theo SRS §2.8
            eventPublisher.publishEvent(InventoryReservedEvent.of(
                    reservationId,
                    item.skuId(),
                    warehouse.getId(),
                    requestedQty,
                    request.refType(),
                    request.refId(),
                    expiresAt
            ));

            itemResponses.add(new ReservationItemResponse(
                    reservationId,
                    item.skuId(),
                    warehouse.getId(),
                    requestedQty,
                    ReservationStatus.PENDING,
                    expiresAt
            ));
        }

        return new ReservationResponse(
                request.refType(),
                request.refId(),
                ReservationStatus.PENDING,
                itemResponses,
                expiresAt
        );
    }

    /**
     * Xác nhận đơn hàng đã thanh toán thành công (Commit Reservation).
     *
     * Luồng xử lý:
     * 1. Khóa phiếu giữ hàng theo reservationId.
     * 2. Kiểm tra trạng thái: phải là PENDING.
     * 3. Khóa bản ghi tồn kho: trừ cả reserved_qty và total_qty tương ứng (hàng thực sự rời khỏi kho).
     * 4. Cập nhật trạng thái phiếu thành CONFIRMED.
     * 5. Ghi sổ cái OUTBOUND.
     * 6. Bắn sự kiện InventoryCommittedEvent.
     */
    @Override
    @Transactional
    public ReservationResponse confirmReservation(UUID reservationId) {
        InventoryReservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_RESERVATION_NOT_FOUND,
                        "Không tìm thấy phiếu giữ hàng có mã " + reservationId
                ));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_ALREADY_COMMITTED,
                    "Phiếu giữ hàng đã được xác nhận xuất kho trước đó"
            );
        }

        if (reservation.getStatus() == ReservationStatus.EXPIRED) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Phiếu giữ hàng đã quá hạn và bị hủy"
            );
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Phiếu giữ hàng không ở trạng thái hợp lệ để xác nhận: " + reservation.getStatus()
            );
        }

        Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getInventory().getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho liên kết với phiếu giữ hàng"
                ));

        int qty = reservation.getQuantity();
        inventory.setReservedQty(Math.max(0, inventory.getReservedQty() - qty));
        inventory.setTotalQty(Math.max(0, inventory.getTotalQty() - qty));
        inventoryRepository.save(inventory);

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Ghi nhận biến động xuất kho OUTBOUND vào sổ cái
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.OUTBOUND,
                qty,
                reservation.getRefType().name(),
                reservation.getRefId(),
                Instant.now()
        );
        ledgerRepository.save(ledger);

        // Bắn sự kiện InventoryCommittedEvent
        eventPublisher.publishEvent(InventoryCommittedEvent.of(
                reservation.getId(),
                inventory.getSkuId(),
                inventory.getWarehouse().getId(),
                qty,
                reservation.getRefType(),
                reservation.getRefId()
        ));

        ReservationItemResponse item = new ReservationItemResponse(
                reservation.getId(),
                inventory.getSkuId(),
                inventory.getWarehouse().getId(),
                qty,
                ReservationStatus.CONFIRMED,
                reservation.getExpiresAt()
        );

        return new ReservationResponse(
                reservation.getRefType(),
                reservation.getRefId(),
                ReservationStatus.CONFIRMED,
                List.of(item),
                reservation.getExpiresAt()
        );
    }

    /**
     * Giải phóng một phiếu giữ hàng cụ thể (do người dùng hủy giỏ hoặc thanh toán thất bại).
     *
     * Luồng xử lý:
     * 1. Khóa phiếu và kiểm tra trạng thái PENDING.
     * 2. Khóa tồn kho, giảm reserved_qty để hoàn trả lại available_qty.
     * 3. Chuyển trạng thái phiếu sang RELEASED.
     * 4. Ghi nhận RELEASE vào sổ cái bất biến.
     * 5. Bắn sự kiện InventoryReleasedEvent.
     */
    @Override
    @Transactional
    public void releaseReservation(UUID reservationId, String reason) {
        InventoryReservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_RESERVATION_NOT_FOUND,
                        "Không tìm thấy phiếu giữ hàng có mã " + reservationId
                ));

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.info("Phiếu giữ hàng {} đã ở trạng thái {}, bỏ qua giải phóng", reservationId, reservation.getStatus());
            return;
        }

        releaseSingleReservation(reservation, ReservationStatus.RELEASED, (reason != null) ? reason : "MANUAL_RELEASE");
    }

    /**
     * Giải phóng toàn bộ các phiếu giữ hàng gắn với mã tham chiếu đơn hàng/phiên đấu giá (ref_id).
     */
    @Override
    @Transactional
    public void releaseReservationsByRef(ReleaseReservationRequest request) {
        List<InventoryReservation> list = reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                request.refType(),
                request.refId(),
                ReservationStatus.PENDING
        );

        String reason = (request.reason() != null) ? request.reason() : "REF_RELEASE";
        for (InventoryReservation res : list) {
            releaseSingleReservation(res, ReservationStatus.RELEASED, reason);
        }
    }

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn 30 phút.
     *
     * Luồng xử lý:
     * 1. Tìm tất cả các phiếu PENDING có expires_at <= current_time.
     * 2. Với mỗi phiếu: khóa bản ghi, chuyển status = EXPIRED, hoàn trả reserved_qty về available_qty.
     * 3. Ghi sổ cái RELEASE.
     * 4. Bắn sự kiện InventoryReservationExpiredEvent theo SRS §2.8.
     */
    @Override
    @Transactional
    public int autoReleaseExpiredReservations() {
        Instant now = Instant.now();
        List<InventoryReservation> expiredList = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING,
                now
        );

        if (expiredList.isEmpty()) {
            return 0;
        }

        log.info("Phát hiện {} phiếu giữ hàng quá hạn 30 phút, tiến hành tự động hoàn kho...", expiredList.size());
        int count = 0;

        for (InventoryReservation reservation : expiredList) {
            try {
                // Khóa lại dòng phiếu để tránh xung đột với request thanh toán đồng thời
                InventoryReservation lockedRes = reservationRepository.findByIdForUpdate(reservation.getId())
                        .orElse(null);

                if (lockedRes != null && lockedRes.getStatus() == ReservationStatus.PENDING) {
                    releaseSingleReservation(lockedRes, ReservationStatus.EXPIRED, "TIMEOUT_EXPIRED");
                    count++;
                }
            } catch (Exception ex) {
                log.error("Lỗi khi tự động hoàn kho phiếu giữ hàng {}: {}", reservation.getId(), ex.getMessage(), ex);
            }
        }

        log.info("Hoàn tất tự động giải phóng {} phiếu giữ hàng quá hạn", count);
        return count;
    }

    /**
     * Nhập thêm hàng hóa vào kho cho SKU và ghi sổ cái INBOUND.
     */
    @Override
    @Transactional
    public InventoryStockResponse inboundStock(StockInboundRequest request) {
        Warehouse warehouse = resolveWarehouse(request.warehouseId());

        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(request.skuId(), warehouse.getId())
                .orElseGet(() -> new Inventory(
                        UUID.randomUUID(),
                        request.skuId(),
                        warehouse,
                        0,
                        0,
                        0
                ));

        inventory.setTotalQty(inventory.getTotalQty() + request.quantity());
        inventory = inventoryRepository.save(inventory);

        // Ghi sổ cái INBOUND
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.INBOUND,
                request.quantity(),
                "INBOUND",
                request.refId(),
                Instant.now()
        );
        ledgerRepository.save(ledger);

        return toStockResponse(inventory);
    }

    /**
     * Điều chỉnh kiểm kê tồn kho thực tế và ghi sổ cái ADJUSTMENT.
     */
    @Override
    @Transactional
    public InventoryStockResponse adjustStock(StockAdjustmentRequest request) {
        if (request.deltaQty() == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Số lượng điều chỉnh deltaQty phải khác 0");
        }

        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(request.skuId(), warehouse.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho để điều chỉnh"
                ));

        int newTotal = inventory.getTotalQty() + request.deltaQty();
        if (newTotal < (inventory.getReservedQty() + inventory.getUnavailableQty())) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Không thể điều chỉnh tồn kho vì tổng số lượng mới (" + newTotal
                            + ") nhỏ hơn số lượng đang giữ và không khả dụng ("
                            + (inventory.getReservedQty() + inventory.getUnavailableQty()) + ")"
            );
        }

        inventory.setTotalQty(newTotal);
        inventory = inventoryRepository.save(inventory);

        // Ghi sổ cái ADJUSTMENT
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.ADJUSTMENT,
                request.deltaQty(),
                "ADJUSTMENT",
                request.refId(),
                Instant.now()
        );
        ledgerRepository.save(ledger);

        return toStockResponse(inventory);
    }

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến (chỉ đọc, phân trang, lọc theo SKU hoặc ref_id).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<InventoryLedgerResponse> getLedger(UUID skuId, UUID refId, Pageable pageable) {
        Page<InventoryLedger> page = ledgerRepository.searchLedger(skuId, refId, pageable);
        return page.map(l -> new InventoryLedgerResponse(
                l.getId(),
                l.getInventory().getSkuId(),
                l.getInventory().getWarehouse().getId(),
                l.getMovementType(),
                l.getQuantity(),
                l.getRefType(),
                l.getRefId(),
                l.getCreatedAt()
        ));
    }

    // ==========================================
    // Các phương thức trợ giúp nội bộ (Helpers)
    // ==========================================

    /**
     * Xác định kho hàng: nếu warehouseId null, tìm kho mặc định theo code 'WH_001'.
     */
    private Warehouse resolveWarehouse(UUID warehouseId) {
        if (warehouseId != null) {
            return warehouseRepository.findByIdAndIsActiveTrue(warehouseId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "Không tìm thấy kho hàng hợp lệ với ID " + warehouseId
                    ));
        }

        String defaultCode = properties.getDefaultWarehouseCode();
        return warehouseRepository.findByCode(defaultCode)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kho mặc định cấu hình (" + defaultCode + ")"
                ));
    }

    /**
     * Giải phóng một phiếu giữ hàng, cập nhật reserved_qty và bắn sự kiện miền tương ứng.
     */
    private void releaseSingleReservation(InventoryReservation reservation, ReservationStatus targetStatus, String reason) {
        Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getInventory().getId())
                .orElse(null);

        if (inventory != null) {
            int qty = reservation.getQuantity();
            inventory.setReservedQty(Math.max(0, inventory.getReservedQty() - qty));
            inventoryRepository.save(inventory);

            // Ghi sổ cái RELEASE
            InventoryLedger ledger = new InventoryLedger(
                    UUID.randomUUID(),
                    inventory,
                    LedgerMovementType.RELEASE,
                    qty,
                    reservation.getRefType().name(),
                    reservation.getRefId(),
                    Instant.now()
            );
            ledgerRepository.save(ledger);

            reservation.setStatus(targetStatus);
            reservationRepository.save(reservation);

            if (targetStatus == ReservationStatus.EXPIRED) {
                // Bắn sự kiện InventoryReservationExpiredEvent theo SRS §2.8
                eventPublisher.publishEvent(InventoryReservationExpiredEvent.of(
                        reservation.getId(),
                        inventory.getSkuId(),
                        inventory.getWarehouse().getId(),
                        qty,
                        reservation.getRefType(),
                        reservation.getRefId()
                ));
            } else {
                // Bắn sự kiện InventoryReleasedEvent theo SRS §2.8
                eventPublisher.publishEvent(InventoryReleasedEvent.of(
                        reservation.getId(),
                        inventory.getSkuId(),
                        inventory.getWarehouse().getId(),
                        qty,
                        reservation.getRefType(),
                        reservation.getRefId(),
                        reason
                ));
            }
        }
    }

    private InventoryStockResponse toStockResponse(Inventory inv) {
        return new InventoryStockResponse(
                inv.getSkuId(),
                inv.getWarehouse().getId(),
                inv.getWarehouse().getCode(),
                inv.getTotalQty(),
                inv.getAvailableQty(),
                inv.getReservedQty(),
                inv.getUnavailableQty()
        );
    }
}
