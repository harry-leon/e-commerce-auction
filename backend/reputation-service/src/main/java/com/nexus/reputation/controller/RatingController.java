package com.nexus.reputation.controller;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.DisputeRatingRequest;
import com.nexus.reputation.dto.RatingResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tiếp nhận, tra cứu và khiếu nại đánh giá hai chiều giữa Buyer và Seller sau giao dịch.
 * - POST /api/v1/ratings: bắt buộc JWT, lấy raterId từ sub của token.
 * - GET /api/v1/ratings/user/{userId}: công khai danh sách đánh giá hợp lệ (không bị khiếu nại).
 * - GET /api/v1/ratings/order/{orderId}: tra cứu các đánh giá gắn với một đơn hàng (yêu cầu đăng nhập).
 * - POST /api/v1/ratings/{ratingId}/dispute: chỉ ratee hoặc ADMIN/SUPPORT được gắn cờ khiếu nại.
 */
@RestController
@RequestMapping("/api/v1/ratings")
@Tag(name = "Ratings", description = "API đánh giá người mua và người bán sau giao dịch")
public class RatingController {

    private final ReputationService reputationService;

    public RatingController(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    /** Tạo đánh giá mới cho đối tác của một đơn hàng đã giao/hoàn tất. */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Gửi đánh giá sau giao dịch")
    public ResponseEntity<ApiResponse<RatingResponse>> createRating(
            @Valid @RequestBody CreateRatingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID raterId = parseSubject(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(reputationService.createRating(raterId, request)));
    }

    /** Tra cứu danh sách phân trang đánh giá hợp lệ mà người dùng đã nhận được. */
    @GetMapping("/user/{userId}")
    @Operation(summary = "Xem đánh giá nhận được theo userId")
    public ResponseEntity<ApiResponse<List<RatingResponse>>> getRatingsForUser(
            @PathVariable UUID userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<RatingResponse> page = reputationService.getRatingsForUser(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    /** Tra cứu các đánh giá liên quan đến một đơn hàng cụ thể. */
    @GetMapping("/order/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xem đánh giá theo orderId")
    public ResponseEntity<ApiResponse<List<RatingResponse>>> getRatingsForOrder(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.getRatingsForOrder(orderId)));
    }

    /** Gắn cờ khiếu nại đánh giá; chỉ người nhận đánh giá (rateeId) hoặc ADMIN/SUPPORT có quyền. */
    @PostMapping("/{ratingId}/dispute")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Gắn cờ khiếu nại đánh giá")
    public ResponseEntity<ApiResponse<RatingResponse>> flagDispute(
            @PathVariable UUID ratingId,
            @Valid @RequestBody(required = false) DisputeRatingRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        boolean isPrivileged = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPPORT".equals(a.getAuthority()));
        UUID actorId = parseSubject(jwt);
        return ResponseEntity.ok(ApiResponse.ok(
                reputationService.flagRatingDispute(ratingId, actorId, isPrivileged, request)));
    }

    private UUID parseSubject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "JWT không chứa user UUID hợp lệ");
        }
    }
}
