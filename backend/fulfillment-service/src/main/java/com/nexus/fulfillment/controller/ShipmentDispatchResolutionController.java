package com.nexus.fulfillment.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.service.ShipmentDispatchResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API Admin xác nhận vận đơn đã tồn tại theo bằng chứng; không gọi createShipment hoặc mở lại attempt. */
@RestController
@RequestMapping("/api/v1/shipments/dispatches")
public class ShipmentDispatchResolutionController {
    private final ShipmentDispatchResolutionService resolutions;
    public ShipmentDispatchResolutionController(ShipmentDispatchResolutionService resolutions) { this.resolutions = resolutions; }

    /** Actor chỉ từ JWT, key bắt buộc; kết quả 200 là đã ghi nhận audit/khôi phục local, không phải carrier đã giao hàng. */
    @PostMapping("/{id}/manual-resolution")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Khôi phục shipment UNKNOWN từ tracking và bằng chứng carrier đã được Admin đối soát")
    public ApiResponse<ShipmentDispatchResolutionService.Result> resolve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody ShipmentDispatchResolutionService.Command body) {
        UUID actor;
        try { actor = UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
        return ApiResponse.ok(resolutions.resolve(id, actor, key, body));
    }
}
