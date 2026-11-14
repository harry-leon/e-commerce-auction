package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.entity.Carrier;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.InventoryCommittedEvent;
import com.nexus.fulfillment.event.ShipmentPickedUpEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.service.impl.WarehouseFulfillmentServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Kiểm thử quy trình thao tác kho vật lý (Pick, Pack, Ship) và tích hợp Sổ cái bất biến (SRS §3.6.1, §3.6.2, KH-S3-2).
 *
 * Kiểm tra:
 * - pick(): chuyển từ PENDING sang PICKING và ghi ShipmentStatusHistory.
 * - pack(): chuyển từ PICKING sang PACKED và ghi ShipmentStatusHistory.
 * - ship(): chuyển từ PACKED sang SHIPPED, trừ tồn kho (reserved_qty -> shipped/committed),
 *   ghi bản ghi OUTBOUND vào inventory_ledger, phát InventoryCommittedEvent và ShipmentPickedUpEvent.
 * - Xử lý idempotent: khi gọi lại ship() cho vận đơn đã SHIPPED thì không trừ kho lần hai.
 */
class WarehouseFulfillmentServiceTest {

    private ShipmentRepository shipmentRepository;
    private ShipmentStatusHistoryRepository historyRepository;
    private InventoryReservationRepository reservationRepository;
    private InventoryRepository inventoryRepository;
    private InventoryLedgerRepository ledgerRepository;
    private ApplicationEventPublisher eventPublisher;
    private FulfillmentEventOutboxService outboxService;

    private WarehouseFulfillmentServiceImpl fulfillmentService;

    private Warehouse warehouse;
    private Carrier carrier;

    @BeforeEach
    void setUp() {
        shipmentRepository = mock(ShipmentRepository.class);
        historyRepository = mock(ShipmentStatusHistoryRepository.class);
        reservationRepository = mock(InventoryReservationRepository.class);
        inventoryRepository = mock(InventoryRepository.class);
        ledgerRepository = mock(InventoryLedgerRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        outboxService = mock(FulfillmentEventOutboxService.class);

        fulfillmentService = new WarehouseFulfillmentServiceImpl(
                shipmentRepository,
                historyRepository,
                reservationRepository,
                inventoryRepository,
                ledgerRepository,
                eventPublisher,
                outboxService
        );

        warehouse = new Warehouse(UUID.randomUUID(), "WH_001", "Kho Trung Tam", "TP.HCM", WarehouseType.STANDARD, true);
        carrier = new Carrier(UUID.randomUUID(), "GHN", "Giao Hang Nhanh", true);
    }

    @Test
    @DisplayName("pick(): chuyển vận đơn PENDING sang PICKING và ghi lịch sử")
    void pick_transitionsToPicking() {
        UUID shipmentId = UUID.randomUUID();
        Shipment shipment = createTestShipment(shipmentId, ShipmentStatus.PENDING);

        when(shipmentRepository.findWithDetailsById(shipmentId)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        ShipmentResponse response = fulfillmentService.pick(shipmentId);

        assertThat(response.status()).isEqualTo(ShipmentStatus.PICKING);
        verify(historyRepository).save(any(ShipmentStatusHistory.class));
    }

    @Test
    @DisplayName("pack(): chuyển vận đơn PICKING sang PACKED và ghi lịch sử")
    void pack_transitionsToPacked() {
        UUID shipmentId = UUID.randomUUID();
        Shipment shipment = createTestShipment(shipmentId, ShipmentStatus.PICKING);

        when(shipmentRepository.findWithDetailsById(shipmentId)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        ShipmentResponse response = fulfillmentService.pack(shipmentId);

        assertThat(response.status()).isEqualTo(ShipmentStatus.PACKED);
        verify(historyRepository).save(any(ShipmentStatusHistory.class));
    }

    @Test
    @DisplayName("ship(): chuyển sang SHIPPED, trừ reserved_qty -> committed, ghi ledger OUTBOUND và phát sự kiện miền")
    void ship_commitsInventoryAndAppendsLedger() {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        Shipment shipment = createTestShipment(shipmentId, ShipmentStatus.PACKED);
        shipment.setOrderId(orderId);

        // Ban đầu: total = 10, reserved = 2 -> sau ship: total = 8, reserved = 0
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, warehouse, 10, 2, 0);

        InventoryReservation reservation = new InventoryReservation(
                UUID.randomUUID(), inventory, 2, ReservationRefType.ORDER, orderId,
                ReservationStatus.PENDING, Instant.now().plusSeconds(1800)
        );

        when(shipmentRepository.findWithDetailsById(shipmentId)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.findByRefTypeAndRefIdForUpdate(ReservationRefType.ORDER, orderId))
                .thenReturn(List.of(reservation));
        when(inventoryRepository.findByIdForUpdate(inventory.getId())).thenReturn(Optional.of(inventory));

        ShipmentResponse response = fulfillmentService.ship(shipmentId);

        // 1. Vận đơn sang SHIPPED
        assertThat(response.status()).isEqualTo(ShipmentStatus.SHIPPED);

        // 2. Tồn kho bị trừ số lượng đã ship: total 10 -> 8, reserved 2 -> 0
        assertThat(inventory.getTotalQty()).isEqualTo(8);
        assertThat(inventory.getReservedQty()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // 3. Ghi sổ cái bất biến OUTBOUND
        verify(ledgerRepository).save(any(InventoryLedger.class));

        // 4. Phát sinh domain events: InventoryCommittedEvent và ShipmentPickedUpEvent
        verify(eventPublisher).publishEvent(any(InventoryCommittedEvent.class));
        verify(eventPublisher).publishEvent(any(ShipmentPickedUpEvent.class));
        verify(outboxService).enqueueShipmentPickedUp(any(Shipment.class));
    }

    @Test
    @DisplayName("ship(): idempotent nếu vận đơn đã ở trạng thái SHIPPED thì không trừ kho lại")
    void ship_idempotentWhenAlreadyShipped() {
        UUID shipmentId = UUID.randomUUID();
        Shipment shipment = createTestShipment(shipmentId, ShipmentStatus.SHIPPED);

        when(shipmentRepository.findWithDetailsById(shipmentId)).thenReturn(Optional.of(shipment));

        ShipmentResponse response = fulfillmentService.ship(shipmentId);

        assertThat(response.status()).isEqualTo(ShipmentStatus.SHIPPED);
        verify(reservationRepository, org.mockito.Mockito.never()).findByRefTypeAndRefIdForUpdate(any(), any());
    }

    private Shipment createTestShipment(UUID shipmentId, ShipmentStatus status) {
        Shipment shipment = new Shipment();
        shipment.setId(shipmentId);
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(warehouse);
        shipment.setCarrier(carrier);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo("GHN-" + UUID.randomUUID().toString().substring(0, 8));
        shipment.setStatus(status);
        shipment.setShippingFee(new BigDecimal("22.00"));
        return shipment;
    }
}
