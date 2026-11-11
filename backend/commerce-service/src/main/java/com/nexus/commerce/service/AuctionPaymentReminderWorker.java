package com.nexus.commerce.service;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Worker nhắc nhở người thắng đấu giá khi sắp hết hạn thanh toán 24 giờ (mặc định trước 2 giờ = 120 phút).
 * Mỗi đơn chỉ được ghi tối đa một sự kiện AUCTION_PAYMENT_REMINDER vào outbox nhờ khóa UNIQUE(order_id, event_type).
 */
@Component
@Slf4j
public class AuctionPaymentReminderWorker {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final PlatformTransactionManager manager;
    private final NotificationOutbox notifications;
    private final int reminderBeforeMinutes;

    @Autowired
    public AuctionPaymentReminderWorker(
            JdbcTemplate jdbc,
            OrderRepository orders,
            PlatformTransactionManager manager,
            NotificationOutbox notifications,
            @Value("${app.payment.auction.reminder-before-minutes:120}") int reminderBeforeMinutes) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.manager = manager;
        this.notifications = notifications;
        this.reminderBeforeMinutes = reminderBeforeMinutes > 0 ? reminderBeforeMinutes : 120;
    }

    /** Quét tối đa 100 đơn winner nằm trong cửa sổ nhắc hạn và chưa có bản ghi nhắc trong outbox. */
    @Scheduled(fixedDelayString = "${app.payment.auction.reminder-poll-ms:60000}")
    public void run() {
        var ids = jdbc.queryForList("""
                SELECT o.id FROM orders o
                WHERE o.source_type='AUCTION'
                  AND o.status='PENDING_PAYMENT'
                  AND o.payment_deadline IS NOT NULL
                  AND o.payment_deadline > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                  AND o.payment_deadline <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + (? * interval '1 minute')
                  AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='SUCCESS')
                  AND NOT EXISTS (
                      SELECT 1 FROM commerce_notification_outbox n
                      WHERE n.order_id=o.id AND n.event_type='AUCTION_PAYMENT_REMINDER'
                  )
                ORDER BY o.payment_deadline, o.id
                LIMIT 100
                """, UUID.class, reminderBeforeMinutes);

        var tx = new TransactionTemplate(manager);
        for (UUID id : ids) {
            try {
                tx.executeWithoutResult(ignored -> remind(id));
            } catch (RuntimeException error) {
                log.warn("Chưa tạo được nhắc nhở hạn thanh toán: order={}, errorType={}",
                        id, error.getClass().getSimpleName());
            }
        }
    }

    private void remind(UUID orderId) {
        var order = orders.findForPaymentWithItems(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentDeadline() == null) {
            return;
        }
        Instant now = Instant.now();
        Instant cutoff = now.plus(reminderBeforeMinutes, ChronoUnit.MINUTES);
        if (!order.getPaymentDeadline().isAfter(now) || order.getPaymentDeadline().isAfter(cutoff)) {
            return;
        }
        Integer paid = jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE order_id=? AND status='SUCCESS'", Integer.class, orderId);
        if (paid != null && paid > 0) {
            return;
        }
        notifications.enqueue(order, "AUCTION_PAYMENT_REMINDER");
    }
}
