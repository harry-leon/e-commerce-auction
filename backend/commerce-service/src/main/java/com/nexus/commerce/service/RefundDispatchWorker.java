package com.nexus.commerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.VnpayMerchantClient;
import com.nexus.commerce.client.VnpayMerchantProtocol;
import com.nexus.commerce.config.VnpayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatch refund đã duyệt: claim/attempt commit trước HTTP, finalize trong transaction riêng sau HTTP.
 * Mỗi refund chỉ có một attempt; lease hết hạn chuyển UNKNOWN, không retry mutation sau crash/mất ACK.
 * QueryDr chỉ lưu bằng chứng PAY; tối đa ba lần rồi REVIEW. Không sửa Order/Payment/kho hoặc phát success event.
 */
@Component
public class RefundDispatchWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RefundDispatchWorker.class);
    private final JdbcTemplate jdbc;
    private final VnpayMerchantClient client;
    private final VnpayProperties config;
    private final TransactionTemplate tx;

    public RefundDispatchWorker(JdbcTemplate jdbc, VnpayMerchantClient client, VnpayProperties config,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.client = client;
        this.config = config;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Cleanup không cần bật mạng; mỗi tick tối đa 10 yêu cầu mới và 10 query, không retry HTTP trong cùng attempt. */
    @Scheduled(fixedDelayString = "${app.payment.vnpay.refund-poll-ms:5000}", scheduler = "refundTaskScheduler")
    public void run() {
        requireOutsideTransaction();
        recoverExpired();
        if (!enabled()) { return; }
        for (UUID id : jdbc.queryForList("SELECT id FROM refund_requests WHERE status='REQUESTED' "
                + "AND dispatch_approved_at IS NOT NULL ORDER BY created_at,id LIMIT 10", UUID.class)) {
            try { dispatch(id); }
            catch (RuntimeException error) {
                // Chỉ log ID/loại lỗi; lease vẫn giữ kết quả chưa rõ để recovery, không đưa body/credential vào log.
                LOG.warn("Refund dispatch cần recovery: refund={}, errorType={}", id, error.getClass().getSimpleName());
            }
        }
        for (UUID id : jdbc.queryForList("SELECT id FROM refund_requests WHERE status='UNKNOWN' AND reconcile_count<3 "
                + "AND reconcile_after<=CURRENT_TIMESTAMP ORDER BY reconcile_after,id LIMIT 10", UUID.class)) {
            try { reconcile(id); }
            catch (RuntimeException error) {
                LOG.warn("Refund query cần recovery: refund={}, errorType={}", id, error.getClass().getSimpleName());
            }
        }
    }

    /**
     * Gửi một refund đã duyệt; trả false nếu tắt/không claim được. DB lỗi trước commit không được gửi HTTP.
     * Sau commit PROCESSING, mọi lỗi gửi hoặc finalize đều để UNKNOWN/lease recovery giữ hạn mức.
     */
    public boolean dispatch(UUID id) {
        requireOutsideTransaction();
        if (!enabled()) { return false; }
        Claim claim = tx.execute(status -> claim(id));
        if (claim == null) { return false; }
        VnpayMerchantClient.Outcome outcome;
        try { outcome = client.exchange(claim.payload(), claim.amount()); }
        catch (RuntimeException error) {
            tx.executeWithoutResult(status -> unknown(claim.attemptId(), id, "TRANSPORT_UNCERTAIN"));
            return true;
        }
        tx.executeWithoutResult(status -> finish(claim, outcome));
        return true;
    }

    /** Hai worker SKIP LOCKED không cùng claim; snapshot lưu không có hash/secret và unique refund_id chặn gửi lại. */
    private Claim claim(UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM refund_requests WHERE id=? AND status='REQUESTED' "
                + "AND dispatch_approved_at IS NOT NULL FOR UPDATE SKIP LOCKED", id);
        if (rows.isEmpty()) { return null; }
        var refund = rows.getFirst();
        if (jdbc.queryForObject("SELECT count(*) FROM refund_payment_holds WHERE payment_id=?", Integer.class,
                refund.get("payment_id")) > 0) {
            review(id, "PAYMENT_RECONCILIATION_HOLD");
            return null;
        }
        var payment = jdbc.queryForList("SELECT * FROM payments WHERE id=?", refund.get("payment_id")).getFirst();
        Map<String, String> payload;
        UUID attempt = UUID.randomUUID();
        String requestId = attempt.toString().replace("-", "");
        BigDecimal amount = (BigDecimal) refund.get("amount");
        try {
            if (!"SUCCESS".equals(payment.get("status")) || !config.getTmnCode().equals(refund.get("merchant_code"))
                    || !refund.get("provider_payment_ref").equals(payment.get("provider_ref"))
                    || amount.compareTo((BigDecimal) payment.get("amount")) != 0) {
                throw new IllegalArgumentException();
            }
            // created_at PAY là timestamp UTC không timezone; không diễn giải theo timezone JVM/Windows.
            Instant original = jdbc.queryForObject("SELECT created_at FROM payments WHERE id=?",
                    (rs, index) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), refund.get("payment_id"));
            payload = protocol().refund(requestId, refund.get("payment_id").toString(), amount,
                    refund.get("provider_payment_ref").toString(), original, Instant.now(),
                    refund.get("dispatch_approved_by").toString(), config.getMerchantSourceIp(), "Refund " + id);
            var stored = new HashMap<>(payload);
            stored.remove("vnp_SecureHash");
            String json = new ObjectMapper().writeValueAsString(stored);
            jdbc.update("INSERT INTO refund_attempts(id,refund_id,request_id,request_payload,status,lease_expires_at) "
                    + "VALUES (?,?,?,?::jsonb,'PROCESSING',CURRENT_TIMESTAMP+interval '120 seconds')", attempt, id, requestId, json);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException error) {
            review(id, "INVALID_DISPATCH_SNAPSHOT");
            return null;
        }
        jdbc.update("UPDATE refund_requests SET status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return new Claim(id, attempt, amount, payload);
    }

    /**
     * Kết quả đã xác minh 00/00/type02 được ghi SUCCEEDED ở refund, không gán Order REFUNDED.
     * Kết quả chờ xử lý giữ UNKNOWN; âm/bất thường vào REVIEW, chưa tự giải phóng hạn mức như FAILED.
     * Response đến sau lease expiry được lưu bằng chứng nhưng vào REVIEW để tránh finalize đồng thời với recovery.
     */
    private void finish(Claim claim, VnpayMerchantClient.Outcome outcome) {
        var states = jdbc.queryForList("SELECT status FROM refund_attempts WHERE id=? FOR UPDATE", claim.attemptId());
        if (states.isEmpty()) { return; }
        var refund = jdbc.queryForList("SELECT * FROM refund_requests WHERE id=? FOR UPDATE", claim.refundId()).getFirst();
        if ("SUCCEEDED".equals(refund.get("status"))) {
            var receipts = jdbc.queryForList("SELECT provider_ref FROM refund_provider_receipts WHERE refund_id=?", claim.refundId());
            if (outcome != null && "00".equals(outcome.responseCode()) && "00".equals(outcome.transactionStatus())
                    && "02".equals(outcome.transactionType()) && !receipts.isEmpty()
                    && receipts.getFirst().get("provider_ref").equals(outcome.providerReference())) { return; }
            // Giữ terminal SUCCEEDED, nhưng kết quả trái ngược phải có evidence riêng và hold để không gửi tiền tiếp.
            conflict(claim, refund, outcome, "LATE_RESULT_AFTER_SUCCESS");
            return;
        }
        if ("FAILED".equals(refund.get("status"))) {
            // Admin đã chốt thất bại nhưng network cũ trả kết quả muộn: giữ lại audit, đóng băng lệnh mới theo payment.
            conflict(claim, refund, outcome, "LATE_RESULT_AFTER_MANUAL_FAILURE");
        }
        String target = "REQUIRES_REVIEW";
        String ref = outcome == null ? null : outcome.providerReference();
        if (outcome != null && "PROCESSING".equals(states.getFirst().get("status"))) {
            if ("00".equals(outcome.responseCode()) && "00".equals(outcome.transactionStatus())
                    && "02".equals(outcome.transactionType()) && ref != null && ref.matches("[0-9]{1,15}") && !ref.matches("0+")) {
                // Cùng provider refund reference không được kết thúc hai refund khác nhau, kể cả chạy nhiều instance.
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "vnpay-refund:" + ref);
                if (jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE provider_ref=? AND status='SUCCEEDED'",
                        Integer.class, ref) == 0) {
                    int recorded = jdbc.update("INSERT INTO refund_provider_receipts(refund_id,provider,merchant_code,provider_ref,"
                            + "amount,currency,source,source_id) VALUES (?,?,?,?,?,?,'PROVIDER',?) ON CONFLICT DO NOTHING",
                            claim.refundId(), refund.get("provider"), refund.get("merchant_code"), ref,
                            claim.amount(), refund.get("currency"), claim.attemptId());
                    if (recorded == 1) { target = "SUCCEEDED"; }
                }
            } else if ("94".equals(outcome.responseCode()) || "05".equals(outcome.transactionStatus())
                    || "06".equals(outcome.transactionStatus())) { target = "UNKNOWN"; }
        }
        jdbc.update("UPDATE refund_attempts SET status=?,response_code=?,transaction_status=?,provider_ref=?,"
                + "completed_at=CURRENT_TIMESTAMP WHERE id=?", target, outcome == null ? null : outcome.responseCode(),
                outcome == null ? null : outcome.transactionStatus(), ref, claim.attemptId());
        jdbc.update("UPDATE refund_requests SET status=?,review_reason=?,reconcile_after=CURRENT_TIMESTAMP+interval '60 seconds',"
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", target, "REQUIRES_REVIEW".equals(target) ? "PROVIDER_RESULT_REVIEW" : null,
                claim.refundId());
    }

    /** Thu hồi lease dưới khóa attempt; không gửi lại payload dù worker trước chết trước hay sau network send. */
    public void recoverExpired() {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT id,refund_id FROM refund_attempts WHERE status='PROCESSING' "
                    + "AND lease_expires_at<CURRENT_TIMESTAMP ORDER BY lease_expires_at LIMIT 100 FOR UPDATE SKIP LOCKED");
            for (var row : rows) { unknown((UUID) row.get("id"), (UUID) row.get("refund_id"), "LEASE_EXPIRED"); }
            // Query cũng có thể chết sau claim: không để lần thứ ba ở UNKNOWN vô hạn khi không còn lượt query.
            jdbc.update("UPDATE refund_reconciliation_checks SET error_type='QUERY_LEASE_EXPIRED' WHERE error_type='IN_FLIGHT' "
                    + "AND created_at<CURRENT_TIMESTAMP-interval '120 seconds'");
            jdbc.update("UPDATE refund_requests SET status='REQUIRES_REVIEW',review_reason='QUERY_LIMIT_REQUIRES_REVIEW',"
                    + "updated_at=CURRENT_TIMESTAMP WHERE status='UNKNOWN' AND reconcile_count>=3 "
                    + "AND reconcile_after<CURRENT_TIMESTAMP-interval '60 seconds'");
        });
    }

    /** Có khóa attempt trước cập nhật request; dữ liệu SUCCEEDED/REVIEW đã commit không bị lỗi cũ ghi đè. */
    private void unknown(UUID attempt, UUID refund, String reason) {
        int changed = jdbc.update("UPDATE refund_attempts SET status='UNKNOWN',error_type=?,completed_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND status='PROCESSING'", reason, attempt);
        if (changed > 0) {
            jdbc.update("UPDATE refund_requests SET status='UNKNOWN',review_reason=?,reconcile_after=CURRENT_TIMESTAMP,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'", reason, refund);
        }
    }

    /**
     * Query chỉ đọc PAY gốc, lưu requestId trước mạng. Tối đa ba lần, không suy diễn PAY thành công là refund thành công.
     * Hai instance dùng reconcile_after như lease ngắn; query mất ACK được ghi lỗi, không quay lại gửi refund.
     */
    public boolean reconcile(UUID id) {
        requireOutsideTransaction();
        if (!enabled()) { return false; }
        Query query = tx.execute(status -> prepareQuery(id));
        if (query == null) { return false; }
        VnpayMerchantClient.Outcome result = null;
        try { result = client.exchange(query.payload(), query.amount()); }
        catch (RuntimeException error) { /* Chỉ lưu loại kết quả, không log cause/body chứa chữ ký. */ }
        VnpayMerchantClient.Outcome outcome = result;
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE refund_reconciliation_checks SET response_code=?,transaction_status=?,transaction_type=?,error_type=? WHERE id=?",
                    outcome == null ? null : outcome.responseCode(), outcome == null ? null : outcome.transactionStatus(),
                    outcome == null ? null : outcome.transactionType(), outcome == null ? "QUERY_UNCERTAIN" : null, query.id());
            jdbc.update("UPDATE refund_requests SET status='REQUIRES_REVIEW',review_reason='QUERY_LIMIT_REQUIRES_REVIEW',"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='UNKNOWN' AND reconcile_count>=3", id);
        });
        return true;
    }

    /** Chỉ query đã đến hạn, claim xong commit trước network; một query mới dùng một requestId mới. */
    private Query prepareQuery(UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM refund_requests WHERE id=? AND status='UNKNOWN' AND reconcile_count<3 "
                + "AND reconcile_after<=CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED", id);
        if (rows.isEmpty()) { return null; }
        var row = rows.getFirst();
        UUID queryId = UUID.randomUUID();
        String requestId = queryId.toString().replace("-", "");
        Instant original = jdbc.queryForObject("SELECT created_at FROM payments WHERE id=?",
                (rs, index) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), row.get("payment_id"));
        var payload = protocol().query(requestId, row.get("payment_id").toString(), original, Instant.now(),
                config.getMerchantSourceIp(), "Reconcile payment for " + id);
        jdbc.update("INSERT INTO refund_reconciliation_checks(id,refund_id,request_id,error_type) VALUES (?,?,?,'IN_FLIGHT')",
                queryId, id, requestId);
        jdbc.update("UPDATE refund_requests SET reconcile_count=reconcile_count+1,reconcile_after=CURRENT_TIMESTAMP+interval '60 seconds' "
                + "WHERE id=?", id);
        return new Query(queryId, (BigDecimal) row.get("amount"), payload);
    }

    private void review(UUID id, String reason) {
        jdbc.update("UPDATE refund_requests SET status='REQUIRES_REVIEW',review_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", reason, id);
    }

    /** Lưu mã kết quả muộn và hold cùng transaction; không log payload hoặc cập nhật lại quyết định Admin đã lưu. */
    private void conflict(Claim claim, Map<String, Object> refund, VnpayMerchantClient.Outcome outcome, String reason) {
        jdbc.update("INSERT INTO refund_result_conflicts(id,refund_id,attempt_id,response_code,transaction_status,transaction_type,"
                + "provider_ref,reason) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING", UUID.randomUUID(), claim.refundId(),
                claim.attemptId(), outcome == null ? null : outcome.responseCode(), outcome == null ? null : outcome.transactionStatus(),
                outcome == null ? null : outcome.transactionType(), outcome == null ? null : outcome.providerReference(), reason);
        jdbc.update("INSERT INTO refund_payment_holds(payment_id,reason) VALUES (?,?) ON CONFLICT DO NOTHING", refund.get("payment_id"), reason);
    }

    private boolean enabled() {
        return config.isRefundWorkerEnabled() && config.isMerchantApiEnabled()
                && !config.getMerchantSourceIp().isBlank() && config.getMerchantSourceIp().matches("[0-9a-fA-F:.]{7,45}");
    }

    private VnpayMerchantProtocol protocol() { return new VnpayMerchantProtocol(config.getTmnCode(), config.getHashSecret()); }

    /** Không cho caller bao cả dispatch trong transaction khác rồi tưởng đã có commit trước khi gửi HTTP. */
    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refund worker phải chạy ngoài transaction của caller");
        }
    }

    private record Claim(UUID refundId, UUID attemptId, BigDecimal amount, Map<String, String> payload) { }
    private record Query(UUID id, BigDecimal amount, Map<String, String> payload) { }
}
