package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.response.PaymentResponse;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.service.PaymentService;
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

/** Xác minh IPN công khai đúng một route, response protocol VNPAY và buyer API vẫn bắt buộc JWT/privilege. */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PaymentControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PaymentService payments;

    /** Exception upstream có thể chứa hash/token; controller chỉ ghi loại lỗi, không in message/stack hoặc query IPN. */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void ipnExceptionDoesNotLogSensitivePayload(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String marker = "private-callback-hash-and-token-must-not-be-logged";
        when(payments.ipn(any())).thenThrow(new IllegalStateException(marker));
        mvc.perform(get("/api/v1/payments/vnpay/ipn").param("vnp_SecureHash", marker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("99"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains(marker));
    }

    /** Contract hết hạn vẫn trả 200 để UI đọc được trạng thái và không mở lại URL đã hết hạn. */
    @Test
    void expiredPaymentLookupReturnsExpiredStatusAndNullUrl() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(payments.get(buyer, id)).thenReturn(new PaymentResponse(id, UUID.randomUUID(), "EXPIRED",
                BigDecimal.TEN, "VND", null, Instant.now().minusSeconds(60)));
        mvc.perform(get("/api/v1/payments/" + id).with(jwt().jwt(j -> j.subject(buyer.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.paymentUrl").isEmpty());
    }

    @Test
    void ipnIsPublicAndUsesProviderProtocol() throws Exception {
        when(payments.ipn(any())).thenReturn(new VnpayIpnResponse("97", "Invalid signature"));
        mvc.perform(get("/api/v1/payments/vnpay/ipn").param("vnp_TxnRef", "forged"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"))
                .andExpect(jsonPath("$.success").doesNotExist());
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/vnpay/ipn")).andExpect(status().isUnauthorized());
    }

    /** Parameter pollution phải bị chặn trước service, không được tùy chọn một trong hai giá trị amount. */
    @Test
    void duplicateParametersNeverReachBusinessLogic() throws Exception {
        mvc.perform(get("/api/v1/payments/vnpay/ipn").param("vnp_Amount", "100", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        verify(payments, never()).ipn(any());
    }

    /** DB lỗi ở service proxy trả 99 để provider retry; không giả ACK 00 dù transaction rollback. */
    @Test
    void transactionFailureReturnsRetryCode() throws Exception {
        when(payments.ipn(any())).thenThrow(new IllegalStateException("commit failure"));
        mvc.perform(get("/api/v1/payments/vnpay/ipn")).andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("99"));
    }

    /** Tạo/poll trả envelope Nexus và validate orderId; buyer không có ORDER.CREATE bị chặn. */
    @Test
    void authenticatedBuyerCanCreateAndPollPayment() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var result = new PaymentResponse(id, UUID.randomUUID(), "PENDING", BigDecimal.TEN, "VND", "https://example.test/pay", Instant.now());
        when(payments.start(any(), any(), any())).thenReturn(result);
        when(payments.get(any(), any())).thenReturn(result);
        var auth = jwt().jwt(j -> j.subject(buyer.toString())).authorities(new SimpleGrantedAuthority("ORDER.CREATE"));
        mvc.perform(post("/api/v1/payments").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + result.orderId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentId").value(id.toString()));
        mvc.perform(get("/api/v1/payments/" + id).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(post("/api/v1/payments").with(auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/payments").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + result.orderId() + "\"}"))
                .andExpect(status().isForbidden());
    }
}
