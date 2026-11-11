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
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF", "app.payment.vnpay.tmn-code=TESTONLY",
        "app.payment.vnpay.hash-secret=unit-test-only-not-a-credential",
        "app.payment.vnpay.return-url=https://example.test/payment-result"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentPricingService.class, AuctionPaymentExpiryWorker.class, NotificationWorker.class, NotificationOutbox.class, CheckoutService.class, PaymentService.class, PaymentInventoryWorker.class, VnpayGateway.class,
        CheckoutLock.class, CheckoutTransactionConfig.class, PaymentPostgresTest.Config.class, com.nexus.commerce.service.impl.OrderServiceImpl.class,
        com.nexus.commerce.dto.mapper.OrderMapper.class, com.nexus.commerce.statemachine.OrderStatusStateMachine.class})
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentPostgresTest {
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService management;
    @Autowired private PaymentService payments;
    @Autowired private PaymentInventoryWorker worker;
    @Autowired private AuctionPaymentExpiryWorker expiry;
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

    /** Nâng fixture checkout thành đơn winner đã handover để kiểm tra transaction IPN/expiry/outbox thật trên PostgreSQL. */
    private void winner(boolean expired) {
        jdbc.update("UPDATE orders SET source_type='AUCTION',auction_id=?,payment_deadline="
                + "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + (? * interval '1 hour') WHERE id=?",
                UUID.randomUUID(), expired ? -1 : 1, orderId);
        // Fixture winner dùng ref kho từ handover; REGULAR trước đó giữ bằng paymentId.
        jdbc.update("UPDATE payments SET inventory_ref_id=? WHERE id=?", orderId, paymentId);
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE order_id<>?", orderId);
        when(tokens.get()).thenReturn("service-jwt");
    }

    /** Callback đồng thời chỉ ghi một COMMIT; PAID/history/thông báo chỉ xuất hiện sau worker commit kho thành công. */
    @Test
    void winnerConcurrentIpnsWaitForInventoryThenCompleteExactlyOnce() throws Exception {
        winner(false);
        var callback = callback("00", "00", "5000000");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            var b = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            gate.countDown();
            assertEquals(List.of("00", "02"), List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).stream().sorted().toList());
        }
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        assertEquals("PENDING_PAYMENT", orderState());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
        worker.run();
        worker.run();
        assertEquals("PAID", orderState());
        assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        verify(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=? AND to_status='PAID'", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'", Integer.class, orderId));
    }

    /** Sweeper kho thắng: tiền được ghi SUCCESS nhưng đơn CANCELLED/REVIEW, không phát thông báo đã thanh toán/giao hàng. */
    @Test
    void expiredInventoryNeverCreatesPaidWinner() {
        winner(false);
        payments.ipn(callback("00", "00", "5000000"));
        doThrow(new com.nexus.common.exception.ApiException("INVENTORY_RESERVATION_EXPIRED",
                org.springframework.http.HttpStatus.CONFLICT, "Phiếu đã hết hạn", null))
                .when(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        worker.run();
        assertEquals("CANCELLED", orderState());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'", Integer.class, orderId));
        assertEquals("02", payments.ipn(callback("00", "00", "5000000")).code());
    }

    /** HTTP response mất sau commit kho: outbox còn pending, lần retry cùng orderId mới xác nhận PAID, không thu tiền lại. */
    @Test
    void winnerInventoryTimeoutRetriesAndDoesNotReportSuccessEarly() {
        winner(false);
        payments.ipn(callback("00", "00", "5000000"));
        doThrow(new IllegalStateException("lost response")).doNothing()
                .when(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        worker.run();
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        assertEquals("PENDING_PAYMENT", orderState());
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        worker.run();
        assertEquals("PAID", orderState());
        verify(inventory, org.mockito.Mockito.times(2)).commitOrder(orderId, List.of(reservation), "service-jwt");
    }

    /** Timeout đơn trước callback: hủy/release chỉ một lần, IPN muộn ghi tiền REVIEW và không tạo COMMIT hồi sinh đơn. */
    @Test
    void winnerDeadlineThenLateIpnKeepsCancellationAndRecordsFunds() {
        winner(true);
        expiry.run();
        expiry.run();
        assertEquals("CANCELLED", orderState());
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals("CANCELLED", orderState());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
    }

    /** IPN và timeout chạy song song phải có một kết quả nhất quán; expiry không hủy đơn có SUCCESS đang chờ kho. */
    @Test
    void winnerExpiryRacesIpnWithoutPaidAndReleaseTogether() throws Exception {
        winner(true);
        var callback = callback("00", "00", "5000000");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            var b = pool.submit(() -> { gate.await(); expiry.run(); return true; });
            gate.countDown();
            assertEquals("00", a.get(20, TimeUnit.SECONDS));
            b.get(20, TimeUnit.SECONDS);
        }
        worker.run();
        var releases = jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, orderId);
        if ("PAID".equals(orderState())) {
            assertEquals(0, releases);
            assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        } else {
            assertEquals("CANCELLED", orderState());
            assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        }
    }

    /** Đọc state bằng connection mới để kiểm tra kết quả commit, không đọc entity còn cache trong test. */
    private String orderState() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
    }

    /** Hai handover thật chạy song song phải nhận cùng một orderId; khóa PostgreSQL bảo vệ read/insert, không giả lập bằng Map. */
    @Test
    void concurrentWinnerHandoverReturnsOnePersistedOrder() throws Exception {
        UUID auction = UUID.randomUUID();
        var deadline = java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new com.nexus.commerce.dto.request.CreateAuctionOrderRequest(auction, buyer, UUID.randomUUID(),
                null, UUID.randomUUID(), new BigDecimal("100000"), deadline, "Winner item", "WINNER-SKU", List.of(reservation));
        var gate = new CountDownLatch(1);
        UUID winnerOrder;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return management.createAuctionOrder(request); });
            var b = pool.submit(() -> { gate.await(); return management.createAuctionOrder(request); });
            gate.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var replay = b.get(20, TimeUnit.SECONDS);
            assertEquals(first.orderId(), replay.orderId());
            assertEquals(deadline, replay.paymentDeadline());
            winnerOrder = first.orderId();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders WHERE auction_id=?", Integer.class, auction));
        var payment = payments.start(buyer, winnerOrder, "127.0.0.1");
        assertEquals(deadline, payment.expiresAt());
        assertEquals("PENDING", payment.status());
        assertEquals("VNPAY", jdbc.queryForObject("SELECT payment_method FROM orders WHERE id=?", String.class, winnerOrder));
    }

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
        assertEquals(created, checkout.create(buyer, key, request, "buyer-jwt"));
        when(tokens.get()).thenReturn("service-jwt");
        paymentId = payments.start(buyer, orderId, "127.0.0.1", UUID.randomUUID().toString()).paymentId();
        // Cô lập tác vụ còn lại của test trước vì mỗi ca dùng transaction thật và không rollback fixture.
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE order_id<>?", orderId);
    }

    /** Đọc hết hạn không sửa DB/đơn; IPN tới sau vẫn commit SUCCESS và chỉ enqueue confirm đúng một lần. */
    @Test
    void expiredProjectionKeepsDatabasePendingUntilVerifiedLateIpn() {
        // Schema lưu timestamp UTC không kèm zone; dùng UTC tường minh, không phụ thuộc timezone của JDBC trên máy test.
        jdbc.update("UPDATE payments SET expires_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '1 minute' WHERE id=?", paymentId);
        var result = payments.get(buyer, paymentId);
        assertEquals("EXPIRED", result.status());
        org.junit.jupiter.api.Assertions.assertNull(result.paymentUrl());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        var ipn = callback("00", "00", "5000000");
        assertEquals("00", payments.ipn(ipn).code());
        assertEquals("02", payments.ipn(ipn).code());
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        worker.run();
        assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
    }

    private Map<String, String> callback(String response, String status, String amount) {
        var params = new HashMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_TxnRef", paymentId.toString(), "vnp_Amount", amount,
                "vnp_ResponseCode", response, "vnp_TransactionStatus", status,
                "vnp_TransactionNo", Long.toString(Math.abs((long) paymentId.hashCode()) + 1)));
        params.put("vnp_SecureHash", gateway.sign(params));
        return params;
    }

    /** Snapshot khóa theo attempt: đổi Catalog giữa hai lần trả tiền chỉ ảnh hưởng attempt mới, không sửa tiền đã gửi cổng. */
    @Test
    void retryRepricesAndFreezesAttemptWhileOfficialPricesStayNullUntilSuccess() {
        assertEquals(false, jdbc.queryForObject("SELECT price_finalized FROM orders WHERE id=?", Boolean.class, orderId));
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
        UUID oldPayment = paymentId;
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("30000"), null, true, seller)));
        String key = UUID.randomUUID().toString();
        var next = payments.start(buyer, orderId, "127.0.0.1", key);
        paymentId = next.paymentId();
        assertEquals(new BigDecimal("60000.00"), next.amount());
        assertEquals(next.paymentId(), payments.start(buyer, orderId, "127.0.0.1", key).paymentId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(new BigDecimal("50000.00"), payments.get(buyer, oldPayment).amount());
        // Catalog tiếp tục tăng sau khi cấp URL; callback vẫn chốt 30.000 mỗi SKU của attempt, không thành 90.000.
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("90000"), null, true, seller)));
        assertEquals("00", payments.ipn(callback("00", "00", "6000000")).code());
        assertEquals(new BigDecimal("60000.00"), jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("30000.00"), jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
        worker.run();
        assertEquals("PAID", orderState());
        // Release attempt cũ chạy cùng batch vẫn nhắm oldPayment, không thể nhả phiếu mới.
        verify(inventory).release(oldPayment, "service-jwt");
        verify(inventory).commitOrder(paymentId, List.of(reservation), "service-jwt");
    }

    /** Hai request retry song song cùng key phải có đúng một attempt mới, dù cả hai cùng thấy lần trước FAILED. */
    @Test
    void concurrentRetryCreatesExactlyOneNewAttempt() throws Exception {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        String key = UUID.randomUUID().toString();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return payments.start(buyer, orderId, "ip", key); });
            var second = pool.submit(() -> { gate.await(); return payments.start(buyer, orderId, "ip", key); });
            gate.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS).paymentId(), second.get(20, TimeUnit.SECONDS).paymentId());
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND status='PENDING'", Integer.class, orderId));
    }

    /** PENDING hết hạn chưa rõ kết quả không được mở lần thu tiền khác chỉ dựa trên đồng hồ client. */
    @Test
    void expiredPendingCannotBeRetriedAndFailedKeyReplaysOnlyItsOwnAttempt() {
        jdbc.update("UPDATE payments SET expires_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '1 minute' WHERE id=?", paymentId);
        assertThrows(com.nexus.common.exception.ApiException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
    }

    /** Thu tiền muộn từ FAILED được lưu REVIEW; không ghi đè snapshot/tổng của attempt mới hoặc tự giao hai lần. */
    @Test
    void lateFundsFromFailedAttemptRequireReviewAndBlockNewCharges() {
        var late = callback("00", "00", "5000000");
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        UUID previous = paymentId;
        var next = payments.start(buyer, orderId, "ip", UUID.randomUUID().toString());
        paymentId = next.paymentId();
        assertEquals("00", payments.ipn(late).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, previous).status());
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertThrows(com.nexus.common.exception.ApiException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        // Attempt mới đã được cấp URL vẫn có thể thu tiền; callback cũng phải đối soát thay vì chốt đè.
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
    }

    /** Reserve timeout sau khi Fulfillment có thể đã giữ hàng: rollback không lưu attempt và bù trừ đúng ref mới. */
    @Test
    void reservationFailureRollsBackAttemptAndReleasesOnlyItsReference() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        doThrow(new IllegalStateException("lost reserve response")).when(inventory).reserve(any(), any(), any());
        assertThrows(IllegalStateException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        verify(inventory).release(org.mockito.ArgumentMatchers.argThat(ref -> !ref.equals(paymentId)), org.mockito.ArgumentMatchers.isNull());
    }

    /** COD không tự coi đã thu tiền lúc tạo; xác nhận backend hợp lệ mới chốt dự kiến và enqueue xuất kho. */
    @Test
    void codConfirmationFinalizesEstimatedPricesAndCreatesInventoryTasksOnce() {
        UUID codBuyer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, codBuyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,1,25000)", item, cart, sku);
        var created = checkout.create(codBuyer, UUID.randomUUID().toString(),
                new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item)), "buyer-jwt");
        assertEquals(false, created.priceFinalized());
        org.junit.jupiter.api.Assertions.assertNull(created.total());
        var confirm = new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.PAID, "Đã xác nhận thu tiền COD");
        var paid = management.updateOrderStatus(created.id(), confirm, UUID.randomUUID(), List.of("ROLE_ADMIN"));
        management.updateOrderStatus(created.id(), confirm, UUID.randomUUID(), List.of("ROLE_ADMIN"));
        assertEquals(true, paid.priceFinalized());
        assertEquals(new BigDecimal("25000.00"), paid.total());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='CONFIRM'", Integer.class, created.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, created.id()));
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
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        // Cô lập outbox của ca này vì các test khác có thể để lại tác vụ cố ý chưa xử lý trong DB tạm.
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        worker.run();
        worker.run();
        verify(inventory).commitOrder(paymentId, List.of(reservation), "service-jwt");
        assertEquals("PAID", orderState());
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
    }

    /** Callback thất bại hủy đơn; worker lỗi mạng vẫn giữ tác vụ, tăng attempts và retry release sau đó. */
    @Test
    void failureCommitsReleaseOutboxAndRetriesTransientErrors() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        doThrow(new IllegalStateException("network")).doNothing().when(inventory).release(paymentId, "service-jwt");
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
        worker.run();
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
            // Deferred commit failure phải rollback cả giá chính thức của Order và OrderItem, không chỉ Payment.status.
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
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
        worker.run();
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
        worker.run();
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
        worker.run();
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
