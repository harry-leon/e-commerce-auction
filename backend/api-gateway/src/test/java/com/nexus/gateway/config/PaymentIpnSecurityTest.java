package com.nexus.gateway.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gateway chỉ mở GET IPN cho VNPAY không có JWT; POST và API payment buyer vẫn bị bảo vệ. */
@WebFluxTest(controllers = PaymentIpnSecurityTest.Probe.class)
@Import({GatewaySecurityConfig.class, PaymentIpnSecurityTest.Probe.class})
@TestPropertySource(properties = {"app.keycloak.jwk-set-uri=https://example.test/jwks", "spring.cloud.config.enabled=false"})
class PaymentIpnSecurityTest {
    @Autowired private WebTestClient client;
    @MockitoBean private ReactiveJwtDecoder decoder;

    @Test
    void onlyProviderGetEndpointIsPublic() {
        client.get().uri("/api/v1/payments/vnpay/ipn").exchange().expectStatus().isOk().expectBody().jsonPath("$.RspCode").isEqualTo("97");
        client.post().uri("/api/v1/payments/vnpay/ipn").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/payments/some-payment").exchange().expectStatus().isUnauthorized();
    }

    /** Probe thay downstream Commerce; chữ ký được test tại Commerce, đây chỉ xác minh security chain Gateway. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/payments/vnpay/ipn")
        Map<String, String> ipn() { return Map.of("RspCode", "97"); }
    }
}
