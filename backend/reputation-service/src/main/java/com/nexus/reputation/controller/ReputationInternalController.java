package com.nexus.reputation.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.service.ReputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ tiếp nhận yêu cầu trừ điểm uy tín từ Commerce Service (ví dụ quá hạn thanh toán đấu giá 24h).
 * Bảo vệ tại SecurityConfig bằng ServiceIdentity: bắt buộc client 'nexus-commerce-service' + quyền 'REPUTATION.PENALTY'.
 */
@RestController
@RequestMapping("/api/v1/internal/reputations")
@Tag(name = "Internal Reputation", description = "API nội bộ ghi nhận trừ điểm uy tín idempotent")
public class ReputationInternalController {

    private final ReputationService reputationService;

    public ReputationInternalController(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    /** Ghi nhận xử phạt vi phạm uy tín một cách lũy nghiệm theo (userId, refType, refId). */
    @PostMapping("/penalties")
    @Operation(summary = "Áp dụng trừ điểm uy tín nội bộ")
    public ResponseEntity<ApiResponse<PenaltyResponse>> applyPenalty(
            @Valid @RequestBody ApplyPenaltyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.applyPenalty(request)));
    }
}
