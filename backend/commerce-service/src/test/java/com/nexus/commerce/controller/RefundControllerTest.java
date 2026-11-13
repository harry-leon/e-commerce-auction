package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.response.RefundResponse;
import com.nexus.commerce.enums.RefundStatus;
import com.nexus.commerce.service.RefundRequestService;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Xác minh refund không phải public API và chỉ Admin có quyền tiếp nhận, response không giả đã hoàn tiền. */
@WebMvcTest(RefundController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class RefundControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private RefundRequestService requests;

    /** Buyer/khách không thể chiếm hạn mức refund, kể cả gửi đủ key và payment UUID. */
    @Test
    void deniesAnonymousAndBuyerBeforeCallingService() throws Exception {
        String body = "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Hoàn tiền\"}";
        mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/refunds").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
    }

    /** Tạo trả 201, replay trả 200; cả hai chỉ là REQUESTED, không trả provider snapshot. */
    @Test
    void adminReceivesCreatedThenReplayWithPendingRequest() throws Exception {
        var response = new RefundResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50000"), "VND", RefundStatus.REQUESTED, Instant.now());
        when(requests.create(any(), any(), any(), any()))
                .thenReturn(new RefundRequestService.Result(response, true), new RefundRequestService.Result(response, false));
        String body = "{\"paymentId\":\"" + response.paymentId() + "\",\"reason\":\"Hoàn tiền\"}";
        for (int expected : new int[] {201, 200}) {
            mvc.perform(post("/api/v1/refunds").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.data.status").value("REQUESTED"))
                    .andExpect(jsonPath("$.data.merchantCode").doesNotExist());
        }
    }
}
