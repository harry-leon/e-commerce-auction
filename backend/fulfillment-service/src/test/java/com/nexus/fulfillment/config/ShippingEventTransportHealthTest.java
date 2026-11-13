package com.nexus.fulfillment.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.fulfillment.client.CommerceShippingClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Policy health không được gọi downstream, báo disabled giả lỗi hoặc đưa SQL/credential trong response. */
class ShippingEventTransportHealthTest {
    /** Tắt worker không cần DB; bật thiếu OAuth phải báo rõ không sẵn sàng. */
    @Test
    void disabledAndUnconfiguredNeverQueryDatabase() {
        var jdbc = mock(JdbcTemplate.class); var client = mock(CommerceShippingClient.class);
        assertEquals(Status.UP, new ShippingEventTransportHealth(jdbc, client, false, 300).health().getStatus());
        verifyNoInteractions(jdbc, client);
        assertEquals(Status.OUT_OF_SERVICE, new ShippingEventTransportHealth(jdbc, client, true, 300).health().getStatus());
        verifyNoInteractions(jdbc);
    }
    /** Dead letter/overdue làm health suy giảm; lỗi DB không trả exception message hoặc URL nhạy cảm. */
    @Test
    void reportsBacklogAndSanitizesDatabaseFailure() {
        var jdbc = mock(JdbcTemplate.class); var client = mock(CommerceShippingClient.class); when(client.configured()).thenReturn(true);
        var health = new ShippingEventTransportHealth(jdbc, client, true, 300);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of("pending", 0L, "dead_letters", 0L, "overdue", 0L));
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(false, health.health().getDetails().get("connectivityVerified"));
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of("pending", 1L, "dead_letters", 1L, "overdue", 0L));
        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
        when(jdbc.queryForMap(anyString(), anyLong())).thenThrow(new DataAccessResourceFailureException("Sensitive database URL"));
        assertEquals(Status.DOWN, health.health().getStatus());
        assertEquals(Map.of("reason", "DATABASE_UNAVAILABLE"), health.health().getDetails());
    }
}
