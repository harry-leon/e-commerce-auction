package com.nexus.fulfillment.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void whoamiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void whoamiReturnsSubjectWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-1"));
    }
}
