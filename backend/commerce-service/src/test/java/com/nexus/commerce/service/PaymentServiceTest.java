package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        service = new PaymentService(payments, orders, gateway, config, locks, jdbc, tokens, org.mockito.Mockito.mock(NotificationOutbox.class));
        lenient().when(tokens.configured()).thenReturn(true);
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setBuyerId(UUID.randomUUID());
        order.setPaymentMethod("VNPAY");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotal(new BigDecimal("50000.00"));
        order.setCreatedAt(Instant.now());
        order.setReservationIds(List.of(UUID.randomUUID()));
        payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(order.getId());
        payment.setProvider("VNPAY");
        payment.setMerchantCode("TESTONLY");
        payment.setStatus("PENDING");
        payment.setAmount(order.getTotal());
        payment.setExpiresAt(Instant.now().plusSeconds(600));
        lenient().when(orders.findForPayment(order.getId())).thenReturn(Optional.of(order));
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
        assertEquals(order.getTotal(), created.amount());
        assertEquals("VND", created.currency());
        assertEquals("PENDING", created.status());
        payment.setPaymentUrl(created.paymentUrl());
        when(payments.findByOrderIdAndProvider(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
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
        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals("123456", payment.getProviderRef());
        assertEquals("02", service.ipn(callback).code());
        verify(payments).flush();
    }

    /** IPN thất bại hợp lệ hủy đơn và giữ payment FAILED; thành công đến sau không tự đảo terminal cũ. */
    @Test
    void failureCancelsOrderAndCannotBeProcessedAgain() {
        assertEquals("00", service.ipn(signed("5000000", "24", "02")).code());
        assertEquals("FAILED", payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("02", service.ipn(signed("5000000", "00", "00")).code());
    }

    /** Chặn hash sai, mã đơn lạ và số tiền sai ở cả snapshot payment lẫn order. */
    @Test
    void rejectsTamperedWebhookAndAmountMismatch() {
        var forged = signed("5000000", "00", "00");
        forged.put("vnp_Amount", "1");
        assertEquals("97", service.ipn(forged).code());
        assertEquals("04", service.ipn(signed("1", "00", "00")).code());
        order.setTotal(new BigDecimal("60000"));
        assertEquals("04", service.ipn(signed("5000000", "00", "00")).code());
        verify(payments, never()).flush();
        assertEquals("PENDING", payment.getStatus());
    }

    /** Trạng thái đang xử lý/nghi ngờ hoặc provider transaction dùng lại cần đối soát, không tự báo PAID. */
    @Test
    void ambiguousAndReusedProviderTransactionsRemainPending() {
        assertEquals("99", service.ipn(signed("5000000", "00", "01")).code());
        assertEquals("99", service.ipn(signed("5000000", "07", "02")).code());
        when(payments.existsByProviderAndProviderRefAndStatus("VNPAY", "123456", "SUCCESS")).thenReturn(true);
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
        assertCode("PAYMENT_RESERVATIONS_MISSING", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        order.setCreatedAt(Instant.now().minusSeconds(1000));
        assertCode("PAYMENT_EXPIRED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        verify(payments, never()).saveAndFlush(any());
    }

    /** Endpoint poll cũng kiểm tra chủ sở hữu; không suy luận kết quả từ query browser trả về. */
    @Test
    void readsOnlyOwnedPaymentAndRejectsExpiredOrCompletedAttempts() {
        assertEquals("PENDING", service.get(order.getBuyerId(), payment.getId()).status());
        assertCode("PAYMENT_ACCESS_DENIED", () -> service.get(UUID.randomUUID(), payment.getId()));
        when(payments.findByOrderIdAndProvider(order.getId(), "VNPAY")).thenReturn(Optional.of(payment));
        payment.setExpiresAt(Instant.now().minusSeconds(1));
        assertCode("PAYMENT_EXPIRED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
        payment.setStatus("SUCCESS");
        assertCode("PAYMENT_ALREADY_COMPLETED", () -> service.start(order.getBuyerId(), order.getId(), "ip"));
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
}
