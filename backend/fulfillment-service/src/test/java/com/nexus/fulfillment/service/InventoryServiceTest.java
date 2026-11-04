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
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.InventoryCheckedEvent;
import com.nexus.fulfillment.event.InventoryCommittedEvent;
import com.nexus.fulfillment.event.InventoryReleasedEvent;
import com.nexus.fulfillment.event.InventoryReservationExpiredEvent;
import com.nexus.fulfillment.event.InventoryReservedEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.impl.InventoryServiceImpl;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    private FulfillmentProperties properties;
    private InventoryServiceImpl inventoryService;

    private Warehouse defaultWarehouse;
    private UUID skuId;

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

        inventoryService = new InventoryServiceImpl(
                inventoryRepository,
                reservationRepository,
                ledgerRepository,
                warehouseRepository,
                properties,
                eventPublisher
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

        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(expiredReservation));
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
    @DisplayName("Nhập kho (Inbound): Tăng total_qty và ghi sổ cái INBOUND")
    void testInboundStock_Success() {
        // Given
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, defaultWarehouse, 50, 0, 0);
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
        when(inventoryRepository.findBySkuIdAndWarehouseIdForUpdate(skuId, defaultWarehouse.getId())).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockInboundRequest req = new StockInboundRequest(skuId, null, 25, UUID.randomUUID());

        // When
        InventoryStockResponse res = inventoryService.inboundStock(req);

        // Then
        assertEquals(75, res.totalQty()); // 50 + 25 = 75
        assertEquals(75, res.availableQty());

        // Kiểm tra ghi sổ cái INBOUND
        ArgumentCaptor<InventoryLedger> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertEquals(LedgerMovementType.INBOUND, ledgerCaptor.getValue().getMovementType());
        assertEquals(25, ledgerCaptor.getValue().getQuantity());
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
}
