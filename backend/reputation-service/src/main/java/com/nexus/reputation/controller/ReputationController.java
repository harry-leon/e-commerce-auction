package com.nexus.reputation.controller;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.service.ReputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tra cứu điểm uy tín và lịch sử xử phạt cho người dùng và quản trị viên.
 * Quy tắc:
 * - GET /api/v1/reputations/{userId}: công khai tóm tắt uy tín (phục vụ hiển thị shop/uy tín).
 * - GET /api/v1/reputations/me: tra cứu hồ sơ uy tín của chính tài khoản đang đăng nhập.
 * - GET /api/v1/reputations/{userId}/penalties: chỉ chính chủ hoặc ADMIN/SUPPORT mới được xem biên bản phạt.
 */
@RestController
@RequestMapping("/api/v1/reputations")
@Tag(name = "Reputation", description = "API tra cứu điểm uy tín và lịch sử xử phạt")
public class ReputationController {

    private final ReputationService reputationService;

    public ReputationController(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    /** Tra cứu điểm uy tín cá nhân dựa trên subject UUID từ JWT đã xác thực. */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xem điểm uy tín của tôi")
    public ResponseEntity<ApiResponse<ReputationSummaryResponse>> getMyReputation(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = parseSubject(jwt);
        return ResponseEntity.ok(ApiResponse.ok(reputationService.getReputation(userId)));
    }

    /** Tra cứu tóm tắt uy tín công khai của một tài khoản theo userId. */
    @GetMapping("/{userId}")
    @Operation(summary = "Xem điểm uy tín theo userId")
    public ResponseEntity<ApiResponse<ReputationSummaryResponse>> getReputation(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.getReputation(userId)));
    }

    /** Tra cứu lịch sử biên bản trừ điểm uy tín của chính chủ hoặc ADMIN/SUPPORT. */
    @GetMapping("/{userId}/penalties")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xem lịch sử biên bản phạt uy tín")
    public ResponseEntity<ApiResponse<List<PenaltyResponse>>> getPenalties(
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        boolean isPrivileged = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPPORT".equals(a.getAuthority()));
        if (!isPrivileged && !parseSubject(jwt).equals(userId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền xem lịch sử xử phạt của tài khoản khác");
        }
        Page<PenaltyResponse> page = reputationService.getPenalties(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page.getContent()));
    }

    private UUID parseSubject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "JWT không chứa user UUID hợp lệ");
        }
    }
}
