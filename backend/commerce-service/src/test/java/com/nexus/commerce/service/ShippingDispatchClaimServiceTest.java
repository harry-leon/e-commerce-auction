package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mặc định tắt claim không chạm DB/khóa hoặc gửi request ra ngoài. */
class ShippingDispatchClaimServiceTest {
    @Test
    void disabledFailsClosedBeforeDatabase() {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class); var locks = org.mockito.Mockito.mock(CheckoutLock.class);
        var service = new ShippingDispatchClaimService(jdbc, locks, false);
        assertEquals(503, assertThrows(ApiException.class, () -> service.claim(UUID.randomUUID(), UUID.randomUUID(),
                new ShippingDispatchClaimService.Command(UUID.randomUUID(), UUID.randomUUID()))).getHttpStatus().value());
        assertEquals(400, assertThrows(ApiException.class, () -> service.claim(null, null, null)).getHttpStatus().value());
        assertEquals(503, assertThrows(ApiException.class, () -> service.handoff(UUID.randomUUID())).getHttpStatus().value());
        assertEquals(503, assertThrows(ApiException.class, () -> service.preflight(UUID.randomUUID(), UUID.randomUUID())).getHttpStatus().value());
        verifyNoInteractions(jdbc, locks);
    }
}
