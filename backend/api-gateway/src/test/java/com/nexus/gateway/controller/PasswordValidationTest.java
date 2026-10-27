package com.nexus.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.gateway.config.PasswordPolicy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Bảo vệ đăng ký trước HTTP IAM: báo đầy đủ điều kiện thiếu và không tạo user nếu bỏ qua validation FE. */
class PasswordValidationTest {
    @Test
    void rejectsScreenshotPasswordWithoutCallingIam() {
        var calls = new AtomicInteger();
        var controller = new AuthGatewayController(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            throw new AssertionError("Invalid password must not call IAM");
        }));
        var response = controller.register(Map.of("email", "user@example.com", "fullName", "User", "password", "123123@")).block();
        assertEquals(422, response.getStatusCode().value());
        var error = (Map<?, ?>) response.getBody().get("error");
        assertEquals("VALIDATION_ERROR", error.get("code"));
        assertEquals(2, ((java.util.List<?>) error.get("details")).size());
        assertEquals(0, calls.get());
    }

    /** Unicode phải thống nhất với policy BE/Keycloak; khoảng trắng không thay thế được ký tự đặc biệt. */
    @Test
    void validatesEveryRuleAndBoundaries() {
        assertTrue(PasswordPolicy.errors("Nexus12@").isEmpty());
        assertTrue(PasswordPolicy.errors("Đabcdef1!").isEmpty());
        assertEquals(1, PasswordPolicy.errors(null).size());
        assertEquals(1, PasswordPolicy.errors(" ").size());
        assertEquals(3, PasswordPolicy.errors("abcdefgh").size());
        assertEquals(1, PasswordPolicy.errors("Abcdef12 ").size());
        assertEquals(1, PasswordPolicy.errors("A1!" + "a".repeat(98)).size());
        assertTrue(PasswordPolicy.errors("A1!" + "a".repeat(97)).isEmpty());
    }
}
