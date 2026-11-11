package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.BatchStockRequest;
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
import com.nexus.fulfillment.dto.response.InventoryReconciliationResponse;
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
import com.nexus.fulfillment.repository.InventoryLedgerSearchRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.security.InventoryAccessPolicy;
import com.nexus.fulfillment.service.InventoryService;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ quản trị tồn kho, phân quyền seller và vòng đời giữ hàng Checkout / Đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Quản lý 4 trạng thái tồn kho theo SKU và kho hàng (total_qty, available_qty, reserved_qty, unavailable_qty).
 * - Đọc danh sách tồn kho theo SKU/kho, batch read và tra cứu theo mã skuCode.
 * - Kiểm tra quyền thao tác và phạm vi dữ liệu seller thông qua InventoryAccessPolicy và CatalogClient.
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với Pessimistic Write Lock, chống Overselling (ALLOW_OVERSELL = false)
 *   và bảo đảm idempotency cho cả ORDER lẫn AUCTION khi retry request.
 * - Quản lý vòng đời giữ tồn kho đấu giá, gia hạn anti-sniping, chuyển tiếp sang đơn hàng và quét hết hạn an toàn.
 * - Chống thao tác lặp khi nhập kho/điều chỉnh kho thông qua idempotencyKey và refId; đối soát khớp sổ cái bất biến.
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
    private final CatalogClient catalogClient;
    private final InventoryAccessPolicy accessPolicy;

    /**
     * Constructor mặc định cho unit test không cần cấu hình CatalogClient hay Spring Security.
     */
    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InventoryLedgerRepository ledgerRepository,
            WarehouseRepository warehouseRepository,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher
    ) {
        this(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                properties,
                eventPublisher,
                emptyCatalogClient(),
                new InventoryAccessPolicy(emptyCatalogClient())
        );
    }

    /**
     * Constructor chính cho Spring IoC; dùng ObjectProvider để hỗ trợ cả ngữ cảnh full app lẫn @DataJpaTest thu gọn.
     */
    @Autowired
    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InventoryLedgerRepository ledgerRepository,
            WarehouseRepository warehouseRepository,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<CatalogClient> catalogClientProvider,
            ObjectProvider<InventoryAccessPolicy> accessPolicyProvider
    ) {
        this(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                properties,
                eventPublisher,
                catalogClientProvider != null ? catalogClientProvider.getIfAvailable(InventoryServiceImpl::emptyCatalogClient) : emptyCatalogClient(),
                accessPolicyProvider != null ? accessPolicyProvider.getIfAvailable() : null
        );
    }

    /**
     * Constructor mở rộng cho phép test tiêm trực tiếp CatalogClient và InventoryAccessPolicy giả lập.
     */
    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InventoryLedgerRepository ledgerRepository,
            WarehouseRepository warehouseRepository,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher,
            CatalogClient catalogClient,
            InventoryAccessPolicy accessPolicy
    ) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.warehouseRepository = warehouseRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.catalogClient = (catalogClient != null) ? catalogClient : emptyCatalogClient();
        this.accessPolicy = (accessPolicy != null) ? accessPolicy : new InventoryAccessPolicy(this.catalogClient);
    }

    private static CatalogClient emptyCatalogClient() {
        return new CatalogClient() {
            @Override
            public Optional<SkuOwnershipInfo> findSkuById(UUID skuId) {
                return Optional.empty();
            }

            @Override
            public Optional<SkuOwnershipInfo> findSkuByCode(String skuCode) {
                return Optional.empty();
            }

            @Override
            public List<SkuOwnershipInfo> findSkusBySeller(UUID sellerId) {
                return List.of();
            }
        };
    }

    /**
     * Tra cứu thông tin 4 trạng thái tồn kho của SKU tại một kho hàng cụ thể (hoặc kho mặc định WH_001).
     */
    @Override
    @Transactional(readOnly = true)
    public InventoryStockResponse getStockBySku(UUID skuId, UUID warehouseId) {
        Warehouse warehouse = resolveWarehouse(warehouseId);
        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId())
                .orElse(null);

        if (inventory == null) {
            return new InventoryStockResponse(skuId, warehouse.getId(), warehouse.getCode(), warehouse.getName(), null, 0, 0, 0, 0);
        }
        return toStockResponse(inventory, null);
    }

    /**
     * Tra cứu tồn kho của SKU theo mã doanh nghiệp skuCode tại kho chỉ định hoặc kho mặc định.
     */
    @Override
    @Transactional(readOnly = true)
    public InventoryStockResponse getStockBySkuCode(String skuCode, UUID warehouseId) {
        CatalogClient.SkuOwnershipInfo sku = resolveSkuByCodeOrThrow(skuCode);
        Warehouse warehouse = resolveWarehouse(warehouseId);
        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseId(sku.skuId(), warehouse.getId())
                .orElse(null);
        if (inventory == null) {
            return new InventoryStockResponse(
                    sku.skuId(),
                    warehouse.getId(),
                    warehouse.getCode(),
                    warehouse.getName(),
                    sku.skuCode(),
                    0,
                    0,
                    0,
                    0
            );
        }
        return toStockResponse(inventory, sku.skuCode());
    }

    /**
     * Tra cứu danh sách tồn kho của SKU trên tất cả các kho đang lưu trữ.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryStockResponse> getAllStockBySku(UUID skuId) {
        List<Inventory> list = inventoryRepository.findBySkuId(skuId);
        return list.stream().map(inv -> toStockResponse(inv, null)).toList();
    }

    /**
     * Đọc hàng loạt 4 trạng thái tồn kho cho danh sách SKU tại một kho hàng (mặc định WH_001).
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryStockResponse> getBatchStock(BatchStockRequest request) {
        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        List<UUID> distinctSkuIds = request.skuIds().stream().distinct().toList();
        List<Inventory> rows = inventoryRepository.findBySkuIdInAndWarehouseId(distinctSkuIds, warehouse.getId());
        Map<UUID, Inventory> bySkuId = new HashMap<>();
        for (Inventory row : rows) {
            bySkuId.put(row.getSkuId(), row);
        }

        List<InventoryStockResponse> responses = new ArrayList<>(distinctSkuIds.size());
        for (UUID skuId : distinctSkuIds) {
            Inventory inv = bySkuId.get(skuId);
            if (inv == null) {
                responses.add(new InventoryStockResponse(skuId, warehouse.getId(), warehouse.getCode(), warehouse.getName(), null, 0, 0, 0, 0));
            } else {
                responses.add(toStockResponse(inv, null));
            }
        }
        return responses;
    }

    /**
     * Liệt kê và lọc tồn kho có phân trang theo SKU, mã skuCode, kho hàng và phạm vi seller.
     * Quy tắc: Seller chỉ nhìn thấy bản ghi tồn kho thuộc các SKU của chính mình; Admin/Support xem toàn hệ thống.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<InventoryStockResponse> listInventory(
            UUID skuId,
            String skuCode,
            UUID warehouseId,
            UUID sellerId,
            Pageable pageable
    ) {
        UUID resolvedSkuId = skuId;
        String resolvedSkuCode = null;
        if (resolvedSkuId == null && skuCode != null && !skuCode.isBlank()) {
            Optional<CatalogClient.SkuOwnershipInfo> skuOpt = catalogClient.findSkuByCode(skuCode.trim());
            if (skuOpt.isEmpty()) {
                // Kiểm tra phạm vi quyền trước khi trả rỗng để Seller không đoán được thông tin sellerId khác.
                accessPolicy.resolveReadableSellerSkuScope(sellerId, null);
                return Page.empty(pageable);
            }
            resolvedSkuId = skuOpt.get().skuId();
            resolvedSkuCode = skuOpt.get().skuCode();
        }

        List<UUID> allowedSkuIds = accessPolicy.resolveReadableSellerSkuScope(sellerId, resolvedSkuId);
        if (allowedSkuIds != null && allowedSkuIds.isEmpty()) {
            return Page.empty(pageable);
        }

        final UUID finalSkuId = resolvedSkuId;
        Specification<Inventory> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (finalSkuId != null) {
                predicates.add(cb.equal(root.get("skuId"), finalSkuId));
            }
            if (allowedSkuIds != null) {
                predicates.add(root.get("skuId").in(allowedSkuIds));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        final String finalSkuCode = resolvedSkuCode;
        return inventoryRepository.findAll(spec, pageable).map(inv -> toStockResponse(inv, finalSkuCode));
    }

    /**
     * Kiểm tra nhanh số lượng tồn khả dụng cho danh sách SKU (dùng cho Cart và Checkout validation).
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

            result.add(new SkuAvailabilityResponse(
                    skuId,
                    warehouse.getId(),
                    available,
                    isAvailable
            ));

            eventPublisher.publishEvent(InventoryCheckedEvent.of(
                    skuId,
                    warehouse.getId(),
                    available
            ));
        }

        return result;
    }

    /**
     * Khóa giữ hàng tồn kho khi người dùng Checkout hoặc giữ kho theo tham chiếu (Atomic Reservation).
     *
     * Đầu vào: CreateReservationRequest chứa refType (ORDER/AUCTION), refId, danh sách SKU + số lượng, timeoutMinutes/expiresAt.
     * Kết quả: ReservationResponse chứa danh sách phiếu giữ hàng PENDING cùng thời điểm hết hạn.
     *
     * Luồng xử lý:
     * 1. Chuẩn hóa kho và sắp xếp items theo (skuId, warehouseId) để tránh Deadlock.
     * 2. Khóa danh sách phiếu hiện có theo (refType, refId) để đảm bảo Idempotency cho cả ORDER và AUCTION:
     *    - Nếu đã có phiếu PENDING khớp đúng tập SKU và số lượng → trả về kết quả cũ, không trừ/giữ kho lần hai.
     *    - Nếu khác SKU/số lượng → ném HTTP 409 ORDER_IDEMPOTENCY_CONFLICT.
     *    - Nếu phiếu đã CONFIRMED/RELEASED/EXPIRED → từ chối với HTTP 409 để không giữ lại đơn đã kết thúc.
     * 3. Khóa dòng tồn kho (SELECT ... FOR UPDATE), kiểm tra ALLOW_OVERSELL = false, tăng reserved_qty, lưu phiếu và sổ cái RESERVE.
     */
    @Override
    @Transactional
    public ReservationResponse reserveStock(CreateReservationRequest request) {
        List<ResolvedReservationItem> resolvedItems = resolveAndSortReservationItems(request.items());

        // Kiểm tra và khóa theo (refType, refId) cho cả ORDER và AUCTION để chống giữ kho lặp khi retry HTTP.
        List<InventoryReservation> existing = reservationRepository.findByRefTypeAndRefIdForUpdate(
                request.refType(),
                request.refId()
        );
        if (!existing.isEmpty()) {
            return handleExistingReservationReplay(request.refType(), request.refId(), existing, resolvedItems);
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

        List<ReservationItemResponse> itemResponses = new ArrayList<>();

        for (ResolvedReservationItem item : resolvedItems) {
            Warehouse warehouse = item.warehouse();

            Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(item.skuId(), warehouse.getId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INVENTORY_NOT_FOUND,
                            "Không tìm thấy bản ghi tồn kho cho SKU " + item.skuId() + " tại kho " + warehouse.getCode()
                    ));

            int available = inventory.getAvailableQty();
            int requestedQty = item.quantity();

            if (available < requestedQty && !Boolean.TRUE.equals(properties.getAllowOversell())) {
                log.warn("Từ chối giữ kho cho SKU {}: yêu cầu={}, khả dụng={}", item.skuId(), requestedQty, available);
                throw new BusinessException(
                        ErrorCode.INVENTORY_INSUFFICIENT,
                        "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua: SKU "
                                + item.skuId() + " yêu cầu " + requestedQty + ", hiện khả dụng " + available
                );
            }

            inventory.setReservedQty(inventory.getReservedQty() + requestedQty);
            inventoryRepository.save(inventory);

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

        List<InventoryReservation> existing = reservationRepository.findByRefTypeAndRefId(
                ReservationRefType.AUCTION,
                auctionId
        );
        if (!existing.isEmpty()) {
            InventoryReservation res = existing.get(0);
            if (res.getStatus() == ReservationStatus.PENDING) {
                log.info("Phiên đấu giá {} đã được giữ kho trước đó (Idempotent), trả về phiếu hiện tại", auctionId);
                return toReservationResponse(ReservationRefType.AUCTION, auctionId, existing);
            }
            if (res.getStatus() == ReservationStatus.CONFIRMED) {
                throw new BusinessException(
                        ErrorCode.INVENTORY_RESERVATION_ALREADY_COMMITTED,
                        "Phiên đấu giá " + auctionId + " đã xuất kho trước đó"
                );
            }
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Phiếu giữ kho của phiên đấu giá " + auctionId + " đã ở trạng thái " + res.getStatus()
            );
        }

        int requestedQty = (request.quantity() != null && request.quantity() > 0) ? request.quantity() : 1;
        Warehouse warehouse = resolveWarehouse(request.warehouseId());

        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(request.skuId(), warehouse.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho cho SKU " + request.skuId() + " tại kho " + warehouse.getCode()
                ));

        int available = inventory.getAvailableQty();

        if (available < requestedQty && !Boolean.TRUE.equals(properties.getAllowOversell())) {
            log.warn("Từ chối tạo phiên đấu giá cho SKU {}: yêu cầu={}, khả dụng={}", request.skuId(), requestedQty, available);
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Số lượng tồn kho khả dụng không đủ để tạo phiên đấu giá: SKU "
                            + request.skuId() + " yêu cầu " + requestedQty + ", hiện khả dụng " + available
            );
        }

        inventory.setReservedQty(inventory.getReservedQty() + requestedQty);
        inventoryRepository.save(inventory);

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
     */
    @Override
    @Transactional
    public ReservationResponse extendAuctionReservation(UUID auctionId, ExtendAuctionReservationRequest request) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá auctionId không được để trống");
        }

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
     * Bảo đảm Idempotent: phiếu đã CONFIRMED trả về ngay, phiếu EXPIRED hoặc RELEASED ném INVENTORY_RESERVATION_EXPIRED.
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
            log.info("Phiếu giữ hàng {} đã được xác nhận xuất kho trước đó (Idempotent), bỏ qua trừ kho lặp", reservationId);
            return toReservationResponse(reservation.getRefType(), reservation.getRefId(), List.of(reservation));
        }

        if (reservation.getStatus() == ReservationStatus.EXPIRED || reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Phiếu giữ hàng đã quá hạn hoặc bị hủy: " + reservation.getStatus()
            );
        }

        commitSinglePendingReservation(reservation, "CONFIRMED_PAYMENT");

        return toReservationResponse(reservation.getRefType(), reservation.getRefId(), List.of(reservation));
    }

    /**
     * Xác nhận trừ kho thực tế cho toàn bộ các mặt hàng của đơn hàng khi thanh toán thành công (SRS §3.6.1).
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

        boolean allConfirmed = reservations.stream()
                .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        if (allConfirmed) {
            log.info("Đơn hàng {} đã được xác nhận xuất kho trước đó (Idempotent), bỏ qua trừ kho lặp", orderId);
            return toReservationResponse(ReservationRefType.ORDER, orderId, reservations);
        }

        boolean hasExpiredOrReleased = reservations.stream()
                .anyMatch(r -> r.getStatus() == ReservationStatus.EXPIRED || r.getStatus() == ReservationStatus.RELEASED);
        if (hasExpiredOrReleased) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Một hoặc nhiều phiếu giữ hàng của đơn hàng " + orderId + " đã hết hạn hoặc bị hủy, không thể xuất kho"
            );
        }

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.PENDING) {
                commitSinglePendingReservation(res, "ORDER_PAID_COMMIT");
            }
        }

        log.info("Xác nhận xuất kho thành công cho đơn hàng {} với {} mặt hàng", orderId, reservations.size());
        return toReservationResponse(ReservationRefType.ORDER, orderId, reservations);
    }

    /**
     * Giải phóng một phiếu giữ hàng cụ thể theo reservationId (Idempotent).
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

        releaseSingleReservation(
                reservation,
                ReservationStatus.RELEASED,
                (reason != null && !reason.isBlank()) ? reason : "MANUAL_RELEASE"
        );
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
     * Giải phóng tồn kho khi thanh toán lỗi/hủy theo đơn hàng (Idempotent).
     */
    @Override
    @Transactional
    public void releaseByOrder(UUID orderId, String reason) {
        releasePendingReservationsByRef(ReservationRefType.ORDER, orderId, reason, "ORDER_RELEASE", "đơn hàng");
    }

    /**
     * Hủy phiên & Giải phóng tồn kho phiên đấu giá (Idempotent).
     */
    @Override
    @Transactional
    public void releaseByAuction(UUID auctionId, String reason) {
        releasePendingReservationsByRef(ReservationRefType.AUCTION, auctionId, reason, "AUCTION_RELEASE", "phiên đấu giá");
    }

    /**
     * Chuyển tiếp trạng thái giữ kho từ AUCTION sang Đơn hàng của người thắng cuộc (Transition to Order).
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

        List<InventoryReservation> existingOrderRes = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                request.orderId()
        );
        if (!existingOrderRes.isEmpty()) {
            log.info("Đơn hàng {} đã được chuyển tiếp giữ kho trước đó (Idempotent), bỏ qua chuyển tiếp lặp", request.orderId());
            return toReservationResponse(ReservationRefType.ORDER, request.orderId(), existingOrderRes);
        }

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

        Instant orderExpiresAt;
        if (request.paymentDeadline() != null) {
            orderExpiresAt = request.paymentDeadline();
        } else {
            int timeoutMinutes = (request.paymentDeadlineMinutes() != null && request.paymentDeadlineMinutes() > 0)
                    ? request.paymentDeadlineMinutes()
                    : properties.getAuctionPaymentDeadlineMinutes();
            orderExpiresAt = Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES);
        }

        for (InventoryReservation res : auctionReservations) {
            res.setRefType(ReservationRefType.ORDER);
            res.setRefId(request.orderId());
            res.setExpiresAt(orderExpiresAt);
            reservationRepository.save(res);
        }

        log.info("Chuyển tiếp thành công phiếu giữ kho từ phiên đấu giá {} sang đơn hàng {} với hạn thanh toán {}",
                auctionId, request.orderId(), orderExpiresAt);

        return toReservationResponse(ReservationRefType.ORDER, request.orderId(), auctionReservations);
    }

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn.
     * Chỉ nạp danh sách ID ứng viên trước khi khóa FOR UPDATE để tránh đọc nhầm trạng thái cache khi cạnh tranh với callback thanh toán hoặc gia hạn anti-sniping.
     */
    @Override
    @Transactional
    public int autoReleaseExpiredReservations() {
        Instant now = Instant.now();
        List<UUID> expiredIds = reservationRepository.findExpiredIds(
                ReservationStatus.PENDING,
                now
        );

        if (expiredIds.isEmpty()) {
            return 0;
        }

        log.info("Phát hiện {} phiếu giữ hàng quá hạn, tiến hành tự động hoàn kho...", expiredIds.size());
        int count = 0;

        for (UUID reservationId : expiredIds) {
            try {
                InventoryReservation lockedRes = reservationRepository.findByIdForUpdate(reservationId)
                        .orElse(null);

                // Kiểm tra lại status và expiresAt sau khi đã giữ khóa dòng (phòng trường hợp vừa được CONFIRMED hoặc gia hạn).
                if (lockedRes != null
                        && lockedRes.getStatus() == ReservationStatus.PENDING
                        && !lockedRes.getExpiresAt().isAfter(Instant.now())) {
                    releaseSingleReservation(lockedRes, ReservationStatus.EXPIRED, "TIMEOUT_EXPIRED");
                    count++;
                }
            } catch (Exception ex) {
                log.error("Lỗi khi tự động hoàn kho phiếu giữ hàng {}: errorType={}",
                        reservationId, ex.getClass().getSimpleName());
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
        return inboundStock(request, null);
    }

    /**
     * Nhập thêm hàng hóa vào kho hỗ trợ kiểm tra quyền sở hữu seller và chống lặp qua idempotencyKey / refId.
     *
     * Luồng xử lý:
     * 1. Phân giải skuId (trực tiếp hoặc qua skuCode từ Catalog Service).
     * 2. Xác minh quyền thao tác và sở hữu SKU của seller (Admin bỏ qua, Seller bắt buộc sở hữu SKU).
     * 3. Xác định khóa chống lặp (Idempotency-Key từ header hoặc body, hoặc refId).
     * 4. Nếu đã có bản ghi INBOUND khớp khóa và số lượng → trả về tồn kho hiện tại, không cộng dồn hai lần.
     * 5. Nếu cùng khóa nhưng khác SKU/kho/số lượng → từ chối với HTTP 409 ORDER_IDEMPOTENCY_CONFLICT.
     * 6. Khóa dòng tồn kho, tăng total_qty và ghi nhận sổ cái INBOUND bất biến.
     */
    @Override
    @Transactional
    public InventoryStockResponse inboundStock(StockInboundRequest request, String headerIdempotencyKey) {
        ResolvedSku sku = resolveSkuForMutation(request.skuId(), request.skuCode());
        InventoryAccessPolicy.ActorContext actor = accessPolicy.verifyWriteAccessAndOwnership(sku.skuId());
        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        String ledgerIdempotencyKey = buildOperationIdempotencyKey("INBOUND", headerIdempotencyKey, request.idempotencyKey());

        if (ledgerIdempotencyKey != null) {
            Optional<InventoryLedger> existingByKey = ledgerRepository.findByIdempotencyKey(ledgerIdempotencyKey);
            if (existingByKey.isPresent()) {
                return verifyInboundReplay(existingByKey.get(), sku.skuId(), warehouse.getId(), request.quantity(), sku.skuCode());
            }
        }

        Inventory existingInventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(sku.skuId(), warehouse.getId())
                .orElse(null);

        if (existingInventory != null && ledgerIdempotencyKey == null && request.refId() != null) {
            Optional<InventoryLedger> existingByRef = ledgerRepository.findFirstByInventoryIdAndMovementTypeAndRefId(
                    existingInventory.getId(),
                    LedgerMovementType.INBOUND,
                    request.refId()
            );
            if (existingByRef.isPresent()) {
                return verifyInboundReplay(existingByRef.get(), sku.skuId(), warehouse.getId(), request.quantity(), sku.skuCode());
            }
        }

        Inventory inventory = (existingInventory != null)
                ? existingInventory
                : new Inventory(UUID.randomUUID(), sku.skuId(), warehouse, 0, 0, 0);

        inventory.setTotalQty(inventory.getTotalQty() + request.quantity());
        inventory = inventoryRepository.save(inventory);

        String reason = (request.reason() != null && !request.reason().isBlank())
                ? request.reason().trim()
                : "Nhập kho hàng hóa";

        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.INBOUND,
                request.quantity(),
                "INBOUND",
                request.refId(),
                Instant.now(),
                ledgerIdempotencyKey,
                reason,
                actor.actorId()
        );
        ledgerRepository.save(ledger);

        return toStockResponse(inventory, sku.skuCode());
    }

    /**
     * Điều chỉnh kiểm kê tồn kho thực tế và ghi sổ cái ADJUSTMENT.
     */
    @Override
    @Transactional
    public InventoryStockResponse adjustStock(StockAdjustmentRequest request) {
        return adjustStock(request, null);
    }

    /**
     * Điều chỉnh tồn kho thực tế hỗ trợ quyền seller, targetTotalQty, unavailableDeltaQty và chống lặp.
     *
     * Quy tắc bảo vệ bất biến:
     * - Chỉ Admin hoặc Seller sở hữu chính SKU mới được điều chỉnh.
     * - Hỗ trợ điều chỉnh tương đối (deltaQty) hoặc đặt tổng tồn mới (targetTotalQty) dưới khóa dòng DB.
     * - Không cho phép tồn kho âm hoặc totalQty < reservedQty + unavailableQty (ném INVENTORY_INSUFFICIENT).
     * - Không ghi bản ghi quantity = 0 vào sổ cái (nếu targetTotalQty bằng đúng tồn hiện tại thì trả về ngay).
     */
    @Override
    @Transactional
    public InventoryStockResponse adjustStock(StockAdjustmentRequest request, String headerIdempotencyKey) {
        if (request.targetTotalQty() == null
                && request.deltaQty() != null
                && request.deltaQty() == 0
                && (request.unavailableDeltaQty() == null || request.unavailableDeltaQty() == 0)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Số lượng điều chỉnh deltaQty phải khác 0");
        }

        ResolvedSku sku = resolveSkuForMutation(request.skuId(), request.skuCode());
        InventoryAccessPolicy.ActorContext actor = accessPolicy.verifyWriteAccessAndOwnership(sku.skuId());
        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        String ledgerIdempotencyKey = buildOperationIdempotencyKey("ADJUST", headerIdempotencyKey, request.idempotencyKey());

        if (ledgerIdempotencyKey != null) {
            Optional<InventoryLedger> existingByKey = ledgerRepository.findByIdempotencyKey(ledgerIdempotencyKey);
            if (existingByKey.isPresent()) {
                return verifyAdjustmentReplay(existingByKey.get(), sku.skuId(), warehouse.getId(), request, sku.skuCode());
            }
        }

        Inventory inventory = inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(sku.skuId(), warehouse.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho để điều chỉnh"
                ));

        if (ledgerIdempotencyKey == null && request.refId() != null) {
            Optional<InventoryLedger> existingByRef = ledgerRepository.findFirstByInventoryIdAndMovementTypeAndRefId(
                    inventory.getId(),
                    LedgerMovementType.ADJUSTMENT,
                    request.refId()
            );
            if (existingByRef.isPresent()) {
                return verifyAdjustmentReplay(existingByRef.get(), sku.skuId(), warehouse.getId(), request, sku.skuCode());
            }
        }

        int deltaTotal = (request.targetTotalQty() != null)
                ? (request.targetTotalQty() - inventory.getTotalQty())
                : (request.deltaQty() != null ? request.deltaQty() : 0);
        int deltaUnavailable = (request.unavailableDeltaQty() != null) ? request.unavailableDeltaQty() : 0;

        int newTotal = inventory.getTotalQty() + deltaTotal;
        int newUnavailable = inventory.getUnavailableQty() + deltaUnavailable;

        if (newTotal < 0 || newUnavailable < 0) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Không thể điều chỉnh làm âm tổng tồn kho hoặc số lượng cách ly"
            );
        }
        if (newTotal < (inventory.getReservedQty() + newUnavailable)) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Không thể điều chỉnh tồn kho vì tổng số lượng mới (" + newTotal
                            + ") nhỏ hơn số lượng đang giữ và không khả dụng ("
                            + (inventory.getReservedQty() + newUnavailable) + ")"
            );
        }

        if (deltaTotal == 0 && deltaUnavailable == 0) {
            // Khi đặt targetTotalQty bằng đúng tổng tồn hiện tại, trả về tồn kho hiện tại mà không ghi sổ cái quantity=0.
            return toStockResponse(inventory, sku.skuCode());
        }

        inventory.setTotalQty(newTotal);
        inventory.setUnavailableQty(newUnavailable);
        inventory = inventoryRepository.save(inventory);

        // Chỉ ghi sổ cái ADJUSTMENT khi tổng tồn vật lý thực sự thay đổi để tổng sổ cái luôn khớp total_qty.
        if (deltaTotal != 0) {
            InventoryLedger ledger = new InventoryLedger(
                    UUID.randomUUID(),
                    inventory,
                    LedgerMovementType.ADJUSTMENT,
                    deltaTotal,
                    "ADJUSTMENT",
                    request.refId(),
                    Instant.now(),
                    ledgerIdempotencyKey,
                    request.reason().trim(),
                    actor.actorId()
            );
            ledgerRepository.save(ledger);
        }

        return toStockResponse(inventory, sku.skuCode());
    }

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến (tương thích ngược chữ ký cũ, áp dụng phạm vi seller).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<InventoryLedgerResponse> getLedger(UUID skuId, UUID refId, Pageable pageable) {
        return getLedger(skuId, null, null, null, null, refId, null, null, pageable);
    }

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến với đầy đủ bộ lọc theo SKU/kho/loại biến động/thời gian và phạm vi seller.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<InventoryLedgerResponse> getLedger(
            UUID skuId,
            String skuCode,
            UUID warehouseId,
            LedgerMovementType movementType,
            String refType,
            UUID refId,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    ) {
        UUID resolvedSkuId = skuId;
        if (resolvedSkuId == null && skuCode != null && !skuCode.isBlank()) {
            Optional<CatalogClient.SkuOwnershipInfo> skuOpt = catalogClient.findSkuByCode(skuCode.trim());
            if (skuOpt.isEmpty()) {
                accessPolicy.resolveReadableSellerSkuScope(null, null);
                return Page.empty(pageable);
            }
            resolvedSkuId = skuOpt.get().skuId();
        }

        List<UUID> allowedSkuIds = accessPolicy.resolveReadableSellerSkuScope(null, resolvedSkuId);
        if (allowedSkuIds != null && allowedSkuIds.isEmpty()) {
            return Page.empty(pageable);
        }

        boolean hasExtendedFilters = allowedSkuIds != null
                || warehouseId != null
                || movementType != null
                || (refType != null && !refType.isBlank())
                || fromTime != null
                || toTime != null;

        Page<InventoryLedger> page;
        if (!hasExtendedFilters) {
            page = ledgerRepository.searchLedger(resolvedSkuId, refId, pageable);
        } else {
            InventoryLedgerSearchRepository.LedgerFilter filter = new InventoryLedgerSearchRepository.LedgerFilter(
                    resolvedSkuId,
                    allowedSkuIds,
                    allowedSkuIds != null,
                    warehouseId,
                    movementType,
                    refType,
                    refId,
                    fromTime,
                    toTime
            );
            page = ledgerRepository.searchLedgerFiltered(filter, pageable);
        }

        return page.map(this::toLedgerResponse);
    }

    /**
     * Đối soát số dư tồn kho thực tế với tổng biến động trong sổ cái bất biến và tổng phiếu giữ hàng PENDING.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InventoryReconciliationResponse> reconcileStock(UUID skuId, UUID warehouseId) {
        List<UUID> allowedSkuIds = accessPolicy.resolveReadableSellerSkuScope(null, skuId);
        if (allowedSkuIds != null && allowedSkuIds.isEmpty()) {
            return List.of();
        }

        Specification<Inventory> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (skuId != null) {
                predicates.add(cb.equal(root.get("skuId"), skuId));
            }
            if (allowedSkuIds != null) {
                predicates.add(root.get("skuId").in(allowedSkuIds));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        List<Inventory> inventories = inventoryRepository.findAll(spec);
        List<InventoryReconciliationResponse> results = new ArrayList<>(inventories.size());

        for (Inventory inv : inventories) {
            List<InventoryLedger> entries = ledgerRepository.findByInventoryIdOrderByCreatedAtAsc(inv.getId());
            int ledgerTotal = 0;
            int ledgerReserved = 0;
            for (InventoryLedger entry : entries) {
                int absQty = Math.abs(entry.getQuantity());
                switch (entry.getMovementType()) {
                    case INBOUND -> ledgerTotal += absQty;
                    case OUTBOUND -> {
                        ledgerTotal -= absQty;
                        ledgerReserved -= absQty;
                    }
                    case ADJUSTMENT -> ledgerTotal += entry.getQuantity();
                    case RESERVE -> ledgerReserved += absQty;
                    case RELEASE -> ledgerReserved -= absQty;
                    default -> {
                    }
                }
            }

            Integer pendingSum = reservationRepository.sumPendingQuantityByInventoryId(inv.getId());
            int activeReservations = (pendingSum != null) ? pendingSum : 0;

            boolean nonNegative = inv.getTotalQty() >= 0
                    && inv.getReservedQty() >= 0
                    && inv.getUnavailableQty() >= 0
                    && inv.getTotalQty() >= (inv.getReservedQty() + inv.getUnavailableQty());

            boolean matched = nonNegative
                    && inv.getTotalQty() == ledgerTotal
                    && inv.getReservedQty() == ledgerReserved
                    && inv.getReservedQty() == activeReservations;

            results.add(new InventoryReconciliationResponse(
                    inv.getSkuId(),
                    inv.getWarehouse().getId(),
                    inv.getWarehouse().getCode(),
                    inv.getTotalQty(),
                    inv.getAvailableQty(),
                    inv.getReservedQty(),
                    inv.getUnavailableQty(),
                    ledgerTotal,
                    ledgerReserved,
                    activeReservations,
                    nonNegative,
                    matched
            ));
        }
        return results;
    }

    // ==========================================
    // Các phương thức trợ giúp nội bộ (Helpers)
    // ==========================================

    private record ResolvedSku(UUID skuId, String skuCode) {
    }

    private record ResolvedReservationItem(UUID skuId, Warehouse warehouse, int quantity) {
    }

    private ResolvedSku resolveSkuForMutation(UUID skuId, String skuCode) {
        if (skuId != null) {
            String normalizedCode = (skuCode != null && !skuCode.isBlank()) ? skuCode.trim() : null;
            return new ResolvedSku(skuId, normalizedCode);
        }
        CatalogClient.SkuOwnershipInfo info = resolveSkuByCodeOrThrow(skuCode);
        return new ResolvedSku(info.skuId(), info.skuCode());
    }

    private CatalogClient.SkuOwnershipInfo resolveSkuByCodeOrThrow(String skuCode) {
        if (skuCode == null || skuCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã skuCode không được để trống");
        }
        return catalogClient.findSkuByCode(skuCode.trim())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SKU_NOT_FOUND,
                        "Không tìm thấy SKU với mã " + skuCode.trim()
                ));
    }

    private List<ResolvedReservationItem> resolveAndSortReservationItems(List<ReservationItemRequest> items) {
        List<ResolvedReservationItem> resolved = new ArrayList<>(items.size());
        for (ReservationItemRequest item : items) {
            Warehouse warehouse = resolveWarehouse(item.warehouseId());
            resolved.add(new ResolvedReservationItem(item.skuId(), warehouse, item.quantity()));
        }
        // Sắp xếp nhất quán theo skuId rồi warehouseId để tránh chu trình chờ khóa (Deadlock).
        resolved.sort(Comparator.comparing(ResolvedReservationItem::skuId)
                .thenComparing(r -> r.warehouse().getId()));
        return resolved;
    }

    private ReservationResponse handleExistingReservationReplay(
            ReservationRefType refType,
            UUID refId,
            List<InventoryReservation> existing,
            List<ResolvedReservationItem> requested
    ) {
        boolean anyConfirmed = existing.stream().anyMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        if (anyConfirmed) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_ALREADY_COMMITTED,
                    "Tham chiếu " + refType + " " + refId + " đã xuất kho trước đó"
            );
        }
        boolean anyClosed = existing.stream()
                .anyMatch(r -> r.getStatus() == ReservationStatus.RELEASED || r.getStatus() == ReservationStatus.EXPIRED);
        if (anyClosed) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_RESERVATION_EXPIRED,
                    "Phiếu giữ kho của tham chiếu " + refType + " " + refId + " đã hết hạn hoặc bị hủy"
            );
        }

        List<InventoryReservation> sortedExisting = new ArrayList<>(existing);
        sortedExisting.sort(Comparator.comparing((InventoryReservation r) -> r.getInventory().getSkuId())
                .thenComparing(r -> r.getInventory().getWarehouse().getId()));

        if (sortedExisting.size() != requested.size()) {
            throw new BusinessException(
                    ErrorCode.ORDER_IDEMPOTENCY_CONFLICT,
                    "Tham chiếu " + refType + " " + refId + " đã giữ kho với số lượng dòng hàng khác"
            );
        }
        for (int i = 0; i < sortedExisting.size(); i++) {
            InventoryReservation current = sortedExisting.get(i);
            ResolvedReservationItem incoming = requested.get(i);
            if (!current.getInventory().getSkuId().equals(incoming.skuId())
                    || !current.getInventory().getWarehouse().getId().equals(incoming.warehouse().getId())
                    || current.getQuantity() != incoming.quantity()) {
                throw new BusinessException(
                        ErrorCode.ORDER_IDEMPOTENCY_CONFLICT,
                        "Tham chiếu " + refType + " " + refId + " đã giữ kho với SKU/kho/số lượng khác"
                );
            }
        }

        log.info("Tham chiếu {} {} đã giữ kho PENDING trùng khớp trước đó (Idempotent), trả về phiếu hiện tại", refType, refId);
        return toReservationResponse(refType, refId, sortedExisting);
    }

    private void commitSinglePendingReservation(InventoryReservation reservation, String reason) {
        Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getInventory().getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho liên kết với phiếu giữ hàng " + reservation.getId()
                ));

        int qty = reservation.getQuantity();
        if (inventory.getReservedQty() < qty || (inventory.getTotalQty() - qty) < inventory.getUnavailableQty()) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Không thể xuất kho vì số dư tồn hoặc đang giữ không hợp lệ cho SKU " + inventory.getSkuId()
            );
        }

        inventory.setReservedQty(inventory.getReservedQty() - qty);
        inventory.setTotalQty(inventory.getTotalQty() - qty);
        inventoryRepository.save(inventory);

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.OUTBOUND,
                qty,
                reservation.getRefType().name(),
                reservation.getRefId(),
                Instant.now(),
                null,
                reason,
                null
        );
        ledgerRepository.save(ledger);

        eventPublisher.publishEvent(InventoryCommittedEvent.of(
                reservation.getId(),
                inventory.getSkuId(),
                inventory.getWarehouse().getId(),
                qty,
                reservation.getRefType(),
                reservation.getRefId()
        ));
    }

    private void releasePendingReservationsByRef(
            ReservationRefType refType,
            UUID refId,
            String reason,
            String defaultReason,
            String label
    ) {
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdForUpdate(refType, refId);
        if (reservations.isEmpty()) {
            log.warn("Không tìm thấy phiếu giữ kho nào cho {} {} khi giải phóng kho", label, refId);
            return;
        }

        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : defaultReason;
        int releasedCount = 0;

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.PENDING) {
                releaseSingleReservation(res, ReservationStatus.RELEASED, effectiveReason);
                releasedCount++;
            } else {
                log.info("Phiếu giữ kho {} của {} {} đã ở trạng thái {} (Idempotent), bỏ qua giải phóng",
                        res.getId(), label, refId, res.getStatus());
            }
        }

        log.info("Đã giải phóng {} phiếu giữ hàng cho {} {} với lý do: {}", releasedCount, label, refId, effectiveReason);
    }

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

    private void releaseSingleReservation(InventoryReservation reservation, ReservationStatus targetStatus, String reason) {
        Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getInventory().getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVENTORY_NOT_FOUND,
                        "Không tìm thấy bản ghi tồn kho liên kết với phiếu giữ hàng " + reservation.getId()
                ));

        int qty = reservation.getQuantity();
        if (inventory.getReservedQty() < qty) {
            throw new BusinessException(
                    ErrorCode.INVENTORY_INSUFFICIENT,
                    "Số lượng đang giữ trong kho nhỏ hơn số lượng phiếu cần giải phóng cho SKU " + inventory.getSkuId()
            );
        }

        inventory.setReservedQty(inventory.getReservedQty() - qty);
        inventoryRepository.save(inventory);

        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.RELEASE,
                qty,
                reservation.getRefType().name(),
                reservation.getRefId(),
                Instant.now(),
                null,
                reason,
                null
        );
        ledgerRepository.save(ledger);

        reservation.setStatus(targetStatus);
        reservationRepository.save(reservation);

        if (targetStatus == ReservationStatus.EXPIRED) {
            eventPublisher.publishEvent(InventoryReservationExpiredEvent.of(
                    reservation.getId(),
                    inventory.getSkuId(),
                    inventory.getWarehouse().getId(),
                    qty,
                    reservation.getRefType(),
                    reservation.getRefId()
            ));
        } else {
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

    private String buildOperationIdempotencyKey(String operationPrefix, String headerKey, String bodyKey) {
        String raw = (headerKey != null && !headerKey.isBlank()) ? headerKey.trim() : (bodyKey != null ? bodyKey.trim() : null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String prefixed = operationPrefix + ":" + raw;
        if (prefixed.length() <= 128) {
            return prefixed;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(prefixed.getBytes(StandardCharsets.UTF_8));
            return operationPrefix + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Không thể băm khóa idempotency", ex);
        }
    }

    private InventoryStockResponse verifyInboundReplay(
            InventoryLedger existing,
            UUID skuId,
            UUID warehouseId,
            int requestedQty,
            String skuCode
    ) {
        Inventory inv = existing.getInventory();
        if (existing.getMovementType() != LedgerMovementType.INBOUND
                || !inv.getSkuId().equals(skuId)
                || !inv.getWarehouse().getId().equals(warehouseId)
                || existing.getQuantity() != requestedQty) {
            throw new BusinessException(
                    ErrorCode.ORDER_IDEMPOTENCY_CONFLICT,
                    "Khóa idempotency hoặc chứng từ nhập kho đã được sử dụng với SKU/kho/số lượng khác"
            );
        }
        log.info("Yêu cầu nhập kho lặp (Idempotent) cho SKU {} tại kho {}, trả về trạng thái hiện tại", skuId, warehouseId);
        return toStockResponse(inv, skuCode);
    }

    private InventoryStockResponse verifyAdjustmentReplay(
            InventoryLedger existing,
            UUID skuId,
            UUID warehouseId,
            StockAdjustmentRequest request,
            String skuCode
    ) {
        Inventory inv = existing.getInventory();
        boolean mismatch = existing.getMovementType() != LedgerMovementType.ADJUSTMENT
                || !inv.getSkuId().equals(skuId)
                || !inv.getWarehouse().getId().equals(warehouseId)
                || (request.deltaQty() != null && existing.getQuantity() != request.deltaQty());
        if (mismatch) {
            throw new BusinessException(
                    ErrorCode.ORDER_IDEMPOTENCY_CONFLICT,
                    "Khóa idempotency hoặc chứng từ điều chỉnh kho đã được sử dụng với tham số khác"
            );
        }
        log.info("Yêu cầu điều chỉnh kho lặp (Idempotent) cho SKU {} tại kho {}, trả về trạng thái hiện tại", skuId, warehouseId);
        return toStockResponse(inv, skuCode);
    }

    private ReservationResponse toReservationResponse(
            ReservationRefType refType,
            UUID refId,
            List<InventoryReservation> reservations
    ) {
        List<ReservationItemResponse> items = reservations.stream()
                .map(r -> new ReservationItemResponse(
                        r.getId(),
                        r.getInventory().getSkuId(),
                        r.getInventory().getWarehouse().getId(),
                        r.getQuantity(),
                        r.getStatus(),
                        r.getExpiresAt()
                ))
                .toList();
        Instant latestExpiresAt = reservations.get(0).getExpiresAt();
        for (InventoryReservation res : reservations) {
            if (res.getExpiresAt().isAfter(latestExpiresAt)) {
                latestExpiresAt = res.getExpiresAt();
            }
        }
        return new ReservationResponse(refType, refId, reservations.get(0).getStatus(), items, latestExpiresAt);
    }

    private InventoryStockResponse toStockResponse(Inventory inv, String skuCode) {
        return new InventoryStockResponse(
                inv.getSkuId(),
                inv.getWarehouse().getId(),
                inv.getWarehouse().getCode(),
                inv.getWarehouse().getName(),
                skuCode,
                inv.getTotalQty(),
                inv.getAvailableQty(),
                inv.getReservedQty(),
                inv.getUnavailableQty()
        );
    }

    private InventoryLedgerResponse toLedgerResponse(InventoryLedger l) {
        return new InventoryLedgerResponse(
                l.getId(),
                l.getInventory().getSkuId(),
                l.getInventory().getWarehouse().getId(),
                l.getInventory().getWarehouse().getCode(),
                l.getMovementType(),
                l.getQuantity(),
                l.getRefType(),
                l.getRefId(),
                l.getReason(),
                l.getActorId(),
                l.getCreatedAt()
        );
    }
}
