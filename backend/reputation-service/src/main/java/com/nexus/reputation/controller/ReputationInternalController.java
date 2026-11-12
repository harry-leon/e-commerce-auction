package com.nexus.reputation.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.service.ReputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ phục vụ giao tiếp service-to-service với Commerce và Auction Service.
 * Bảo vệ tại SecurityConfig bằng ServiceIdentity theo từng đường dẫn.
 */
@RestController
@RequestMapping("/api/v1/internal/reputations")
@Tag(name = "Internal Reputation", description = "API nội bộ trừ điểm, thống kê giao dịch và kiểm tra điều kiện đấu giá")
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

    /** Kiểm tra nội bộ điều kiện uy tín của buyer/seller khi tạo phiên hoặc đặt giá tại auction-service. */
    @GetMapping("/{userId}/eligibility")
    @Operation(summary = "Kiểm tra điều kiện uy tín nội bộ")
    public ResponseEntity<ApiResponse<ReputationEligibilityResponse>> checkEligibilityInternal(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "BID") EligibilityAction action) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.checkEligibility(userId, action)));
    }

    /** Cập nhật thống kê giao dịch thành công/thất bại cho hồ sơ uy tín. */
    @PostMapping("/transactions")
    @Operation(summary = "Cập nhật thống kê giao dịch uy tín nội bộ")
    public ResponseEntity<ApiResponse<ReputationSummaryResponse>> recordTransactionOutcome(
            @Valid @RequestBody RecordTransactionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(reputationService.recordTransactionOutcome(request)));
    }
}
