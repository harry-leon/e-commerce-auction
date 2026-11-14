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
import com.nexus.commerce.dto.response.PaymentReceipt;
import com.nexus.commerce.service.PaymentReceiptService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.GlobalExceptionHandler;
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

/** Bảo vệ route tải biên nhận: chặn khách/Seller, giữ file riêng tư và không lộ entity Payment trong JSON. */
@WebMvcTest(PaymentReceiptController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PaymentReceiptControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PaymentReceiptService service;

    /** Không cho khách/Seller gọi service; quyền service account không thay thế role người dùng. */
    @Test
    void blocksAnonymousSellerAndService() throws Exception {
        String path = "/api/v1/payments/" + UUID.randomUUID() + "/receipt";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_SELLER", "COMMERCE.SHIPPING_READ"}) {
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    /** Buyer/Admin nhận file JSON no-store, tên từ UUID; DTO không chứa merchant/providerRef hoặc PII giao hàng. */
    @Test
    void downloadsPrivateDocumentForBuyerAndAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.download(eq(id), any())).thenReturn(new PaymentReceipt(1, "INTERNAL_PAYMENT_RECEIPT", "PAY-" + id,
                id, UUID.randomUUID(), Instant.parse("2026-11-13T00:00:00Z"), "VND", "VNPAY", null));
        for (String role : new String[]{"ROLE_BUYER", "ROLE_ADMIN"}) {
            mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"payment-receipt-" + id + ".json\""))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.receiptId").value("PAY-" + id))
                    .andExpect(jsonPath("$.merchantCode").doesNotExist()).andExpect(jsonPath("$.providerRef").doesNotExist())
                    .andExpect(jsonPath("$.buyerEmail").doesNotExist()).andExpect(jsonPath("$.shippingAddress").doesNotExist());
        }
    }

    /** Sai UUID trả 400; lỗi ownership/state giữ status chuẩn và không trả file thành công giả. */
    @Test
    void propagatesMissingAndNotReady() throws Exception {
        mvc.perform(get("/api/v1/payments/bad/receipt").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isBadRequest());
        for (HttpStatus code : new HttpStatus[]{HttpStatus.NOT_FOUND, HttpStatus.CONFLICT}) {
            UUID id = UUID.randomUUID();
            when(service.download(eq(id), any())).thenThrow(new ApiException("FIXTURE", code, "Không thể tải", null));
            mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                    .andExpect(status().is(code.value())).andExpect(header().doesNotExist("Content-Disposition"));
        }
    }
}
