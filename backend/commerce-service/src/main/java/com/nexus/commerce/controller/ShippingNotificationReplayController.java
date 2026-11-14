package com.nexus.commerce.controller;

import com.nexus.commerce.service.ShippingNotificationReplayService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Admin replay inbox/push Shipping bằng audit nguyên tử; endpoint không gọi Notification hoặc carrier trực tiếp. */
@RestController
@RequiredArgsConstructor
public class ShippingNotificationReplayController {
    private final ShippingNotificationReplayService replays;
    /** Actor từ JWT, key bắt buộc; trả auditId chỉ xác nhận đã đưa vào hàng đợi, chưa khẳng định đã giao thông báo. */
    @PostMapping("/api/v1/orders/shipping-notifications/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replay Shipping notification DEAD_LETTER có audit, giữ nguyên eventId/payload")
    public ApiResponse<Result> replay(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Request body) {
        UUID actor;
        try { actor = UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
        return ApiResponse.ok(new Result(replays.replay(id, actor, key, body.reason())));
    }
    /** Lý do bắt buộc được service kiểm tra, lưu nội bộ; không nhập secret/token. */
    public record Request(String reason) { }
    /** Định danh audit ổn định qua retry cùng key/body. */
    public record Result(UUID auditId) { }
}
