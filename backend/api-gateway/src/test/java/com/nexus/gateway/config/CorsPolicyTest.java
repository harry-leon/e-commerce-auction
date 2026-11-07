package com.nexus.gateway.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Cấu hình CORS có credential phải từ chối wildcard/rỗng khi boot, tránh mở nhầm origin sau deploy. */
class CorsPolicyTest {
    @Test
    void rejectsWildcardAndEmptyOriginsBeforeServingRequests() {
        var config = new GatewaySecurityConfig();
        for (String invalid : new String[] {"*", "https://*.example.test", "", ","}) {
            ReflectionTestUtils.setField(config, "allowedOrigins", invalid);
            assertThrows(IllegalArgumentException.class, config::corsConfigurationSource);
        }
        ReflectionTestUtils.setField(config, "allowedOrigins", " https://nexus.example.test , http://localhost:3000 ");
        assertNotNull(config.corsConfigurationSource());
    }
}
