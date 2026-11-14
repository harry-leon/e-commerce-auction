package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.DispatchJobReconciliationService;
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

/** GET/đối soát chỉ Admin; actor từ JWT và key bắt buộc, không lộ lease/PII hoặc đổi 409 thành 500. */
@WebMvcTest(DispatchJobReconciliationController.class)
@Import({SecurityConfig.class, ShipmentDispatchExceptionHandler.class})
@ActiveProfiles("test")
class DispatchJobReconciliationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DispatchJobReconciliationService jobs;

    /** Buyer/service privilege không được đọc trạng thái vận hành hoặc tạo audit đối soát. */
    @Test
    void onlyAdminCanReadOrReconcile() throws Exception {
        String path = path(UUID.randomUUID());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_BUYER", "SHIPPING.ORDER_PAID"}) {
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
            mvc.perform(post(path + "/reconcile").with(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(jobs);
    }

    /** Audit 201/replay 200 giữ định danh; metadata GET không trả token lease, recipient hoặc raw evidence. */
    @Test
    void returnsStableAuditAndSafeMetadata() throws Exception {
        UUID id = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID(); UUID audit = UUID.randomUUID();
        var auth = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var resolution = new DispatchJobReconciliationService.Resolution(audit, id, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        when(jobs.reconcile(eq(id), eq(actor), eq(key), any())).thenReturn(new DispatchJobReconciliationService.Result(resolution, true),
                new DispatchJobReconciliationService.Result(resolution, false));
        for (int expected : new int[]{201, 200}) {
            mvc.perform(post(path(id) + "/reconcile").with(auth).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"evidenceReference\":\"CASE\",\"note\":\"Fixture\",\"actorId\":\"ignored\"}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.data.auditId").value(audit.toString()))
                    .andExpect(jsonPath("$.data.note").doesNotExist()).andExpect(jsonPath("$.data.evidenceReference").doesNotExist());
        }
        when(jobs.get(id)).thenReturn(new DispatchJobReconciliationService.Job(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "REQUIRES_REVIEW", null, 1, "Fault", Instant.now()));
        mvc.perform(get(path(id)).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist()).andExpect(jsonPath("$.data.recipientPhone").doesNotExist());
    }

    /** Key/subject sai dừng trước service; lỗi nghiệp vụ vẫn giữ 409 và error code chuẩn. */
    @Test
    void validatesActorKeyAndPreservesConflict() throws Exception {
        UUID id = UUID.randomUUID(); UUID actor = UUID.randomUUID();
        var auth = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(post(path(id) + "/reconcile").with(auth).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(path(id)).with(jwt().jwt(j -> j.subject("bad")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jobs);
        when(jobs.reconcile(eq(id), eq(actor), any(), any())).thenThrow(new ApiException("JOB_CONFLICT", HttpStatus.CONFLICT, "Fixture", null));
        mvc.perform(post(path(id) + "/reconcile").with(auth).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_CONFLICT"));
    }

    private String path(UUID id) { return "/api/v1/shipments/dispatch-jobs/" + id; }
}
