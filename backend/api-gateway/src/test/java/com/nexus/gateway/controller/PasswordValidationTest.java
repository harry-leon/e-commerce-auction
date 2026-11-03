package com.nexus.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.gateway.config.PasswordPolicy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Kiểm thử tính năng xác thực mật khẩu tại API Gateway trước khi gửi tới Keycloak IAM.
 * Đảm bảo mật khẩu không đạt chuẩn bị từ chối ngay với mã lỗi 422 và envelope ApiResponse chuẩn.
 */
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
        var body = response.getBody();
        assertFalse(body.success());
        var error = body.error();
        assertEquals("VALIDATION_ERROR", error.code());
        assertEquals(2, ((java.util.List<?>) error.details()).size());
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
