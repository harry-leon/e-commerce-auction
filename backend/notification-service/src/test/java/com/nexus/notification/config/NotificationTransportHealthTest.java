package com.nexus.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;

/** Broker health phải phản ánh cấu hình và lifecycle thật, không luôn UP khi push bắt buộc chưa có broker. */
class NotificationTransportHealthTest {
    /** Chưa start/đã dừng broker không sẵn sàng cho push; lifecycle UP rồi DOWN phải được phản ánh lại. */
    @Test
    void enabledPushTracksBrokerAvailability() {
        var health = new NotificationTransportHealth(new NotificationProperties());
        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
        health.availabilityChanged(new BrokerAvailabilityEvent(true, this));
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(false, health.health().getDetails().get("durablePush"));
        health.availabilityChanged(new BrokerAvailabilityEvent(false, this));
        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
    }

    /** Chủ động tắt push không làm service inbox/email DOWN; health vẫn khai báo transport không bền vững. */
    @Test
    void disabledPushDoesNotRequireBroker() {
        var config = new NotificationProperties();
        config.setPushEnabled(false);
        var health = new NotificationTransportHealth(config);
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(false, health.health().getDetails().get("pushEnabled"));
    }
}
