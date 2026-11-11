package com.nexus.commerce.service;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hủy đơn winner quá deadline bằng khóa Order chung với IPN/worker kho, mỗi đơn một transaction.
 * Tiền SUCCESS đã ghi nhận được dành cho worker kho xử lý; payment PENDING giữ nguyên để IPN trễ vẫn ghi nhận tiền cần đối soát.
 * RELEASE đi qua outbox idempotent; không truy cập DB Fulfillment hoặc tuyên bố đã hoàn tiền.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionPaymentExpiryWorker {
    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final PlatformTransactionManager manager;

    /** Quét tối đa 100 đơn/tick; kiểm tra lại deadline và payment dưới khóa để không hủy đơn IPN vừa thu tiền. */
    @Scheduled(fixedDelayString = "${app.payment.auction.expiry-poll-ms:10000}")
    public void run() {
        var ids = jdbc.queryForList("SELECT o.id FROM orders o WHERE source_type='AUCTION' AND status='PENDING_PAYMENT' "
                + "AND payment_deadline <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') "
                + "AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='SUCCESS') "
                + "ORDER BY payment_deadline,id LIMIT 100", UUID.class);
        var tx = new TransactionTemplate(manager);
        for (UUID id : ids) {
            try {
                tx.executeWithoutResult(ignored -> expire(id));
            } catch (RuntimeException error) {
                // Lỗi một đơn không chặn batch; transaction riêng rollback và tick sau thử lại, không log bind values.
                log.warn("Chưa xử lý hạn thanh toán: order={}, errorType={}", id, error.getClass().getSimpleName());
            }
        }
    }

    /** IPN và expiry cùng khóa Order; bên nhận tiền trước giữ quyền hoàn tất kho, bên hủy trước chỉ cho IPN ghi REVIEW. */
    private void expire(UUID id) {
        var order = orders.findForPayment(id).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentDeadline() == null
                || order.getPaymentDeadline().isAfter(java.time.Instant.now())) { return; }
        Integer paid = jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND status='SUCCESS'", Integer.class, id);
        if (paid != null && paid > 0) { return; }
        order.setStatus(OrderStatus.CANCELLED);
        jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason) "
                + "VALUES (?,?,'PENDING_PAYMENT','CANCELLED','AUCTION_PAYMENT_DEADLINE')", UUID.randomUUID(), id);
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,'RELEASE') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), id, id);
    }
}
