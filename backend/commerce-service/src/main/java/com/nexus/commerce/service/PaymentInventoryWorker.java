package com.nexus.commerce.service;

import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.common.exception.ApiException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đẩy outbox đã commit sang kho bằng service account; SKIP LOCKED chia tác vụ an toàn giữa nhiều instance.
 * Remote thành công nhưng worker chết trước commit sẽ được retry: confirm ALREADY_COMMITTED/release lặp là idempotent.
 */
@Component
@Slf4j
public class PaymentInventoryWorker {
    private final JdbcTemplate jdbc;
    private final InventoryClient inventory;
    private final InventoryServiceToken tokens;
    private final TransactionTemplate transaction;

    public PaymentInventoryWorker(JdbcTemplate jdbc, InventoryClient inventory, InventoryServiceToken tokens,
                                    PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.tokens = tokens;
        transaction = new TransactionTemplate(manager);
    }

    /** Mỗi tick xử lý tối đa 10 tác vụ, từng transaction riêng; thiếu config không đánh dấu hoàn thành giả. */
    @Scheduled(fixedDelayString = "${app.payment.inventory.poll-ms:5000}")
    public void run() {
        if (!tokens.configured()) { return; }
        for (int i = 0; i < 10; i++) {
            Boolean found = transaction.execute(status -> processOne());
            if (!Boolean.TRUE.equals(found)) { break; }
        }
    }

    private boolean processOne() {
        var rows = jdbc.queryForList("SELECT * FROM payment_inventory_tasks WHERE NOT done AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) { return false; }
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        try {
            String token = tokens.get();
            if ("CONFIRM".equals(row.get("action"))) { inventory.confirm((UUID) row.get("target_id"), token); }
            else { inventory.release((UUID) row.get("order_id"), token); }
            jdbc.update("UPDATE payment_inventory_tasks SET done=true, attempts=attempts+1 WHERE id=?", id);
        } catch (RuntimeException error) {
            if (error instanceof ApiException api && api.getHttpStatus().value() == 401) { tokens.invalidate(); }
            // Retry vô hạn với khoảng chờ tối đa 10 phút; phiếu EXPIRED cần quản trị đối soát/refund, không reserve lại âm thầm.
            jdbc.update("UPDATE payment_inventory_tasks SET attempts=attempts+1, "
                    + "next_attempt_at=CURRENT_TIMESTAMP + least(attempts+1,60) * interval '10 seconds' WHERE id=?", id);
            log.warn("Outbox kho chưa hoàn tất: task={}, order={}, errorType={}; cần theo dõi attempts", id, row.get("order_id"), error.getClass().getSimpleName());
        }
        return true;
    }
}
