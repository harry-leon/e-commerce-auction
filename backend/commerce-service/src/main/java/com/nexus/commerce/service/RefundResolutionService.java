package com.nexus.commerce.service;

import com.nexus.commerce.enums.RefundStatus;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin đối soát UNKNOWN/REVIEW bằng bằng chứng ngoài hệ thống; chỉ ghi quyết định, không gọi provider hoặc hoàn kho.
 * Khóa key → Order → attempt → refund, tương thích thứ tự attempt/refund của worker và khóa Order của tạo yêu cầu mới.
 * Không khóa Payment trước attempt để tránh deadlock với worker ghi receipt/hold. Audit và trạng thái commit cùng nhau.
 */
@Service
@RequiredArgsConstructor
public class RefundResolutionService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;

    /**
     * Kiểm tra snapshot tiền/ref, lý do và mã bằng chứng; chỉ Admin đã đối chiếu kết quả cuối cùng được gọi.
     * Cùng actor/key/body trả audit cũ; đổi nội dung hoặc sửa terminal status trả 409.
     * FAILED giải phóng hạn mức của yêu cầu này, nhưng không gỡ payment hold do mâu thuẫn; không cập nhật Order.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Result resolve(UUID refundId, UUID actor, UUID key, Command command) {
        validate(refundId, actor, key, command);
        String note = command.note().trim();
        String evidence = command.evidenceReference().trim();
        locks.acquire("refund-resolution:" + actor + ":" + key);
        var previous = jdbc.queryForList("SELECT * FROM refund_manual_resolutions WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!refundId.equals(row.get("refund_id")) || !command.decision().name().equals(row.get("decision"))
                    || command.amount().compareTo((BigDecimal) row.get("amount")) != 0
                    || !command.currency().equals(row.get("currency")) || !command.providerPaymentReference().equals(row.get("provider_payment_ref"))
                    || !Objects.equals(command.providerRefundReference(), row.get("provider_ref"))
                    || !note.equals(row.get("note")) || !evidence.equals(row.get("evidence_reference"))) { throw conflict(); }
            return new Result(new Decision((UUID) row.get("id"), refundId, command.decision(),
                    ((java.sql.Timestamp) row.get("created_at")).toInstant()), false);
        }
        var references = jdbc.queryForList("SELECT order_id,payment_id FROM refund_requests WHERE id=?", refundId);
        if (references.isEmpty()) { throw error("REFUND_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy refund"); }
        UUID orderId = (UUID) references.getFirst().get("order_id");
        UUID paymentId = (UUID) references.getFirst().get("payment_id");
        // Cùng khóa Order với tạo refund: giải phóng/ghi nhận hạn mức không chạy đồng thời với cấp hạn mức mới.
        jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", orderId);
        jdbc.queryForList("SELECT id FROM refund_attempts WHERE refund_id=? FOR UPDATE", refundId);
        var refund = jdbc.queryForList("SELECT * FROM refund_requests WHERE id=? FOR UPDATE", refundId).getFirst();
        String state = refund.get("status").toString();
        if (!("UNKNOWN".equals(state) || "REQUIRES_REVIEW".equals(state))) { throw conflict(); }
        if (command.amount().compareTo((BigDecimal) refund.get("amount")) != 0
                || !command.currency().equals(refund.get("currency"))
                || !command.providerPaymentReference().equals(refund.get("provider_payment_ref"))) { throw conflict(); }
        UUID resolutionId = UUID.randomUUID();
        if (command.decision() == RefundStatus.SUCCEEDED) {
            BigDecimal captured = jdbc.queryForObject("SELECT amount FROM payments WHERE id=?", BigDecimal.class, paymentId);
            BigDecimal allocated = jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM refund_requests WHERE payment_id=? "
                    + "AND id<>? AND status IN ('REQUESTED','PROCESSING','UNKNOWN','REQUIRES_REVIEW','SUCCEEDED')",
                    BigDecimal.class, paymentId, refundId);
            if (allocated.add(command.amount()).compareTo(captured) > 0) { throw conflict(); }
            // Cùng namespace khóa reference với worker; registry unique cũng bảo vệ caller khác/instance khác.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "vnpay-refund:" + command.providerRefundReference());
            jdbc.update("INSERT INTO refund_provider_receipts(refund_id,provider,merchant_code,provider_ref,amount,currency,source,source_id) "
                    + "VALUES (?,?,?,?,?,?,'MANUAL',?) ON CONFLICT DO NOTHING", refundId, refund.get("provider"),
                    refund.get("merchant_code"), command.providerRefundReference(), command.amount(), command.currency(), resolutionId);
            Integer receipt = jdbc.queryForObject("SELECT count(*) FROM refund_provider_receipts WHERE refund_id=? "
                    + "AND provider_ref=?", Integer.class, refundId, command.providerRefundReference());
            if (receipt == null || receipt != 1) { throw conflict(); }
        } else if (jdbc.queryForObject("SELECT count(*) FROM refund_provider_receipts WHERE refund_id=?", Integer.class, refundId) > 0) {
            throw conflict();
        }
        Instant decided = jdbc.queryForObject("INSERT INTO refund_manual_resolutions(id,refund_id,actor_id,idempotency_key,previous_status,"
                + "decision,amount,currency,provider_payment_ref,provider_ref,evidence_reference,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                + "RETURNING created_at", (rs, index) -> rs.getTimestamp(1).toInstant(), resolutionId, refundId, actor, key, state,
                command.decision().name(), command.amount(), command.currency(), command.providerPaymentReference(),
                command.providerRefundReference(), evidence, note);
        jdbc.update("UPDATE refund_attempts SET status=?,provider_ref=COALESCE(?,provider_ref),error_type=?,"
                + "completed_at=CURRENT_TIMESTAMP WHERE refund_id=?",
                command.decision() == RefundStatus.SUCCEEDED ? "SUCCEEDED" : "REQUIRES_REVIEW",
                command.providerRefundReference(), "MANUAL_" + command.decision().name(), refundId);
        jdbc.update("UPDATE refund_requests SET status=?,review_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                command.decision().name(), "MANUAL_" + command.decision().name(), refundId);
        return new Result(new Decision(resolutionId, refundId, command.decision(), decided), true);
    }

    /** Không chấp nhận PROCESSING, tiền lẻ/âm, dữ liệu control hoặc thiếu bằng chứng; không log nội dung đầu vào. */
    private void validate(UUID refund, UUID actor, UUID key, Command command) {
        if (refund == null || actor == null || key == null || command == null
                || !(command.decision() == RefundStatus.SUCCEEDED || command.decision() == RefundStatus.FAILED)
                || command.amount() == null || command.amount().signum() <= 0 || command.amount().stripTrailingZeros().scale() > 0
                || !"VND".equals(command.currency()) || !text(command.providerPaymentReference(), 100)
                || !text(command.evidenceReference(), 200) || !text(command.note(), 500)
                || (command.decision() == RefundStatus.SUCCEEDED && (command.providerRefundReference() == null
                    || !command.providerRefundReference().matches("[0-9]{1,15}") || command.providerRefundReference().matches("0+")))
                || (command.decision() == RefundStatus.FAILED && command.providerRefundReference() != null)) {
            throw error("REFUND_RESOLUTION_INVALID", HttpStatus.BAD_REQUEST, "Quyết định đối soát hoặc bằng chứng không hợp lệ");
        }
    }

    private boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl);
    }

    private ApiException conflict() {
        return error("REFUND_RESOLUTION_CONFLICT", HttpStatus.CONFLICT, "Quyết định mâu thuẫn trạng thái, hạn mức hoặc bằng chứng đã lưu");
    }

    private ApiException error(String code, HttpStatus status, String message) { return new ApiException(code, status, message, null); }

    /** Đầu vào đối soát thủ công; reference là mã bằng chứng bên ngoài, không tải/parse URL hoặc xác minh hộ operator. */
    public record Command(RefundStatus decision, BigDecimal amount, String currency, String providerPaymentReference,
            String providerRefundReference, String evidenceReference, String note) { }
    /** Snapshot phản hồi bền vững của quyết định, không lộ note/bằng chứng tài chính trong response. */
    public record Decision(UUID resolutionId, UUID refundId, RefundStatus status, Instant decidedAt) { }
    public record Result(Decision response, boolean created) { }
}
