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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Chỉ Fulfillment được giữ quyền dispatch; không nhận Admin/browser thay cho service identity/privilege riêng. */
@RestController
@RequiredArgsConstructor
public class ShippingDispatchClaimController {
    private final ShippingDispatchClaimService claims;

    /** Caller từ JWT subject UUID; 201 mới/200 replay, chưa gọi carrier, lỗi giữ ApiError chung. */
    @PostMapping("/api/v1/internal/commerce/shipping-dispatch-claims")
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')")
    @Operation(summary = "Fulfillment claim handoff/kiện tuần tự với tạo refund",
            description = "Key UUID bắt buộc; 409 điều kiện thay đổi, 503 mặc định tắt. Replay không cho phép gửi carrier lần nữa.")
    public ResponseEntity<ApiResponse<ShippingDispatchClaimService.Claim>> claim(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody ShippingDispatchClaimService.Command body) {
        UUID caller;
        try { caller = UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu service UUID hợp lệ", null);
        }
        var result = claims.claim(caller, key, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(result.claim()));
    }
}
