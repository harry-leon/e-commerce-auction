package com.nexus.commerce.service;

import com.nexus.commerce.entity.PaymentPriceSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.VnpayGateway;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.commerce.config.VnpayProperties;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.PaymentRepository;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** Kiểm thử nghiệp vụ payment bằng chữ ký thật với merchant fixture; callback sai không được đổi Order/Payment. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock private PaymentRepository payments;
    @Mock private OrderRepository orders;
    @Mock private CheckoutLock locks;
    @Mock private JdbcTemplate jdbc;
    @Mock private InventoryServiceToken tokens;
    @Mock private PaymentPricingService pricing;
    @Mock private com.nexus.commerce.client.InventoryClient inventory;
    private VnpayGateway gateway;
    private PaymentService service;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setup() {
        var config = new VnpayProperties();
        config.setTmnCode("TESTONLY");
        config.setHashSecret("unit-test-only-not-a-credential");
        config.setReturnUrl("https://example.test/payment-result");
        gateway = new VnpayGateway(config);
        service = new PaymentService(payments, orders, gateway, config, locks, jdbc, tokens,
                org.mockito.Mockito.mock(NotificationOutbox.class), pricing, inventory);
        lenient().when(tokens.configured()).thenReturn(true);
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setBuyerId(UUID.randomUUID());
        order.setPaymentMethod("VNPAY");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotal(new BigDecimal("50000.00"));
        // Fixture chưa thu tiền: official null; snapshot giá cố định để kiểm thử IPN độc lập Catalog.
        order.setEstimatedTotal(order.getTotal());
        order.setPriceFinalized(false);
        order.setTotal(null);
        var item = com.nexus.commerce.entity.OrderItem.builder().skuId(UUID.randomUUID()).productName("Áo")
                .skuCode("AO").quantity(2).estimatedUnitPrice(new BigDecimal("25000.00")).build();
        order.addItem(item);
        var snapshot = new PaymentPriceSnapshot(new BigDecimal("50000.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"), new BigDecimal("50000.00"), List.of(new PaymentPriceSnapshot.Line(
                item.getId(), item.getSkuId(), 2, new BigDecimal("25000.00"), new BigDecimal("50000.00"))));
        lenient().when(pricing.quote(order)).thenReturn(snapshot);
        lenient().when(inventory.reserve(any(), any(), any())).thenReturn(List.of(UUID.randomUUID()));
        order.setCreatedAt(Instant.now());
        order.setReservationIds(List.of(UUID.randomUUID()));
        payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(order.getId());
        payment.setProvider("VNPAY");
        payment.setMerchantCode("TESTONLY");
        payment.setStatus("PENDING");
        payment.setAmount(snapshot.total());
        payment.setPriceSnapshot(snapshot);
        payment.setInventoryRefId(payment.getId());
        payment.setExpiresAt(Instant.now().plusSeconds(600));
        lenient().when(orders.findForPayment(order.getId())).thenReturn(Optional.of(order));
        // IPN dùng query fetch sẵn items để tạo notification snapshot trong cùng transaction.
        lenient().when(orders.findForPaymentWithItems(order.getId())).thenReturn(Optional.of(order));
        lenient().when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        lenient().when(payments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Map<String, String> signed(String amount, String code, String status) {
        var params = new HashMap<>(Map.of("vnp_TxnRef", payment.getId().toString(), "vnp_TmnCode", "TESTONLY",
                "vnp_Amount", amount, "vnp_ResponseCode", code, "vnp_TransactionStatus", status, "vnp_TransactionNo", "123456"));
        params.put("vnp_SecureHash", gateway.sign(params));
        return params;
    }

    /** URL chỉ dùng total DB; cùng order trả lại attempt cũ, callback mới là nguồn trạng thái. */
    @Test
    void createsAndReplaysPendingUrlWithoutNewAttempt() {
        var created = service.start(order.getBuyerId(), order.getId(), "192.0.2.1");
        assertEquals(order.getEstimatedTotal(), created.amount());
        assertEquals("VND", created.currency());
        assertEquals("PENDING", created.status());
        payment.setPaymentUrl(created.paymentUrl());
        when(payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        assertEquals(created.paymentUrl(), service.start(order.getBuyerId(), order.getId(), "192.0.2.2").paymentUrl());
        assertEquals(created.paymentUrl(), service.checkoutUrl(order, "192.0.2.2"));
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
    }

    /** IPN thành công đổi PAID/SUCCESS một lần; callback lặp không tạo history/outbox lần hai. */
    @Test
    void successIsTerminalAndDuplicatesAreAcknowledgedWithoutSideEffects() {
        var callback = signed("5000000", "00", "00");
        assertEquals("00", service.ipn(callback).code());
        assertEquals("SUCCESS", payment.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(new BigDecimal("50000.00"), order.getTotal());
        assertEquals("PENDING", payment.getInventoryStatus());
        assertEquals("123456", payment.getProviderRef());
        assertEquals("02", service.ipn(callback).code());
        verify(payments).flush();
    }

    /** FAILED giữ đơn cho retry; success đến sau được lưu REVIEW để không làm mất dấu tiền đã thu muộn. */
    @Test
    void failureKeepsOrderRetryableAndLateSuccessRequiresReview() {
        assertEquals("00", service.ipn(signed("5000000", "24", "02")).code());
        assertEquals("FAILED", payment.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertNull(order.getTotal());
        assertEquals("00", service.ipn(signed("5000000", "00", "00")).code());
        assertEquals("REVIEW", payment.getStatus());
        assertEquals("REQUIRES_REVIEW", service.get(order.getBuyerId(), payment.getId()).status());
    }

    /** Chặn hash sai, mã đơn lạ và số tiền sai ở cả snapshot payment lẫn order. */
    @Test
    void rejectsTamperedWebhookAndAmountMismatch() {
        var forged = signed("5000000", "00", "00");
        forged.put("vnp_Amount", "1");
        assertEquals("97", service.ipn(forged).code());
        assertEquals("04", service.ipn(signed("1", "00", "00")).code());
        // Đối soát theo snapshot attempt, không so giá trên Order hay Catalog đã thay đổi.
        payment.setAmount(new BigDecimal("60000"));
        assertEquals("04", service.ipn(signed("5000000", "00", "00")).code());
        verify(payments, never()).flush();
        assertEquals("PENDING", payment.getStatus());
    }

    /** Trạng thái đang xử lý/nghi ngờ hoặc provider transaction dùng lại cần đối soát, không tự báo PAID. */
    @Test
    void ambiguousAndReusedProviderTransactionsRemainPending() {
        assertEquals("99", service.ipn(signed("5000000", "00", "01")).code());
        assertEquals("99", service.ipn(signed("5000000", "07", "02")).code());
        when(payments.existsByProviderAndProviderRefAndStatusIn("VNPAY", "123456", List.of("SUCCESS", "REVIEW"))).thenReturn(true);
        assertEquals("99", service.ipn(signed("5000000", "00", "00")).code());
        assertEquals("PENDING", payment.getStatus());
        verify(payments, never()).flush();
    }

    /** Buyer khác, COD, đơn đã trả tiền, hết hạn hoặc không có phiếu giữ kho đều không được cấp URL mới. */
    @Test
    void validatesOwnershipStateAndReservationDeadline() {
        assertCode("PAYMENT_ACCESS_DENIED", () -> service.start(UUID.randomUUID(), order.getId(), "ip"));
        order.setPaymentMethod("COD");
        assertCode("PAYMENT_METHOD_UNSUPPORTED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        order.setPaymentMethod("VNPAY");
        order.setStatus(OrderStatus.PAID);
        assertCode("ORDER_STATUS_INVALID", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setReservationIds(List.of());
        when(inventory.reserve(any(), any(), any())).thenReturn(List.of());
        assertCode("PAYMENT_RESERVATIONS_MISSING", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        order.setPaymentDeadline(Instant.now().minusSeconds(1000));
        assertCode("PAYMENT_EXPIRED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        verify(payments, never()).saveAndFlush(any());
    }

    /** Endpoint poll cũng kiểm tra chủ sở hữu; không suy luận kết quả từ query browser trả về. */
    @Test
    void readsOnlyOwnedPaymentAndRejectsExpiredOrCompletedAttempts() {
        assertEquals("PENDING", service.get(order.getBuyerId(), payment.getId()).status());
        assertCode("PAYMENT_ACCESS_DENIED", () -> service.get(UUID.randomUUID(), payment.getId()));
        when(payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        payment.setExpiresAt(Instant.now().minusSeconds(1));
        assertCode("PAYMENT_EXPIRED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        payment.setStatus("SUCCESS");
        assertEquals("SUCCESS", service.start(order.getBuyerId(), order.getId(), "ip").status());
    }

    /** Hết hạn URL phải hiện EXPIRED/null link, nhưng GET không hủy đơn hay ghi tác vụ kho khi chưa biết đã thu tiền chưa. */
    @Test
    void expiredLookupDoesNotMutatePendingPaymentOrOrder() {
        payment.setPaymentUrl("https://example.test/old-payment");
        payment.setExpiresAt(Instant.now().minusSeconds(1));
        var result = service.get(order.getBuyerId(), payment.getId());
        assertEquals("EXPIRED", result.status());
        assertNull(result.paymentUrl());
        assertEquals(payment.getExpiresAt(), result.expiresAt());
        assertEquals("PENDING", payment.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertCode("PAYMENT_ACCESS_DENIED", () -> service.get(UUID.randomUUID(), payment.getId()));
        verify(payments, never()).flush();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** Hạn cũ không được ghi đè kết quả cuối đã xác nhận từ provider. */
    @Test
    void terminalResultsTakePriorityOverExpiredLink() {
        payment.setExpiresAt(Instant.now().minusSeconds(1));
        for (String terminal : List.of("SUCCESS", "FAILED", "CANCELLED")) {
            payment.setStatus(terminal);
            assertEquals(terminal, service.get(order.getBuyerId(), payment.getId()).status());
        }
    }

    /** IPN hợp lệ đến sau khi client đã thấy EXPIRED vẫn ghi nhận SUCCESS, callback lặp không enqueue hai lần. */
    @Test
    void lateSuccessAfterExpiredLookupStillConfirmsExactlyOnce() {
        payment.setExpiresAt(Instant.now().minusSeconds(60));
        assertEquals("EXPIRED", service.get(order.getBuyerId(), payment.getId()).status());
        var callback = signed("5000000", "00", "00");
        assertEquals("00", service.ipn(callback).code());
        assertEquals("PROCESSING", service.get(order.getBuyerId(), payment.getId()).status());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals("02", service.ipn(callback).code());
        verify(payments).flush();
    }

    /** IPN thất bại đến chậm mới cho phép release/retry; đồng hồ client không được tự kết luận thất bại. */
    @Test
    void lateFailureAfterExpiredLookupKeepsOrderRetryable() {
        payment.setExpiresAt(Instant.now().minusSeconds(60));
        assertEquals("EXPIRED", service.get(order.getBuyerId(), payment.getId()).status());
        assertEquals("00", service.ipn(signed("5000000", "24", "02")).code());
        assertEquals("FAILED", service.get(order.getBuyerId(), payment.getId()).status());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        verify(payments).flush();
    }

    /** Callback đã ký nhưng reference/merchant sai không được cập nhật DB. */
    @Test
    void rejectsUnknownPaymentAndMerchantSnapshotMismatch() {
        when(payments.findById(payment.getId())).thenReturn(Optional.empty());
        assertEquals("01", service.ipn(signed("5000000", "00", "00")).code());
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        payment.setMerchantCode("OTHER");
        assertEquals("97", service.ipn(signed("5000000", "00", "00")).code());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getCode());
    }

    /** Retry FAILED cần UUID mới; cùng key luôn replay kết quả cũ, không đọc giá hoặc giữ kho lại. */
    @Test
    void failedAttemptRequiresNewKeyAndReplaysOldKeyWithoutChargingAgain() {
        payment.setStatus("FAILED");
        when(payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        assertCode("PAYMENT_RETRY_KEY_REQUIRED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        assertCode("PAYMENT_KEY_INVALID", () -> service.start(order.getBuyerId(), order.getId(), "ip", "invalid"));
        String oldKey = UUID.randomUUID().toString();
        when(payments.findByOrderIdAndIdempotencyKey(order.getId(), order.getId() + ":" + oldKey)).thenReturn(Optional.of(payment));
        assertEquals(payment.getId(), service.start(order.getBuyerId(), order.getId(), "ip", oldKey).paymentId());
        verify(inventory, never()).reserve(any(), any(), any());
        var next = service.start(order.getBuyerId(), order.getId(), "ip", UUID.randomUUID().toString());
        org.junit.jupiter.api.Assertions.assertNotEquals(payment.getId(), next.paymentId());
        verify(inventory).reserve(org.mockito.ArgumentMatchers.eq(next.paymentId()), any(), any());
        assertNull(order.getTotal());
    }

    /** Có tiền cần đối soát thì chặn mọi attempt mới, kể cả attempt mới nhất đang FAILED. */
    @Test
    void reviewBlocksFurtherPaymentAttempts() {
        when(payments.existsByOrderIdAndInventoryStatus(order.getId(), "REVIEW")).thenReturn(true);
        assertCode("PAYMENT_REQUIRES_REVIEW", () ->
                service.start(order.getBuyerId(), order.getId(), "ip", UUID.randomUUID().toString()));
        verify(inventory, never()).reserve(any(), any(), any());
    }

    /** Hồi quy P1: cùng key của attempt mới không được vượt qua REVIEW do attempt cũ nhận tiền muộn. */
    @Test
    void replaySuppressesPaymentUrlWhenAnotherAttemptNeedsReview() {
        String key = UUID.randomUUID().toString();
        payment.setPaymentUrl("https://example.test/pending-attempt");
        when(payments.findByOrderIdAndIdempotencyKey(order.getId(), order.getId() + ":" + key)).thenReturn(Optional.of(payment));
        when(payments.existsByOrderIdAndInventoryStatus(order.getId(), "REVIEW")).thenReturn(true);
        var result = service.start(order.getBuyerId(), order.getId(), "ip", key);
        assertEquals("REQUIRES_REVIEW", result.status());
        assertNull(result.paymentUrl());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    /** Hồi quy P1: poll và helper URL cũ cùng áp dụng hàng rào đối soát toàn đơn, không chỉ nhìn riêng attempt. */
    @Test
    void pollAndLegacyUrlSuppressPendingAttemptOnReviewedOrder() {
        payment.setPaymentUrl("https://example.test/pending-attempt");
        when(payments.existsByOrderIdAndInventoryStatus(order.getId(), "REVIEW")).thenReturn(true);
        when(payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        var result = service.get(order.getBuyerId(), payment.getId());
        assertEquals("REQUIRES_REVIEW", result.status());
        assertNull(result.paymentUrl());
        assertNull(service.checkoutUrl(order, "ip"));
        assertEquals("PENDING", payment.getStatus());
        verify(payments, never()).flush();
    }

    /** Một key mới khi đang có PENDING phải bị từ chối, không được báo replay rồi có thể tạo tiền mới khi gửi lại sau FAILED. */
    @Test
    void differentKeyCannotClaimAnExistingPendingAttempt() {
        when(payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        assertCode("PAYMENT_IN_PROGRESS", () ->
                service.start(order.getBuyerId(), order.getId(), "ip", UUID.randomUUID().toString()));
        verify(inventory, never()).reserve(any(), any(), any());
    }

    /** Winner mới chọn VNPAY dùng giá DB và deadline settlement; replay giữ URL/TxnRef cũ, không gia hạn thêm 24h. */
    @Test
    void winnerSelectsVnpayUsingSettlementDeadline() {
        order.setSourceType(com.nexus.commerce.enums.OrderSourceType.AUCTION);
        order.setPaymentMethod("AUCTION");
        order.setCreatedAt(Instant.now().minusSeconds(3600));
        Instant deadline = Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        order.setPaymentDeadline(deadline);
        var result = service.start(order.getBuyerId(), order.getId(), "127.0.0.1");
        assertEquals(deadline, result.expiresAt());
        assertEquals("VNPAY", order.getPaymentMethod());
        assertEquals(order.getEstimatedTotal(), result.amount());
    }

    /** Thiếu deadline/kho từ handover phải fail closed, không tự chọn mốc mới hoặc cấp URL cho người không phải winner. */
    @Test
    void winnerPaymentRequiresCompleteHandoverAndOwnership() {
        order.setSourceType(com.nexus.commerce.enums.OrderSourceType.AUCTION);
        order.setPaymentMethod("AUCTION");
        assertCode("PAYMENT_ACCESS_DENIED", () -> service.start(UUID.randomUUID(), order.getId(), "ip"));
        assertCode("AUCTION_PAYMENT_NOT_READY", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        order.setPaymentDeadline(Instant.now().plusSeconds(1000));
        order.setReservationIds(List.of());
        assertCode("PAYMENT_RESERVATIONS_MISSING", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        verify(payments, never()).saveAndFlush(any());
    }

    /** IPN winner chỉ ghi tiền và tác vụ kho một lần; client chưa thấy SUCCESS trước khi worker commit kho. */
    @Test
    void winnerSuccessWaitsForInventoryAndDuplicateHasNoSideEffects() {
        order.setSourceType(com.nexus.commerce.enums.OrderSourceType.AUCTION);
        var callback = signed("5000000", "00", "00");
        assertEquals("00", service.ipn(callback).code());
        assertEquals("SUCCESS", payment.getStatus());
        assertEquals("PENDING", payment.getInventoryStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals("PROCESSING", service.get(order.getBuyerId(), payment.getId()).status());
        assertNull(service.get(order.getBuyerId(), payment.getId()).paymentUrl());
        assertEquals("02", service.ipn(callback).code());
        verify(payments).flush();
    }

    /** Callback thu tiền sau timeout vẫn được lưu để hoàn tiền/đối soát; tuyệt đối không hồi sinh đơn CANCELLED. */
    @Test
    void lateWinnerFundsAfterCancellationRequireReview() {
        order.setSourceType(com.nexus.commerce.enums.OrderSourceType.AUCTION);
        order.setStatus(OrderStatus.CANCELLED);
        assertEquals("00", service.ipn(signed("5000000", "00", "00")).code());
        assertEquals("REVIEW", payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("REQUIRES_REVIEW", service.get(order.getBuyerId(), payment.getId()).status());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
