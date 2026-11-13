package com.nexus.fulfillment.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CarrierWebhookRequest;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.entity.Carrier;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.ShipmentDeliveredEvent;
import com.nexus.fulfillment.event.ShipmentInTransitEvent;
import com.nexus.fulfillment.repository.CarrierRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.FulfillmentEventOutboxService;
import com.nexus.fulfillment.service.impl.ShippingServiceImpl;
import com.nexus.fulfillment.shipping.adapter.CarrierAdapterRegistry;
import com.nexus.fulfillment.shipping.adapter.CarrierQuoteResult;
import com.nexus.fulfillment.shipping.adapter.ShippingCarrierAdapter;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierCircuitBreaker;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierCircuitBreakerState;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierResilienceManager;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierRetryPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;

/**
 * Kiểm thử khả năng chịu lỗi của hãng vận chuyển và cơ chế Idempotent khi nhận webhook lặp lại (SRS §3.6.2, §5.2, §5.3, KH-S3-3).
 *
 * Chức năng:
 * - Mô phỏng độ trễ API hãng vận chuyển vượt quá 3000ms (SHIPPING_API_TIMEOUT_MS = 3000).
 * - Xác thực chính sách thử lại (Retry Policy tối đa 3 lần) và ngắt mạch (Circuit Breaker OPEN) kích hoạt Fail-Fast mượt mà,
 *   không làm treo thread pool hay gián đoạn luồng đặt/báo giá đơn hàng.
 * - Phát lại liên tục các webhook payload trùng lặp (replay attack / network retry) và kiểm tra tính Idempotent:
 *   không ghi trùng log lịch sử vận đơn và không phát sinh trùng sự kiện miền / outbox.
 *
 * Quy tắc và lý do:
 * - Bảo vệ kết nối tới bên thứ ba (GHN, GHTK): lỗi từ một đối tác phải được cô lập tuyệt đối.
 * - Idempotent theo eventId bảo đảm tính toàn vẹn dữ liệu ngay cả khi hệ thống nhận callback lặp hoặc sai thứ tự.
 */
class CarrierResilienceAndDuplicateCallbackTest {

    private FulfillmentProperties properties;
    private CarrierResilienceManager resilienceManager;
    private ShipmentRepository shipmentRepository;
    private ShipmentStatusHistoryRepository historyRepository;
    private CarrierRepository carrierRepository;
    private WarehouseRepository warehouseRepository;
    private CarrierAdapterRegistry adapterRegistry;
    private FulfillmentEventOutboxService outboxService;
    private ApplicationEventPublisher eventPublisher;
    private ShippingServiceImpl shippingService;

    private Warehouse warehouse;
    private Carrier ghnCarrier;
    private Carrier ghtkCarrier;

    @BeforeEach
    void setUp() {
        properties = new FulfillmentProperties();
        properties.getShipping().setApiTimeoutMs(3000);
        properties.getShipping().setRetryLimit(3);
        properties.getShipping().setDefaultCarrier("GHN");

        resilienceManager = new CarrierResilienceManager(properties);
        shipmentRepository = mock(ShipmentRepository.class);
        historyRepository = mock(ShipmentStatusHistoryRepository.class);
        carrierRepository = mock(CarrierRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        adapterRegistry = mock(CarrierAdapterRegistry.class);
        outboxService = mock(FulfillmentEventOutboxService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        warehouse = new Warehouse(
                UUID.randomUUID(), "WH_001", "Kho trung tam", "Ha Noi", WarehouseType.STANDARD, true
        );
        ghnCarrier = new Carrier(UUID.randomUUID(), "GHN", "Giao Hang Nhanh", true);
        ghtkCarrier = new Carrier(UUID.randomUUID(), "GHTK", "Giao Hang Tiet Kiem", true);

        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(carrierRepository.findByCodeIgnoreCase("GHN")).thenReturn(Optional.of(ghnCarrier));
        when(carrierRepository.findByCodeIgnoreCase("GHTK")).thenReturn(Optional.of(ghtkCarrier));

        shippingService = new ShippingServiceImpl(
                shipmentRepository,
                historyRepository,
                carrierRepository,
                warehouseRepository,
                adapterRegistry,
                properties,
                eventPublisher,
                outboxService,
                resilienceManager
        );
    }

    @Test
    @DisplayName("Carrier Timeout > 3000ms: Retry tối đa 3 lần và Circuit Breaker ngắt mạch OPEN kích hoạt Fail-Fast")
    void carrierTimeoutExceeding3000ms_triggersRetryAndOpensCircuitBreaker() {
        CarrierRetryPolicy retryPolicy = new CarrierRetryPolicy("GHN", 3, 5L);
        CarrierCircuitBreaker cb = new CarrierCircuitBreaker("GHN", 3, 5000L, 2);

        AtomicInteger invocationAttempts = new AtomicInteger(0);

        // Mô phỏng 3 lần liên tiếp carrier API latency vượt quá 3000ms và timeout
        for (int i = 1; i <= 3; i++) {
            cb.acquirePermission();
            try {
                retryPolicy.execute(() -> {
                    invocationAttempts.incrementAndGet();
                    throw new ResourceAccessException(
                            "I/O error on POST request: Read timed out after 3000ms",
                            new SocketTimeoutException("Read timed out")
                    );
                });
            } catch (Exception ex) {
                cb.recordFailure(ex);
            }
        }

        // Mỗi lần gọi gặp timeout đều được retry đúng 3 lần -> tổng 9 lượt gọi
        assertThat(invocationAttempts.get()).isEqualTo(9);

        // Sau 3 chuỗi timeout liên tiếp, Circuit Breaker phải ngắt sang trạng thái OPEN
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.OPEN);

        // Lần gọi tiếp theo lập tức bị chặn bởi cơ chế Fail-Fast mà không cần chờ 3000ms
        assertThatThrownBy(cb::acquirePermission)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_ERROR.name());
    }

    @Test
    @DisplayName("Carrier Timeout: Khi GHN bị timeout > 3000ms, luồng báo giá vẫn thành công nhờ đối tác thay thế (GHTK)")
    void quoteShipping_gracefullyHandlesCarrierTimeoutWithoutCrashingOrderFlow() {
        ShippingCarrierAdapter ghnAdapter = mock(ShippingCarrierAdapter.class);
        ShippingCarrierAdapter ghtkAdapter = mock(ShippingCarrierAdapter.class);

        when(ghnAdapter.getCarrierCode()).thenReturn("GHN");
        when(ghtkAdapter.getCarrierCode()).thenReturn("GHTK");

        // GHN adapter ném ngoại lệ timeout > 3000ms
        when(ghnAdapter.quote(any())).thenThrow(new ResourceAccessException(
                "Read timed out after 3000ms", new SocketTimeoutException("timeout")));

        // GHTK adapter phản hồi bình thường trong thời gian hợp lệ
        when(ghtkAdapter.quote(any())).thenReturn(new CarrierQuoteResult(
                "GHTK", "STANDARD", new BigDecimal("35000"), 2, Instant.now().plusSeconds(172800)
        ));

        when(adapterRegistry.getSupportedAdapters()).thenReturn(List.of(ghnAdapter, ghtkAdapter));

        ShippingQuoteRequest request = new ShippingQuoteRequest(
                UUID.randomUUID(), warehouse.getId(), null, "STANDARD", "Ha Noi", 100, "01", 500, new BigDecimal("50")
        );

        // Luồng báo giá đơn hàng không bị sập (no crash)
        ShippingQuoteResponse response = shippingService.quoteShipping(request);

        assertThat(response).isNotNull();
        assertThat(response.options()).hasSize(1);
        assertThat(response.options().get(0).carrier()).isEqualTo("GHTK");
        assertThat(response.options().get(0).originalFee()).isEqualByComparingTo(new BigDecimal("35000"));
    }

    @Test
    @DisplayName("Idempotent Webhook Replay: Phát lại webhook 5 lần liên tiếp cùng eventId -> không nhân bản log và domain events")
    void replayIdenticalCarrierWebhookPayloads_ensuresStrictIdempotency() {
        String trackingNo = "GHN-REPLAY-999";
        String eventId = "evt-duplicate-callback-001";

        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(warehouse);
        shipment.setCarrier(ghnCarrier);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);

        when(shipmentRepository.findByTrackingNo(trackingNo)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));

        // Lần đầu eventId chưa tồn tại; các lần sau historyRepository báo đã tồn tại
        when(historyRepository.existsByEventId(eventId)).thenReturn(false).thenReturn(true);

        CarrierWebhookRequest webhook = new CarrierWebhookRequest(
                eventId,
                trackingNo,
                "delivered",
                "GHN",
                "Kho Hoan Kiem",
                "Nguoi nhan da ky nhan",
                Instant.now()
        );

        ShippingCarrierAdapter ghnAdapter = mock(ShippingCarrierAdapter.class);
        when(ghnAdapter.mapCarrierStatus("delivered")).thenReturn(ShipmentStatus.DELIVERED);
        when(adapterRegistry.findAdapter("GHN")).thenReturn(Optional.of(ghnAdapter));

        // Phát lại payload 5 lần liên tiếp
        for (int i = 0; i < 5; i++) {
            ShipmentResponse resp = shippingService.processCarrierWebhook("GHN", webhook);
            assertThat(resp.status()).isEqualTo(ShipmentStatus.DELIVERED);
        }

        // Domain event ShipmentDeliveredEvent chỉ phát đúng 1 lần duy nhất
        verify(eventPublisher, times(1)).publishEvent(any(ShipmentDeliveredEvent.class));

        // Outbox event enqueueShipmentDelivered chỉ ghi đúng 1 lần duy nhất
        verify(outboxService, times(1)).enqueueShipmentDelivered(any(Shipment.class));

        // Lịch sử trạng thái ShipmentStatusHistory chỉ ghi đúng 1 lần duy nhất cho eventId này
        verify(historyRepository, times(1)).save(any(ShipmentStatusHistory.class));
    }

    @Test
    @DisplayName("Idempotent Webhook Replay: Chặn cập nhật trễ (out-of-order) khi vận đơn đã ở trạng thái kết thúc DELIVERED")
    void outOfOrderCallback_afterDeliveredTerminalState_isSafelyIgnored() {
        String trackingNo = "GHN-OUT-OF-ORDER";
        String lateEventId = "evt-late-delivering-888";

        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setWarehouse(warehouse);
        shipment.setCarrier(ghnCarrier);
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo(trackingNo);
        shipment.setStatus(ShipmentStatus.DELIVERED); // Đã ở trạng thái kết thúc DELIVERED

        when(shipmentRepository.findByTrackingNo(trackingNo)).thenReturn(Optional.of(shipment));
        when(historyRepository.existsByEventId(lateEventId)).thenReturn(false);

        ShippingCarrierAdapter ghnAdapter = mock(ShippingCarrierAdapter.class);
        when(ghnAdapter.mapCarrierStatus("delivering")).thenReturn(ShipmentStatus.IN_TRANSIT);
        when(adapterRegistry.findAdapter("GHN")).thenReturn(Optional.of(ghnAdapter));

        CarrierWebhookRequest lateWebhook = new CarrierWebhookRequest(
                lateEventId,
                trackingNo,
                "delivering",
                "GHN",
                "Ha Noi",
                "Callback trung chuyen den cham sau khi da giao",
                Instant.now()
        );

        ShipmentResponse resp = shippingService.processCarrierWebhook("GHN", lateWebhook);

        // Vận đơn vẫn giữ nguyên trạng thái kết thúc DELIVERED, không bị lùi về IN_TRANSIT
        assertThat(resp.status()).isEqualTo(ShipmentStatus.DELIVERED);

        // Không phát sự kiện lặp ShipmentInTransitEvent
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(ShipmentInTransitEvent.class));
        verify(outboxService, org.mockito.Mockito.never()).enqueueShipmentInTransit(any(), any());
    }
}
