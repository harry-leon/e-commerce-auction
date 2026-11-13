package com.nexus.reputation.controller;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import com.nexus.reputation.dto.AdminAdjustReputationRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.dto.ResolveDisputeRequest;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.service.ReputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tra cứu điểm uy tín, điều kiện đấu giá, lịch sử xử phạt và kiểm duyệt Admin/Support.
 */
@RestController
@RequestMapping("/api/v1/reputations")
@Tag(name = "Reputation", description = "API tra cứu điểm uy tín, điều kiện đấu giá và kiểm duyệt Admin")
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

    /** Kiểm tra người dùng có đủ điểm uy tín tối thiểu để tham gia đấu giá hoặc mở phiên không. */
    @GetMapping("/{userId}/eligibility")
    @Operation(summary = "Kiểm tra điều kiện điểm uy tín cho đấu giá")
    public ResponseEntity<ApiResponse<ReputationEligibilityResponse>> checkEligibility(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "BID") EligibilityAction action) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.checkEligibility(userId, action)));
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

    /** Admin/Support tra cứu danh sách hồ sơ uy tín phân trang, hỗ trợ lọc theo trustLevel. */
    @GetMapping("/admin/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "[Admin/Support] Tra cứu danh sách điểm uy tín")
    public ResponseEntity<ApiResponse<List<ReputationSummaryResponse>>> adminListReputations(
            @RequestParam(required = false) TrustLevel trustLevel,
            @PageableDefault(size = 20, sort = "score", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.adminGetReputations(trustLevel, pageable)));
    }

    /** Admin/Support tra cứu danh sách đánh giá (lọc các bản ghi đang bị khiếu nại hoặc theo rateeId). */
    @GetMapping("/admin/ratings")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "[Admin/Support] Tra cứu đánh giá và khiếu nại")
    public ResponseEntity<ApiResponse<List<RatingResponse>>> adminListRatings(
            @RequestParam(required = false) Boolean disputedOnly,
            @RequestParam(required = false) UUID rateeId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.adminGetRatings(disputedOnly, rateeId, pageable)));
    }

    /** Admin/Support tra cứu toàn bộ nhật ký xử phạt và điều chỉnh điểm thủ công. */
    @GetMapping("/admin/penalties")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "[Admin/Support] Tra cứu nhật ký xử phạt và điều chỉnh điểm")
    public ResponseEntity<ApiResponse<List<PenaltyResponse>>> adminListPenalties(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) PenaltyRefType refType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.adminGetPenalties(userId, refType, pageable)));
    }

    /** Chỉ Admin mới được điều chỉnh điểm uy tín thủ công kèm lý do kiểm toán. */
    @PostMapping("/admin/adjustments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Điều chỉnh điểm uy tín thủ công")
    public ResponseEntity<ApiResponse<PenaltyResponse>> adminAdjustReputation(
            @Valid @RequestBody AdminAdjustReputationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = parseSubject(jwt);
        return ResponseEntity.ok(ApiResponse.ok(reputationService.adminAdjustReputation(adminId, request)));
    }

    /** Chỉ Admin mới được phán quyết khiếu nại đánh giá (DISMISS hoặc INVALIDATE). */
    @PostMapping("/admin/ratings/{ratingId}/dispute/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Xử lý khiếu nại đánh giá")
    public ResponseEntity<ApiResponse<RatingResponse>> resolveDispute(
            @PathVariable UUID ratingId,
            @Valid @RequestBody ResolveDisputeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID adminId = parseSubject(jwt);
        return ResponseEntity.ok(ApiResponse.ok(reputationService.resolveRatingDispute(ratingId, adminId, request)));
    }

    private UUID parseSubject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "JWT không chứa user UUID hợp lệ");
        }
    }
}
