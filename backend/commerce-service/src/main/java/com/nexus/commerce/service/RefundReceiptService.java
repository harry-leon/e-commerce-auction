package com.nexus.commerce.service;

import com.nexus.commerce.dto.response.RefundReceipt;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xuất biên nhận từ registry refund đã chốt, tái dùng quyền và snapshot biên nhận thu gốc.
 * Chỉ đọc Commerce; cùng snapshot transaction để không ghép trạng thái trước/sau đối soát đồng thời.
 * Không gửi refund, gỡ hold, phát chứng từ thuế hoặc lưu một registry phát hành mới.
 */
@Service
@RequiredArgsConstructor
public class RefundReceiptService {
    private final JdbcTemplate jdbc;
    private final PaymentReceiptService payments;

    /**
     * Buyer chủ đơn/Admin tải refund SUCCEEDED có registry và nguồn bằng chứng khớp.
     * Mã REFUND-{refundId} ổn định; dữ liệu thiếu/mâu thuẫn trả 409, không dùng status SUCCEEDED đơn lẻ.
     */
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RefundReceipt download(UUID id, Authentication authentication) {
        requireUser(authentication);
        // Chỉ tính boolean bằng chứng tại DB, không mang providerRef/merchant/note nhạy cảm ra DTO hoặc log.
        var rows = jdbc.query("""
                SELECT r.payment_id,r.order_id,r.amount,r.currency,r.status,c.source,c.recorded_at,
                  (c.provider=r.provider AND c.merchant_code=r.merchant_code AND c.amount=r.amount
                    AND c.currency=r.currency AND length(trim(c.provider_ref))>0 AND (
                    (c.source='PROVIDER' AND EXISTS (SELECT 1 FROM refund_attempts a
                      WHERE a.id=c.source_id AND a.refund_id=r.id AND a.status='SUCCEEDED' AND a.provider_ref=c.provider_ref))
                    OR (c.source='MANUAL' AND EXISTS (SELECT 1 FROM refund_manual_resolutions m
                      WHERE m.id=c.source_id AND m.refund_id=r.id AND m.decision='SUCCEEDED'
                        AND m.provider_ref=c.provider_ref AND m.amount=c.amount AND m.currency=c.currency)))) AS evidence_valid,
                  (EXISTS (SELECT 1 FROM refund_payment_holds h WHERE h.payment_id=r.payment_id)
                    OR EXISTS (SELECT 1 FROM refund_result_conflicts x WHERE x.refund_id=r.id)) AS disputed
                FROM refund_requests r LEFT JOIN refund_provider_receipts c ON c.refund_id=r.id WHERE r.id=?
                """, (rs, index) -> new Evidence(rs.getObject("payment_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"), rs.getString("source"),
                rs.getTimestamp("recorded_at") == null ? null : rs.getTimestamp("recorded_at").toInstant(),
                rs.getBoolean("evidence_valid"), rs.getBoolean("disputed")), id);
        if (rows.isEmpty()) { throw notFound(); }
        var row = rows.getFirst();
        // Kiểm tra ownership trước trả lỗi state để Buyer khác không dò được tình trạng refund.
        var original = payments.download(row.paymentId(), authentication);
        if (!"SUCCEEDED".equals(row.status()) || !row.valid() || row.disputed() || row.recordedAt() == null
                || !original.orderId().equals(row.orderId()) || !"VND".equals(row.currency())
                || row.amount() == null || row.amount().signum() <= 0 || row.amount().stripTrailingZeros().scale() > 0
                || row.amount().compareTo(original.prices().total()) > 0) {
            throw new ApiException("REFUND_RECEIPT_NOT_READY", HttpStatus.CONFLICT,
                    "Refund chưa đủ bằng chứng nhất quán để xuất biên nhận", null);
        }
        return new RefundReceipt(1, "INTERNAL_REFUND_RECEIPT", "REFUND-" + id, id, original.receiptId(),
                row.paymentId(), row.orderId(), row.amount(), row.currency(), row.recordedAt(), row.source());
    }

    /** Chặn subject/role không phù hợp trước đọc DB; không nhận actor hoặc cờ quyền từ request. */
    private void requireUser(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_BUYER".equals(a.getAuthority()))) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Chỉ Buyer sở hữu đơn hoặc Admin được tải biên nhận", null);
        }
    }

    private ApiException notFound() {
        return new ApiException("RECEIPT_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy biên nhận thuộc quyền truy cập", null);
    }

    /** Projection nội bộ, không serialize; valid/disputed do registry và lịch sử đối soát cùng transaction xác định. */
    record Evidence(UUID paymentId, UUID orderId, BigDecimal amount, String currency, String status,
                    String source, Instant recordedAt, boolean valid, boolean disputed) { }
}
