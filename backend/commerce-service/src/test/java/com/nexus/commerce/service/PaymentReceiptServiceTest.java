package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.entity.PaymentPriceSnapshot;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.PaymentRepository;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Bảo vệ ownership, snapshot thu gốc và fail closed cho attempt chưa xác nhận hoặc dữ liệu lịch sử hỏng. */
@ExtendWith(MockitoExtension.class)
class PaymentReceiptServiceTest {
    @Mock private PaymentRepository payments;
    @Mock private OrderRepository orders;
    private PaymentReceiptService service;
    private Payment payment;
    private Order order;
    private final UUID buyer = UUID.randomUUID();

    /** Fixture chỉ mô phỏng snapshot đã xác nhận; không chứng minh giao dịch VNPAY thật. */
    @BeforeEach
    void setup() {
        service = new PaymentReceiptService(payments, orders);
        order = Order.builder().id(UUID.randomUUID()).buyerId(buyer).total(new BigDecimal("999999")).build();
        payment = new Payment();
        payment.setId(UUID.randomUUID()); payment.setOrderId(order.getId()); payment.setStatus("SUCCESS");
        payment.setProvider("VNPAY"); payment.setMethod("VNPAY"); payment.setProviderRef("1234");
        payment.setResponseCode("00"); payment.setTransactionStatus("00"); payment.setCompletedAt(Instant.parse("2026-11-13T00:00:00Z"));
        payment.setAmount(new BigDecimal("105")); payment.setPriceSnapshot(snapshot(new BigDecimal("100"), new BigDecimal("105")));
    }

    /** Tải lại và đổi state Order không làm đổi ID, thời điểm hoặc số tiền đã thu từ Payment. */
    @Test
    void stableOriginalReceiptForBuyerAndAdmin() {
        stub();
        var first = service.download(payment.getId(), auth(buyer.toString(), "BUYER"));
        order.setStatus(OrderStatus.REFUNDED);
        var second = service.download(payment.getId(), auth(UUID.randomUUID().toString(), "ADMIN"));
        assertEquals(first, second);
        assertEquals("PAY-" + payment.getId(), first.receiptId());
        assertEquals(new BigDecimal("105"), first.prices().total());
        assertEquals("INTERNAL_PAYMENT_RECEIPT", first.documentType());
    }

    /** Không tiết lộ tồn tại payment cho Buyer khác; Seller/service account không được đọc dù cùng subject. */
    @Test
    void rejectsWrongOwnerAndRole() {
        stub();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.download(payment.getId(), auth(UUID.randomUUID().toString(), "BUYER"))).getHttpStatus().value());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.download(payment.getId(), auth(buyer.toString(), "SELLER"))).getHttpStatus().value());
    }

    /** Thiếu/sai subject phải bị chặn trước truy vấn repository, không cho UUID request thay thế actor. */
    @Test
    void requiresUuidJwtBeforeDatabase() {
        assertThrows(ApiException.class, () -> service.download(payment.getId(), null));
        assertThrows(ApiException.class, () -> service.download(payment.getId(), auth("bad", "ADMIN")));
        verifyNoInteractions(payments, orders);
    }

    /** REVIEW hoặc thất bại dù Order có giá chính thức cũng không phải chứng cứ đã thu tiền thành công. */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REVIEW", "FAILED", "EXPIRED"})
    void rejectsUnconfirmedPayment(String status) {
        stub(); payment.setStatus(status);
        assertNotReady();
    }

    /** Từ chối snapshot thiếu, sai tổng/đơn giá, VND lẻ và thiếu bằng chứng IPN; không fallback Order.total. */
    @Test
    void rejectsIncompleteAndInconsistentEvidence() {
        stub();
        var good = payment.getPriceSnapshot();
        payment.setPriceSnapshot(null); assertNotReady();
        payment.setPriceSnapshot(snapshot(new BigDecimal("99"), new BigDecimal("105"))); assertNotReady();
        payment.setPriceSnapshot(snapshot(new BigDecimal("100"), new BigDecimal("106"))); assertNotReady();
        payment.setPriceSnapshot(new PaymentPriceSnapshot(new BigDecimal("100.5"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.5"), good.items())); assertNotReady();
        payment.setPriceSnapshot(good); payment.setAmount(BigDecimal.ONE); assertNotReady();
        payment.setAmount(good.total()); payment.setCompletedAt(null); assertNotReady();
        payment.setCompletedAt(Instant.now()); payment.setProviderRef(null); assertNotReady();
        payment.setProviderRef("1234"); payment.setResponseCode("07"); assertNotReady();
        payment.setResponseCode("00"); payment.setTransactionStatus("06"); assertNotReady();
        payment.setTransactionStatus("00"); payment.setMethod("COD"); assertNotReady();
    }

    /** Mã không tồn tại trả cùng lỗi với sai ownership, không tự tạo biên nhận cho attempt mới. */
    @Test
    void missingPaymentIsNotFound() {
        when(payments.findById(payment.getId())).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.download(payment.getId(), auth(buyer.toString(), "BUYER"))).getHttpStatus().value());
        verifyNoInteractions(orders);
    }

    private void stub() {
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private void assertNotReady() {
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.download(payment.getId(), auth(buyer.toString(), "BUYER"))).getHttpStatus().value());
    }

    private PaymentPriceSnapshot snapshot(BigDecimal subtotal, BigDecimal total) {
        return new PaymentPriceSnapshot(subtotal, BigDecimal.TEN, new BigDecimal("5"), total,
                List.of(new PaymentPriceSnapshot.Line(UUID.randomUUID(), UUID.randomUUID(), 2, new BigDecimal("50"), new BigDecimal("100"))));
    }

    private JwtAuthenticationToken auth(String subject, String role) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none").subject(subject).build(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
