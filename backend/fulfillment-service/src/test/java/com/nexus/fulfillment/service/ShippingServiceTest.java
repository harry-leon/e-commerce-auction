package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.entity.Carrier;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.ShipmentCreatedEvent;
import com.nexus.fulfillment.event.ShippingQuoteRequestedEvent;
import com.nexus.fulfillment.repository.CarrierRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.impl.ShippingServiceImpl;
import com.nexus.fulfillment.shipping.adapter.CarrierAdapterRegistry;
import com.nexus.fulfillment.shipping.adapter.GhnCarrierAdapter;
import com.nexus.fulfillment.shipping.adapter.GhtkCarrierAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test cho ShippingServiceImpl: báo giá đa hãng, FREE_SHIPPING_THRESHOLD, tạo vận đơn,
 * idempotency, chọn hãng theo cấu hình/quy tắc và phát sự kiện miền (KH-S3-1).
 */
class ShippingServiceTest {

    private ShipmentRepository shipmentRepository;
    private ShipmentStatusHistoryRepository historyRepository;
    private CarrierRepository carrierRepository;
    private WarehouseRepository warehouseRepository;
    private FulfillmentProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private FulfillmentEventOutboxService outboxService;
    private ShippingServiceImpl shippingService;

    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        shipmentRepository = mock(ShipmentRepository.class);
        historyRepository = mock(ShipmentStatusHistoryRepository.class);
        carrierRepository = mock(CarrierRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        properties = new FulfillmentProperties();
        eventPublisher = mock(ApplicationEventPublisher.class);
        outboxService = mock(FulfillmentEventOutboxService.class);

        CarrierAdapterRegistry registry = new CarrierAdapterRegistry(
                List.of(new GhnCarrierAdapter(properties), new GhtkCarrierAdapter(properties)),
                properties
        );
        shippingService = new ShippingServiceImpl(
                shipmentRepository,
                historyRepository,
                carrierRepository,
                warehouseRepository,
                registry,
                properties,
                eventPublisher,
                outboxService
        );

        defaultWarehouse = new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "WH_001",
                "Kho trung tam",
                "TP. Thu Duc, TP.HCM",
                WarehouseType.STANDARD,
                true
        );
        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(defaultWarehouse));
    }

    @Test
    @DisplayName("quoteShipping: trả về báo giá của GHN & GHTK, phát ShippingQuoteRequestedEvent")
    void quoteShipping_returnsAllSupportedCarriers() {
        UUID orderId = UUID.randomUUID();
        ShippingQuoteResponse response = shippingService.quoteShipping(new ShippingQuoteRequest(
                orderId, null, null, "STANDARD", "Q1, TP.HCM", 1454, "21211", 500, new BigDecimal("50")));

        assertThat(response.defaultCarrier()).isEqualTo("GHN");
        assertThat(response.freeShippingApplied()).isFalse();
        assertThat(response.options()).extracting(opt -> opt.carrier()).containsExactly("GHN", "GHTK");
        assertThat(response.options()).allSatisfy(opt -> assertThat(opt.shippingFee()).isPositive());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(ShippingQuoteRequestedEvent.class);
    }

    @Test
    @DisplayName("quoteShipping: đơn hàng đạt FREE_SHIPPING_THRESHOLD (>= 100) được miễn phí ship")
    void quoteShipping_appliesFreeShippingThreshold() {
        ShippingQuoteResponse response = shippingService.quoteShipping(new ShippingQuoteRequest(
                UUID.randomUUID(), null, "GHN", "STANDARD", "Q1, TP.HCM", 1454, "21211",
                500, new BigDecimal("100")));

        assertThat(response.freeShippingApplied()).isTrue();
        assertThat(response.options()).hasSize(1);
        assertThat(response.options().get(0).shippingFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.options().get(0).originalFee()).isPositive();
    }

    @Test
    @DisplayName("createShipment: dùng DEFAULT_CARRIER (GHN), lưu Shipment + History, ghi Outbox và phát ShipmentCreatedEvent")
    void createShipment_usesDefaultCarrierAndEmitsEvent() {
        UUID orderId = UUID.randomUUID();
        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "Giao Hang Nhanh", true);
        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(carrierRepository.findByCodeIgnoreCase("GHN")).thenReturn(Optional.of(ghn));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        ShipmentResponse response = shippingService.createShipment(new CreateShipmentRequest(
                orderId, null, null, "STANDARD", "Nguyen Van A", "0901234567",
                "123 Le Loi", 1454, "21211", 500, new BigDecimal("80"), BigDecimal.ZERO, "Test"), null);

        assertThat(response.carrier()).isEqualTo("GHN");
        assertThat(response.trackingNumber()).startsWith("GHN-");
        assertThat(response.status()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(response.freeShippingApplied()).isFalse();

        verify(historyRepository).save(any(ShipmentStatusHistory.class));
        verify(outboxService).enqueueShipmentCreated(any(Shipment.class));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(ShipmentCreatedEvent.class);
    }

    @Test
    @DisplayName("createShipment: hàng nặng > 10kg tự động chọn GHTK khi không chỉ định hãng")
    void createShipment_heavyParcelSelectsGhtk() {
        UUID orderId = UUID.randomUUID();
        Carrier ghtk = new Carrier(UUID.randomUUID(), "GHTK", "Giao Hang Tiet Kiem", true);
        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(carrierRepository.findByCodeIgnoreCase("GHTK")).thenReturn(Optional.of(ghtk));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        ShipmentResponse response = shippingService.createShipment(new CreateShipmentRequest(
                orderId, null, null, "STANDARD", "Nguyen Van B", "0909999999",
                "Ha Noi", null, null, 12000, new BigDecimal("150"), BigDecimal.ZERO, null), null);

        assertThat(response.carrier()).isEqualTo("GHTK");
        assertThat(response.trackingNumber()).startsWith("GHTK-");
        assertThat(response.freeShippingApplied()).isTrue();
        assertThat(response.shippingFee()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("createShipment: trùng Idempotency-Key trả về vận đơn cũ, trùng orderId báo SHIPMENT_ALREADY_CREATED")
    void createShipment_idempotencyAndDuplicateOrder() {
        UUID orderId = UUID.randomUUID();
        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment existing = new Shipment();
        existing.setId(UUID.randomUUID());
        existing.setOrderId(orderId);
        existing.setWarehouse(defaultWarehouse);
        existing.setCarrier(ghn);
        existing.setCarrierCode("GHN");
        existing.setTrackingNo("GHN-EXIST");
        existing.setStatus(ShipmentStatus.PENDING);

        when(shipmentRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));
        ShipmentResponse idemResponse = shippingService.createShipment(new CreateShipmentRequest(
                orderId, null, null, null, null, null, null, null, null, 500, null, null, null), "idem-1");
        assertThat(idemResponse.trackingNumber()).isEqualTo("GHN-EXIST");
        verify(shipmentRepository, never()).save(any());

        when(shipmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> shippingService.createShipment(new CreateShipmentRequest(
                orderId, null, null, null, null, null, null, null, null, 500, null, null, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_ALREADY_CREATED.name());
    }

    @Test
    @DisplayName("trackShipment: đồng bộ trạng thái mới từ adapter và ghi ShipmentStatusHistory")
    void trackShipment_syncsStatusAndHistory() {
        UUID shipmentId = UUID.randomUUID();
        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment shipment = new Shipment();
        shipment.setId(shipmentId);
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(defaultWarehouse);
        shipment.setCarrier(ghn);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo("GHN-12345678");
        shipment.setStatus(ShipmentStatus.PENDING);

        when(shipmentRepository.findWithDetailsById(shipmentId)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByShipmentIdOrderByOccurredAtDesc(shipmentId)).thenReturn(List.of(
                new ShipmentStatusHistory(shipment, "picking", ShipmentStatus.PICKING, java.time.Instant.now())
        ));

        ShipmentTrackingResponse tracking = shippingService.trackShipment(shipmentId);

        assertThat(tracking.status()).isEqualTo(ShipmentStatus.PICKING);
        assertThat(tracking.carrierStatus()).isEqualTo("picking");
        assertThat(tracking.history()).hasSize(1);
        verify(historyRepository).save(any(ShipmentStatusHistory.class));
    }

    @Test
    @DisplayName("processCarrierWebhook: bỏ qua sự kiện trùng lặp theo eventId (Idempotency)")
    void processCarrierWebhook_idempotentDuplicateEventIgnored() {
        String trackingNo = "GHN-IDEMPOTENT-01";
        String eventId = "evt-duplicate-123";

        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(defaultWarehouse);
        shipment.setCarrier(ghn);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);

        when(shipmentRepository.findByTrackingNo(trackingNo)).thenReturn(Optional.of(shipment));
        when(historyRepository.existsByEventId(eventId)).thenReturn(true);

        com.nexus.fulfillment.dto.request.CarrierWebhookRequest request =
                new com.nexus.fulfillment.dto.request.CarrierWebhookRequest(
                        eventId, trackingNo, "delivered", "GHN", "Ha Noi", "Giao thanh cong", Instant.now()
                );

        ShipmentResponse response = shippingService.processCarrierWebhook("GHN", request);

        assertThat(response.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(com.nexus.fulfillment.event.ShipmentDeliveredEvent.class));
        verify(shipmentRepository, org.mockito.Mockito.never()).save(any(Shipment.class));
    }

    @Test
    @DisplayName("processCarrierWebhook: chuyển trạng thái hợp lệ sang DELIVERED, phát ShipmentDeliveredEvent và ghi Outbox")
    void processCarrierWebhook_validTransition_emitsEvents() {
        String trackingNo = "GHN-DELIVERY-01";
        String eventId = "evt-new-456";

        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(defaultWarehouse);
        shipment.setCarrier(ghn);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);

        when(shipmentRepository.findByTrackingNo(trackingNo)).thenReturn(Optional.of(shipment));
        when(historyRepository.existsByEventId(eventId)).thenReturn(false);
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        com.nexus.fulfillment.dto.request.CarrierWebhookRequest request =
                new com.nexus.fulfillment.dto.request.CarrierWebhookRequest(
                        eventId, trackingNo, "delivered", "GHN", "TP.HCM", "Nguoi nhan da nhan hang", Instant.now()
                );

        ShipmentResponse response = shippingService.processCarrierWebhook("GHN", request);

        assertThat(response.status()).isEqualTo(ShipmentStatus.DELIVERED);
        verify(eventPublisher).publishEvent(any(com.nexus.fulfillment.event.ShipmentDeliveredEvent.class));
        verify(outboxService).enqueueShipmentDelivered(any(Shipment.class));
        verify(historyRepository).save(any(ShipmentStatusHistory.class));
    }

    @Test
    @DisplayName("processCarrierWebhook: chặn out-of-order callback khi vận đơn đã ở trạng thái kết thúc DELIVERED")
    void processCarrierWebhook_terminalState_blocksEarlierStatus() {
        String trackingNo = "GHN-TERMINAL-01";
        String eventId = "evt-late-789";

        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(defaultWarehouse);
        shipment.setCarrier(ghn);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.DELIVERED);

        when(shipmentRepository.findByTrackingNo(trackingNo)).thenReturn(Optional.of(shipment));
        when(historyRepository.existsByEventId(eventId)).thenReturn(false);

        com.nexus.fulfillment.dto.request.CarrierWebhookRequest request =
                new com.nexus.fulfillment.dto.request.CarrierWebhookRequest(
                        eventId, trackingNo, "delivering", "GHN", "Q1", "Late webhook delivering", Instant.now()
                );

        ShipmentResponse response = shippingService.processCarrierWebhook("GHN", request);

        assertThat(response.status()).isEqualTo(ShipmentStatus.DELIVERED);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(com.nexus.fulfillment.event.ShipmentInTransitEvent.class));
    }

    @Test
    @DisplayName("pollActiveShipments: quét các vận đơn active và cập nhật trạng thái mới")
    void pollActiveShipments_scansAndUpdates() {
        Carrier ghn = new Carrier(UUID.randomUUID(), "GHN", "GHN", true);
        Shipment s1 = new Shipment();
        s1.setId(UUID.randomUUID());
        s1.setOrderId(UUID.randomUUID());
        s1.setWarehouse(defaultWarehouse);
        s1.setCarrier(ghn);
        s1.setCarrierCode("GHN");
        s1.setTrackingNo("GHN-POLL-01");
        s1.setStatus(ShipmentStatus.PENDING);

        when(shipmentRepository.findByStatusIn(any())).thenReturn(List.of(s1));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        int updated = shippingService.pollActiveShipments();

        assertThat(updated).isGreaterThanOrEqualTo(0);
        verify(shipmentRepository).findByStatusIn(any());
    }
}
