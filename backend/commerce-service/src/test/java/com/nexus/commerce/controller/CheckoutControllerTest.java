package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.response.CheckoutPreviewResponse;
import com.nexus.commerce.dto.response.CreateOrderResponse;
import com.nexus.commerce.service.CheckoutService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.List;
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

/** HTTP contract bảo vệ quyền ORDER.CREATE/CHECKOUT.START, validation và envelope v2; buyer chỉ lấy từ JWT. */
@WebMvcTest(CheckoutController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class CheckoutControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CheckoutService checkout;
    private final UUID buyer = UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();
    private final String body = """
            {"shippingAddress":"123 Lê Lợi","recipientName":"Người nhận","recipientPhone":"0901234567","paymentMethod":"COD"}
            """;

    /** Request giả header user không thể đổi chủ giỏ; trả 201 và envelope chuẩn khi đủ quyền. */
    @Test
    void createsWithJwtOwnerAndForwardsBearerToken() throws Exception {
        var result = new CreateOrderResponse(UUID.randomUUID(), "ORD-1", "PENDING_PAYMENT", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "COD");
        // Controller truyền IP peer để tạo URL VNPAY; buyer/token vẫn lấy từ JWT đã xác minh.
        when(checkout.create(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.isNull())).thenReturn(result);
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(j -> j.subject(buyer.toString()))
                        .authorities(new SimpleGrantedAuthority("ORDER.CREATE")))
                        .header("X-User-Id", UUID.randomUUID()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(result.id().toString()))
                .andExpect(jsonPath("$.error").isEmpty()).andExpect(jsonPath("$.meta.timestamp").exists());
        verify(checkout).create(eq(buyer), eq(key), any(), eq("token"), eq("127.0.0.1"), org.mockito.ArgumentMatchers.isNull());
    }

    /** Chưa xác thực/không có privilege bị chặn trước khi chạy checkout. */
    @Test
    void rejectsAnonymousAndMissingPrivilege() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").with(jwt()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(checkout);
    }

    /** Thiếu key trả 400; địa chỉ trống/UUID item sai trả lỗi dữ liệu trước khi gọi nghiệp vụ. */
    @Test
    void validatesBodyAndRequiredHeader() throws Exception {
        var auth = jwt().jwt(j -> j.subject(buyer.toString())).authorities(new SimpleGrantedAuthority("ORDER.CREATE"));
        mvc.perform(post("/api/v1/orders").with(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_HEADER"));
        mvc.perform(post("/api/v1/orders").with(auth).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("123 Lê Lợi", "")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/orders").with(auth).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"COD\"", "\"COD\",\"selectedItemIds\":[\"invalid\"]")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(checkout);
    }

    /** Thiếu hàng phải trả 409 với mã rõ ràng, không bọc thành success hoặc lỗi 500. */
    @Test
    void returnsInsufficientStockAsConflict() throws Exception {
        when(checkout.create(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.isNull())).thenThrow(new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT));
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(j -> j.subject(buyer.toString()))
                        .authorities(new SimpleGrantedAuthority("ORDER.CREATE"))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVENTORY_INSUFFICIENT"));
    }

    /** Preview cần privilege riêng và trả giá server; subject JWT sai định dạng phải báo 401. */
    @Test
    void previewUsesCheckoutPrivilegeAndRejectsMalformedSubject() throws Exception {
        when(checkout.preview(any(), any())).thenReturn(new CheckoutPreviewResponse(BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null, List.of(), true, List.of()));
        mvc.perform(post("/api/v1/orders/checkout/preview").with(jwt().jwt(j -> j.subject(buyer.toString()))
                        .authorities(new SimpleGrantedAuthority("CHECKOUT.START")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shippingAddress\":\"123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOrderable").value(true));
        mvc.perform(post("/api/v1/orders").with(jwt().jwt(j -> j.subject("not-uuid"))
                        .authorities(new SimpleGrantedAuthority("ORDER.CREATE"))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
