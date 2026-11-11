package com.nexus.commerce.service;

import com.nexus.commerce.client.AuctionTimeoutSyncClient;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker bền vững đọc outbox auction_timeout_tasks bằng SKIP LOCKED để gọi Reputation Service (trừ 10 điểm)
 * và Auction Service (đồng bộ settlement DEFAULTED) sau khi đơn quá hạn 24h đã hủy an toàn.
 * Cô lập hoàn toàn lỗi mạng/service downtime bằng cơ chế retry backoff, không ảnh hưởng việc hoàn kho/hủy đơn.
 */
@Component
@Slf4j
public class AuctionTimeoutSideEffectWorker {

    private final JdbcTemplate jdbc;
    private final AuctionTimeoutSyncClient syncClient;
    private final TransactionTemplate transaction;
    private final AtomicBoolean configWarning = new AtomicBoolean();

    public AuctionTimeoutSideEffectWorker(
            JdbcTemplate jdbc,
            AuctionTimeoutSyncClient syncClient,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.syncClient = syncClient;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Tối đa 10 tác vụ mỗi chu kỳ; thiếu OAuth giữ nguyên outbox để retry khi cấu hình sẵn sàng. */
    @Scheduled(fixedDelayString = "${app.payment.auction.timeout-poll-ms:5000}")
    public void run() {
        if (!syncClient.configured()) {
            if (configWarning.compareAndSet(false, true)) {
                log.warn("Chưa cấu hình OAuth cho service account Commerce; giữ nguyên hàng đợi phạt uy tín/đồng bộ");
            }
            return;
        }
        configWarning.set(false);
        for (int i = 0; i < 10; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> processOne()))) {
                break;
            }
        }
    }

    private boolean processOne() {
        var rows = jdbc.queryForList("SELECT * FROM auction_timeout_tasks WHERE NOT done "
                + "AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) {
            return false;
        }
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        UUID orderId = (UUID) row.get("order_id");
        UUID userId = (UUID) row.get("user_id");
        UUID auctionId = (UUID) row.get("auction_id");
        String taskType = row.get("task_type").toString();
        int scoreDelta = ((Number) row.get("score_delta")).intValue();
        String reason = row.get("reason").toString();

        try {
            if ("REPUTATION_PENALTY".equals(taskType)) {
                syncClient.applyTimeoutPenalty(userId, auctionId, orderId, scoreDelta, reason);
            } else if ("AUCTION_DEFAULTED".equals(taskType)) {
                syncClient.markAuctionDefaulted(auctionId, orderId);
            } else {
                throw new IllegalArgumentException("Loại tác vụ timeout không hợp lệ: " + taskType);
            }
            jdbc.update("UPDATE auction_timeout_tasks SET done=true,attempts=attempts+1 WHERE id=?", id);
        } catch (RuntimeException error) {
            jdbc.update("UPDATE auction_timeout_tasks SET attempts=attempts+1, "
                    + "next_attempt_at=CURRENT_TIMESTAMP+least(attempts+1,60)*interval '10 seconds' WHERE id=?", id);
            log.warn("Chưa xử lý được tác vụ quá hạn {}: order={}, errorType={}; giữ outbox để retry",
                    taskType, orderId, error.getClass().getSimpleName());
        }
        return true;
    }
}
