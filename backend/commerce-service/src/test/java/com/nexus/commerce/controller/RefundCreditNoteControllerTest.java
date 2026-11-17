package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.response.RefundCreditNote;
import com.nexus.commerce.service.RefundCreditNoteService;
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

/**
 * Bảo vệ route chứng từ điều chỉnh: chỉ Admin được phát hành, Buyer/Seller/service bị chặn,
 * key idempotency bắt buộc và response không lộ ghi chú nội bộ.
 */
@WebMvcTest(RefundCreditNoteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class RefundCreditNoteControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private RefundCreditNoteService notes;
    private final UUID refund = UUID.randomUUID();

    /** Anonymous/Seller/service token không phát hành được chứng từ; Admin thiếu key trả 400. */
    @Test
    void restrictsIssuingToAdminWithIdempotencyKey() throws Exception {
        String path = "/api/v1/refunds/" + refund + "/credit-note";
        String body = "{\"note\":\"Đối soát CASE-1\"}";
        UUID key = UUID.randomUUID();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_BUYER", "ROLE_SELLER", "COMMERCE.SHIPPING_READ"}) {
            mvc.perform(post(path).with(adminOr(role)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        // Header thiếu được phát hiện khi bind tham số, trước khi vào method security.
        mvc.perform(post(path).with(adminOr("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        // Thiếu note đi qua bean validation nên trả 422.
        mvc.perform(post(path).with(adminOr("ROLE_ADMIN"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(notes);
    }

    /** Admin tạo mới trả 201 với số chứng từ ổn định; service quyết định created/replay. */
    @Test
    void issuesCreditNoteForAdmin() throws Exception {
        when(notes.issue(eq(refund), any(), any(), any(), any())).thenReturn(
                new RefundCreditNoteService.Result(note(), true));
        mvc.perform(post("/api/v1/refunds/" + refund + "/credit-note")
                        .with(adminOr("ROLE_ADMIN"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Đối soát CASE-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentNo").value("CN-" + refund))
                .andExpect(jsonPath("$.data.documentType").value("INTERNAL_REFUND_CREDIT_NOTE"))
                .andExpect(jsonPath("$.data.originalReceiptId").value("PAY-" + UUID.fromString(
                        "11111111-1111-4111-8111-111111111111")))
                // Ghi chú nội bộ không được trả ra ngoài.
                .andExpect(jsonPath("$.data.note").doesNotExist());
    }

    /** Buyer chủ đơn đọc được; Seller/service bị chặn ở tầng method security. */
    @Test
    void allowsBuyerAndAdminToRead() throws Exception {
        when(notes.get(eq(refund), any())).thenReturn(note());
        for (String role : new String[]{"ROLE_BUYER", "ROLE_ADMIN"}) {
            mvc.perform(get("/api/v1/refunds/" + refund + "/credit-note").with(adminOr(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditNoteId").exists());
        }
        mvc.perform(get("/api/v1/refunds/" + refund + "/credit-note")
                        .with(adminOr("ROLE_SELLER")))
                .andExpect(status().isForbidden());
    }

    /** JWT thử nghiệm cần subject UUID hợp lệ vì controller từ chối token không có UUID. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminOr(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private RefundCreditNote note() {
        return new RefundCreditNote(1, "INTERNAL_REFUND_CREDIT_NOTE", "CN-" + refund, UUID.randomUUID(), refund,
                UUID.fromString("11111111-1111-4111-8111-111111111111"), UUID.randomUUID(),
                "PAY-11111111-1111-4111-8111-111111111111", "REFUND-" + refund, new BigDecimal("50000"), "VND",
                "PROVIDER", Instant.parse("2026-11-13T00:00:00Z"));
    }
}
