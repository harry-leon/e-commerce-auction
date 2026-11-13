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
    @MockitoBean private com.nexus.commerce.service.RefundResolutionService resolutions;

    /** Chỉ Admin đối soát; replay giữ nguyên audit và không để caller giả actor qua body. */
    @Test
    void manualResolutionRequiresAdminAndReturnsStableDecision() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String path = "/api/v1/refunds/" + id + "/manual-resolution";
        String body = "{\"decision\":\"FAILED\",\"amount\":50000,\"currency\":\"VND\","
                + "\"providerPaymentReference\":\"12345\",\"evidenceReference\":\"CASE-TEST\",\"note\":\"Đã đối soát\"}";
        mvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER"))).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(resolutions);
        var decision = new com.nexus.commerce.service.RefundResolutionService.Decision(UUID.randomUUID(), id, RefundStatus.FAILED, Instant.now());
        when(resolutions.resolve(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.eq(actor),
                org.mockito.ArgumentMatchers.eq(key), any())).thenReturn(
                        new com.nexus.commerce.service.RefundResolutionService.Result(decision, true),
                        new com.nexus.commerce.service.RefundResolutionService.Result(decision, false));
        for (int expected : new int[] {201, 200}) {
            mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected))
                    .andExpect(jsonPath("$.data.resolutionId").value(decision.resolutionId().toString()))
                    .andExpect(jsonPath("$.data.note").doesNotExist());
        }
    }

    /** Chỉ Admin duyệt dispatch; người dùng có quyền tạo đơn không được biến REQUESTED thành lệnh gửi tiền. */
    @Test
    void dispatchApprovalRequiresAdminAndUsesJwtActor() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String path = "/api/v1/refunds/" + id + "/dispatch-approval";
        String body = "{\"note\":\"Đã kiểm tra case TEST\"}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER"))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
        var response = new RefundResponse(id, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN,
                "VND", RefundStatus.REQUESTED, Instant.now());
        when(requests.approve(id, actor, "Đã kiểm tra case TEST")).thenReturn(response);
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REQUESTED"));
        org.mockito.Mockito.verify(requests).approve(id, actor, "Đã kiểm tra case TEST");
    }

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
