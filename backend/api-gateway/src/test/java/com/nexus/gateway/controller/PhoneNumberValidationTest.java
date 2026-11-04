package com.nexus.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Đăng ký phải từ chối số di động sai độ dài/đầu số trước khi tạo tài khoản IAM. */
class PhoneNumberValidationTest {
    @Test
    void rejectsInvalidPhoneWithoutCallingIam() {
        var calls = new AtomicInteger();
        var controller = new AuthGatewayController(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            throw new AssertionError("Số điện thoại sai không được gọi IAM");
        }));
        for (Object phone : new Object[]{"091234567", "09123456789", "0212345678", "0123456789",
                "+84912345678", "091234567a", "091 2345678", "0912345678 ", "０９１２３４５６７８", 9123456789L}) {
            Map<String, Object> body = new HashMap<>(Map.of("email", "test@example.com",
                    "fullName", "Test Buyer", "password", "Nexus12@", "identifyNumber", "079203001234"));
            body.put("phone", phone);
            var response = controller.register(body).block();
            assertEquals(422, response.getStatusCode().value());
            var details = (List<?>) response.getBody().error().details();
            assertEquals("phone", ((Map<?, ?>) details.getFirst()).get("field"));
        }
        assertEquals(0, calls.get());
    }
}
