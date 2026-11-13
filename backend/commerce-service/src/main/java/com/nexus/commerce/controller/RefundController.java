package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.RefundResponse;
import com.nexus.commerce.service.RefundRequestService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API tiếp nhận refund toàn phần; chỉ lưu REQUESTED, chưa gửi cổng hoặc xác nhận đã hoàn tiền. */
@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
public class RefundController {
    private final RefundRequestService requests;

    /** Admin tạo yêu cầu 201 hoặc replay 200; không nhận amount/status do client khai báo. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Tiếp nhận yêu cầu refund VNPAY toàn phần, chưa thực hiện hoàn tiền")
    public ResponseEntity<ApiResponse<RefundResponse>> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Request body) {
        var result = requests.create(actor(jwt), body.paymentId(), key, body.reason());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.response()));
    }

    /** Quyền Admin lấy từ Authentication đã xác minh, không nhận flag quyền từ query/body. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Tra cứu refund của chủ đơn hoặc Admin")
    public ApiResponse<RefundResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ApiResponse.ok(requests.get(id, actor(jwt), admin));
    }

    /** JWT phải chứa subject UUID; token không phù hợp không được tạo audit actor rỗng. */
    private UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
    }

    /** Lý do chỉ lưu nội bộ cho audit, không trả trong response công khai. */
    public record Request(@NotNull UUID paymentId, @NotBlank @Size(max = 500) String reason) { }
}
