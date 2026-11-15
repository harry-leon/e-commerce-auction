package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.response.RefundReceipt;
import com.nexus.commerce.service.RefundReceiptService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Bảo vệ route refund receipt khỏi khách/Seller/service và giữ tài liệu tài chính không cache. */
@WebMvcTest(RefundReceiptController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class RefundReceiptControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private RefundReceiptService service;

    /** Role nghiệp vụ của service không được dùng để tải biên nhận người mua. */
    @Test
    void rejectsAnonymousSellerAndService() throws Exception {
        String path = "/api/v1/refunds/" + UUID.randomUUID() + "/receipt";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_SELLER", "COMMERCE.SHIPPING_READ"}) {
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    /** Buyer/Admin tải JSON có liên kết thu gốc và nguồn đối soát, không xuất merchant hoặc bằng chứng nội bộ. */
    @Test
    void returnsPrivateLinkedDocument() throws Exception {
        UUID id = UUID.randomUUID(); UUID payment = UUID.randomUUID();
        when(service.download(eq(id), any())).thenReturn(new RefundReceipt(1, "INTERNAL_REFUND_RECEIPT", "REFUND-" + id,
                id, "PAY-" + payment, payment, UUID.randomUUID(), BigDecimal.TEN, "VND", Instant.now(), "MANUAL"));
        for (String role : new String[]{"ROLE_BUYER", "ROLE_ADMIN"}) {
            mvc.perform(get("/api/v1/refunds/" + id + "/receipt").with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"refund-receipt-" + id + ".json\""))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.originalReceiptId").value("PAY-" + payment)).andExpect(jsonPath("$.evidenceSource").value("MANUAL"))
                    .andExpect(jsonPath("$.providerRef").doesNotExist()).andExpect(jsonPath("$.merchantCode").doesNotExist())
                    .andExpect(jsonPath("$.evidenceReference").doesNotExist()).andExpect(jsonPath("$.note").doesNotExist());
        }
    }

    /** UUID sai và lỗi nghiệp vụ giữ status chuẩn; không trả attachment cho bản ghi chưa đủ bằng chứng. */
    @Test
    void preservesErrorStatus() throws Exception {
        mvc.perform(get("/api/v1/refunds/bad/receipt").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isBadRequest());
        for (HttpStatus code : new HttpStatus[]{HttpStatus.NOT_FOUND, HttpStatus.CONFLICT}) {
            UUID id = UUID.randomUUID();
            when(service.download(eq(id), any())).thenThrow(new ApiException("FIXTURE", code, "Chưa đủ bằng chứng", null));
            mvc.perform(get("/api/v1/refunds/" + id + "/receipt").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                    .andExpect(status().is(code.value())).andExpect(header().doesNotExist("Content-Disposition"));
        }
    }
}
