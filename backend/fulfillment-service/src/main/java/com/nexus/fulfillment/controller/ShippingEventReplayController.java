package com.nexus.fulfillment.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.service.ShippingEventReplayService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** API Admin replay dead letter HTTP Fulfillment → Commerce; chỉ trả định danh audit, không trực tiếp giao sự kiện. */
@RestController
public class ShippingEventReplayController {
    private final ShippingEventReplayService replays;
    public ShippingEventReplayController(ShippingEventReplayService replays) { this.replays = replays; }
    /** Actor lấy từ JWT; key bắt buộc và lý do do service kiểm tra trước khi ghi audit/reset. */
    @PostMapping("/api/v1/shipments/events/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replay shipment event đã hết retry có audit, không gửi lại lệnh carrier")
    public ApiResponse<Map<String, UUID>> replay(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Request body) {
        UUID actor;
        try { actor = UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
        return ApiResponse.ok(Map.of("auditId", replays.replay(id, actor, key, body.reason())));
    }
    /** Chỉ lưu lý do nội bộ, không nhận actor/event payload hoặc secret từ body. */
    public record Request(String reason) { }
}
