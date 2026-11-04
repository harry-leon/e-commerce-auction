package com.nexus.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Bảo vệ đăng ký: CCCD sai phải trả lỗi theo field trước mọi request tạo tài khoản IAM/profile. */
class IdentityNumberValidationTest {
    @Test
    void rejectsMissingOrMalformedIdentityBeforeCallingIam() {
        var calls = new AtomicInteger();
        var controller = new AuthGatewayController(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            throw new AssertionError("CCCD sai không được gọi IAM");
        }));
        // Bao gồm thiếu field, sai kiểu JSON, mất số 0 đầu, ký tự mẫu, khoảng trắng và chữ số Unicode.
        for (Object number : new Object[]{null, "", " ", "079xxxxxxxxx", "07920300123",
                "0792030012345", "07920300123a", " 079203001234", "０７９２０３００１２３４", 79203001234L}) {
            Map<String, Object> body = new HashMap<>(Map.of("email", "user@example.com",
                    "fullName", "Test Buyer", "password", "Nexus12@"));
            if (number != null) {
                body.put("identifyNumber", number);
            }
            var response = controller.register(body).block();
            assertEquals(422, response.getStatusCode().value());
            assertEquals("VALIDATION_ERROR", response.getBody().error().code());
            var details = (List<?>) response.getBody().error().details();
            assertEquals("identifyNumber", ((Map<?, ?>) details.getFirst()).get("field"));
        }
        assertEquals(0, calls.get());
    }
}
