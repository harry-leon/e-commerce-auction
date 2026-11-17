package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.SellerSkuStatus;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.OrderPaidEvent;
import com.nexus.fulfillment.repository.FulfillmentOutboxEventRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.SellerSkuRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Kiểm thử tích hợp toàn luồng Shipping với H2 Database thật (KH-S3-1):
 * OrderPaidEvent -> ShippingEventListener -> tạo vận đơn -> lưu Outbox ShipmentCreated -> Tracking.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private SellerSkuRepository sellerSkuRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private FulfillmentOutboxEventRepository outboxRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void ensureDefaultWarehouse() {
        warehouseRepository.findByCode("WH_001").orElseGet(() -> warehouseRepository.save(new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "WH_001",
                "Kho trung tam Nexus",
                "TP. Thu Duc, TP.HCM",
                WarehouseType.STANDARD,
                true
        )));
    }

    @Test
    @DisplayName("OrderPaidEvent tự động tạo vận đơn idempotent, ghi Outbox ShipmentCreated và hỗ trợ tracking")
    void orderPaidEvent_createsShipmentAndAllowsTracking() {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        Warehouse warehouse = warehouseRepository.findByCode("WH_001").orElseThrow();
        SellerSku sellerSku = sellerSkuRepository.save(new SellerSku(
                UUID.randomUUID(), UUID.randomUUID(), skuId, "FSKU-SHIP-01", SellerSkuStatus.ACTIVE));
        Inventory inventory = new Inventory(UUID.randomUUID(), skuId, warehouse, 10, 0, 0);
        inventory.setSellerSku(sellerSku);
        inventoryRepository.save(inventory);
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                java.util.List.of(new ReservationItemRequest(skuId, 1, warehouse.getId())),
                30));

        // Phát OrderPaidEvent lần 1 -> trừ kho và tạo vận đơn mới
        eventPublisher.publishEvent(OrderPaidEvent.of(orderId));
        // Phát lặp OrderPaidEvent lần 2 -> idempotent, không tạo trùng vận đơn
        eventPublisher.publishEvent(OrderPaidEvent.of(orderId));

        ShipmentResponse shipment = shippingService.getShipmentByOrderId(orderId);
        assertThat(shipment.orderId()).isEqualTo(orderId);
        assertThat(shipment.carrier()).isEqualTo("GHN");
        assertThat(shipment.trackingNumber()).startsWith("GHN-");
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.PENDING);

        assertThat(outboxRepository.findAll())
                .anyMatch(e -> "ShipmentCreated".equals(e.getEventType())
                        && shipment.id().equals(e.getAggregateId()));

        ShipmentTrackingResponse tracking = shippingService.trackShipment(shipment.id());
        assertThat(tracking.trackingNumber()).isEqualTo(shipment.trackingNumber());
        assertThat(tracking.status()).isEqualTo(ShipmentStatus.PICKING);
        assertThat(tracking.history()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("quoteShipping tích hợp trả về phương án từ GHN & GHTK và áp dụng miễn phí ship khi có voucher FREESHIP")
    void quoteShipping_integration() {
        ShippingQuoteResponse quote = shippingService.quoteShipping(new ShippingQuoteRequest(
                UUID.randomUUID(), null, null, "STANDARD", "Q1, TP.HCM", 1454, "21211",
                600, new BigDecimal("120"), "FREESHIP"));

        assertThat(quote.freeShippingApplied()).isTrue();
        assertThat(quote.options()).hasSize(2);
        assertThat(quote.options()).allSatisfy(opt ->
                assertThat(opt.shippingFee()).isEqualByComparingTo(BigDecimal.ZERO));
    }
}
