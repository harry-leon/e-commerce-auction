package com.nexus.notification.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Kiểm tra audit thật qua HTTP/security/JDBC: đúng bảng ERD, không trùng sự kiện và không lộ lịch sử người khác. */
@SpringBootTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class UserAuditPostgresTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    /** Service account ghi lặp UUID chỉ tạo một dòng; query chỉ lấy payload của đúng user đích. */
    @Test
    void persistsIdempotentlyAndFiltersHistory() throws Exception {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of("id", id, "userId", user, "action", "USER_UPDATED",
                "description", "Cập nhật hồ sơ", "status", "SUCCESS", "createdAt", "2026-10-27T02:03:04"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/internal/user-audit").with(jwt().jwt(j -> j.subject("audit-writer"))
                            .authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_logs WHERE id = ?", Integer.class, id));
        assertEquals("audit-writer", jdbc.queryForObject("SELECT operator_id FROM event_logs WHERE id = ?", String.class, id));
        mvc.perform(get("/internal/user-audit/{user}", user).with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("USER_UPDATED"));
        mvc.perform(get("/internal/user-audit/{user}", UUID.randomUUID()).with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Người dùng thường không được gọi API nội bộ; query sai giới hạn/sort bị chặn trước khi ghép SQL. */
    @Test
    void rejectsUnauthorizedAndInvalidQueries() throws Exception {
        String path = "/internal/user-audit/" + UUID.randomUUID();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt())).andExpect(status().isForbidden());
        for (String query : new String[]{"?size=101", "?page=-1", "?sort=payload,desc", "?sort=id,invalid"}) {
            mvc.perform(get(path + query).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/user-audit").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFILE_WRITER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
