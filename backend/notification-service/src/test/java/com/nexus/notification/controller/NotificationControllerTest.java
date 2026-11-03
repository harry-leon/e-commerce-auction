package com.nexus.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.notification.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Test HTTP/security chạy cả khi CI không có PostgreSQL; suite Postgres bảo vệ constraint/transaction thật. */
@WebMvcTest({NotificationController.class, CommerceNotificationController.class})
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class NotificationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JdbcTemplate jdbc;
    private final String buyer = UUID.randomUUID().toString();

    private String body(String type) {
        return """
                {"id":"11111111-1111-4111-8111-111111111111","userId":"22222222-2222-4222-8222-222222222222",
                 "orderId":"33333333-3333-4333-8333-333333333333","orderNo":"ORD-DEMO","type":"%s",
                 "amount":50000,"occurredAt":"2026-10-29T01:02:03Z"}
                """.formatted(type);
    }

    /** Anonymous/buyer không được ghi nội bộ; thông báo mới có nội dung do server sinh, ba loại hợp lệ đều nhận được. */
    @Test
    void protectsIngestionAndAcceptsOnlyTrustedWriter() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/commerce-notifications").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ORDER_CREATED"))).andExpect(status().isForbidden());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        for (String type : List.of("ORDER_CREATED", "PAYMENT_SUCCESS", "PAYMENT_FAILED")) {
            mvc.perform(post("/internal/commerce-notifications").with(jwt()
                            .authorities(new SimpleGrantedAuthority("ROLE_NOTIFICATION_WRITER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body(type))).andExpect(status().isNoContent());
        }
    }

    /** Retry chỉ ACK nếu payload khớp; xung đột UUID không được chuyển người nhận hoặc sửa nội dung đã giao. */
    @Test
    void handlesDuplicateAndConflictingEvent() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1, 0);
        for (int expected : List.of(204, 409)) {
            mvc.perform(post("/internal/commerce-notifications").with(jwt()
                            .authorities(new SimpleGrantedAuthority("ROLE_NOTIFICATION_WRITER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body("ORDER_CREATED"))).andExpect(status().is(expected));
        }
    }

    /** Query luôn bind buyer JWT, map timestamp UTC và READ; kiểm tra phân trang cùng số chưa đọc toàn hộp thư. */
    @Test
    @SuppressWarnings("unchecked")
    void listsOwnedInboxWithUtcTimestamp() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(UUID.fromString(buyer)), eq(20), eq(0L)))
                .thenAnswer(call -> {
                    var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getString("status")).thenReturn("READ");
                    when(rs.getString("title")).thenReturn("Đã tạo đơn hàng");
                    when(rs.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.valueOf("2026-10-29 01:02:03"));
                    return List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
                });
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(UUID.fromString(buyer)))).thenReturn(1L, 0L);
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(buyer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.content[0].read").value(true))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-29T01:02:03Z"));
    }

    /** READ chỉ thành công khi UPDATE có bản ghi của buyer; 404 tránh tiết lộ ID của người khác. */
    @Test
    void scopesReadAndValidatesQueries() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0, 1);
        for (int expected : List.of(404, 200)) {
            mvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()).with(jwt().jwt(j -> j.subject(buyer))))
                    .andExpect(status().is(expected));
        }
        mvc.perform(post("/api/v1/notifications/read-all").with(jwt().jwt(j -> j.subject(buyer)))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject("invalid")))).andExpect(status().isUnauthorized());
        for (String query : List.of("?page=-1", "?page=10001", "?size=0", "?size=101")) {
            mvc.perform(get("/api/v1/notifications" + query).with(jwt())).andExpect(status().isBadRequest());
        }
    }
}
