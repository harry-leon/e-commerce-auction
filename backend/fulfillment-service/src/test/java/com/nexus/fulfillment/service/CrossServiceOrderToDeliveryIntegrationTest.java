package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.fulfillment.client.CatalogClient;
import com.nexus.fulfillment.dto.request.CarrierWebhookRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.entity.FulfillmentOutboxEvent;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.OrderPaidEvent;
import com.nexus.fulfillment.repository.FulfillmentOutboxEventRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm thử tích hợp toàn luồng Cross-Service (OrderPaid -> ShipmentCreated -> ShipmentDelivered -> Buyer Notification) (SRS §3.6.1, §3.6.2, §5.2, KH-S3-3).
 *
 * Chức năng:
 * - Kiểm chứng luồng liên dịch vụ hoàn chỉnh giữa Commerce, Fulfillment và Notification Service:
 *   1. OrderPaid (từ Commerce Service sau thanh toán thành công): tự động kích hoạt tạo vận đơn Shipment tại Fulfillment Service.
 *   2. ShipmentCreated: Vận đơn được tạo thành công, gắn mã tracking chuẩn và ghi sự kiện Transactional Outbox.
 *   3. Fulfillment quy trình kho (Pick -> Pack -> Ship): chuyển giao kiện hàng sang đơn vị vận chuyển.
 *   4. Carrier Webhooks: tiếp nhận hành trình IN_TRANSIT rồi DELIVERED một cách Idempotent.
 *   5. ShipmentDelivered & Buyer Notification: phát sinh sự kiện giao hàng thành công, ghi nhận Outbox payload chứa đầy đủ
 *      orderId, carrier, trackingNumber, deliveredAt để Notification Service gửi thông báo email hóa đơn và In-App push cho người mua.
 *
 * Quy tắc và lý do:
 * - Bảo đảm tính nhất quán sau cùng (Eventual Consistency) qua Transactional Outbox Pattern.
 * - Idempotency triệt để: khi đối tác giao vận hoặc hệ thống gửi lặp sự kiện OrderPaid / Webhook, không làm phát sinh trùng thông báo người mua.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CrossServiceOrderToDeliveryIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalogClient;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private WarehouseFulfillmentService warehouseFulfillmentService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private FulfillmentOutboxEventRepository outboxRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private Warehouse defaultWarehouse;
    private UUID sellerId;
    private UUID skuId;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        skuId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        sellerId.toString(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_SELLER"))
                )
        );

        Mockito.when(catalogClient.findSkuById(Mockito.any(UUID.class))).thenAnswer(inv -> Optional.of(
                new CatalogClient.SkuOwnershipInfo(
                        inv.getArgument(0),
                        UUID.randomUUID(),
                        "Cross Service Product",
                        new BigDecimal("250000"),
                        new BigDecimal("220000"),
                        sellerId,
                        true
                )
        ));

        defaultWarehouse = warehouseRepository.findByCode("WH_001").orElseGet(() -> warehouseRepository.save(new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "WH_001",
                "Kho trung tam Nexus",
                "TP. Thu Duc, TP.HCM",
                WarehouseType.STANDARD,
                true
        )));

        // Nhập kho 10 sản phẩm
        inventoryService.inboundStock(new StockInboundRequest(
                skuId, defaultWarehouse.getId(), 10, UUID.randomUUID()
        ));
    }

    @Test
    @DisplayName("Cross-Service E2E Flow: OrderPaid -> ShipmentCreated -> Pick/Pack/Ship -> ShipmentDelivered -> Buyer Notification")
    void testCrossServiceOrderPaidToDeliveredFlow_withBuyerNotification() {
        UUID orderId = UUID.randomUUID();

        // Bước 1: Người mua checkout đơn hàng tại Commerce Service -> Giữ tồn kho PENDING
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 2, defaultWarehouse.getId())),
                30
        ));

        // Bước 2: Thanh toán thành công tại Commerce Service -> Bắn OrderPaidEvent sang Fulfillment Service
        eventPublisher.publishEvent(OrderPaidEvent.of(orderId));

        // Xác nhận vận đơn Shipment được tạo tự động với trạng thái PENDING
        ShipmentResponse createdShipment = shippingService.getShipmentByOrderId(orderId);
        assertThat(createdShipment).isNotNull();
        assertThat(createdShipment.orderId()).isEqualTo(orderId);
        assertThat(createdShipment.status()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(createdShipment.trackingNumber()).isNotBlank();
        assertThat(createdShipment.carrier()).isEqualTo("GHN");

        // Xác nhận Transactional Outbox ghi nhận sự kiện ShipmentCreated để truyền thông sang Commerce & Notification Service
        List<FulfillmentOutboxEvent> outboxEvents = outboxRepository.findAll();
        assertThat(outboxEvents).anyMatch(e ->
                "ShipmentCreated".equals(e.getEventType()) && createdShipment.id().equals(e.getAggregateId())
        );

        // Bước 3: Nhân viên kho thực hiện quy trình Pick & Pack kiện hàng
        warehouseFulfillmentService.pick(createdShipment.id());
        warehouseFulfillmentService.pack(createdShipment.id());

        // Bước 4: Xuất kho bàn giao đối tác giao vận (Ship)
        warehouseFulfillmentService.ship(createdShipment.id());

        Shipment shipmentAfterShip = shipmentRepository.findById(createdShipment.id()).orElseThrow();
        assertThat(shipmentAfterShip.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);

        // Xác nhận Outbox có sự kiện ShipmentPickedUp
        assertThat(outboxRepository.findAll()).anyMatch(e ->
                "ShipmentPickedUp".equals(e.getEventType()) && createdShipment.id().equals(e.getAggregateId())
        );

        // Bước 5: Đối tác GHN gửi webhook cập nhật hàng đang trên đường giao (delivering -> IN_TRANSIT)
        CarrierWebhookRequest inTransitWebhook = new CarrierWebhookRequest(
                "evt-ghn-intransit-" + orderId,
                createdShipment.trackingNumber(),
                "delivering",
                "GHN",
                "Buu cuc Thu Duc",
                "Dang van chuyen toi dia chi nguoi nhan",
                Instant.now()
        );
        shippingService.processCarrierWebhook("GHN", inTransitWebhook);

        Shipment shipmentInTransit = shipmentRepository.findById(createdShipment.id()).orElseThrow();
        assertThat(shipmentInTransit.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

        // Bước 6: Đối tác GHN gửi webhook xác nhận giao hàng thành công (delivered -> DELIVERED)
        Instant deliveredAt = Instant.now();
        CarrierWebhookRequest deliveredWebhook = new CarrierWebhookRequest(
                "evt-ghn-delivered-" + orderId,
                createdShipment.trackingNumber(),
                "delivered",
                "GHN",
                "Dia chi nguoi nhan",
                "Giao hang thanh cong va nguoi mua da ky nhan",
                deliveredAt
        );
        shippingService.processCarrierWebhook("GHN", deliveredWebhook);

        Shipment shipmentDelivered = shipmentRepository.findById(createdShipment.id()).orElseThrow();
        assertThat(shipmentDelivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);

        // Xác nhận Outbox có sự kiện ShipmentDelivered mang đầy đủ dữ liệu để Notification Service thông báo cho Buyer
        FulfillmentOutboxEvent deliveredOutbox = outboxRepository.findAll().stream()
                .filter(e -> "ShipmentDelivered".equals(e.getEventType()) && createdShipment.id().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow();

        assertThat(deliveredOutbox.getPayload()).contains(orderId.toString());
        assertThat(deliveredOutbox.getPayload()).contains(createdShipment.trackingNumber());
        assertThat(deliveredOutbox.getPayload()).contains("DELIVERED");
        assertThat(deliveredOutbox.getPayload()).contains("GHN");

        // Bước 7: Kiểm chứng Idempotency khi đối tác GHN gửi lặp webhook Delivered nhiều lần
        shippingService.processCarrierWebhook("GHN", deliveredWebhook);
        shippingService.processCarrierWebhook("GHN", deliveredWebhook);

        // Xác nhận không bị nhân bản bản ghi Outbox ShipmentDelivered
        long deliveredOutboxCount = outboxRepository.findAll().stream()
                .filter(e -> "ShipmentDelivered".equals(e.getEventType()) && createdShipment.id().equals(e.getAggregateId()))
                .count();
        assertThat(deliveredOutboxCount).isEqualTo(1L);

        // Tra cứu hành trình vận đơn cuối cùng
        ShipmentTrackingResponse tracking = shippingService.trackShipment(createdShipment.id());
        assertThat(tracking.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(tracking.history()).isNotEmpty();
    }
}
