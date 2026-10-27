package com.nexus.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.notification.config.SecurityConfig;
import com.nexus.common.exception.GlobalExceptionHandler;
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

/** Bảo vệ quyền API audit và validation trước JDBC; PostgreSQL test riêng kiểm chứng SQL/payload thật. */
@WebMvcTest(UserAuditController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAuditControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JdbcTemplate jdbc;

    /** Token thường không được đọc/ghi event; service account nhận trang rỗng thực sự khi count bằng 0. */
    @Test
    void restrictsAccessAndPagesEvents() throws Exception {
        UUID user = UUID.randomUUID();
        String path = "/internal/user-audit/" + user;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt())).andExpect(status().isForbidden());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(user.toString()))).thenReturn(0L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UserAuditController.Activity>>any(),
                eq(user.toString()), eq(20), eq(0L))).thenReturn(List.of());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Ghi audit dùng actor từ JWT, UUID do client cung cấp và bảng event_logs; DTO thiếu thông tin bị chặn. */
    @Test
    void recordsValidatedEventUsingAuthenticatedOperator() throws Exception {
        UUID event = UUID.randomUUID();
        String body = "{\"id\":\"" + event + "\",\"action\":\"USER_UPDATED\",\"status\":\"SUCCESS\",\"createdAt\":\"2026-10-27T02:03:04\"}";
        mvc.perform(post("/internal/user-audit").with(jwt().jwt(j -> j.subject("writer"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        verify(jdbc).update(anyString(), eq(event), eq(event.toString()), anyString(), eq("writer"), any());
        mvc.perform(post("/internal/user-audit").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Sort chỉ nhận whitelist và asc/desc; dữ liệu người dùng không được ghép thành biểu thức SQL. */
    @Test
    void rejectsInvalidPaginationAndSort() throws Exception {
        String path = "/internal/user-audit/" + UUID.randomUUID();
        for (String query : new String[]{"?size=101", "?size=0", "?page=-1", "?sort=payload,desc", "?sort=id,invalid", "?sort=id"}) {
            mvc.perform(get(path + query).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER"))))
                    .andExpect(status().isBadRequest());
        }
    }
}
