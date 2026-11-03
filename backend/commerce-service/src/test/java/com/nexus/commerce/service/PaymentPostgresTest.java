package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.commerce.client.NotificationDelivery;
import com.nexus.commerce.client.VnpayGateway;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CheckoutTransactionConfig;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.config.VnpayProperties;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.repository.CheckoutLock;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checkout→Payment→IPN chạy transaction/Flyway thật; chỉ API Catalog/kho được mock, không cần merchant thật cho test. */
@DataJpaTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.show-sql=false", "app.payment.vnpay.tmn-code=TESTONLY",
        "app.payment.vnpay.hash-secret=unit-test-only-not-a-credential",
        "app.payment.vnpay.return-url=https://example.test/payment-result"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationWorker.class, NotificationOutbox.class, CheckoutService.class, PaymentService.class, PaymentInventoryWorker.class, VnpayGateway.class,
        CheckoutLock.class, CheckoutTransactionConfig.class, PaymentPostgresTest.Config.class, com.nexus.commerce.service.impl.OrderServiceImpl.class,
        com.nexus.commerce.dto.mapper.OrderMapper.class, com.nexus.commerce.statemachine.OrderStatusStateMachine.class})
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentPostgresTest {
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService management;
    @Autowired private PaymentService payments;
    @Autowired private PaymentInventoryWorker worker;
    @Autowired private VnpayGateway gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationWorker notificationWorker;
    @MockitoBean private NotificationDelivery notificationDelivery;
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private InventoryClient inventory;
    @MockitoBean private InventoryServiceToken tokens;
    private UUID buyer;
    private UUID orderId;
    private UUID paymentId;
    private UUID reservation;

    @TestConfiguration
    static class Config {
        @Bean CommerceProperties commerceProperties() { return new CommerceProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean VnpayProperties vnpayProperties() {
            var properties = new VnpayProperties();
            properties.setTmnCode("TESTONLY");
            properties.setHashSecret("unit-test-only-not-a-credential");
            properties.setReturnUrl("https://example.test/payment-result");
            return properties;
        }
    }

    @BeforeEach
    void setup() {
        // Service account fixture chỉ mở cổng tạo URL; không gửi yêu cầu tới Keycloak thật.
        when(tokens.configured()).thenReturn(true);
        buyer = UUID.randomUUID();
        reservation = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, buyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,2,25000)", item, cart, sku);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("25000.00"), null, true, UUID.randomUUID())));
        when(inventory.reserve(any(), any(), any())).thenReturn(List.of(reservation));
        var request = new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "VNPAY", null, null, List.of(item));
        String key = UUID.randomUUID().toString();
        var created = checkout.create(buyer, key, request, "buyer-jwt", "127.0.0.1", "buyer@example.test");
        orderId = created.id();
        paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id=?", UUID.class, orderId);
        assertEquals(created, checkout.create(buyer, key, request, "buyer-jwt"));
    }

    private Map<String, String> callback(String response, String status, String amount) {
        var params = new HashMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_TxnRef", paymentId.toString(), "vnp_Amount", amount,
                "vnp_ResponseCode", response, "vnp_TransactionStatus", status,
                "vnp_TransactionNo", Long.toString(Math.abs((long) paymentId.hashCode()) + 1)));
        params.put("vnp_SecureHash", gateway.sign(params));
        return params;
    }

    /** Hai callback song song chỉ một lần đổi trạng thái, ghi history và enqueue confirm; worker chạy lặp không gọi kho lại. */
    @Test
    void concurrentSuccessCommitsExactlyOnceAndWorkerConfirmsStock() throws Exception {
        var callback = callback("00", "00", "5000000");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return payments.ipn(callback); });
            var second = executor.submit(() -> { start.await(); return payments.ipn(callback); });
            start.countDown();
            List<String> codes = List.of(first.get(20, TimeUnit.SECONDS).code(), second.get(20, TimeUnit.SECONDS).code()).stream().sorted().toList();
            assertEquals(List.of("00", "02"), codes);
        }
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        // Cô lập outbox của ca này vì các test khác có thể để lại tác vụ cố ý chưa xử lý trong DB tạm.
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        worker.run();
        worker.run();
        verify(inventory).confirm(reservation, "service-jwt");
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
    }

    /** Callback thất bại hủy đơn; worker lỗi mạng vẫn giữ tác vụ, tăng attempts và retry release sau đó. */
    @Test
    void failureCommitsReleaseOutboxAndRetriesTransientErrors() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        doThrow(new IllegalStateException("network")).doNothing().when(inventory).release(orderId, "service-jwt");
        worker.run();
        assertEquals(false, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        worker.run();
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
    }

    /** IPN sai chữ ký/số tiền không tạo history/outbox; payment và order phải giữ nguyên trạng thái pending. */
    @Test
    void invalidWebhookCannotChangeDatabase() {
        var tampered = callback("00", "00", "5000000");
        tampered.put("vnp_Amount", "1");
        assertEquals("97", payments.ipn(tampered).code());
        assertEquals("04", payments.ipn(callback("00", "00", "1")).code());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
    }

    /** Lỗi Notification xảy ra sau commit không đổi PAID/SUCCESS; retry giữ UUID/payload và không tạo thêm sự kiện. */
    @Test
    void notificationFailureCannotUndoPaymentAndRetriesSameSnapshot() {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals("02", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
        jdbc.update("UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(notificationDelivery.configured()).thenReturn(true);
        doThrow(new IllegalStateException("network")).when(notificationDelivery).send(any());
        notificationWorker.run();
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND NOT done AND attempts=1", Integer.class, orderId));
        org.mockito.Mockito.doNothing().when(notificationDelivery).send(any());
        jdbc.update("UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        notificationWorker.run();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND done AND attempts=2", Integer.class, orderId));
        var events = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(notificationDelivery, org.mockito.Mockito.times(4)).send(events.capture());
        assertEquals(new java.util.HashSet<>(events.getAllValues().subList(0, 2)),
                new java.util.HashSet<>(events.getAllValues().subList(2, 4)));
        assertEquals(buyer, events.getAllValues().get(0).get("userId"));
    }

    /** Thiếu OAuth giữ backlog pending; IPN thất bại tạo đúng thông báo thất bại thay vì thành công. */
    @Test
    void disabledDeliveryKeepsEventsAndFailureUsesCorrectType() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        when(notificationDelivery.configured()).thenReturn(false);
        notificationWorker.run();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_FAILED' AND NOT done", Integer.class, orderId));
        verify(notificationDelivery, org.mockito.Mockito.never()).send(any());
    }

    /** Constraint lỗi đúng lúc commit phải rollback cả trạng thái lẫn history/outbox; IPN retry sau đó vẫn xử lý được. */
    @Test
    void databaseFailureRollsBackFinancialStateAndOutboxTogether() {
        jdbc.execute("CREATE FUNCTION payment_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END; $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER payment_test_failure AFTER UPDATE ON payments DEFERRABLE INITIALLY DEFERRED "
                + "FOR EACH ROW EXECUTE FUNCTION payment_test_reject()");
        try {
            assertThrows(RuntimeException.class, () -> payments.ipn(callback("00", "00", "5000000")));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
            assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
        } finally {
            jdbc.execute("DROP TRIGGER payment_test_failure ON payments");
            jdbc.execute("DROP FUNCTION payment_test_reject()");
        }
        VnpayIpnResponse result = payments.ipn(callback("00", "00", "5000000"));
        assertEquals("00", result.code());
    }

    /** Checkout/IPN, quyền sở hữu, state machine và history chạy service/DB thật, không mock OrderService. */
    @Test
    void paidOrderCompletesRealLifecycleAndKeepsEmailSnapshot() {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        assertThrows(com.nexus.common.exception.BusinessException.class, () ->
                management.getOrderDetail(orderId, UUID.randomUUID(), List.of("ROLE_BUYER")));
        for (var status : List.of(com.nexus.commerce.enums.OrderStatus.PROCESSING,
                com.nexus.commerce.enums.OrderStatus.SHIPPED, com.nexus.commerce.enums.OrderStatus.DELIVERED)) {
            assertEquals(status, management.updateOrderStatus(orderId,
                    new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(status, "Cập nhật giao hàng"),
                    seller, List.of("ROLE_SELLER")).status());
        }
        management.updateOrderStatus(orderId, new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(
                com.nexus.commerce.enums.OrderStatus.COMPLETED, "Hoàn tất giao hàng"), UUID.randomUUID(), List.of("ROLE_ADMIN"));
        assertEquals("COMPLETED", management.getOrderDetail(orderId, buyer, List.of("ROLE_BUYER")).status().name());
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, orderId));
        assertEquals("buyer@example.test", jdbc.queryForObject(
                "SELECT email_payload->>'recipientEmail' FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'",
                String.class, orderId));
        assertEquals("Sản phẩm", jdbc.queryForObject(
                "SELECT email_payload->'items'->0->>'productName' FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'",
                String.class, orderId));
    }

    /** Hai request cùng chuyển PAID→PROCESSING phải đọc lại sau row lock, chỉ ghi history một lần. */
    @Test
    void concurrentStatusUpdatesAreSerialized() throws Exception {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        var request = new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(
                com.nexus.commerce.enums.OrderStatus.PROCESSING, "Bắt đầu đóng gói");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return management.updateOrderStatus(orderId, request, seller, List.of("ROLE_SELLER")); });
            var second = executor.submit(() -> { gate.await(); return management.updateOrderStatus(orderId, request, seller, List.of("ROLE_SELLER")); });
            gate.countDown();
            assertEquals("PROCESSING", first.get(20, TimeUnit.SECONDS).status().name());
            assertEquals("PROCESSING", second.get(20, TimeUnit.SECONDS).status().name());
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id=? AND from_status='PAID' AND to_status='PROCESSING'", Integer.class, orderId));
    }

    /** Chưa có refund/cancel provider: từ chối hủy VNPAY đang chờ hoặc PAID, không làm sai dữ liệu tài chính. */
    @Test
    void cancellationCannotFakeRefundOrRaceAnActiveVnpayPayment() {
        var request = new com.nexus.commerce.dto.request.CancelOrderRequest("Người mua yêu cầu hủy");
        assertThrows(com.nexus.common.exception.BusinessException.class,
                () -> management.cancelOrder(orderId, request, buyer, List.of("ROLE_BUYER")));
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertThrows(com.nexus.common.exception.BusinessException.class,
                () -> management.cancelOrder(orderId, request, buyer, List.of("ROLE_BUYER")));
        assertThrows(com.nexus.common.exception.BusinessException.class, () -> management.updateOrderStatus(orderId,
                new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.REFUNDED, "Hoàn tiền"),
                UUID.randomUUID(), List.of("ROLE_ADMIN")));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
    }

    /** Hủy COD thật phải ghi tác vụ release trong cùng transaction; gọi lặp không tạo history/tác vụ trùng. */
    @Test
    void cancellingCodPersistsAnIdempotentInventoryRelease() {
        UUID codBuyer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, codBuyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,1,25000)", item, cart, sku);
        var request = new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item));
        UUID codOrder = checkout.create(codBuyer, UUID.randomUUID().toString(), request, "buyer-jwt").id();
        var cancel = new com.nexus.commerce.dto.request.CancelOrderRequest("Không còn nhu cầu mua");
        management.cancelOrder(codOrder, cancel, codBuyer, List.of("ROLE_BUYER"));
        management.cancelOrder(codOrder, cancel, codBuyer, List.of("ROLE_BUYER"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, codOrder));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, codOrder));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, codOrder));
        when(tokens.get()).thenReturn("service-jwt");
        worker.run();
        verify(inventory).release(codOrder, "service-jwt");
    }
}
