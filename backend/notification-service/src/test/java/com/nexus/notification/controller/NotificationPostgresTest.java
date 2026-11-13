package com.nexus.notification.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** Flyway/JDBC/security thật: giao một lần, đúng buyer, retry sau READ và không cho đổi payload UUID đã dùng. */
@SpringBootTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class NotificationPostgresTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    /** Refund giữ đúng requestId trong inbox/push, replay sau READ không tạo tin mới; đổi refund trên cùng eventId trả 409. */
    @Test
    void refundContextIsDurableAndCannotBeChangedOnReplay() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        var body = event(buyer, "REFUND_REQUIRES_REVIEW");
        body.put("refundId", refund);
        send(body, 204);
        send(body, 204);
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].refundId").value(refund.toString()))
                .andExpect(jsonPath("$.data.content[0].title").value("Yêu cầu hoàn tiền cần đối soát"));
        mvc.perform(post("/api/v1/notifications/{id}/read", body.get("id"))
                .with(jwt().jwt(j -> j.subject(buyer.toString())))).andExpect(status().isOk());
        send(body, 204);
        assertEquals("READ", jdbc.queryForObject("SELECT status FROM notifications WHERE id=?", String.class, body.get("id")));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE refund_id=?", Integer.class, refund));
        body.put("refundId", UUID.randomUUID());
        send(body, 409);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, body.get("id").toString()));
    }

    /** Các trạng thái refund có thông điệp riêng, chỉ SUCCEEDED xác nhận hoàn tiền; source phải gửi đủ context hợp lệ. */
    @Test
    void validatesAndRendersRefundLifecycle() throws Exception {
        UUID buyer = UUID.randomUUID();
        var body = event(buyer, "REFUND_REQUESTED");
        send(body, 422);
        body.put("refundId", UUID.randomUUID());
        body.put("amount", 0);
        send(body, 422);
        body.put("amount", 0.5);
        send(body, 422);
        body.put("amount", 50000);
        for (String type : new String[] {"REFUND_REQUESTED", "REFUND_SUCCEEDED", "REFUND_FAILED", "REFUND_REQUIRES_REVIEW"}) {
            body.put("id", UUID.randomUUID());
            body.put("type", type);
            send(body, 204);
        }
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND channel='IN_APP'", Integer.class, buyer));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND channel='IN_APP' "
                + "AND title='Đã xác nhận hoàn tiền' AND event_type='REFUND_SUCCEEDED'", Integer.class, buyer));
    }

    /** Health của transport thực trong Spring context phản ánh broker đã start; không chứng minh browser nhận push. */
    @Test
    void exposesBrokerHealthAfterStartup() throws Exception {
        mvc.perform(get("/actuator/health/notificationTransport").with(jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private Map<String, Object> event(UUID buyer, String type) {
        return new HashMap<>(Map.of("id", UUID.randomUUID(), "userId", buyer, "orderId", UUID.randomUUID(),
                "orderNo", "ORD-DEMO", "type", type, "amount", 50000, "occurredAt", "2026-10-29T01:02:03Z"));
    }

    private void send(Map<String, Object> body, int expected) throws Exception {
        mvc.perform(post("/internal/commerce-notifications").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_NOTIFICATION_WRITER")))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    /** Lặp gửi/đọc không tăng số tin hoặc reset READ; người khác không xem/đánh dấu được tin đã giao. */
    @Test
    void deliversOnceToOwnerAndKeepsReadAcrossRetries() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var body = event(buyer, "ORDER_CREATED");
        send(body, 204);
        send(body, 204);
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Đã tạo đơn hàng"))
                .andExpect(jsonPath("$.data.content[0].content").value("Đơn ORD-DEMO • 50000.00 VND"))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-29T01:02:03Z"));
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(other.toString()))))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(post("/api/v1/notifications/{id}/read", body.get("id"))
                        .with(jwt().jwt(j -> j.subject(other.toString())))).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/notifications/{id}/read", body.get("id"))
                    .with(jwt().jwt(j -> j.subject(buyer.toString())))).andExpect(status().isOk());
        }
        send(body, 204);
        assertEquals("READ", jdbc.queryForObject("SELECT status FROM notifications WHERE id=?", String.class, body.get("id")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_attempts WHERE notification_id=?", Integer.class, body.get("id")));
        body.put("userId", other);
        send(body, 409);
        body.put("userId", buyer);
        body.put("amount", 10);
        send(body, 409);
    }

    /** Nội dung success/failure đúng loại, read-all chỉ sửa tài khoản hiện tại, phân trang ổn định. */
    @Test
    void rendersPaymentUpdatesAndScopesReadAll() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        send(event(buyer, "PAYMENT_SUCCESS"), 204);
        send(event(buyer, "PAYMENT_FAILED"), 204);
        send(event(other, "ORDER_CREATED"), 204);
        mvc.perform(get("/api/v1/notifications?size=1&page=1").with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(post("/api/v1/notifications/read-all").with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(other.toString()))))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    /** Chặn anonymous, buyer gọi ingestion, JWT subject không phải UUID và dữ liệu/sự kiện ngoài contract. */
    @Test
    void rejectsUnauthorizedAndInvalidInput() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/commerce-notifications").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(event(UUID.randomUUID(), "ORDER_CREATED")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized());
        for (String query : new String[]{"?size=0", "?size=101", "?page=-1", "?page=10001"}) {
            mvc.perform(get("/api/v1/notifications" + query).with(jwt())).andExpect(status().isBadRequest());
        }
        send(Map.of(), 422);
        var invalid = event(UUID.randomUUID(), "ORDER_CREATED");
        invalid.put("amount", -1);
        send(invalid, 422);
        invalid.put("amount", 100);
        invalid.put("type", "SHIPMENT_DELIVERED");
        send(invalid, 400);
    }
}
