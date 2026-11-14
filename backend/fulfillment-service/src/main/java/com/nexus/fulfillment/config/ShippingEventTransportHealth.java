package com.nexus.fulfillment.config;

import com.nexus.fulfillment.client.CommerceShippingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Health cục bộ của HTTP outbox shipment; không gọi Commerce/IAM/carrier và không suy ra kết nối downstream đã tốt. */
@Component("shippingEventTransport")
public class ShippingEventTransportHealth implements HealthIndicator {
    private final JdbcTemplate jdbc;
    private final CommerceShippingClient client;
    private final boolean enabled;
    private final long maxAgeSeconds;

    public ShippingEventTransportHealth(JdbcTemplate jdbc, CommerceShippingClient client,
            @Value("${app.shipping.commerce.events-enabled:false}") boolean enabled,
            @Value("${app.shipping.commerce.backlog-max-age-seconds:300}") long maxAgeSeconds) {
        this.jdbc = jdbc; this.client = client; this.enabled = enabled; this.maxAgeSeconds = Math.max(1, maxAgeSeconds);
    }

    /** Disabled không đụng DB/OAuth; bật thiếu cấu hình hoặc backlog lỗi thì OUT_OF_SERVICE, lỗi DB trả DOWN không lộ SQL. */
    @Override
    public Health health() {
        if (!enabled) { return Health.up().withDetail("enabled", false).withDetail("connectivityVerified", false).build(); }
        if (!client.configured()) { return Health.outOfService().withDetail("reason", "OAUTH_NOT_CONFIGURED").build(); }
        try {
            var counts = jdbc.queryForMap("SELECT count(*) FILTER (WHERE attempts>=8) AS dead_letters, "
                    + "count(*) FILTER (WHERE attempts<8) AS pending, count(*) FILTER (WHERE attempts<8 AND "
                    + "created_at<CURRENT_TIMESTAMP-(? * interval '1 second')) AS overdue FROM fulfillment_event_outbox "
                    + "WHERE aggregate_type='SHIPMENT' AND done=false AND event_type IN "
                    + "('ShipmentCreated','ShipmentPickedUp','ShipmentInTransit','ShipmentDelivered','ShipmentFailed','ShipmentReturned')", maxAgeSeconds);
            long dead = ((Number) counts.get("dead_letters")).longValue();
            long overdue = ((Number) counts.get("overdue")).longValue();
            return (dead > 0 || overdue > 0 ? Health.outOfService() : Health.up())
                    .withDetail("enabled", true).withDetail("mode", "LOCAL_BACKLOG_ONLY").withDetail("connectivityVerified", false)
                    .withDetail("pending", counts.get("pending")).withDetail("deadLetters", dead).withDetail("overdue", overdue)
                    .withDetail("maxAgeSeconds", maxAgeSeconds).build();
        } catch (DataAccessException error) {
            return Health.down().withDetail("reason", "DATABASE_UNAVAILABLE").build();
        }
    }
}
