package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
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
 * Cài đặt nghiệp vụ quản trị tồn kho và vòng đời giữ hàng Checkout / Đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Quản lý 4 trạng thái tồn kho chính xác theo SKU và kho hàng (total_qty, available_qty, reserved_qty, unavailable_qty).
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với Pessimistic Write Lock để chống Overselling (ALLOW_OVERSELL = false).
 * - Quản lý toàn vẹn vòng đời giữ tồn kho cho phiên đấu giá (Auction Inventory Lifecycle):
 *   + Khóa giữ kho khi tạo phiên (TTL = endTime, bao quát toàn bộ phiên).
 *   + Gia hạn thời hạn giữ kho khi có Anti-sniping (+5 phút hoặc mốc mới).
 *   + Hủy phiên & giải phóng hoàn kho tức thời khi phiên bị hủy hoặc không có người đặt giá (Idempotent).
 *   + Chuyển tiếp trạng thái giữ kho sang Đơn hàng của Winner (Transition to Order) mà không bị Reserve kép.
 * - Tự động giải phóng các phiếu giữ hàng quá hạn.
 * - Ghi nhận mọi biến động vào Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 * - Phát các sự kiện miền InventoryChecked, InventoryReserved, InventoryReleased, InventoryReservationExpired, InventoryCommitted.
 *
 * Quy tắc và lý do:
 * - Khi giữ hàng nhiều SKU cùng lúc, sắp xếp tăng dần theo skuId trước khi khóa dòng DB để triệt tiêu Deadlock.
 * - Sổ cái tồn kho chỉ thêm mới (INSERT) và đọc (SELECT), tuyệt đối cấm sửa đổi hoặc xóa.
 * - Chuyển tiếp AUCTION sang ORDER giữ nguyên reserved_qty, không tạo khoảng trống cho khách mua ngay cướp kho.
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
            return new InventoryStockResponse(skuId, warehouse.getId(), warehouse.getCode(), 0, 0, 0, 0);
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
        return list.stream().map(this::toStockResponse).toList();
    }

    /**
     * Kiểm tra nhanh số lượng tồn khả dụng cho danh sách SKU (dùng cho Cart và Checkout validation).
     *
     * Luồng xử lý: Với mỗi item trong danh sách, tìm kho và bản ghi tồn kho, kiểm tra available_qty >= quantity.
     * Bắn sự kiện InventoryCheckedEvent theo SRS §2.8.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SkuAvailabilityResponse> checkAvailability(SkuAvailabilityCheckRequest request) {
        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        List<SkuAvailabilityResponse> result = new ArrayList<>();

        for (UUID skuId : request.skuIds()) {
            Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId())
                    .orElse(null);

            int available = (inventory != null) ? inventory.getAvailableQty() : 0;
            boolean isAvailable = available > 0;

            // Khởi tạo DTO phản hồi tình trạng tồn kho SKU
            result.add(new SkuAvailabilityResponse(
                    skuId,
                    warehouse.getId(),
                    available,
                    isAvailable
            ));

            // Bắn sự kiện InventoryCheckedEvent theo SRS §2.8 thông báo số lượng tồn kho khả dụng
            eventPublisher.publishEvent(InventoryCheckedEvent.of(
                    skuId,
                    warehouse.getId(),
                    available
            ));
        }

        return result;
    }

    /**
     * Khóa giữ hàng tồn kho khi người dùng Checkout (Atomic Reservation).
     *
     * Đầu vào: CreateReservationRequest chứa refType (ORDER/AUCTION), refId, danh sách SKU + số lượng, timeoutMinutes/expiresAt.
     * Kết quả: ReservationResponse chứa danh sách phiếu giữ hàng PENDING cùng thời điểm hết hạn.
     *
     * Luồng xử lý:
     * 1. Kiểm tra Idempotency cho AUCTION: nếu đã giữ kho thì trả về kết quả hiện tại.
     * 2. Sắp xếp danh sách items tăng dần theo skuId để triệt tiêu nguy cơ Deadlock khi khóa nhiều dòng DB.
     * 3. Khóa ghi (SELECT FOR UPDATE) từng dòng tồn kho.
     * 4. Kiểm tra available_qty: Nếu < số lượng yêu cầu và allowOversell = false → ném INVENTORY_INSUFFICIENT.
     * 5. Tăng reserved_qty của tồn kho (làm giảm available_qty tương ứng).
     * 6. Tạo bản ghi InventoryReservation với trạng thái PENDING và expires_at.
     * 7. Ghi nhận biến động RESERVE vào Sổ cái tồn kho bất biến (inventory_ledger).
     * 8. Bắn sự kiện miền InventoryReservedEvent cho từng mặt hàng được giữ.
     */
    @Override
    @Transactional
    public ReservationResponse reserveStock(CreateReservationRequest request) {
        // Kiểm tra tính Idempotent đối với AUCTION
        if (request.refType() == ReservationRefType.AUCTION) {
            List<InventoryReservation> existing = reservationRepository.findByRefTypeAndRefId(
                    ReservationRefType.AUCTION,
                    request.refId()
            );
            if (!existing.isEmpty()) {
                InventoryReservation res = existing.get(0);
                if (res.getStatus() == ReservationStatus.PENDING) {
                    log.info("Phiên đấu giá {} đã được giữ kho trước đó (Idempotent), trả về phiếu hiện tại", request.refId());
                    ReservationItemResponse item = new ReservationItemResponse(
                            res.getId(),
                            res.getInventory().getSkuId(),
                            res.getInventory().getWarehouse().getId(),
                            res.getQuantity(),
                            res.getStatus(),
                            res.getExpiresAt()
                    );
                    return new ReservationResponse(
                            res.getRefType(),
                            res.getRefId(),
                            res.getStatus(),
                            List.of(item),
                            res.getExpiresAt()
                    );
                }
            }
        }

        Instant expiresAt;
        if (request.expiresAt() != null) {
            expiresAt = request.expiresAt();
        } else {
            int timeoutMinutes = (request.timeoutMinutes() != null && request.timeoutMinutes() > 0)
                    ? request.timeoutMinutes()
                    : properties.getReservationTimeoutMinutes();
            expiresAt = Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES);
        }

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
     * Khóa giữ hàng chuyên biệt khi Seller tạo phiên đấu giá (Auction Reservation - SRS §3.6.1).
     *
     * Chức năng:
     * - Khóa giữ 1 đơn vị tồn kho khả dụng cho sản phẩm đấu giá với refType = AUCTION.
     * - Thiết lập TTL của phiếu giữ kho bao quát toàn bộ thời gian diễn ra phiên đấu giá (expires_at = endTime).
     * - Nghiêm cấm bán vượt số lượng tồn (ALLOW_OVERSELL = false), từ chối tạo phiên nếu tồn kho khả dụng không đủ.
     *
     * Quy tắc và kiểm tra nghiệp vụ:
     * - endTime phải sau startTime và phải ở tương lai.
     * - Thời lượng phiên phải >= AUCTION_MIN_DURATION_MINUTES (60 phút) và <= AUCTION_MAX_DURATION_HOURS (168 giờ = 7 ngày).
     * - Idempotency: nếu cùng auctionId đã có phiếu PENDING, trả về kết quả hiện tại, không khóa kho trùng lặp.
     */
    @Override
    @Transactional
    public ReservationResponse reserveAuctionStock(UUID auctionId, ReserveAuctionInventoryRequest request) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá auctionId không được để trống");
        }
        if (request.startTime() == null || request.endTime() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thời gian bắt đầu và kết thúc phiên đấu giá không được để trống");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thời gian kết thúc phiên đấu giá phải sau thời gian bắt đầu");
        }
        if (!request.endTime().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thời gian kết thúc phiên đấu giá phải ở tương lai");
        }

        // Kiểm tra thời lượng tối thiểu và tối đa của phiên đấu giá (SRS §2.6)
        long durationMinutes = ChronoUnit.MINUTES.between(request.startTime(), request.endTime());
        if (durationMinutes < properties.getAuctionMinDurationMinutes()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "Thời lượng phiên đấu giá tối thiểu là " + properties.getAuctionMinDurationMinutes() + " phút"
            );
        }
        long durationHours = ChronoUnit.HOURS.between(request.startTime(), request.endTime());
        if (durationHours > properties.getAuctionMaxDurationHours()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "Thời lượng phiên đấu giá tối đa là " + properties.getAuctionMaxDurationHours() + " giờ (7 ngày)"
            );
        }

        // Kiểm tra tính Idempotent: nếu đã giữ kho cho phiên này rồi, trả về phiếu PENDING hiện tại
        List<InventoryReservation> existing = reservationRepository.findByRefTypeAndRefId(
                ReservationRefType.AUCTION,
                auctionId
        );
        if (!existing.isEmpty()) {
            InventoryReservation res = existing.get(0);
            if (res.getStatus() == ReservationStatus.PENDING) {
                log.info("Phiên đấu giá {} đã được giữ kho trước đó (Idempotent), trả về phiếu hiện tại", auctionId);
                ReservationItemResponse item = new ReservationItemResponse(
                        res.getId(),
                        res.getInventory().getSkuId(),
                        res.getInventory().getWarehouse().getId(),
                        res.getQuantity(),
                        res.getStatus(),
                        res.getExpiresAt()
                );
                return new ReservationResponse(
                        res.getRefType(),
                        res.getRefId(),
                        res.getStatus(),
                        List.of(item),
                        res.getExpiresAt()
                );
            }
        }

        int requestedQty = (request.quantity() != null && request.quantity() > 0) ? request.quantity() : 1;
        Warehouse warehouse = resolveWarehouse(request.warehouseId());

        // Áp dụng Pessimistic Write Lock trên bản ghi tồn kho SKU
        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(request.skuId(), warehouse.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho cho SKU " + request.skuId() + " tại kho " + warehouse.getCode()
                ));

        int available = inventory.getAvailableQty();

        // Kiểm tra chống bán vượt tồn kho (ALLOW_OVERSELL = false)
        if (available < requestedQty && !Boolean.TRUE.equals(properties.getAllowOversell())) {
            log.warn("Từ chối tạo phiên đấu giá cho SKU {}: yêu cầu={}, khả dụng={}", request.skuId(), requestedQty, available);
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Số lượng tồn kho khả dụng không đủ để tạo phiên đấu giá: SKU "
                            + request.skuId() + " yêu cầu " + requestedQty + ", hiện khả dụng " + available
            );
        }

        // Tăng reserved_qty để khóa sản phẩm
        inventory.setReservedQty(inventory.getReservedQty() + requestedQty);
        inventoryRepository.save(inventory);

        // Tạo phiếu giữ kho AUCTION với expires_at bao quát toàn bộ thời gian phiên (đến endTime)
        UUID reservationId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                reservationId,
                inventory,
                requestedQty,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                request.endTime()
        );
        reservationRepository.save(reservation);

        // Ghi nhận vào Sổ cái tồn kho bất biến (RESERVE cho AUCTION)
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.RESERVE,
                requestedQty,
                "AUCTION",
                auctionId,
                Instant.now()
        );
        ledgerRepository.save(ledger);

        // Phát sinh sự kiện miền InventoryReservedEvent theo SRS §2.8
        eventPublisher.publishEvent(InventoryReservedEvent.of(
                reservationId,
                request.skuId(),
                warehouse.getId(),
                requestedQty,
                ReservationRefType.AUCTION,
                auctionId,
                request.endTime()
        ));

        log.info("Giữ kho thành công cho phiên đấu giá {} với SKU {}, hết hạn lúc {}",
                auctionId, request.skuId(), request.endTime());

        ReservationItemResponse itemResponse = new ReservationItemResponse(
                reservationId,
                request.skuId(),
                warehouse.getId(),
                requestedQty,
                ReservationStatus.PENDING,
                request.endTime()
        );

        return new ReservationResponse(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                List.of(itemResponse),
                request.endTime()
        );
    }

    /**
     * Gia hạn thời gian giữ kho khi phiên đấu giá bị kéo dài do Anti-sniping (SRS §2.6 & §3.6.1).
     *
     * Chức năng:
     * - Tiếp nhận tín hiệu gia hạn khi phiên đấu giá kéo dài (mặc định ANTI_SNIPING_EXTENSION_MINUTES = 5 phút).
     * - Tự động kéo dài expires_at của phiếu giữ kho tương ứng, ngăn chặn việc kho bị giải phóng nhầm khi phiên vẫn đang mở.
     *
     * Quy tắc:
     * - Khóa bi quan phiếu giữ kho AUCTION (PENDING).
     * - Thời hạn mới phải lớn hơn thời hạn hiện tại.
     */
    @Override
    @Transactional
    public ReservationResponse extendAuctionReservation(UUID auctionId, ExtendAuctionReservationRequest request) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá auctionId không được để trống");
        }

        // Khóa phiếu giữ kho của phiên đấu giá đang ở trạng thái PENDING
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        );

        if (reservations.isEmpty()) {
            List<InventoryReservation> anyRes = reservationRepository.findByRefTypeAndRefId(
                    ReservationRefType.AUCTION,
                    auctionId
            );
            if (anyRes.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.INVENTORY_RESERVATION_NOT_FOUND,
                        "Không tìm thấy phiếu giữ kho cho phiên đấu giá " + auctionId
                );
            } else {
                throw new BusinessException(
                        ErrorCode.AUCTION_NOT_ACTIVE,
                        "Phiếu giữ kho của phiên đấu giá " + auctionId + " không ở trạng thái PENDING để gia hạn: "
                                + anyRes.get(0).getStatus()
                );
            }
        }

        List<ReservationItemResponse> items = new ArrayList<>();
        Instant latestExpiresAt = reservations.get(0).getExpiresAt();

        for (InventoryReservation res : reservations) {
            Instant newExpiresAt;
            if (request != null && request.newEndTime() != null) {
                newExpiresAt = request.newEndTime();
            } else {
                int extMin = (request != null && request.extensionMinutes() != null && request.extensionMinutes() > 0)
                        ? request.extensionMinutes()
                        : properties.getAntiSnipingExtensionMinutes();
                newExpiresAt = res.getExpiresAt().plus(extMin, ChronoUnit.MINUTES);
            }

            if (!newExpiresAt.isAfter(res.getExpiresAt())) {
                throw new BusinessException(
                        ErrorCode.INVALID_PARAM_VALUE,
                        "Thời hạn gia hạn mới (" + newExpiresAt + ") phải lớn hơn thời hạn hiện tại ("
                                + res.getExpiresAt() + ") của phiếu giữ kho"
                );
            }

            res.setExpiresAt(newExpiresAt);
            reservationRepository.save(res);

            log.info("Gia hạn phiếu giữ kho {} của phiên đấu giá {} đến {} (Anti-sniping)",
                    res.getId(), auctionId, newExpiresAt);

            items.add(new ReservationItemResponse(
                    res.getId(),
                    res.getInventory().getSkuId(),
                    res.getInventory().getWarehouse().getId(),
                    res.getQuantity(),
                    res.getStatus(),
                    newExpiresAt
            ));
            if (newExpiresAt.isAfter(latestExpiresAt)) {
                latestExpiresAt = newExpiresAt;
            }
        }

        return new ReservationResponse(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                items,
                latestExpiresAt
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

        // Quy tắc Idempotent: Nếu đã CONFIRMED trước đó (do webhook gửi trùng hoặc retry), trả về kết quả ngay
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Phiếu giữ hàng {} đã được xác nhận xuất kho trước đó (Idempotent), bỏ qua trừ kho lặp", reservationId);
            ReservationItemResponse item = new ReservationItemResponse(
                    reservation.getId(),
                    reservation.getInventory().getSkuId(),
                    reservation.getInventory().getWarehouse().getId(),
                    reservation.getQuantity(),
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
        // Trừ vĩnh viễn số lượng trong reserved_qty và total_qty
        inventory.setReservedQty(Math.max(0, inventory.getReservedQty() - qty));
        inventory.setTotalQty(Math.max(0, inventory.getTotalQty() - qty));
        inventoryRepository.save(inventory);

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Ghi nhận biến động xuất kho OUTBOUND vào sổ cái bất biến (transaction_type = COMMITTED)
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

        // Phát sinh sự kiện miền InventoryCommittedEvent theo SRS §2.8
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
     * Xác nhận trừ kho thực tế cho toàn bộ các mặt hàng của đơn hàng khi thanh toán thành công (SRS §3.6.1).
     *
     * Chức năng:
     * - Trừ vĩnh viễn số lượng trong reserved_qty và total_qty cho từng mặt hàng.
     * - Ghi bản ghi xuất kho thực tế vào sổ cái (movement_type = OUTBOUND).
     * - Phát sinh sự kiện miền InventoryCommittedEvent.
     *
     * Quy tắc Idempotency:
     * - Khóa danh sách phiếu của orderId theo thứ tự r.id ASC để tránh Deadlock giữa các tiến trình đồng thời.
     * - Nếu toàn bộ các phiếu đã CONFIRMED: trả về kết quả thành công ngay lập tức, không trừ kho lần 2.
     * - Nếu phiếu ở trạng thái PENDING: thực hiện trừ kho và chuyển sang CONFIRMED.
     * - Nếu phiếu đã EXPIRED hoặc RELEASED: ném lỗi nghiệp vụ vì không thể xuất kho phiếu đã bị hủy/quá hạn.
     */
    @Override
    @Transactional
    public ReservationResponse commitByOrder(UUID orderId) {
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                orderId
        );

        if (reservations.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_NOT_FOUND,
                    "Không tìm thấy phiếu giữ hàng cho đơn hàng " + orderId
            );
        }

        // Kiểm tra tính Idempotent: nếu tất cả đã CONFIRMED thì trả về luôn, không trừ kho lặp
        boolean allConfirmed = reservations.stream()
                .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        if (allConfirmed) {
            log.info("Đơn hàng {} đã được xác nhận xuất kho trước đó (Idempotent), bỏ qua trừ kho lặp", orderId);
            List<ReservationItemResponse> items = reservations.stream()
                    .map(r -> new ReservationItemResponse(
                            r.getId(),
                            r.getInventory().getSkuId(),
                            r.getInventory().getWarehouse().getId(),
                            r.getQuantity(),
                            ReservationStatus.CONFIRMED,
                            r.getExpiresAt()
                    ))
                    .toList();
            return new ReservationResponse(
                    ReservationRefType.ORDER,
                    orderId,
                    ReservationStatus.CONFIRMED,
                    items,
                    reservations.get(0).getExpiresAt()
            );
        }

        // Kiểm tra trạng thái không hợp lệ (đã bị hoàn hủy hoặc hết hạn)
        boolean hasExpiredOrReleased = reservations.stream()
                .anyMatch(r -> r.getStatus() == ReservationStatus.EXPIRED || r.getStatus() == ReservationStatus.RELEASED);
        if (hasExpiredOrReleased) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Một hoặc nhiều phiếu giữ hàng của đơn hàng " + orderId + " đã hết hạn hoặc bị hủy, không thể xuất kho"
            );
        }

        List<ReservationItemResponse> items = new ArrayList<>();
        Instant latestExpiresAt = reservations.get(0).getExpiresAt();

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.PENDING) {
                Inventory inventory = inventoryRepository.findByIdForUpdate(res.getInventory().getId())
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.INVENTORY_NOT_FOUND,
                                "Không tìm thấy bản ghi tồn kho liên kết với phiếu giữ hàng " + res.getId()
                        ));

                int qty = res.getQuantity();
                // Trừ vĩnh viễn số lượng trong reserved_qty và total_qty
                inventory.setReservedQty(Math.max(0, inventory.getReservedQty() - qty));
                inventory.setTotalQty(Math.max(0, inventory.getTotalQty() - qty));
                inventoryRepository.save(inventory);

                res.setStatus(ReservationStatus.CONFIRMED);
                reservationRepository.save(res);

                // Ghi bản ghi xuất kho thực tế vào sổ cái (OUTBOUND)
                InventoryLedger ledger = new InventoryLedger(
                        UUID.randomUUID(),
                        inventory,
                        LedgerMovementType.OUTBOUND,
                        qty,
                        res.getRefType().name(),
                        res.getRefId(),
                        Instant.now()
                );
                ledgerRepository.save(ledger);

                // Phát sinh sự kiện miền InventoryCommittedEvent
                eventPublisher.publishEvent(InventoryCommittedEvent.of(
                        res.getId(),
                        inventory.getSkuId(),
                        inventory.getWarehouse().getId(),
                        qty,
                        res.getRefType(),
                        res.getRefId()
                ));
            }

            items.add(new ReservationItemResponse(
                    res.getId(),
                    res.getInventory().getSkuId(),
                    res.getInventory().getWarehouse().getId(),
                    res.getQuantity(),
                    ReservationStatus.CONFIRMED,
                    res.getExpiresAt()
            ));
            if (res.getExpiresAt().isAfter(latestExpiresAt)) {
                latestExpiresAt = res.getExpiresAt();
            }
        }

        log.info("Xác nhận xuất kho thành công cho đơn hàng {} với {} mặt hàng", orderId, items.size());
        return new ReservationResponse(
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.CONFIRMED,
                items,
                latestExpiresAt
        );
    }

    /**
     * Giải phóng một phiếu giữ hàng cụ thể (do người dùng hủy giỏ hoặc thanh toán thất bại).
     *
     * Luồng xử lý:
     * 1. Khóa phiếu và kiểm tra trạng thái PENDING.
     * 2. Nếu phiếu đã RELEASED hoặc EXPIRED: bỏ qua không hoàn kho lần hai (Idempotent).
     * 3. Khóa tồn kho, giảm reserved_qty để hoàn trả lại available_qty.
     * 4. Chuyển trạng thái phiếu sang RELEASED.
     * 5. Ghi nhận RELEASE vào sổ cái bất biến.
     * 6. Bắn sự kiện InventoryReleasedEvent.
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
            log.info("Phiếu giữ hàng {} đã ở trạng thái {} (Idempotent), bỏ qua giải phóng lặp",
                    reservationId, reservation.getStatus());
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
        if (request.refType() == ReservationRefType.ORDER) {
            releaseByOrder(request.refId(), request.reason());
        } else if (request.refType() == ReservationRefType.AUCTION) {
            releaseByAuction(request.refId(), request.reason());
        } else {
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
    }

    /**
     * Giải phóng tồn kho khi thanh toán lỗi/hủy (Release Inventory theo đơn hàng) (SRS §3.6.1).
     *
     * Chức năng:
     * - Lắng nghe/xử lý sự kiện OrderPaymentFailed hoặc OrderCancelled.
     * - Giảm reserved_qty và hoàn trả đầy đủ số lượng về lại available_qty.
     * - Ghi nhận giao dịch vào sổ cái (movement_type = RELEASE) và phát sinh sự kiện InventoryReleasedEvent.
     *
     * Quy tắc Idempotency:
     * - Khóa danh sách phiếu của orderId theo thứ tự r.id ASC để tránh Deadlock.
     * - Nếu các phiếu đã ở trạng thái RELEASED hoặc EXPIRED: bỏ qua không hoàn kho lần hai.
     * - Chỉ hoàn kho các phiếu còn ở trạng thái PENDING.
     */
    @Override
    @Transactional
    public void releaseByOrder(UUID orderId, String reason) {
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                orderId
        );

        if (reservations.isEmpty()) {
            log.warn("Không tìm thấy phiếu giữ kho nào cho đơn hàng {} khi giải phóng kho", orderId);
            return;
        }

        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : "ORDER_RELEASE";
        int releasedCount = 0;

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.PENDING) {
                releaseSingleReservation(res, ReservationStatus.RELEASED, effectiveReason);
                releasedCount++;
            } else {
                log.info("Phiếu giữ hàng {} của đơn {} đã ở trạng thái {} (Idempotent), bỏ qua giải phóng",
                        res.getId(), orderId, res.getStatus());
            }
        }

        log.info("Đã giải phóng {} phiếu giữ hàng cho đơn hàng {} với lý do: {}", releasedCount, orderId, effectiveReason);
    }

    /**
     * Hủy phiên & Giải phóng tồn kho phiên đấu giá (Release Inventory for Auction) (SRS §3.6.1).
     *
     * Chức năng:
     * - Khi phiên bị Seller/Admin hủy (AuctionCancelled) hoặc kết thúc mà không có người đặt giá (AuctionFailed):
     *   Tức thời giải phóng số lượng giữ trước, hoàn trả về available_qty trong kho.
     * - Ghi bản ghi biến động vào sổ cái (inventory_ledger) với loại giao dịch RELEASE.
     *
     * Quy tắc Idempotency:
     * - Khóa danh sách phiếu của auctionId theo thứ tự ID ASC để tránh Deadlock.
     * - Nếu các phiếu đã ở trạng thái RELEASED hoặc EXPIRED: bỏ qua không hoàn kho lần hai (retry release không tăng kho thừa).
     * - Chỉ hoàn kho các phiếu còn ở trạng thái PENDING.
     */
    @Override
    @Transactional
    public void releaseByAuction(UUID auctionId, String reason) {
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.AUCTION,
                auctionId
        );

        if (reservations.isEmpty()) {
            log.warn("Không tìm thấy phiếu giữ kho nào cho phiên đấu giá {} khi giải phóng kho", auctionId);
            return;
        }

        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : "AUCTION_RELEASE";
        int releasedCount = 0;

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.PENDING) {
                releaseSingleReservation(res, ReservationStatus.RELEASED, effectiveReason);
                releasedCount++;
            } else {
                log.info("Phiếu giữ kho {} của phiên {} đã ở trạng thái {} (Idempotent), bỏ qua giải phóng",
                        res.getId(), auctionId, res.getStatus());
            }
        }

        log.info("Đã giải phóng {} phiếu giữ hàng cho phiên đấu giá {} với lý do: {}", releasedCount, auctionId, effectiveReason);
    }

    /**
     * Chuyển tiếp trạng thái giữ kho sang Đơn hàng (Transition to Order) (SRS §3.6.1).
     *
     * Chức năng:
     * - Đảm bảo không xảy ra hiện tượng "Reserve kép" (Double Reservation): Khi chốt người thắng cuộc,
     *   chuyển đổi mã tham chiếu từ auction_id sang order_id mà không cần nhả kho rồi giữ lại từ đầu.
     * - Cập nhật hạn thanh toán mới cho đơn hàng (mặc định 24 giờ kể từ thời điểm chốt).
     *
     * Quy tắc Idempotency và an toàn dữ liệu:
     * - Nếu đơn hàng orderId đã có phiếu giữ kho loại ORDER, trả về kết quả thành công ngay lập tức (Idempotent).
     * - Khóa bi quan các phiếu AUCTION đang PENDING.
     * - Chuyển đổi trực tiếp refType thành ORDER, refId thành orderId, cập nhật expiresAt = paymentDeadline.
     * - Tuyệt đối KHÔNG thay đổi total_qty hoặc reserved_qty trong kho, hàng hóa được giữ liên tục không gián đoạn.
     */
    @Override
    @Transactional
    public ReservationResponse transitionAuctionToOrder(UUID auctionId, TransitionAuctionToOrderRequest request) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá auctionId không được để trống");
        }
        if (request.orderId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã đơn hàng orderId không được để trống");
        }

        // Bước 1: Kiểm tra tính Idempotent: nếu đơn hàng đã được chuyển tiếp trước đó, trả về luôn
        List<InventoryReservation> existingOrderRes = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                request.orderId()
        );
        if (!existingOrderRes.isEmpty()) {
            log.info("Đơn hàng {} đã được chuyển tiếp giữ kho trước đó (Idempotent), bỏ qua chuyển tiếp lặp", request.orderId());
            List<ReservationItemResponse> items = existingOrderRes.stream()
                    .map(r -> new ReservationItemResponse(
                            r.getId(),
                            r.getInventory().getSkuId(),
                            r.getInventory().getWarehouse().getId(),
                            r.getQuantity(),
                            r.getStatus(),
                            r.getExpiresAt()
                    ))
                    .toList();
            return new ReservationResponse(
                    ReservationRefType.ORDER,
                    request.orderId(),
                    existingOrderRes.get(0).getStatus(),
                    items,
                    existingOrderRes.get(0).getExpiresAt()
            );
        }

        // Bước 2: Khóa các phiếu giữ kho AUCTION đang ở trạng thái PENDING
        List<InventoryReservation> auctionReservations = reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        );

        if (auctionReservations.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_NOT_FOUND,
                    "Không tìm thấy phiếu giữ kho PENDING nào cho phiên đấu giá " + auctionId + " để chuyển tiếp sang đơn hàng"
            );
        }

        // Bước 3: Xác định thời hạn thanh toán đơn hàng (SRS §3.6.1: mặc định now + 24 giờ)
        Instant orderExpiresAt;
        if (request.paymentDeadline() != null) {
            orderExpiresAt = request.paymentDeadline();
        } else {
            int timeoutMinutes = (request.paymentDeadlineMinutes() != null && request.paymentDeadlineMinutes() > 0)
                    ? request.paymentDeadlineMinutes()
                    : properties.getAuctionPaymentDeadlineMinutes();
            orderExpiresAt = Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES);
        }

        // Bước 4: Chuyển đổi mã tham chiếu mà không nhả kho rồi giữ lại (chống Double Reservation và chống cướp kho)
        List<ReservationItemResponse> items = new ArrayList<>();
        for (InventoryReservation res : auctionReservations) {
            res.setRefType(ReservationRefType.ORDER);
            res.setRefId(request.orderId());
            res.setExpiresAt(orderExpiresAt);
            reservationRepository.save(res);

            items.add(new ReservationItemResponse(
                    res.getId(),
                    res.getInventory().getSkuId(),
                    res.getInventory().getWarehouse().getId(),
                    res.getQuantity(),
                    res.getStatus(),
                    orderExpiresAt
            ));
        }

        log.info("Chuyển tiếp thành công phiếu giữ kho từ phiên đấu giá {} sang đơn hàng {} với hạn thanh toán {}",
                auctionId, request.orderId(), orderExpiresAt);

        return new ReservationResponse(
                ReservationRefType.ORDER,
                request.orderId(),
                ReservationStatus.PENDING,
                items,
                orderExpiresAt
        );
    }

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn.
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

        log.info("Phát hiện {} phiếu giữ hàng quá hạn, tiến hành tự động hoàn kho...", expiredList.size());
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
