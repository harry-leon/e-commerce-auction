package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.service.RefundedOrderFinalizationService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Route Admin riêng không cho Buyer/Seller/service chốt trạng thái tài chính bằng request trực tiếp. */
@WebMvcTest(RefundedOrderFinalizationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class RefundedOrderFinalizationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private RefundedOrderFinalizationService service;

    /** Không token/sai role bị chặn trước service; role service không thay thế quyền Admin. */
    @Test
    void rejectsUnauthorizedActors() throws Exception {
        String url = "/api/v1/refunds/" + UUID.randomUUID() + "/finalize-order";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Verified\"}"))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_BUYER", "ROLE_SELLER", "COMMERCE.SHIPPING_DISPATCH"}) {
            mvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Verified\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    /** 201 mới và 200 replay trả cùng audit, không xuất ghi chú nội bộ hoặc giả kết quả hoàn kho. */
    @Test
    void returnsCreatedOrReplayDecision() throws Exception {
        UUID id = UUID.randomUUID(); UUID key = UUID.randomUUID();
        var decision = new RefundedOrderFinalizationService.Decision(UUID.randomUUID(), UUID.randomUUID(), id, "REFUNDED", Instant.now());
        when(service.finalizeRefund(eq(id), eq(key), eq("Verified"), any()))
                .thenReturn(new RefundedOrderFinalizationService.Result(decision, true), new RefundedOrderFinalizationService.Result(decision, false));
        for (int expected : new int[]{201, 200}) {
            mvc.perform(post("/api/v1/refunds/" + id + "/finalize-order").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Verified\"}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.data.auditId").value(decision.auditId().toString()))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED")).andExpect(jsonPath("$.data.note").doesNotExist());
        }
    }

    /** Key thiếu/sai trả 400, cổng tắt trả 503 theo envelope; không biến lỗi nghiệp vụ thành 200. */
    @Test
    void preservesValidationAndDisabledStatus() throws Exception {
        String url = "/api/v1/refunds/" + UUID.randomUUID() + "/finalize-order";
        mvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Verified\"}"))
                .andExpect(status().isBadRequest());
        when(service.finalizeRefund(any(), any(), any(), any())).thenThrow(new ApiException("REFUND_FINALIZATION_DISABLED",
                HttpStatus.SERVICE_UNAVAILABLE, "Disabled", null));
        mvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Verified\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("REFUND_FINALIZATION_DISABLED"));
    }
}
