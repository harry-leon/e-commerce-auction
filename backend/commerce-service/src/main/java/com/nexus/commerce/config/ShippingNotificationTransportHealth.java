package com.nexus.commerce.config;

import com.nexus.commerce.client.NotificationDelivery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Health backlog Shipping notification cục bộ, không probe Notification/IAM hoặc khẳng định browser đã nhận push. */
@Component("shippingNotificationTransport")
public class ShippingNotificationTransportHealth implements HealthIndicator {
    private final JdbcTemplate jdbc;
    private final NotificationDelivery delivery;
    private final boolean enabled;
    private final long maxAgeSeconds;

    public ShippingNotificationTransportHealth(JdbcTemplate jdbc, NotificationDelivery delivery,
            @Value("${app.shipping.notification-enabled:false}") boolean enabled,
            @Value("${app.shipping.notification-backlog-max-age-seconds:300}") long maxAgeSeconds) {
        this.jdbc = jdbc; this.delivery = delivery; this.enabled = enabled; this.maxAgeSeconds = Math.max(1, maxAgeSeconds);
    }

    /** Chỉ query khi worker bật/có OAuth; dead letter/overdue báo OUT_OF_SERVICE, DB lỗi DOWN không lộ cause/SQL. */
    @Override
    public Health health() {
        if (!enabled) { return Health.up().withDetail("enabled", false).withDetail("connectivityVerified", false).build(); }
        if (!delivery.configured()) { return Health.outOfService().withDetail("reason", "OAUTH_NOT_CONFIGURED").build(); }
        try {
            var counts = jdbc.queryForMap("SELECT count(*) FILTER (WHERE status='DEAD_LETTER') AS dead_letters, "
                    + "count(*) FILTER (WHERE status='PENDING') AS pending, count(*) FILTER (WHERE status='PENDING' "
                    + "AND created_at<now()-(? * interval '1 second')) AS overdue FROM shipping_notification_outbox "
                    + "WHERE status IN ('PENDING','DEAD_LETTER')", maxAgeSeconds);
            long dead = ((Number) counts.get("dead_letters")).longValue();
            long overdue = ((Number) counts.get("overdue")).longValue();
            return (dead > 0 || overdue > 0 ? Health.outOfService() : Health.up())
                    .withDetail("enabled", true).withDetail("mode", "LOCAL_BACKLOG_ONLY").withDetail("connectivityVerified", false)
                    .withDetail("pending", counts.get("pending")).withDetail("deadLetters", dead).withDetail("overdue", overdue)
                    .withDetail("maxAgeSeconds", maxAgeSeconds).build();
        } catch (DataAccessException error) { return Health.down().withDetail("reason", "DATABASE_UNAVAILABLE").build(); }
    }
}
