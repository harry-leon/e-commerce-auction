package com.nexus.commerce.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.NotificationDelivery;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Health không được đồng nghĩa Notification reachable; không query backlog khi worker tắt hoặc thiếu OAuth. */
class ShippingNotificationTransportHealthTest {
    /** Worker tắt UP nhưng không chứng minh connectivity; bật thiếu OAuth OUT_OF_SERVICE mà không query DB. */
    @Test
    void disabledAndUnconfiguredNeverQueryDatabase() {
        var jdbc = mock(JdbcTemplate.class); var delivery = mock(NotificationDelivery.class);
        assertEquals(Status.UP, new ShippingNotificationTransportHealth(jdbc, delivery, false, 300).health().getStatus());
        verifyNoInteractions(jdbc, delivery);
        assertEquals(Status.OUT_OF_SERVICE, new ShippingNotificationTransportHealth(jdbc, delivery, true, 300).health().getStatus());
        verifyNoInteractions(jdbc);
    }
    /** Backlog lỗi hoặc quá hạn suy giảm; lỗi DB chỉ trả phân loại, không lộ SQL/credential. */
    @Test
    void reportsBacklogAndSanitizesDatabaseFailure() {
        var jdbc = mock(JdbcTemplate.class); var delivery = mock(NotificationDelivery.class); when(delivery.configured()).thenReturn(true);
        var health = new ShippingNotificationTransportHealth(jdbc, delivery, true, 300);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of("pending", 0L, "dead_letters", 0L, "overdue", 0L));
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(false, health.health().getDetails().get("connectivityVerified"));
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of("pending", 1L, "dead_letters", 0L, "overdue", 1L));
        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
        when(jdbc.queryForMap(anyString(), anyLong())).thenThrow(new DataAccessResourceFailureException("Sensitive SQL"));
        assertEquals(Status.DOWN, health.health().getStatus());
        assertEquals(Map.of("reason", "DATABASE_UNAVAILABLE"), health.health().getDetails());
    }
}
