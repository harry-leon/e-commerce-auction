package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.dto.response.RefundReceipt;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Bảo vệ việc phát hành chứng từ điều chỉnh: không xuất sớm hơn kết quả tài chính, không sửa biên nhận gốc,
 * không tạo bản ghi thứ hai cho cùng refund và chỉ Admin được phát hành.
 */
@ExtendWith(MockitoExtension.class)
class RefundCreditNoteServiceTest {
    @Mock private JdbcTemplate jdbc;
    @Mock private CheckoutLock locks;
    @Mock private RefundReceiptService refundReceipts;
    private RefundCreditNoteService service;

    private final UUID refund = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final Instant recorded = Instant.parse("2026-11-13T00:00:00Z");

    @BeforeEach
    void setup() { service = new RefundCreditNoteService(jdbc, locks, refundReceipts); }

    /** Lần đầu tạo mới, số chứng từ ổn định theo refundId và liên kết đúng hai biên nhận. */
    @Test
    void issuesStableCreditNoteLinkedToBothReceipts() {
        stubReceipt("PROVIDER");
        when(jdbc.queryForList(anyString(), eq(actor), eq(key))).thenReturn(List.of());
        when(jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refund)).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // ID do service sinh nên stub theo chính tham số mà service truyền, không đoán trước UUID.
        when(jdbc.queryForList(eq("SELECT * FROM refund_credit_notes WHERE id=?"), any(UUID.class)))
                .thenAnswer(call -> List.of(row("PROVIDER", (UUID) call.getArgument(1))));

        var result = service.issue(refund, actor, key, "Đối soát CASE-1", auth("ADMIN"));

        assertTrue(result.created());
        assertEquals("CN-" + refund, result.note().documentNo());
        assertEquals("PAY-" + payment, result.note().originalReceiptId());
        assertEquals("REFUND-" + refund, result.note().refundReceiptId());
        assertEquals("INTERNAL_REFUND_CREDIT_NOTE", result.note().documentType());
        assertEquals("PROVIDER", result.note().evidenceSource());
    }

    /** Replay cùng key/nội dung trả bản ghi cũ, không tạo thêm; đổi nội dung cùng key trả 409. */
    @Test
    void replaysSameKeyAndRejectsChangedContent() {
        var stored = row("MANUAL");
        when(jdbc.queryForList(anyString(), eq(actor), eq(key))).thenReturn(List.of(stored));

        var replay = service.issue(refund, actor, key, "Đối soát CASE-1", auth("ADMIN"));
        assertFalse(replay.created());

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.issue(refund, actor, key, "Nội dung khác", auth("ADMIN"))).getHttpStatus().value());
    }

    /** Refund chưa đủ bằng chứng bị RefundReceiptService từ chối; credit note không được phát hành. */
    @Test
    void refusesWhenRefundReceiptIsNotReady() {
        when(jdbc.queryForList(anyString(), eq(actor), eq(key))).thenReturn(List.of());
        when(refundReceipts.download(eq(refund), any())).thenThrow(
                new ApiException("REFUND_RECEIPT_NOT_READY", HttpStatus.CONFLICT, "Fixture", null));

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.issue(refund, actor, key, "Lý do", auth("ADMIN"))).getHttpStatus().value());
        // Không được chạm bảng credit note sau khi biên nhận từ chối.
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    /** Refund không tồn tại giữ 404 thay vì biến thành 409, để Admin phân biệt sai mã với chưa đủ điều kiện. */
    @Test
    void propagatesNotFoundForUnknownRefund() {
        when(jdbc.queryForList(anyString(), eq(actor), eq(key))).thenReturn(List.of());
        when(refundReceipts.download(eq(refund), any())).thenThrow(
                new ApiException("RECEIPT_NOT_FOUND", HttpStatus.NOT_FOUND, "Fixture", null));

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.issue(refund, actor, key, "Lý do", auth("ADMIN"))).getHttpStatus().value());
    }

    /** Đã có credit note cho refund nhưng key mới vẫn trả 409; không sinh số chứng từ thứ hai. */
    @Test
    void refusesSecondCreditNoteForSameRefund() {
        stubReceipt("PROVIDER");
        when(jdbc.queryForList(anyString(), eq(actor), eq(key))).thenReturn(List.of());
        when(jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refund))
                .thenReturn(List.of(row("PROVIDER")));

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.issue(refund, actor, key, "Lý do", auth("ADMIN"))).getHttpStatus().value());
    }

    /** Đầu vào thiếu/sai bị chặn trước khóa và trước khi gọi dịch vụ biên nhận. */
    @Test
    void validatesInputBeforeTouchingDatabase() {
        for (String note : new String[]{null, " ", "x".repeat(501), "ký\tự điều khiển"}) {
            assertEquals(400, assertThrows(ApiException.class,
                    () -> service.issue(refund, actor, key, note, auth("ADMIN"))).getHttpStatus().value());
        }
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.issue(null, actor, key, "Lý do", auth("ADMIN"))).getHttpStatus().value());
        verifyNoInteractions(locks, refundReceipts);
    }

    /** Tra cứu luôn đi qua kiểm tra quyền biên nhận trước khi đọc chứng từ; chưa phát hành trả 404. */
    @Test
    void getChecksOwnershipThroughReceiptService() {
        stubReceipt("PROVIDER");
        when(jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refund)).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(refund, auth("BUYER"))).getHttpStatus().value());

        when(jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refund))
                .thenReturn(List.of(row("PROVIDER")));
        assertEquals("CN-" + refund, service.get(refund, auth("ADMIN")).documentNo());
    }

    private void stubReceipt(String source) {
        when(refundReceipts.download(eq(refund), any())).thenReturn(new RefundReceipt(1, "INTERNAL_REFUND_RECEIPT",
                "REFUND-" + refund, refund, "PAY-" + payment, payment, order, new BigDecimal("50000"), "VND",
                recorded, source));
    }

    private Map<String, Object> row(String source) {
        return row(source, UUID.randomUUID());
    }

    private Map<String, Object> row(String source, UUID id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("refund_id", refund);
        row.put("payment_id", payment);
        row.put("order_id", order);
        row.put("document_no", "CN-" + refund);
        row.put("original_receipt_id", "PAY-" + payment);
        row.put("refund_receipt_id", "REFUND-" + refund);
        row.put("amount", new BigDecimal("50000"));
        row.put("currency", "VND");
        row.put("evidence_source", source);
        row.put("issued_at", java.sql.Timestamp.from(recorded));
        row.put("note", "Đối soát CASE-1");
        return row;
    }

    private JwtAuthenticationToken auth(String role) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .subject(actor.toString()).build(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
