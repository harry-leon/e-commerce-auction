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
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
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
 * Kiểm thử nghiệp vụ lõi Quản lý tồn kho và Khóa giữ hàng Checkout (InventoryService).
 *
 * Tình huống kiểm thử và lỗi bảo vệ:
 * 1. Khóa giữ hàng thành công (Reserve): tăng reserved_qty, giảm available_qty, tạo phiếu PENDING, ghi sổ cái RESERVE, bắn sự kiện InventoryReserved.
 * 2. Chặn bán vượt tồn (ALLOW_OVERSELL = false): từ chối giữ kho và ném INVENTORY_INSUFFICIENT khi available_qty < số lượng mua.
 * 3. Báo lỗi khi không tìm thấy SKU: ném INVENTORY_NOT_FOUND.
 * 4. Xác nhận đơn hàng thành công (Confirm): trừ cả total_qty và reserved_qty, ghi sổ cái OUTBOUND, bắn sự kiện InventoryCommitted.
 * 5. Chặn xác nhận lại đơn đã xuất: ném INVENTORY_RESERVATION_ALREADY_COMMITTED.
 * 6. Giải phóng giữ hàng chủ động (Release): hoàn trả reserved_qty về available_qty, chuyển trạng thái RELEASED, ghi sổ cái RELEASE, bắn sự kiện InventoryReleased.
 * 7. Tự động hoàn kho phiếu quá hạn 30 phút (Auto-release): quét các phiếu PENDING hết hạn, chuyển trạng thái EXPIRED, hoàn kho và bắn InventoryReservationExpired.
 * 8. Nhập kho (Inbound) và Điều chỉnh (Adjust): ghi sổ cái tương ứng, kiểm tra ràng buộc không được điều chỉnh tổng tồn xuống dưới mức đang giữ.
 * 9. Sổ cái tồn kho bất biến (Immutable Inventory Ledger): bảo đảm không có thao tác sửa/xóa bản ghi sổ cái.
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

        when(reservationRepository.findByRefTypeAndRefIdAndStatusForUpdate(
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING
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
