package com.nexus.commerce.service;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hủy đơn winner quá deadline bằng khóa Order chung với IPN/worker kho, mỗi đơn một transaction.
 * Trong cùng transaction hủy đơn: hoàn kho qua payment_inventory_tasks (RELEASE), phát thông báo
 * AUCTION_PAYMENT_TIMEOUT, và ghi outbox trừ 10 điểm uy tín + đồng bộ DEFAULTED cho Auction Service.
 */
@Component
@Slf4j
public class AuctionPaymentExpiryWorker {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final PlatformTransactionManager manager;
    private final NotificationOutbox notifications;
    private final int timeoutPenaltyScore;

    public AuctionPaymentExpiryWorker(JdbcTemplate jdbc, OrderRepository orders, PlatformTransactionManager manager) {
        this(jdbc, orders, manager, null, 10);
    }

    @Autowired
    public AuctionPaymentExpiryWorker(
            JdbcTemplate jdbc,
            OrderRepository orders,
            PlatformTransactionManager manager,
            @Autowired(required = false) NotificationOutbox notifications,
            @Value("${app.payment.auction.timeout-penalty-score:${PAYMENT_TIMEOUT_PENALTY_SCORE:10}}") int timeoutPenaltyScore) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.manager = manager;
        this.notifications = notifications;
        this.timeoutPenaltyScore = timeoutPenaltyScore > 0 ? timeoutPenaltyScore : 10;
    }

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
        var order = orders.findForPaymentWithItems(id).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentDeadline() == null
                || order.getPaymentDeadline().isAfter(Instant.now())) {
            return;
        }
        Integer paid = jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND status='SUCCESS'",
                Integer.class, id);
        if (paid != null && paid > 0) {
            return;
        }
        order.setStatus(OrderStatus.CANCELLED);
        jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason) "
                + "VALUES (?,?,'PENDING_PAYMENT','CANCELLED','AUCTION_PAYMENT_DEADLINE')", UUID.randomUUID(), id);
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,'RELEASE') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), id, id);

        UUID auctionRef = order.getAuctionId() != null ? order.getAuctionId() : id;
        String penaltyReason = "Quá hạn thanh toán 24h cho đơn đấu giá " + order.getOrderNo();
        jdbc.update("INSERT INTO auction_timeout_tasks(id,order_id,user_id,auction_id,task_type,score_delta,reason) "
                + "VALUES (?,?,?,?,'REPUTATION_PENALTY',?,?) ON CONFLICT (order_id,task_type) DO NOTHING",
                UUID.randomUUID(), id, order.getBuyerId(), auctionRef, timeoutPenaltyScore, penaltyReason);
        jdbc.update("INSERT INTO auction_timeout_tasks(id,order_id,user_id,auction_id,task_type,score_delta,reason) "
                + "VALUES (?,?,?,?,'AUCTION_DEFAULTED',?,?) ON CONFLICT (order_id,task_type) DO NOTHING",
                UUID.randomUUID(), id, order.getBuyerId(), auctionRef, timeoutPenaltyScore, penaltyReason);

        if (notifications != null) {
            notifications.enqueue(order, "AUCTION_PAYMENT_TIMEOUT");
        }
    }
}
