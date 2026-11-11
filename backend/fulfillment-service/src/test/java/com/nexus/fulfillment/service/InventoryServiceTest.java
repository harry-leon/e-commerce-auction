package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
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
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SellerSkuDetailResponse;
import com.nexus.fulfillment.dto.response.SellerSkuResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.SellerSkuStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.InventoryCheckedEvent;
import com.nexus.fulfillment.event.InventoryCommittedEvent;
import com.nexus.fulfillment.event.InventoryReleasedEvent;
import com.nexus.fulfillment.event.InventoryReservationExpiredEvent;
import com.nexus.fulfillment.event.InventoryReservedEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.SellerSkuRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.security.InventoryAccessPolicy;
import com.nexus.fulfillment.service.impl.InventoryServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Kiểm thử nghiệp vụ lõi Quản lý tồn kho, Khóa giữ hàng Checkout và Vòng đời giữ kho Đấu giá (InventoryService) (SRS §3.6.1).
 *
 * Tình huống kiểm thử và lỗi bảo vệ:
 * 1. Khóa giữ hàng Checkout & Đấu giá: tăng reserved_qty, giảm available_qty, tạo phiếu PENDING, ghi sổ cái RESERVE, bắn sự kiện InventoryReserved.
 * 2. Chặn bán vượt tồn (ALLOW_OVERSELL = false): từ chối giữ kho và ném INVENTORY_INSUFFICIENT khi available_qty < số lượng yêu cầu.
 * 3. Vòng đời giữ hàng Đấu giá (Auction Inventory Lifecycle):
 *    - Khóa giữ kho khi tạo phiên (reserveAuctionStock): TTL bao quát toàn bộ phiên, quantity = 1, chống bán vượt.
 *    - Gia hạn chống sniping (extendAuctionReservation): kéo dài TTL thêm 5 phút hoặc mốc mới, ngăn sweep nhầm.
 *    - Hủy phiên & Thất bại (releaseByAuction): hoàn trả available_qty, ghi sổ cái RELEASE, Idempotent không tăng kho thừa.
 *    - Chuyển tiếp sang Đơn hàng (transitionAuctionToOrder): đổi AUCTION sang ORDER mà không nhả kho rồi giữ lại (chống Reserve kép).
 * 4. Xác nhận đơn hàng thành công (Confirm): trừ cả total_qty và reserved_qty, ghi sổ cái OUTBOUND, bắn sự kiện InventoryCommitted.
 * 5. Tự động hoàn kho phiếu quá hạn (Auto-release): quét các phiếu PENDING hết hạn, chuyển trạng thái EXPIRED, hoàn kho và bắn InventoryReservationExpired.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private InventoryLedgerRepository ledgerRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SellerSkuRepository sellerSkuRepository;

    @Mock
    private SellerSkuRegistrar sellerSkuRegistrar;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private FulfillmentEventOutboxService outboxService;

    private InventoryAccessPolicy accessPolicy;
    private FulfillmentProperties properties;
    private InventoryServiceImpl inventoryService;

    private Warehouse defaultWarehouse;
    private UUID skuId;
    private UUID sellerId;

    @BeforeEach
    void setUp() {
        properties = new FulfillmentProperties();
        properties.setDefaultWarehouseCode("WH_001");
        properties.setReservationTimeoutMinutes(30);
        properties.setAllowOversell(false);
        properties.setAntiSnipingExtensionMinutes(5);
        properties.setAuctionPaymentDeadlineMinutes(1440);
        properties.setAuctionMinDurationMinutes(60);
        properties.setAuctionMaxDurationHours(168);

        accessPolicy = new InventoryAccessPolicy(catalogClient);
        inventoryService = new InventoryServiceImpl(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                properties,
                eventPublisher,
                catalogClient,
                accessPolicy,
                sellerSkuRepository,
                sellerSkuRegistrar,
                outboxService,
                null
        );

        defaultWarehouse = new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "WH_001",
                "Kho trung tâm",
                "Hồ Chí Minh",
                WarehouseType.STANDARD,
                true
        );

        skuId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Xem tồn kho SKU: Trả về chính xác 4 trạng thái tồn kho (total, available, reserved, unavailable)")
    void testGetStockBySku_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 100, 20, 5);
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));

        // When
        InventoryStockResponse res = inventoryService.getStockBySku(skuId, null);

        // Then
        assertEquals(skuId, res.skuId());
        assertEquals(defaultWarehouse.getId(), res.warehouseId());
        assertEquals(100, res.totalQty());
        assertEquals(75, res.availableQty()); // 100 - 20 - 5 = 75
        assertEquals(20, res.reservedQty());
        assertEquals(5, res.unavailableQty());
    }

    @Test
    @DisplayName("Kiểm tra tồn kho khả dụng (Check): Tính đúng available và bắn sự kiện InventoryCheckedEvent")
    void testCheckAvailability_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 50, 10, 0);
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));

        SkuAvailabilityCheckRequest req = new SkuAvailabilityCheckRequest(List.of(skuId), null);

        // When
        List<SkuAvailabilityResponse> list = inventoryService.checkAvailability(req);

        // Then
        assertEquals(1, list.size());
        assertEquals(40, list.get(0).availableQty());
        assertTrue(list.get(0).inStock());
        verify(eventPublisher, times(1)).publishEvent(any(InventoryCheckedEvent.class));
    }

    @Test
    @DisplayName("Khóa giữ hàng khi Checkout: Thành công khi tồn kho khả dụng đủ, tạo phiếu PENDING và ghi sổ cái RESERVE")
    void testReserveStock_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 2, 0); // available = 8
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));

        UUID orderId = UUID.randomUUID();
        CreateReservationRequest req = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 3, null)),
                30
        );

        // When
        ReservationResponse response = inventoryService.reserveStock(req);

        // Then
        assertNotNull(response);
        assertEquals(ReservationStatus.PENDING, response.status());
        assertEquals(1, response.items().size());
        assertEquals(3, response.items().get(0).quantity());

        // Kiểm tra số lượng reserved_qty được tăng từ 2 lên 5
        assertEquals(5, inventory.getReservedQty());
        assertEquals(5, inventory.getAvailableQty()); // 10 - 5 - 0 = 5

        verify(inventoryRepository, times(1)).save(inventory);
        verify(reservationRepository, times(1)).save(any(InventoryReservation.class));

        // Kiểm tra bản ghi ghi vào Sổ cái tồn kho bất biến
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        InventoryLedger savedLedger = ledgerCaptor.getValue();
        assertEquals(LedgerMovementType.RESERVE, savedLedger.getMovementType());
        assertEquals(3, savedLedger.getQuantity());
        assertEquals("ORDER", savedLedger.getRefType());
        assertEquals(orderId, savedLedger.getRefId());

        // Kiểm tra bắn sự kiện miền InventoryReservedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReservedEvent.class));
    }

    @Test
    @DisplayName("Khóa giữ hàng khi Checkout: Từ chối và ném INVENTORY_INSUFFICIENT khi yêu cầu vượt quá tồn khả dụng")
    void testReserveStock_InsufficientStock_ThrowsException() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 8, 0); // available = 2
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));

        UUID orderId = UUID.randomUUID();
        CreateReservationRequest req = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 5, null)), // Yêu cầu 5 trong khi khả dụng chỉ có 2
                30
        );

        // When & Then
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.reserveStock(req));
        assertEquals(ErrorCode.INVENTORY_INSUFFICIENT.name(), ex.getCode());

        // Đảm bảo không lưu thay đổi và không ghi sổ cái khi bị từ chối
        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(InventoryReservedEvent.class));
    }

    // ==========================================
    // Kiểm thử Vòng đời giữ tồn kho Đấu giá (KH-S2-1)
    // ==========================================

    @Test
    @DisplayName("Khóa giữ kho Đấu giá: Khóa thành công 1 sản phẩm với TTL bao quát đến endTime")
    void testReserveAuctionStock_Success() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startTime = now.plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(24, ChronoUnit.HOURS);

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 0, 0); // available = 5
        when(reservationRepository.findByRefTypeAndRefId(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of());
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId()))
                .thenReturn(Optional.of(inventory));

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId, null, startTime, endTime, 1
        );

        // When
        ReservationResponse res = inventoryService.reserveAuctionStock(auctionId, req);

        // Then
        assertNotNull(res);
        assertEquals(ReservationRefType.AUCTION, res.refType());
        assertEquals(auctionId, res.refId());
        assertEquals(ReservationStatus.PENDING, res.status());
        assertEquals(endTime, res.expiresAt());
        assertEquals(1, res.items().size());
        assertEquals(1, res.items().get(0).quantity());

        // Kiểm tra tăng reserved_qty lên 1
        assertEquals(1, inventory.getReservedQty());
        assertEquals(4, inventory.getAvailableQty()); // 5 - 1 = 4
        verify(inventoryRepository, times(1)).save(inventory);

        // Kiểm tra lưu phiếu giữ kho
        ArgumentCaptor<InventoryReservation> resCaptor = ArgumentCaptor.forClass(InventoryReservation.class);
        verify(reservationRepository, times(1)).save(resCaptor.capture());
        assertEquals(ReservationRefType.AUCTION, resCaptor.getValue().getRefType());
        assertEquals(auctionId, resCaptor.getValue().getRefId());
        assertEquals(endTime, resCaptor.getValue().getExpiresAt());

        // Kiểm tra ghi sổ cái RESERVE
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.RESERVE, ledgerCaptor.getValue().getMovementType());
        assertEquals(1, ledgerCaptor.getValue().getQuantity());
        assertEquals("AUCTION", ledgerCaptor.getValue().getRefType());
        assertEquals(auctionId, ledgerCaptor.getValue().getRefId());

        // Kiểm tra phát sự kiện InventoryReservedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReservedEvent.class));
    }

    @Test
    @DisplayName("Khóa giữ kho Đấu giá: Từ chối khi hết hàng khả dụng (chống bán vượt tồn ALLOW_OVERSELL = false)")
    void testReserveAuctionStock_InsufficientStock_ThrowsException() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startTime = now.plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(2, ChronoUnit.HOURS);

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 2, 2, 0); // available = 0
        when(reservationRepository.findByRefTypeAndRefId(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of());
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId()))
                .thenReturn(Optional.of(inventory));

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId, null, startTime, endTime, 1
        );

        // When & Then
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.reserveAuctionStock(auctionId, req));
        assertEquals(ErrorCode.INVENTORY_INSUFFICIENT.name(), ex.getCode());

        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("Khóa giữ kho Đấu giá: Từ chối khi thời lượng phiên nhỏ hơn 60 phút")
    void testReserveAuctionStock_DurationTooShort_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startTime = now.plus(10, ChronoUnit.MINUTES);
        Instant endTime = startTime.plus(30, ChronoUnit.MINUTES); // 30 phút < 60 phút

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId, null, startTime, endTime, 1
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.reserveAuctionStock(auctionId, req));
        assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), ex.getCode());
    }

    @Test
    @DisplayName("Khóa giữ kho Đấu giá: Từ chối khi thời lượng phiên vượt quá 168 giờ (7 ngày)")
    void testReserveAuctionStock_DurationTooLong_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startTime = now.plus(10, ChronoUnit.MINUTES);
        Instant endTime = startTime.plus(180, ChronoUnit.HOURS); // 180 giờ > 168 giờ

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId, null, startTime, endTime, 1
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.reserveAuctionStock(auctionId, req));
        assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), ex.getCode());
    }

    @Test
    @DisplayName("Khóa giữ kho Đấu giá Idempotent: Nếu đã giữ kho PENDING cho auctionId thì trả về kết quả hiện tại")
    void testReserveAuctionStock_Idempotent() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startTime = now.plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(24, ChronoUnit.HOURS);

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 1, 0);
        InventoryReservation existingRes = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                endTime
        );

        when(reservationRepository.findByRefTypeAndRefId(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of(existingRes));

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId, null, startTime, endTime, 1
        );

        // When
        ReservationResponse res = inventoryService.reserveAuctionStock(auctionId, req);

        // Then
        assertEquals(ReservationRefType.AUCTION, res.refType());
        assertEquals(auctionId, res.refId());
        assertEquals(ReservationStatus.PENDING, res.status());

        // Đảm bảo không khóa thêm dòng tồn kho và không lưu thêm bản ghi
        verify(inventoryRepository, never()).findBySkuIdAndWarehouseIdForUpdate(any(), any());
        verify(inventoryRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("Gia hạn thời gian giữ kho (Anti-sniping): Tự động cộng thêm 5 phút khi kích hoạt Anti-sniping")
    void testExtendAuctionReservation_Success_WithMinutes() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Instant originalExpiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 1, 0);
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                originalExpiresAt
        );

        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        )).thenReturn(List.of(reservation));

        ExtendAuctionReservationRequest req = new ExtendAuctionReservationRequest(5, null);

        // When
        ReservationResponse res = inventoryService.extendAuctionReservation(auctionId, req);

        // Then
        Instant expectedExpiresAt = originalExpiresAt.plus(5, ChronoUnit.MINUTES);
        assertEquals(expectedExpiresAt, reservation.getExpiresAt());
        assertEquals(expectedExpiresAt, res.expiresAt());
        verify(reservationRepository, times(1)).save(reservation);
    }

    @Test
    @DisplayName("Gia hạn thời gian giữ kho (Anti-sniping): Gia hạn theo mốc newEndTime cụ thể")
    void testExtendAuctionReservation_Success_WithNewEndTime() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Instant originalExpiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant newEndTime = originalExpiresAt.plus(15, ChronoUnit.MINUTES);

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 1, 0);
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                originalExpiresAt
        );

        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        )).thenReturn(List.of(reservation));

        ExtendAuctionReservationRequest req = new ExtendAuctionReservationRequest(null, newEndTime);

        // When
        ReservationResponse res = inventoryService.extendAuctionReservation(auctionId, req);

        // Then
        assertEquals(newEndTime, reservation.getExpiresAt());
        assertEquals(newEndTime, res.expiresAt());
        verify(reservationRepository, times(1)).save(reservation);
    }

    @Test
    @DisplayName("Gia hạn thời gian giữ kho: Báo lỗi khi phiếu không ở trạng thái PENDING")
    void testExtendAuctionReservation_NonPending_ThrowsException() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 0, 0);
        InventoryReservation releasedRes = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.RELEASED,
                Instant.now()
        );

        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        )).thenReturn(List.of());

        when(reservationRepository.findByRefTypeAndRefId(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of(releasedRes));

        ExtendAuctionReservationRequest req = new ExtendAuctionReservationRequest(5, null);

        // When & Then
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.extendAuctionReservation(auctionId, req));
        assertEquals(ErrorCode.AUCTION_NOT_ACTIVE.name(), ex.getCode());
    }

    @Test
    @DisplayName("Hủy phiên & Giải phóng kho (Release by Auction): Tức thời giải phóng, hoàn trả available_qty, ghi sổ cái RELEASE")
    void testReleaseByAuction_Success() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 1, 0); // available = 9
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                Instant.now().plus(2, ChronoUnit.HOURS)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        inventoryService.releaseByAuction(auctionId, "AUCTION_CANCELLED");

        // Then
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(0, inventory.getReservedQty()); // 1 - 1 = 0
        assertEquals(10, inventory.getAvailableQty()); // 10 - 0 = 10

        // Ghi sổ cái RELEASE
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.RELEASE, ledgerCaptor.getValue().getMovementType());
        assertEquals("AUCTION", ledgerCaptor.getValue().getRefType());
        assertEquals(auctionId, ledgerCaptor.getValue().getRefId());

        // Bắn sự kiện InventoryReleasedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Hủy phiên & Giải phóng kho Idempotent: Phiếu đã RELEASED không bị hoàn trả thêm lần nữa (retry release không tăng kho thừa)")
    void testReleaseByAuction_Idempotent() {
        // Given
        UUID auctionId = UUID.randomUUID();
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 0, 0);
        InventoryReservation alreadyReleased = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.RELEASED,
                Instant.now().plus(2, ChronoUnit.HOURS)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.AUCTION, auctionId))
                .thenReturn(List.of(alreadyReleased));

        // When
        inventoryService.releaseByAuction(auctionId, "AUCTION_CANCELLED");

        // Then
        verify(inventoryRepository, never()).findByIdForUpdate(any());
        verify(inventoryRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Chuyển tiếp giữ kho sang Đơn hàng (Transition to Order): Chuyển đổi mã tham chiếu, không nhả kho, không Reserve kép")
    void testTransitionAuctionToOrder_Success() {
        // Given
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 1, 0);
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                Instant.now().plus(2, ChronoUnit.HOURS)
        );

        // Chưa có phiếu ORDER nào cho orderId
        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of());
        // Phiếu AUCTION đang PENDING
        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        )).thenReturn(List.of(reservation));

        TransitionAuctionToOrderRequest req = new TransitionAuctionToOrderRequest(orderId, 1440, null);

        // When
        ReservationResponse res = inventoryService.transitionAuctionToOrder(auctionId, req);

        // Then
        assertEquals(ReservationRefType.ORDER, res.refType());
        assertEquals(orderId, res.refId());
        assertEquals(ReservationStatus.PENDING, res.status());

        // Kiểm tra phiếu được cập nhật trực tiếp
        assertEquals(ReservationRefType.ORDER, reservation.getRefType());
        assertEquals(orderId, reservation.getRefId());
        verify(reservationRepository, times(1)).save(reservation);

        // Số lượng tồn kho tuyệt đối không bị thay đổi (không nhả kho và không trừ gấp đôi)
        assertEquals(1, inventory.getReservedQty());
        assertEquals(4, inventory.getAvailableQty());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Chuyển tiếp giữ kho sang Đơn hàng Idempotent: Đã chuyển tiếp trước đó thì trả về ngay kết quả")
    void testTransitionAuctionToOrder_Idempotent() {
        // Given
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 5, 1, 0);
        InventoryReservation transitionedRes = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                1,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(24, ChronoUnit.HOURS)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(transitionedRes));

        TransitionAuctionToOrderRequest req = new TransitionAuctionToOrderRequest(orderId, 1440, null);

        // When
        ReservationResponse res = inventoryService.transitionAuctionToOrder(auctionId, req);

        // Then
        assertEquals(ReservationRefType.ORDER, res.refType());
        assertEquals(orderId, res.refId());
        assertEquals(ReservationStatus.PENDING, res.status());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Chuyển tiếp giữ kho sang Đơn hàng: Báo lỗi khi không tìm thấy phiếu AUCTION PENDING")
    void testTransitionAuctionToOrder_NoPendingAuctionReservation_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of());
        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING
        )).thenReturn(List.of());

        TransitionAuctionToOrderRequest req = new TransitionAuctionToOrderRequest(orderId, 1440, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.transitionAuctionToOrder(auctionId, req));
        assertEquals(ErrorCode.INVENTORY_RESERVATION_NOT_FOUND.name(), ex.getCode());
    }

    // ==========================================
    // Các ca kiểm thử xác nhận xuất kho và quản trị
    // ==========================================

    @Test
    @DisplayName("Xác nhận xuất kho (Confirm): Giảm total_qty và reserved_qty, ghi sổ cái OUTBOUND, bắn InventoryCommittedEvent")
    void testConfirmReservation_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 5, 0);
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                reservationId,
                inventory,
                5,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        ReservationResponse res = inventoryService.confirmReservation(reservationId);

        // Then
        assertEquals(ReservationStatus.CONFIRMED, res.status());
        assertEquals(15, inventory.getTotalQty()); // 20 - 5 = 15
        assertEquals(0, inventory.getReservedQty()); // 5 - 5 = 0
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());

        // Kiểm tra ghi sổ cái OUTBOUND
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.OUTBOUND, ledgerCaptor.getValue().getMovementType());
        assertEquals(5, ledgerCaptor.getValue().getQuantity());

        // Kiểm tra bắn sự kiện miền InventoryCommittedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryCommittedEvent.class));
    }

    @Test
    @DisplayName("Xác nhận xuất kho (Confirm) Idempotent: Phiếu đã CONFIRMED thì trả về kết quả ngay, không trừ kho lặp")
    void testConfirmReservation_Idempotent() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 0, 0);
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                reservationId,
                inventory,
                5,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.CONFIRMED,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        // When
        ReservationResponse res = inventoryService.confirmReservation(reservationId);

        // Then
        assertEquals(ReservationStatus.CONFIRMED, res.status());
        assertEquals(reservationId, res.items().get(0).reservationId());

        // Đảm bảo không gọi trừ tồn kho hay ghi thêm sổ cái lặp lại
        verify(inventoryRepository, never()).findByIdForUpdate(any());
        verify(inventoryRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(InventoryCommittedEvent.class));
    }

    @Test
    @DisplayName("Xác nhận trừ kho theo đơn hàng (Commit by Order): Trừ vĩnh viễn reserved_qty và total_qty, ghi sổ cái OUTBOUND, bắn InventoryCommittedEvent")
    void testCommitByOrder_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 50, 10, 0);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                reservationId,
                inventory,
                10,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        ReservationResponse res = inventoryService.commitByOrder(orderId);

        // Then
        assertEquals(ReservationStatus.CONFIRMED, res.status());
        assertEquals(1, res.items().size());
        assertEquals(40, inventory.getTotalQty()); // 50 - 10 = 40
        assertEquals(0, inventory.getReservedQty()); // 10 - 10 = 0
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());

        // Kiểm tra ghi sổ cái OUTBOUND
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.OUTBOUND, ledgerCaptor.getValue().getMovementType());
        assertEquals(10, ledgerCaptor.getValue().getQuantity());

        // Kiểm tra phát sinh sự kiện InventoryCommittedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryCommittedEvent.class));
    }

    @Test
    @DisplayName("Xác nhận trừ kho theo đơn hàng Idempotent: Đơn đã CONFIRMED không bị trừ kho lần 2 khi nhận trùng webhook")
    void testCommitByOrder_Idempotent() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 40, 0, 0);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        InventoryReservation confirmedReservation = new InventoryReservation(
                reservationId,
                inventory,
                10,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.CONFIRMED,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(confirmedReservation));

        // When
        ReservationResponse res = inventoryService.commitByOrder(orderId);

        // Then
        assertEquals(ReservationStatus.CONFIRMED, res.status());
        assertEquals(orderId, res.refId());

        // Đảm bảo không lưu thay đổi và không ghi sổ cái lần 2
        verify(inventoryRepository, never()).findByIdForUpdate(any());
        verify(inventoryRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(InventoryCommittedEvent.class));
    }

    @Test
    @DisplayName("Xác nhận trừ kho theo đơn hàng: Từ chối khi phiếu giữ kho đã hết hạn hoặc bị hủy")
    void testCommitByOrder_ExpiredOrReleased_ThrowsException() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 50, 0, 0);
        UUID orderId = UUID.randomUUID();
        InventoryReservation expiredRes = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                5,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.EXPIRED,
                Instant.now().minus(5, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(expiredRes));

        // When & Then
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.commitByOrder(orderId));
        assertEquals(ErrorCode.INVENTORY_RESERVATION_EXPIRED.name(), ex.getCode());
    }

    @Test
    @DisplayName("Giải phóng tồn kho theo đơn hàng (Release by Order): Hoàn trả available_qty, ghi sổ cái RELEASE, bắn InventoryReleasedEvent")
    void testReleaseByOrder_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 30, 6, 0);
        UUID orderId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                6,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        inventoryService.releaseByOrder(orderId, "PAYMENT_FAILED");

        // Then
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(0, inventory.getReservedQty()); // 6 - 6 = 0, hoàn trả lại cho available
        assertEquals(30, inventory.getAvailableQty());

        // Kiểm tra ghi sổ cái RELEASE
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.RELEASE, ledgerCaptor.getValue().getMovementType());

        // Kiểm tra bắn sự kiện InventoryReleasedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Giải phóng tồn kho theo đơn hàng Idempotent: Phiếu đã RELEASED không bị hoàn trả thêm lần nữa")
    void testReleaseByOrder_Idempotent() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 30, 0, 0);
        UUID orderId = UUID.randomUUID();
        InventoryReservation alreadyReleased = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                6,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.RELEASED,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(alreadyReleased));

        // When
        inventoryService.releaseByOrder(orderId, "ORDER_CANCELLED");

        // Then
        verify(inventoryRepository, never()).findByIdForUpdate(any());
        verify(inventoryRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Giải phóng giữ hàng (Release): Hoàn trả tồn khả dụng, ghi sổ cái RELEASE, bắn InventoryReleasedEvent")
    void testReleaseReservation_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 4, 0);
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                reservationId,
                inventory,
                4,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        inventoryService.releaseReservation(reservationId, "BUYER_CANCELLED");

        // Then
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(0, inventory.getReservedQty()); // 4 - 4 = 0
        assertEquals(20, inventory.getAvailableQty()); // Hoàn trả lại tồn khả dụng

        // Kiểm tra ghi sổ cái RELEASE
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.RELEASE, ledgerCaptor.getValue().getMovementType());

        // Kiểm tra bắn sự kiện InventoryReleasedEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Giải phóng giữ hàng theo Ref: Hoàn kho toàn bộ các phiếu PENDING của đơn hàng")
    void testReleaseReservationsByRef_Success() {
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 4, 0);
        UUID orderId = UUID.randomUUID();
        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(),
                inventory,
                4,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        when(reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                orderId
        )).thenReturn(List.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        inventoryService.releaseReservationsByRef(new ReleaseReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                "ORDER_CANCELLED"
        ));

        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(0, inventory.getReservedQty());
        verify(ledgerRepository, times(1)).save(any(InventoryLedger.class));
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReleasedEvent.class));
    }

    @Test
    @DisplayName("Tự động hoàn kho phiếu quá hạn 30 phút (Auto-release): Chuyển sang EXPIRED và bắn InventoryReservationExpiredEvent")
    void testAutoReleaseExpiredReservations_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 2, 0);
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant expiredTime = Instant.now().minus(5, ChronoUnit.MINUTES);
        InventoryReservation expiredReservation = new InventoryReservation(
                reservationId,
                inventory,
                2,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                expiredTime
        );

        // Sweeper chỉ nạp ID trước khi khóa, để nhận trạng thái mới nhất nếu callback vừa commit kho.
        when(reservationRepository.findExpiredIds(eq(ReservationStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(reservationId));
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(expiredReservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        // When
        int count = inventoryService.autoReleaseExpiredReservations();

        // Then
        assertEquals(1, count);
        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(0, inventory.getReservedQty()); // 2 - 2 = 0, hoàn trả lại cho available_qty
        assertEquals(10, inventory.getAvailableQty());

        // Kiểm tra ghi sổ cái RELEASE
        verify(ledgerRepository, times(1)).save(any(InventoryLedger.class));

        // Kiểm tra bắn sự kiện miền InventoryReservationExpiredEvent
        verify(eventPublisher, times(1)).publishEvent(any(InventoryReservationExpiredEvent.class));
    }

    @Test
    @DisplayName("Nhập kho lần đầu (Inbound): Xác thực Catalog, cấp phát FSKU-XXXXXXXX, ghi sổ cái INBOUND và Outbox")
    void testInboundStock_Success() {
        setSellerAuth(sellerId);
        SellerSku generatedSellerSku = new SellerSku(
                UUID.randomUUID(),
                sellerId,
                skuId,
                "FSKU-00000001",
                SellerSkuStatus.ACTIVE
        );
        Inventory existingInventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 50, 0, 0);
        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(
                skuId,
                UUID.randomUUID(),
                "Áo khoác Nexus",
                new BigDecimal("250000"),
                new BigDecimal("200000"),
                sellerId,
                true
        )));
        when(sellerSkuRepository.findBySkuId(skuId)).thenReturn(Optional.empty());
        when(sellerSkuRegistrar.registerIfAbsent(skuId, sellerId))
                .thenReturn(new SellerSkuRegistrar.RegistrationResult(generatedSellerSku, true));
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySellerSkuIdAndWarehouseIdForUpdate(
                generatedSellerSku.getId(),
                defaultWarehouse.getId()
        )).thenReturn(Optional.empty());
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId()))
                .thenReturn(Optional.of(existingInventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockInboundRequest req = new StockInboundRequest(skuId, null, 25, UUID.randomUUID());

        InventoryStockResponse res = inventoryService.inboundStock(req);

        assertEquals(75, res.totalQty());
        assertEquals(75, res.availableQty());
        assertEquals(generatedSellerSku.getId(), res.sellerSkuId());
        assertEquals("FSKU-00000001", res.skuCode());

        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.INBOUND, ledgerCaptor.getValue().getMovementType());
        assertEquals(25, ledgerCaptor.getValue().getQuantity());
        verify(outboxService, times(1)).enqueueInboundCompleted(eq(generatedSellerSku), any(Inventory.class), eq(25), eq(true));
    }

    @Test
    @DisplayName("Nhập kho lần hai trở đi: Tái sử dụng đúng skuCode FSKU- đã cấp và phát outbox firstInbound=false")
    void testInboundStock_RepeatInboundReusesExistingSkuCode() {
        setSellerAuth(sellerId);
        SellerSku existingSellerSku = new SellerSku(
                UUID.randomUUID(),
                sellerId,
                skuId,
                "FSKU-00000007",
                SellerSkuStatus.ACTIVE
        );
        Inventory existingInventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 2, 0);
        existingInventory.setSellerSku(existingSellerSku);

        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(
                skuId,
                UUID.randomUUID(),
                "Áo khoác Nexus",
                new BigDecimal("250000"),
                new BigDecimal("200000"),
                sellerId,
                true
        )));
        when(sellerSkuRepository.findBySkuId(skuId)).thenReturn(Optional.of(existingSellerSku));
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySellerSkuIdAndWarehouseIdForUpdate(
                existingSellerSku.getId(),
                defaultWarehouse.getId()
        )).thenReturn(Optional.of(existingInventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryStockResponse res = inventoryService.inboundStock(
                new StockInboundRequest(skuId, null, 5, UUID.randomUUID())
        );

        assertEquals("FSKU-00000007", res.skuCode());
        assertEquals(15, res.totalQty());
        assertEquals(13, res.availableQty());
        verify(sellerSkuRegistrar, never()).registerIfAbsent(any(), any());
        verify(outboxService, times(1)).enqueueInboundCompleted(eq(existingSellerSku), any(Inventory.class), eq(5), eq(false));
    }

    @Test
    @DisplayName("Nhập kho đồng thời gặp DataIntegrityViolationException ở seller_skus: Đọc lại bản ghi của luồng thắng")
    void testInboundStock_ConcurrentRaceRetriesReadFromDb() {
        setSellerAuth(sellerId);
        SellerSku winnerSellerSku = new SellerSku(
                UUID.randomUUID(),
                sellerId,
                skuId,
                "FSKU-00000099",
                SellerSkuStatus.ACTIVE
        );
        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(
                skuId,
                UUID.randomUUID(),
                "Race Product",
                new BigDecimal("100000"),
                new BigDecimal("90000"),
                sellerId,
                true
        )));
        when(sellerSkuRepository.findBySkuId(skuId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerSellerSku));
        when(sellerSkuRegistrar.registerIfAbsent(skuId, sellerId))
                .thenThrow(new DataIntegrityViolationException("duplicate sku_id"));
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySellerSkuIdAndWarehouseIdForUpdate(
                winnerSellerSku.getId(),
                defaultWarehouse.getId()
        )).thenReturn(Optional.empty());
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId()))
                .thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryStockResponse res = inventoryService.inboundStock(
                new StockInboundRequest(skuId, null, 8, UUID.randomUUID())
        );

        assertEquals("FSKU-00000099", res.skuCode());
        assertEquals(8, res.totalQty());
        verify(outboxService, times(1)).enqueueInboundCompleted(eq(winnerSellerSku), any(Inventory.class), eq(8), eq(false));
    }

    @Test
    @DisplayName("Nhập kho bị từ chối 403 AUTH_FORBIDDEN nếu Seller nhập SKU thuộc Seller khác")
    void testInboundStock_DifferentSellerForbidden() {
        UUID otherSellerId = UUID.randomUUID();
        setSellerAuth(sellerId);
        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(
                skuId,
                UUID.randomUUID(),
                "Other Seller Product",
                new BigDecimal("100000"),
                new BigDecimal("80000"),
                otherSellerId,
                true
        )));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> inventoryService.inboundStock(new StockInboundRequest(skuId, null, 5, UUID.randomUUID()))
        );
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
        verify(sellerSkuRegistrar, never()).registerIfAbsent(any(), any());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Admin được phép nhập kho hộ Seller bằng sellerId lấy từ Catalog")
    void testInboundStock_AdminCanInboundForAnySeller() {
        setAdminAuth();
        SellerSku created = new SellerSku(UUID.randomUUID(), sellerId, skuId, "FSKU-00000010", SellerSkuStatus.ACTIVE);
        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(
                skuId,
                UUID.randomUUID(),
                "Seller Product",
                new BigDecimal("100000"),
                new BigDecimal("90000"),
                sellerId,
                true
        )));
        when(sellerSkuRepository.findBySkuId(skuId)).thenReturn(Optional.empty());
        when(sellerSkuRegistrar.registerIfAbsent(skuId, sellerId))
                .thenReturn(new SellerSkuRegistrar.RegistrationResult(created, true));
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySellerSkuIdAndWarehouseIdForUpdate(created.getId(), defaultWarehouse.getId()))
                .thenReturn(Optional.empty());
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId()))
                .thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryStockResponse res = inventoryService.inboundStock(
                new StockInboundRequest(skuId, null, 4, UUID.randomUUID())
        );

        assertEquals("FSKU-00000010", res.skuCode());
        assertEquals(4, res.totalQty());
    }

    @Test
    @DisplayName("Nhập kho thất bại 404 SKU_NOT_FOUND khi SKU không tồn tại bên Catalog")
    void testInboundStock_CatalogMissingThrows404() {
        setSellerAuth(sellerId);
        when(catalogClient.findSkuById(skuId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> inventoryService.inboundStock(new StockInboundRequest(skuId, null, 1, null))
        );
        assertEquals(ErrorCode.SKU_NOT_FOUND.name(), ex.getCode());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nhập kho thất bại 502 BAD_GATEWAY khi Catalog Service gián đoạn")
    void testInboundStock_CatalogUnavailableThrows502() {
        setSellerAuth(sellerId);
        when(catalogClient.findSkuById(skuId))
                .thenThrow(new ApiException("CATALOG_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Down", null));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> inventoryService.inboundStock(new StockInboundRequest(skuId, null, 1, null))
        );
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tra cứu GET /inventory/skus theo Seller: Tự động scope sellerId và tổng hợp tồn kho đa kho")
    void testGetSellerSkus_SellerScopedAndAggregatesStock() {
        setSellerAuth(sellerId);
        SellerSku s = new SellerSku(UUID.randomUUID(), sellerId, skuId, "FSKU-00000021", SellerSkuStatus.ACTIVE);
        Warehouse wh2 = new Warehouse(UUID.randomUUID(), "WH_002", "Kho Hà Nội", "Hà Nội", WarehouseType.STANDARD, true);
        Inventory inv1 = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 10, 2, 1);
        inv1.setSellerSku(s);
        Inventory inv2 = new Inventory(UUID.randomUUID(), skuId, wh2, 20, 5, 0);
        inv2.setSellerSku(s);

        Pageable pageable = PageRequest.of(0, 20);
        when(sellerSkuRepository.findAll(ArgumentMatchers.<Specification<SellerSku>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(s), pageable, 1));
        when(inventoryRepository.findBySellerSkuIdInWithWarehouse(List.of(s.getId())))
                .thenReturn(List.of(inv1, inv2));

        Page<SellerSkuResponse> res = inventoryService.getSellerSkus(" FSKU ", SellerSkuStatus.ACTIVE, null, pageable);

        assertEquals(1, res.getTotalElements());
        SellerSkuResponse item = res.getContent().get(0);
        assertEquals("FSKU-00000021", item.skuCode());
        assertEquals(30, item.totalQty());
        assertEquals(22, item.availableQty());
        assertEquals(7, item.reservedQty());
        assertEquals(1, item.unavailableQty());
        assertEquals(2, item.warehouseCount());
    }

    @Test
    @DisplayName("Seller truyền sellerId của người bán khác khi tra cứu danh sách bị chặn 403 AUTH_FORBIDDEN")
    void testGetSellerSkus_SellerRequestingOtherSellerForbidden() {
        setSellerAuth(sellerId);
        Pageable pageable = PageRequest.of(0, 20);
        UUID otherSeller = UUID.randomUUID();

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> inventoryService.getSellerSkus(null, null, otherSeller, pageable)
        );
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
    }

    @Test
    @DisplayName("Chi tiết seller_sku: Chặn Seller xem SKU người khác và trả danh sách kho cho chính chủ")
    void testGetSellerSkuDetail_SuccessAndForbidden() {
        SellerSku owned = new SellerSku(UUID.randomUUID(), sellerId, skuId, "FSKU-00000033", SellerSkuStatus.ACTIVE);
        Inventory inv = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 12, 3, 1);
        inv.setSellerSku(owned);

        when(sellerSkuRepository.findByIdOrSkuId(owned.getId(), owned.getId())).thenReturn(Optional.of(owned));
        when(inventoryRepository.findBySellerSkuIdInWithWarehouse(List.of(owned.getId()))).thenReturn(List.of(inv));

        setSellerAuth(sellerId);
        SellerSkuDetailResponse detail = inventoryService.getSellerSkuDetail(owned.getId());
        assertEquals("FSKU-00000033", detail.skuCode());
        assertEquals(1, detail.warehouses().size());
        assertEquals(12, detail.totalQty());
        assertEquals(8, detail.availableQty());

        setSellerAuth(UUID.randomUUID());
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> inventoryService.getSellerSkuDetail(owned.getId())
        );
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
    }

    private void setSellerAuth(UUID callerSellerId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        callerSellerId.toString(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SELLER"))
                )
        );
    }

    private void setAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );
    }

    @Test
    @DisplayName("Điều chỉnh tồn kho (Adjust): Giảm tổng số lượng vượt quá mức khả dụng sẽ bị từ chối")
    void testAdjustStock_NegativeExceedingAvailable_ThrowsException() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 15, 0); // available = 5
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));

        StockAdjustmentRequest req = new StockAdjustmentRequest(skuId, null, -10, "Hàng vỡ", UUID.randomUUID());

        // When & Then: giảm 10 làm tổng mới = 10, trong khi đang giữ 15 -> vi phạm ràng buộc
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.adjustStock(req));
        assertEquals(ErrorCode.INVENTORY_INSUFFICIENT.name(), ex.getCode());
    }

    @Test
    @DisplayName("Tra cứu Sổ cái bất biến: Trả về danh sách biến động có phân trang")
    void testGetLedger_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 20, 0, 0);
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(),
                inventory,
                LedgerMovementType.INBOUND,
                20,
                "INBOUND",
                null,
                Instant.now()
        );
        Pageable pageable = PageRequest.of(0, 10);
        Page<InventoryLedger> page = new PageImpl<>(List.of(ledger), pageable, 1);

        when(ledgerRepository.searchLedger(skuId, null, pageable)).thenReturn(page);

        // When
        Page<InventoryLedgerResponse> result = inventoryService.getLedger(skuId, null, pageable);

        // Then
        assertEquals(1, result.getTotalElements());
        assertEquals(LedgerMovementType.INBOUND, result.getContent().get(0).movementType());
        assertEquals(20, result.getContent().get(0).quantity());
    }

    @Test
    @DisplayName("Tra cứu GET /inventory/skus chuẩn hóa sort không hợp lệ và giới hạn kích thước trang")
    void testGetSellerSkus_SanitizesInvalidSortAndOversizedPage() {
        // Bảo vệ endpoint danh sách seller_skus trước lỗi 500 từ tham số mặc định Swagger sort=["string"] & size lớn
        setSellerAuth(sellerId);
        Pageable rawSwaggerPageable = PageRequest.of(0, 500, Sort.by("[\"string\"]"));
        Pageable expectedSafePageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));

        when(sellerSkuRepository.findAll(ArgumentMatchers.<Specification<SellerSku>>any(), eq(expectedSafePageable)))
                .thenReturn(new PageImpl<>(List.of(), expectedSafePageable, 0));

        Page<SellerSkuResponse> res = inventoryService.getSellerSkus(null, null, null, rawSwaggerPageable);

        assertEquals(0, res.getTotalElements());
        verify(sellerSkuRepository).findAll(ArgumentMatchers.<Specification<SellerSku>>any(), eq(expectedSafePageable));
    }

    @Test
    @DisplayName("Reserve ORDER lặp lại trả về phiếu PENDING cũ và chặn mismatch hoặc phiếu đã đóng")
    void reserveStock_OrderIdempotentReplayAndConflict() {
        UUID skuId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Warehouse wh = new Warehouse(warehouseId, "WH_001", "Kho mac dinh", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        Inventory inv = new Inventory(UUID.randomUUID(), skuId, wh, 10, 3, 0);
        InventoryReservation existingPending = new InventoryReservation(
                UUID.randomUUID(),
                inv,
                3,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                Instant.now().plusSeconds(600)
        );

        when(warehouseRepository.findByIdAndIsActiveTrue(warehouseId)).thenReturn(Optional.of(wh));
        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(existingPending));

        CreateReservationRequest matchingRetry = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new com.nexus.fulfillment.dto.request.ReservationItemRequest(skuId, 3, warehouseId)),
                15,
                null
        );
        com.nexus.fulfillment.dto.response.ReservationResponse replay = inventoryService.reserveStock(matchingRetry);
        assertEquals(ReservationStatus.PENDING, replay.status());
        assertEquals(1, replay.items().size());
        assertEquals(3, replay.items().get(0).quantity());
        verify(inventoryRepository, never()).findBySkuIdAndWarehouseIdForUpdate(any(), any());

        // Thao tác lặp cùng orderId nhưng khác số lượng -> ORDER_IDEMPOTENCY_CONFLICT
        CreateReservationRequest conflictingRetry = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new com.nexus.fulfillment.dto.request.ReservationItemRequest(skuId, 5, warehouseId)),
                15,
                null
        );
        BusinessException conflictEx = assertThrows(BusinessException.class,
                () -> inventoryService.reserveStock(conflictingRetry));
        assertEquals(ErrorCode.ORDER_IDEMPOTENCY_CONFLICT.name(), conflictEx.getCode());

        // Khi phiếu giữ kho đã RELEASED -> chặn giữ lại đơn đã đóng bằng INVENTORY_RESERVATION_EXPIRED
        InventoryReservation closedRes = new InventoryReservation(
                UUID.randomUUID(),
                inv,
                3,
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.RELEASED,
                Instant.now().plusSeconds(600)
        );
        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(closedRes));
        BusinessException expiredEx = assertThrows(BusinessException.class,
                () -> inventoryService.reserveStock(matchingRetry));
        assertEquals(ErrorCode.INVENTORY_RESERVATION_EXPIRED.name(), expiredEx.getCode());
    }

    @Test
    @DisplayName("Confirm phiếu giữ kho đã bị RELEASED ném INVENTORY_RESERVATION_EXPIRED để chặn xuất muộn")
    void confirmReservation_RejectsReleasedReservation() {
        UUID reservationId = UUID.randomUUID();
        Warehouse wh = new Warehouse(UUID.randomUUID(), "WH_001", "Kho mac dinh", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        Inventory inv = new Inventory(UUID.randomUUID(), UUID.randomUUID(), wh, 10, 0, 0);
        InventoryReservation released = new InventoryReservation(
                reservationId,
                inv,
                2,
                ReservationRefType.ORDER,
                UUID.randomUUID(),
                ReservationStatus.RELEASED,
                Instant.now().minusSeconds(60)
        );

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(released));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.confirmReservation(reservationId));
        assertEquals(ErrorCode.INVENTORY_RESERVATION_EXPIRED.name(), ex.getCode());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nhập kho theo skuCode với Idempotency-Key chống lặp cộng dồn và chặn Seller khác")
    void inboundStock_ResolvesSkuCode_EnforcesIdempotencyAndSellerOwnership() {
        UUID sellerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Warehouse wh = new Warehouse(warehouseId, "WH_001", "Kho TP.HCM", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        com.nexus.fulfillment.client.CatalogClient mockCatalog =
                org.mockito.Mockito.mock(com.nexus.fulfillment.client.CatalogClient.class);
        com.nexus.fulfillment.security.InventoryAccessPolicy mockPolicy =
                org.mockito.Mockito.mock(com.nexus.fulfillment.security.InventoryAccessPolicy.class);
        InventoryServiceImpl scopedService = new InventoryServiceImpl(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                new com.nexus.fulfillment.config.FulfillmentProperties(),
                eventPublisher,
                mockCatalog,
                mockPolicy,
                sellerSkuRepository,
                sellerSkuRegistrar,
                outboxService,
                null
        );

        SellerSku camSku = new SellerSku(UUID.randomUUID(), sellerId, skuId, "SKU-CAM-01", SellerSkuStatus.ACTIVE);
        when(sellerSkuRepository.findBySkuCodeIgnoreCase("SKU-CAM-01")).thenReturn(Optional.of(camSku));
        when(mockPolicy.verifyWriteAccessAndOwnership(skuId)).thenReturn(
                new com.nexus.fulfillment.security.InventoryAccessPolicy.ActorContext(sellerId, false, false, true, false));
        when(warehouseRepository.findByIdAndIsActiveTrue(warehouseId)).thenReturn(Optional.of(wh));
        when(ledgerRepository.findByIdempotencyKey("INBOUND:key-inb-1")).thenReturn(Optional.empty());
        Inventory inv = new Inventory(UUID.randomUUID(), skuId, wh, 5, 0, 0);
        inv.setSellerSku(camSku);
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, warehouseId)).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(i -> i.getArgument(0));
        when(ledgerRepository.save(any(InventoryLedger.class))).thenAnswer(i -> i.getArgument(0));

        StockInboundRequest req = new StockInboundRequest(null, "SKU-CAM-01", warehouseId, 10, null, "Nhap lo 1", null);
        InventoryStockResponse first = scopedService.inboundStock(req, "key-inb-1");

        assertEquals(15, first.totalQty());
        assertEquals(15, first.availableQty());
        assertEquals("SKU-CAM-01", first.skuCode());

        // Replay cùng Idempotency-Key + cùng số lượng -> không cộng thêm kho, không lưu sổ cái mới
        InventoryLedger recorded = new InventoryLedger(
                UUID.randomUUID(), inv, LedgerMovementType.INBOUND, 10, "INBOUND", null, Instant.now(),
                "INBOUND:key-inb-1", "Nhap lo 1", sellerId
        );
        when(ledgerRepository.findByIdempotencyKey("INBOUND:key-inb-1")).thenReturn(Optional.of(recorded));

        InventoryStockResponse replay = scopedService.inboundStock(req, "key-inb-1");
        assertEquals(15, replay.totalQty());
        verify(inventoryRepository, org.mockito.Mockito.times(1)).save(any());

        // Replay cùng Idempotency-Key nhưng đổi số lượng -> ORDER_IDEMPOTENCY_CONFLICT
        StockInboundRequest tampered = new StockInboundRequest(null, "SKU-CAM-01", warehouseId, 99, null, "Nhap sai", null);
        BusinessException conflictEx = assertThrows(BusinessException.class,
                () -> scopedService.inboundStock(tampered, "key-inb-1"));
        assertEquals(ErrorCode.ORDER_IDEMPOTENCY_CONFLICT.name(), conflictEx.getCode());

        // Seller không sở hữu SKU -> AUTH_FORBIDDEN
        UUID foreignSku = UUID.randomUUID();
        when(mockPolicy.verifyWriteAccessAndOwnership(foreignSku))
                .thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN, "Khong thuoc so huu"));
        StockInboundRequest foreignReq = new StockInboundRequest(foreignSku, warehouseId, 5, null);
        BusinessException forbiddenEx = assertThrows(BusinessException.class,
                () -> scopedService.inboundStock(foreignReq, "key-foreign"));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), forbiddenEx.getCode());
    }

    @Test
    @DisplayName("Điều chỉnh kho hỗ trợ targetTotalQty, no-op khi không đổi, chống âm kho và replay theo refId")
    void adjustStock_TargetQty_NoOp_NonNegative_AndReplay() {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        Warehouse wh = new Warehouse(warehouseId, "WH_001", "Kho TP.HCM", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        Inventory inv = new Inventory(UUID.randomUUID(), skuId, wh, 10, 4, 1);

        when(warehouseRepository.findByIdAndIsActiveTrue(warehouseId)).thenReturn(Optional.of(wh));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, warehouseId)).thenReturn(Optional.of(inv));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(i -> i.getArgument(0));
        when(ledgerRepository.save(any(InventoryLedger.class))).thenAnswer(i -> i.getArgument(0));

        // targetTotalQty = 10 bằng đúng tổng tồn hiện tại -> không lưu DB/ledger
        StockAdjustmentRequest noopReq = new StockAdjustmentRequest(skuId, null, warehouseId, null, 10, null, "Kiem ke khop", null, null);
        InventoryStockResponse noopRes = inventoryService.adjustStock(noopReq);
        assertEquals(10, noopRes.totalQty());
        verify(ledgerRepository, never()).save(any());

        // Cố giảm xuống 3 khi reserved + unavailable = 5 -> chặn INVENTORY_INSUFFICIENT
        StockAdjustmentRequest negativeAvailableReq = new StockAdjustmentRequest(
                skuId, null, warehouseId, null, 3, null, "Giam qua muc dang giu", null, null);
        BusinessException insufficientEx = assertThrows(BusinessException.class,
                () -> inventoryService.adjustStock(negativeAvailableReq));
        assertEquals(ErrorCode.INVENTORY_INSUFFICIENT.name(), insufficientEx.getCode());

        // Giảm xuống targetTotalQty = 8 (hợp lệ vì 8 >= 4 + 1) -> ghi ledger ADJUSTMENT = -2
        StockAdjustmentRequest validTargetReq = new StockAdjustmentRequest(
                skuId, null, warehouseId, null, 8, null, "Hao hut kiem ke", refId, null);
        when(ledgerRepository.findFirstByInventoryIdAndMovementTypeAndRefId(inv.getId(), LedgerMovementType.ADJUSTMENT, refId))
                .thenReturn(Optional.empty());
        InventoryStockResponse adjusted = inventoryService.adjustStock(validTargetReq);
        assertEquals(8, adjusted.totalQty());
        assertEquals(3, adjusted.availableQty());
        verify(ledgerRepository, org.mockito.Mockito.times(1)).save(any());

        // Retry theo refId -> idempotent trả về tồn hiện tại, không trừ thêm lần nữa
        InventoryLedger recorded = new InventoryLedger(
                UUID.randomUUID(), inv, LedgerMovementType.ADJUSTMENT, -2, "ADJUSTMENT", refId, Instant.now()
        );
        when(ledgerRepository.findFirstByInventoryIdAndMovementTypeAndRefId(inv.getId(), LedgerMovementType.ADJUSTMENT, refId))
                .thenReturn(Optional.of(recorded));
        InventoryStockResponse replay = inventoryService.adjustStock(validTargetReq);
        assertEquals(8, replay.totalQty());
        verify(ledgerRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("Đọc batch theo nhiều SKU, tra cứu theo skuCode và giới hạn phạm vi listInventory theo Seller")
    @SuppressWarnings("unchecked")
    void batchRead_SkuCodeLookup_AndListInventorySellerScope() {
        UUID sellerId = UUID.randomUUID();
        UUID sku1 = UUID.randomUUID();
        UUID skuMissing = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Warehouse wh = new Warehouse(warehouseId, "WH_001", "Kho TP.HCM", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        Inventory inv1 = new Inventory(UUID.randomUUID(), sku1, wh, 12, 2, 0);

        com.nexus.fulfillment.client.CatalogClient mockCatalog =
                org.mockito.Mockito.mock(com.nexus.fulfillment.client.CatalogClient.class);
        com.nexus.fulfillment.security.InventoryAccessPolicy mockPolicy =
                org.mockito.Mockito.mock(com.nexus.fulfillment.security.InventoryAccessPolicy.class);
        InventoryServiceImpl scopedService = new InventoryServiceImpl(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                new com.nexus.fulfillment.config.FulfillmentProperties(),
                eventPublisher,
                mockCatalog,
                mockPolicy,
                sellerSkuRepository,
                sellerSkuRegistrar,
                outboxService,
                null
        );

        when(warehouseRepository.findByIdAndIsActiveTrue(warehouseId)).thenReturn(Optional.of(wh));
        when(inventoryRepository.findBySkuIdInAndWarehouseId(List.of(sku1, skuMissing), warehouseId))
                .thenReturn(List.of(inv1));

        List<InventoryStockResponse> batch = scopedService.getBatchStock(
                new com.nexus.fulfillment.dto.request.BatchStockRequest(List.of(sku1, skuMissing, sku1), warehouseId));
        assertEquals(2, batch.size());
        assertEquals(12, batch.get(0).totalQty());
        assertEquals(0, batch.get(1).totalQty());

        SellerSku sellerSku1 = new SellerSku(UUID.randomUUID(), sellerId, sku1, "SKU-1", SellerSkuStatus.ACTIVE);
        inv1.setSellerSku(sellerSku1);
        when(sellerSkuRepository.findBySkuCodeIgnoreCase("SKU-1")).thenReturn(Optional.of(sellerSku1));
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku1, warehouseId)).thenReturn(Optional.of(inv1));
        InventoryStockResponse byCode = scopedService.getStockBySkuCode("SKU-1", warehouseId);
        assertEquals("SKU-1", byCode.skuCode());
        assertEquals(10, byCode.availableQty());

        // Seller chưa có SKU nào -> trả trang rỗng ngay lập tức
        when(mockPolicy.resolveReadableSellerSkuScope(null, null)).thenReturn(List.of());
        Page<InventoryStockResponse> emptyPage = scopedService.listInventory(null, null, warehouseId, null, PageRequest.of(0, 10));
        assertEquals(0, emptyPage.getTotalElements());
        verify(inventoryRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Tra cứu sổ cái với bộ lọc mở rộng gọi Criteria searchLedgerFiltered")
    void getLedger_ExtendedFilters_UsesSearchLedgerFiltered() {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Warehouse wh = new Warehouse(warehouseId, "WH_002", "Kho Ha Noi", "HN", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);
        Inventory inv = new Inventory(UUID.randomUUID(), skuId, wh, 20, 0, 0);
        InventoryLedger ledger = new InventoryLedger(
                UUID.randomUUID(), inv, LedgerMovementType.INBOUND, 20, "INBOUND", null, Instant.now(), "INBOUND:k", "Nhap HN", null
        );
        Pageable pageable = PageRequest.of(0, 10);
        when(ledgerRepository.searchLedgerFiltered(
                any(com.nexus.fulfillment.repository.InventoryLedgerSearchRepository.LedgerFilter.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(ledger), pageable, 1));

        Page<InventoryLedgerResponse> result = inventoryService.getLedger(
                skuId, null, warehouseId, LedgerMovementType.INBOUND, "INBOUND", null, Instant.now().minusSeconds(3600), Instant.now(), pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("WH_002", result.getContent().get(0).warehouseCode());
        assertEquals("Nhap HN", result.getContent().get(0).reason());
    }

    @Test
    @DisplayName("Đối soát tồn kho khớp sổ cái qua chuỗi INBOUND -> RESERVE -> RELEASE -> OUTBOUND -> ADJUSTMENT và phát hiện lệch")
    @SuppressWarnings("unchecked")
    void reconcileStock_VerifiesMatchedAndDetectsDeviation() {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        Warehouse wh = new Warehouse(whId, "WH_001", "Kho TP.HCM", "HCM", com.nexus.fulfillment.enums.WarehouseType.STANDARD, true);

        // totalQty = 10 - 2 - 1 = 7; reservedQty = 3 - 1 + 2 - 2 = 2; available = 5
        Inventory matchedInv = new Inventory(UUID.randomUUID(), skuId, wh, 7, 2, 0);
        when(inventoryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(matchedInv));
        when(ledgerRepository.findByInventoryIdOrderByCreatedAtAsc(matchedInv.getId())).thenReturn(List.of(
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.INBOUND, 10, "INBOUND", null, Instant.now()),
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.RESERVE, 3, "ORDER", UUID.randomUUID(), Instant.now()),
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.RELEASE, 1, "ORDER", UUID.randomUUID(), Instant.now()),
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.RESERVE, 2, "ORDER", UUID.randomUUID(), Instant.now()),
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.OUTBOUND, 2, "ORDER", UUID.randomUUID(), Instant.now()),
                new InventoryLedger(UUID.randomUUID(), matchedInv, LedgerMovementType.ADJUSTMENT, -1, "ADJUSTMENT", null, Instant.now())
        ));
        when(reservationRepository.sumPendingQuantityByInventoryId(matchedInv.getId())).thenReturn(2);

        List<com.nexus.fulfillment.dto.response.InventoryReconciliationResponse> ok = inventoryService.reconcileStock(skuId, whId);
        assertEquals(1, ok.size());
        assertTrue(ok.get(0).nonNegative());
        assertTrue(ok.get(0).matched());
        assertEquals(7, ok.get(0).ledgerTotalQty());
        assertEquals(2, ok.get(0).ledgerReservedQty());
        assertEquals(2, ok.get(0).activeReservationQty());

        // Giả lập số dư bảng inventories bị lệch so với sổ cái -> matched phải là false
        matchedInv.setTotalQty(99);
        List<com.nexus.fulfillment.dto.response.InventoryReconciliationResponse> mismatched = inventoryService.reconcileStock(skuId, whId);
        assertEquals(1, mismatched.size());
        assertEquals(false, mismatched.get(0).matched());
    }
}

