package com.nexus.notification.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.stereotype.Component;

/**
 * Phản ánh broker STOMP trong process: push bật nhưng broker chưa sẵn sàng thì OUT_OF_SERVICE.
 * Không chứng minh browser nhận tin, không kiểm tra Kafka/Redis và không coi RAM broker là transport bền vững.
 * Liveness mặc định không chứa indicator này; vận hành có thể thêm notificationTransport vào readiness group.
 */
@Component("notificationTransport")
public class NotificationTransportHealth implements HealthIndicator {
    private final NotificationProperties properties;
    private volatile boolean brokerAvailable;

    public NotificationTransportHealth(NotificationProperties properties) { this.properties = properties; }

    /** Sự kiện vòng đời do Spring broker phát, không nhận trạng thái health từ WebSocket frame của client. */
    @EventListener
    public void availabilityChanged(BrokerAvailabilityEvent event) { brokerAvailable = event.isBrokerAvailable(); }

    /** Chỉ trả metadata kỹ thuật công khai, không URL/credential hoặc ID người nhận. */
    @Override
    public Health health() {
        boolean enabled = properties.isPushEnabled();
        var builder = !enabled || brokerAvailable ? Health.up() : Health.outOfService();
        return builder.withDetail("transport", "SIMPLE_BROKER_SINGLE_INSTANCE")
                .withDetail("pushEnabled", enabled).withDetail("brokerAvailable", brokerAvailable)
                .withDetail("durablePush", false).build();
    }
}
