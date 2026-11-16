package com.nexus.commerce.service;

import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin chốt Order REFUNDED từ biên nhận refund đã xác minh, chỉ trước claim/vận đơn và hoàn toàn phần.
 * Cổng mặc định tắt để review policy trước rollout. Không gọi provider/kho, không sửa Payment hoặc biên nhận thu gốc.
 */
@Service
public class RefundedOrderFinalizationService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;
    private final RefundReceiptService receipts;
    private final boolean enabled;

    public RefundedOrderFinalizationService(JdbcTemplate jdbc, CheckoutLock locks, RefundReceiptService receipts,
            @Value("${app.payment.refund-finalization-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.locks = locks; this.receipts = receipts; this.enabled = enabled;
    }

    /** Key/body replay trả audit cũ; mới thì khóa Order/attempt/refund/payment, xác minh receipt rồi ghi ba thay đổi nguyên tử. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Result finalizeRefund(UUID refund, UUID key, String note, Authentication authentication) {
        UUID actor = actor(authentication);
        if (refund == null || key == null || note == null || note.isBlank() || note.trim().length() > 500
                || note.chars().anyMatch(Character::isISOControl)) {
            throw error("REFUND_FINALIZATION_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (!enabled) { throw error("REFUND_FINALIZATION_DISABLED", HttpStatus.SERVICE_UNAVAILABLE); }
        String normalized = note.trim();
        locks.acquire("refund-finalization:" + actor + ":" + key);
        var replay = jdbc.queryForList("SELECT * FROM refunded_order_finalizations WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!replay.isEmpty()) {
            var row = replay.getFirst();
            if (!refund.equals(row.get("refund_id")) || !normalized.equals(row.get("note"))) { throw conflict(); }
            return new Result(decision(row), false);
        }
        var ids = jdbc.queryForList("SELECT order_id FROM refund_requests WHERE id=?", UUID.class, refund);
        if (ids.isEmpty()) { throw error("REFUND_NOT_FOUND", HttpStatus.NOT_FOUND); }
        UUID order = ids.getFirst();
        var orders = jdbc.queryForList("SELECT * FROM orders WHERE id=? FOR UPDATE", order);
        // Cùng thứ tự Order → attempt → refund với manual resolution; worker finalize cũng khóa attempt trước refund.
        // Giữ khóa đến commit để kết quả muộn/hold không chen vào giữa kiểm tra receipt và ghi Order.
        jdbc.queryForList("SELECT a.id FROM refund_attempts a JOIN refund_requests r ON r.id=a.refund_id "
                + "WHERE r.order_id=? ORDER BY a.id FOR UPDATE OF a", order);
        jdbc.queryForList("SELECT id FROM refund_requests WHERE order_id=? ORDER BY id FOR UPDATE", order);
        jdbc.queryForList("SELECT id FROM payments WHERE order_id=? ORDER BY id FOR UPDATE", order);
        var row = orders.getFirst(); String state = (String) row.get("status");
        if (!("PAID".equals(state) || "PROCESSING".equals(state)) || !Boolean.TRUE.equals(row.get("price_finalized"))
                || !"VNPAY".equals(row.get("payment_method")) || "REVIEW".equals(row.get("fulfillment_status"))) { throw conflict(); }
        Boolean eligible = jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM shipping_dispatch_claims WHERE order_id=?)
                  AND NOT EXISTS (SELECT 1 FROM shipping_order_bindings WHERE order_id=?)
                  AND NOT EXISTS (SELECT 1 FROM refund_requests WHERE order_id=? AND id<>? AND status<>'FAILED')
                  AND NOT EXISTS (SELECT 1 FROM payments WHERE order_id=? AND (status='REVIEW' OR inventory_status='REVIEW'))
                  AND (SELECT count(*) FROM payments WHERE order_id=? AND status='SUCCESS')=1
                  AND NOT EXISTS (SELECT 1 FROM payments WHERE order_id=? AND status='SUCCESS' AND inventory_status IS DISTINCT FROM 'CONFIRMED')
                  AND EXISTS (SELECT 1 FROM refund_requests r JOIN payments p ON p.id=r.payment_id
                    WHERE r.id=? AND r.order_id=? AND p.order_id=r.order_id AND r.provider='VNPAY'
                      AND r.merchant_code=p.merchant_code AND p.merchant_code<>'DEMO_ONLY' AND length(trim(p.merchant_code))>0
                      AND r.provider_payment_ref=p.provider_ref AND p.provider_ref ~ '^[0-9]{1,15}$' AND p.provider_ref !~ '^0+$')
                """, Boolean.class, order, order, order, refund, order, order, order, refund, order);
        if (!Boolean.TRUE.equals(eligible)) { throw conflict(); }
        // Không tin SUCCEEDED đơn lẻ: dùng lại validation snapshot/registry/nguồn audit/hold của refund receipt.
        var receipt = receipts.download(refund, authentication);
        BigDecimal total = (BigDecimal) row.get("total");
        BigDecimal captured = jdbc.queryForObject("SELECT amount FROM payments WHERE id=?", BigDecimal.class, receipt.paymentId());
        if (!order.equals(receipt.orderId()) || receipt.amount().compareTo(total) != 0 || receipt.amount().compareTo(captured) != 0) { throw conflict(); }
        UUID audit = UUID.randomUUID();
        var saved = jdbc.queryForMap("INSERT INTO refunded_order_finalizations(id,order_id,refund_id,actor_id,idempotency_key,previous_status,note) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING *", audit, order, refund, actor, key, state, normalized);
        jdbc.update("UPDATE orders SET status='REFUNDED' WHERE id=?", order);
        jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason) VALUES (?,?,?,'REFUNDED',?)",
                UUID.randomUUID(), order, state, "[ADMIN] Verified full refund; audit=" + audit);
        // Handoff cũ có thể còn WAITING_PARCEL nhưng claim/preflight sẽ từ chối Order REFUNDED; không xóa dấu dispatch.
        return new Result(decision(saved), true);
    }

    /** Actor chỉ từ JWT UUID và role Admin, không nhận actorId/amount/status từ body, kể cả caller gọi thẳng service. */
    private UUID actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw error("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw error("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw error("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED); }
    }

    /** Response chỉ có định danh/audit/thời điểm, không trả note hoặc thông tin tài chính nhạy cảm. */
    private Decision decision(java.util.Map<String, Object> row) {
        return new Decision((UUID) row.get("id"), (UUID) row.get("order_id"), (UUID) row.get("refund_id"),
                "REFUNDED", ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }
    private ApiException conflict() { return error("REFUND_FINALIZATION_CONFLICT", HttpStatus.CONFLICT); }
    private ApiException error(String code, HttpStatus status) {
        return new ApiException(code, status, "Chốt đơn hoàn tiền cần đúng quyền, trạng thái và bằng chứng đầy đủ", null);
    }
    /** Snapshot audit ổn định qua replay; không xác nhận đã hoàn kho hoặc carrier cancellation. */
    public record Decision(UUID auditId, UUID orderId, UUID refundId, String status, Instant createdAt) { }
    /** created phân biệt 201 mới và 200 replay, không gửi tiền trong cả hai trường hợp. */
    public record Result(Decision decision, boolean created) { }
}
