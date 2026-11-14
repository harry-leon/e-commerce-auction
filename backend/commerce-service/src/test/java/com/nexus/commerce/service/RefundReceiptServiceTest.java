package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.dto.response.PaymentReceipt;
import com.nexus.commerce.entity.PaymentPriceSnapshot;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Không phát biên nhận hoàn tiền chỉ từ status; bảo vệ quyền và liên kết với snapshot khoản thu gốc. */
@ExtendWith(MockitoExtension.class)
class RefundReceiptServiceTest {
    @Mock private JdbcTemplate jdbc;
    @Mock private PaymentReceiptService payments;
    private RefundReceiptService service;
    private final UUID id = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final Instant recorded = Instant.parse("2026-11-13T00:00:00Z");

    @BeforeEach
    void setup() { service = new RefundReceiptService(jdbc, payments); }

    /** PROVIDER/MANUAL đều giữ loại bằng chứng rõ ràng và ID ổn định qua tải lại. */
    @ParameterizedTest
    @ValueSource(strings = {"PROVIDER", "MANUAL"})
    void stableLinkedReceipt(String source) {
        stub(row("SUCCEEDED", source, true, false, new BigDecimal("100"), recorded));
        var first = service.download(id, auth("BUYER"));
        assertEquals(first, service.download(id, auth("ADMIN")));
        assertEquals("PAY-" + payment, first.originalReceiptId());
        assertEquals("REFUND-" + id, first.receiptId());
        assertEquals(recorded, first.recordedAt());
        assertEquals(source, first.evidenceSource());
    }

    /** Đang chờ/thất bại/không rõ không được biến thành xác nhận đã hoàn dù fixture có registry. */
    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "PROCESSING", "UNKNOWN", "REQUIRES_REVIEW", "FAILED"})
    void rejectsNonSucceeded(String status) {
        stub(row(status, "PROVIDER", true, false, BigDecimal.ONE, recorded));
        notReady();
    }

    /** Hold/mâu thuẫn, thiếu timestamp, quá tiền thu hoặc bằng chứng không khớp đều cần đối soát trước khi xuất. */
    @Test
    void rejectsInvalidEvidenceAndMoney() {
        for (var row : List.of(row("SUCCEEDED", "PROVIDER", false, false, BigDecimal.ONE, recorded),
                row("SUCCEEDED", "MANUAL", true, true, BigDecimal.ONE, recorded),
                row("SUCCEEDED", "PROVIDER", true, false, BigDecimal.ONE, null),
                row("SUCCEEDED", "PROVIDER", true, false, new BigDecimal("101"), recorded),
                row("SUCCEEDED", "PROVIDER", true, false, BigDecimal.ZERO, recorded),
                row("SUCCEEDED", "PROVIDER", true, false, new BigDecimal("1.5"), recorded))) {
            stub(row); notReady();
        }
    }

    /** Sai chủ đơn phải giữ 404 trước lỗi trạng thái; không tiết lộ refund đang chờ cho người khác. */
    @Test
    void enforcesOriginalReceiptOwnership() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<RefundReceiptService.Evidence>>any(), eq(id)))
                .thenReturn(List.of(row("REQUESTED", null, false, false, BigDecimal.ONE, null)));
        when(payments.download(eq(payment), any())).thenThrow(new ApiException("RECEIPT_NOT_FOUND", HttpStatus.NOT_FOUND, "Fixture", null));
        assertEquals(404, assertThrows(ApiException.class, () -> service.download(id, auth("BUYER"))).getHttpStatus().value());
    }

    /** Khách/Seller/JWT sai subject bị chặn trước DB; mã refund không tồn tại không gọi Payment. */
    @Test
    void requiresUserAndHandlesMissingRefund() {
        assertEquals(401, assertThrows(ApiException.class, () -> service.download(id, null)).getHttpStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(id, auth("SELLER"))).getHttpStatus().value());
        var malformed = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none").subject("bad").build());
        assertEquals(401, assertThrows(ApiException.class, () -> service.download(id, malformed)).getHttpStatus().value());
        verifyNoInteractions(jdbc, payments);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<RefundReceiptService.Evidence>>any(), eq(id))).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiException.class, () -> service.download(id, auth("ADMIN"))).getHttpStatus().value());
        verifyNoInteractions(payments);
    }

    private void stub(RefundReceiptService.Evidence evidence) {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<RefundReceiptService.Evidence>>any(), eq(id))).thenReturn(List.of(evidence));
        when(payments.download(eq(payment), any())).thenReturn(new PaymentReceipt(1, "INTERNAL_PAYMENT_RECEIPT", "PAY-" + payment,
                payment, order, recorded, "VND", "VNPAY", new PaymentPriceSnapshot(new BigDecimal("100"), BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("100"), List.of())));
    }

    private RefundReceiptService.Evidence row(String status, String source, boolean valid, boolean disputed, BigDecimal amount, Instant time) {
        return new RefundReceiptService.Evidence(payment, order, amount, "VND", status, source, time, valid, disputed);
    }

    private void notReady() {
        assertEquals(409, assertThrows(ApiException.class, () -> service.download(id, auth("BUYER"))).getHttpStatus().value());
    }

    private JwtAuthenticationToken auth(String role) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none").subject(UUID.randomUUID().toString()).build(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
