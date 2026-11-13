package com.nexus.commerce.service;

import com.nexus.commerce.dto.response.RefundResponse;
import com.nexus.commerce.enums.RefundStatus;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiếp nhận refund toàn phần do Admin yêu cầu và giữ hạn mức trong PostgreSQL, không gọi provider.
 * Khóa idempotency rồi Order/Payment; kiểm tra tiền đã thu và tổng refund trước INSERT cùng transaction.
 * REQUESTED chưa được dispatch: không đổi Payment/Order, không release kho hoặc phát thông báo thành công.
 */
@Service
@RequiredArgsConstructor
public class RefundRequestService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;

    /**
     * Admin gửi paymentId, key UUID và reason; tiền lấy từ DB, chỉ nhận VNPAY có kết quả thu tiền đã xác minh.
     * Replay cùng key/nội dung trả bản ghi cũ; nội dung khác trả 409. Chưa hỗ trợ COD/partial refund.
     * Khóa DB tự nhả khi commit/rollback; lỗi không giữ hạn mức hoặc tạo yêu cầu nửa chừng.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Result create(UUID actor, UUID paymentId, UUID key, String reason) {
        if (actor == null || paymentId == null || key == null || reason == null
                || reason.isBlank() || reason.trim().length() > 500) {
            throw error("REFUND_REQUEST_INVALID", HttpStatus.BAD_REQUEST, "Yêu cầu hoàn tiền không hợp lệ");
        }
        String normalized = reason.trim();
        // Khóa key chung nhiều instance trước đọc replay; key không gắn với payment để phát hiện dùng lại sai đơn.
        locks.acquire("refund-key:" + actor + ":" + key);
        var existing = jdbc.query("SELECT * FROM refund_requests WHERE requested_by=? AND idempotency_key=?",
                (rs, index) -> new Replay(map(rs), rs.getString("reason")), actor, key);
        if (!existing.isEmpty()) {
            var replay = existing.getFirst();
            if (!paymentId.equals(replay.response().paymentId()) || !normalized.equals(replay.reason())) {
                throw error("REFUND_KEY_CONFLICT", HttpStatus.CONFLICT, "Key đã gắn với yêu cầu khác");
            }
            return new Result(replay.response(), false);
        }
        // Chỉ đọc UUID trước khóa để không giữ snapshot tiền cũ trong persistence context.
        var orderIds = jdbc.queryForList("SELECT order_id FROM payments WHERE id=?", UUID.class, paymentId);
        if (orderIds.isEmpty()) { throw notFound(); }
        UUID orderId = orderIds.getFirst();
        jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", orderId);
        var payments = jdbc.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE", paymentId);
        if (payments.isEmpty()) { throw notFound(); }
        var payment = payments.getFirst();
        String reference = (String) payment.get("provider_ref");
        String merchant = (String) payment.get("merchant_code");
        // Không cho dữ liệu DEMO_ONLY hoặc SUCCESS chưa có reference đã xác minh đi vào workflow tài chính thật.
        if (!"VNPAY".equals(payment.get("provider")) || !"SUCCESS".equals(payment.get("status"))
                || reference == null || !reference.matches("[0-9]{1,100}") || reference.matches("0+")
                || merchant == null || merchant.isBlank() || "DEMO_ONLY".equals(merchant)) {
            throw error("REFUND_PAYMENT_NOT_SETTLED", HttpStatus.CONFLICT, "Payment chưa đủ bằng chứng thu tiền");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND "
                + "(status='REVIEW' OR inventory_status='REVIEW')", Integer.class, orderId) > 0) {
            throw error("REFUND_REQUIRES_REVIEW", HttpStatus.CONFLICT, "Đơn đang cần đối soát thủ công");
        }
        BigDecimal amount = (BigDecimal) payment.get("amount");
        BigDecimal succeeded = total(paymentId, "status='SUCCEEDED'");
        BigDecimal reserved = total(paymentId, "status IN ('REQUESTED','PROCESSING','UNKNOWN','REQUIRES_REVIEW')");
        try { RefundPolicy.validateAmount(amount, succeeded, reserved, amount); }
        catch (IllegalArgumentException exception) {
            throw error("REFUND_AMOUNT_EXCEEDED", HttpStatus.CONFLICT, "Payment đã có yêu cầu hoàn tiền hoặc hết hạn mức");
        }
        UUID id = UUID.randomUUID();
        Instant created = jdbc.queryForObject("INSERT INTO refund_requests(id,payment_id,order_id,requested_by,"
                + "idempotency_key,reason,amount,status,provider,merchant_code,provider_payment_ref) "
                + "VALUES (?,?,?,?,?,?,?,'REQUESTED','VNPAY',?,?) RETURNING created_at",
                (rs, index) -> rs.getTimestamp(1).toInstant(), id, paymentId, orderId, actor, key, normalized,
                amount, merchant, reference);
        return new Result(new RefundResponse(id, paymentId, orderId, amount, "VND", RefundStatus.REQUESTED, created), true);
    }

    /** Buyer chỉ xem yêu cầu của đơn mình; Admin xem được, đối tượng ngoài phạm vi trả 404 để tránh lộ định danh. */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public RefundResponse get(UUID id, UUID actor, boolean admin) {
        var rows = jdbc.query("SELECT r.* FROM refund_requests r JOIN orders o ON o.id=r.order_id "
                + "WHERE r.id=? AND (? OR o.buyer_id=?)", (rs, index) -> map(rs), id, admin, actor);
        if (rows.isEmpty()) { throw notFound(); }
        return rows.getFirst();
    }

    /** Chỉ nội bộ truyền predicate cố định; không nhận SQL hoặc tổng hạn mức từ request. */
    private BigDecimal total(UUID payment, String predicate) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM refund_requests WHERE payment_id=? AND "
                + predicate, BigDecimal.class, payment);
    }

    /** Chỉ ánh xạ trường công khai, không đưa reason/provider snapshot nhạy cảm vào response. */
    private static RefundResponse map(ResultSet rs) throws SQLException {
        return new RefundResponse(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
                RefundStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant());
    }

    private static ApiException error(String code, HttpStatus status, String message) {
        return new ApiException(code, status, message, null);
    }

    private static ApiException notFound() {
        return error("REFUND_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hoặc payment trong phạm vi truy cập");
    }

    /** Cờ created quyết định 201/200; không phải kết quả hoàn tiền provider. */
    public record Result(RefundResponse response, boolean created) { }
    private record Replay(RefundResponse response, String reason) { }
}
