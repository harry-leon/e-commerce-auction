package com.nexus.commerce.controller;

import com.nexus.commerce.dto.request.CheckoutPreviewRequest;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.CheckoutPreviewResponse;
import com.nexus.commerce.dto.response.CreateOrderResponse;
import com.nexus.commerce.service.CheckoutService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API checkout lấy buyer từ JWT, validate DTO rồi gọi service; không tin X-User-Id/giá do client gửi. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Checkout & Orders")
public class OrderController {
    private final CheckoutService checkout;

    /** Preview chỉ dành quyền CHECKOUT.START, không reserve kho. */
    @PostMapping("/checkout/preview")
    @PreAuthorize("hasAuthority('CHECKOUT.START')")
    @Operation(summary = "Kiểm tra giỏ hàng, giá live và tồn kho trước checkout")
    public ApiResponse<CheckoutPreviewResponse> preview(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody CheckoutPreviewRequest request) {
        return ApiResponse.ok(checkout.preview(buyer(jwt), request));
    }

    /** Tạo/replay đơn từ giỏ; chuyển tiếp token tới Fulfillment để reserve/release có xác thực. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER.CREATE')")
    @Operation(summary = "Tạo đơn COD từ giỏ hàng, chống trùng bằng Idempotency-Key UUID")
    public ApiResponse<CreateOrderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.created(checkout.create(buyer(jwt), key, request, jwt.getTokenValue()));
    }

    /** JWT hợp lệ vẫn cần subject UUID của Nexus; token sai định dạng trả 401 thay vì lỗi máy chủ. */
    private UUID buyer(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT không có user UUID hợp lệ", null);
        }
    }
}
