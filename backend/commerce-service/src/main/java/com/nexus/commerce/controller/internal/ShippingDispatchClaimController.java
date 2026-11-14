package com.nexus.commerce.controller.internal;

import com.nexus.commerce.service.ShippingDispatchClaimService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Chỉ Fulfillment được giữ quyền dispatch; không nhận Admin/browser thay cho service identity/privilege riêng. */
@RestController
@RequiredArgsConstructor
public class ShippingDispatchClaimController {
    private final ShippingDispatchClaimService claims;

    /** Service Fulfillment tra handoff theo Order; không mở namespace này qua gateway. */
    @GetMapping("/api/v1/internal/commerce/shipping-handoffs/{order}")
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')")
    @Operation(summary = "Tra handoff sau tiền/kho; 404 nếu chưa có, không phải quyền dispatch")
    public ApiResponse<ShippingDispatchClaimService.Handoff> handoff(@PathVariable UUID order) {
        return ApiResponse.ok(claims.handoff(order));
    }

    /** Kiểm tra lại điều kiện tài chính và trả snapshot sau claim, không dùng replay cũ làm quyền gửi mới. */
    @PostMapping("/api/v1/internal/commerce/shipping-dispatch-claims/{id}/preflight")
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')"
            + " and T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_READ')")
    @Operation(summary = "Preflight claim với caller gốc; READ + DISPATCH, trả snapshot, 409 khi review/state thay đổi")
    public ApiResponse<ShippingDispatchClaimService.Authorization> preflight(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(claims.preflight(caller(jwt), id));
    }

    /** Caller từ JWT subject UUID; 201 mới/200 replay, chưa gọi carrier, lỗi giữ ApiError chung. */
    @PostMapping("/api/v1/internal/commerce/shipping-dispatch-claims")
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')")
    @Operation(summary = "Fulfillment claim handoff/kiện tuần tự với tạo refund",
            description = "Key UUID bắt buộc; 409 điều kiện thay đổi, 503 mặc định tắt. Replay không cho phép gửi carrier lần nữa.")
    public ResponseEntity<ApiResponse<ShippingDispatchClaimService.Claim>> claim(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody ShippingDispatchClaimService.Command body) {
        var result = claims.claim(caller(jwt), key, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(result.claim()));
    }

    /** JWT không có service UUID phải dừng trước DB; không nhận caller do client khai báo. */
    private UUID caller(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu service UUID hợp lệ", null);
        }
    }
}
