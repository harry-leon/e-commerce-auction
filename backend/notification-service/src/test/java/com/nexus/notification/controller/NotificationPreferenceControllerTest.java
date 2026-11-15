package com.nexus.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.notification.config.SecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bảo vệ API cấu hình nhận thông báo: chỉ thao tác trên cấu hình của JWT subject, chặn kênh/loại lạ
 * và trả 401 khi token thiếu UUID. Suite PostgreSQL kiểm tra unique constraint thật.
 */
@WebMvcTest(NotificationPreferenceController.class)
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class NotificationPreferenceControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JdbcTemplate jdbc;
    private final String buyer = UUID.randomUUID().toString();

    /** Anonymous bị chặn, token thiếu UUID trả 401; danh sách chỉ đọc theo user_id của JWT. */
    @Test
    void listsPreferencesScopedToTokenSubject() throws Exception {
        mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/preferences").with(jwt().jwt(j -> j.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(UUID.fromString(buyer)))).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/notifications/preferences").with(jwt().jwt(j -> j.subject(buyer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }

    /** Tắt PUSH cho một loại sự kiện là thao tác hợp lệ; kênh IN_APP hoặc loại lạ bị từ chối trước DB. */
    @Test
    void updatesOnlyConfigurableChannelForKnownType() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String body = "{\"type\":\"REFUND_SUCCEEDED\",\"channel\":\"push\",\"enabled\":false}";
        mvc.perform(put("/api/v1/notifications/preferences")
                        .with(jwt().jwt(j -> j.subject(buyer))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.channel").value("PUSH"))
                .andExpect(jsonPath("$.data.enabled").value(false));
        // Reset để phần kiểm chứng "không chạm DB" bên dưới chỉ tính các request sai.
        org.mockito.Mockito.clearInvocations(jdbc);
        for (String invalid : java.util.List.of(
                "{\"type\":\"REFUND_SUCCEEDED\",\"channel\":\"IN_APP\",\"enabled\":false}",
                "{\"type\":\"NOT_A_TYPE\",\"channel\":\"PUSH\",\"enabled\":false}")) {
            mvc.perform(put("/api/v1/notifications/preferences")
                            .with(jwt().jwt(j -> j.subject(buyer))).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        // Thiếu field bắt buộc đi qua bean validation nên trả 422 theo envelope chung, không phải 400.
        mvc.perform(put("/api/v1/notifications/preferences")
                        .with(jwt().jwt(j -> j.subject(buyer))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"PUSH\",\"enabled\":false}"))
                .andExpect(status().isUnprocessableEntity());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
