package com.nexus.auction.controller;

import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.service.AuctionSettlementService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ tiếp nhận đồng bộ trạng thái quyết toán đấu giá từ Commerce Service (KH-S2-3).
 * Bảo vệ tại SecurityConfig bằng ServiceIdentity: bắt buộc client 'nexus-commerce-service' + 'AUCTION.SETTLEMENT_SYNC'.
 */
@RestController
@RequestMapping("/api/v1/internal/auctions")
@Tag(name = "Internal Auction Settlement Sync", description = "API nội bộ đồng bộ kết quả DEFAULTED từ Commerce")
public class AuctionSettlementInternalController {

    private final AuctionSettlementService auctionSettlementService;

    public AuctionSettlementInternalController(AuctionSettlementService auctionSettlementService) {
        this.auctionSettlementService = auctionSettlementService;
    }

    /** Payload đối chiếu đơn hàng quá hạn thanh toán do Commerce Service gửi sang. */
    public record MarkDefaultedRequest(UUID orderId) {
    }

    /** Chuyển settlement từ SUCCESS sang DEFAULTED một cách lũy nghiệm khi quá hạn thanh toán 24h. */
    @PostMapping("/{auctionId}/settlement/defaulted")
    @Operation(summary = "Đồng bộ trạng thái DEFAULTED cho phiên quá hạn thanh toán")
    public ResponseEntity<ApiResponse<AuctionSettlementResponse>> markSettlementDefaulted(
            @PathVariable("auctionId") UUID auctionId,
            @RequestBody(required = false) MarkDefaultedRequest request) {
        UUID orderId = request != null ? request.orderId() : null;
        return ResponseEntity.ok(ApiResponse.ok(auctionSettlementService.markSettlementDefaulted(auctionId, orderId)));
    }
}
