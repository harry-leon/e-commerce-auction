package com.nexus.auction.controller;

import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.service.AuctionOrderHandoverService;
import com.nexus.auction.service.AuctionSettlementService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cung cấp REST API cho hoạt động quyết toán phiên đấu giá (Settlement).
 *
 * Chức năng:
 * - POST /api/v1/auctions/{auctionId}/settle: Kích hoạt quyết toán phiên đấu giá.
 * - POST /api/v1/auctions/{auctionId}/handover: Bàn giao kết quả chốt phiên sang Commerce Service tạo đơn hàng (DoD KH-S2-2).
 * - GET /api/v1/auctions/{auctionId}/settlement: Xem kết quả quyết toán của phiên đấu giá.
 *
 * Quy tắc và lý do:
 * - Chuẩn hóa trả về bằng envelope ApiResponse theo quy chuẩn nexus-common v2.
 * - POST settle / handover cho phép ROLE_ADMIN, ROLE_SELLER hoặc token hệ thống nội bộ.
 * - GET settlement cho phép tất cả người dùng đã xác thực (Buyer, Seller, Admin).
 */
@RestController
@RequestMapping("/api/v1/auctions")
@Tag(name = "Auction Settlement", description = "API quản lý quyết toán phiên đấu giá và xác định người chiến thắng")
public class AuctionSettlementController {

    private final AuctionSettlementService auctionSettlementService;
    private final AuctionOrderHandoverService auctionOrderHandoverService;

    public AuctionSettlementController(AuctionSettlementService auctionSettlementService) {
        this(auctionSettlementService, null);
    }

    @Autowired
    public AuctionSettlementController(
            AuctionSettlementService auctionSettlementService,
            @Autowired(required = false) AuctionOrderHandoverService auctionOrderHandoverService) {
        this.auctionSettlementService = auctionSettlementService;
        this.auctionOrderHandoverService = auctionOrderHandoverService;
    }

    /**
     * Kích hoạt quyết toán một phiên đấu giá.
     * Quy tắc phân quyền: Chỉ cho phép người dùng có vai trò ADMIN hoặc SELLER. Người dùng ẩn danh bị từ chối 401.
     */
    @PostMapping("/{auctionId}/settle")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    @Operation(summary = "Kích hoạt quyết toán phiên đấu giá", description = "Khóa phiên và xác định người chiến thắng khi phiên kết thúc")
    public ResponseEntity<ApiResponse<AuctionSettlementResponse>> settleAuction(
            @PathVariable("auctionId") UUID auctionId) {
        AuctionSettlementResponse response = auctionSettlementService.settleAuction(auctionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Kích hoạt bàn giao kết quả phiên đấu giá sang Commerce Service để tạo đơn hàng (DoD KH-S2-2 / SRS §3.5.3).
     *
     * Chức năng: Tạo đơn hàng tương ứng với phiên trúng đấu giá một cách idempotent.
     * Phân quyền: Yêu cầu vai trò ADMIN hoặc SELLER.
     */
    @PostMapping("/{auctionId}/handover")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    @Operation(summary = "Bàn giao kết quả phiên thắng sang Commerce Service", description = "Khởi tạo đơn hàng idempotent cho người chiến thắng")
    public ResponseEntity<ApiResponse<AuctionSettlementResponse>> handoverAuctionOrder(
            @PathVariable("auctionId") UUID auctionId) {
        if (auctionOrderHandoverService == null) {
            throw new IllegalStateException("AuctionOrderHandoverService chưa được cấu hình");
        }
        AuctionSettlementResponse response = auctionOrderHandoverService.handoverAuctionOrder(auctionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Tra cứu thông tin kết quả quyết toán của phiên đấu giá.
     * Quy tắc phân quyền: Yêu cầu người dùng đã xác thực (BUYER, SELLER, ADMIN).
     */
    @GetMapping("/{auctionId}/settlement")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Tra cứu kết quả quyết toán", description = "Lấy thông tin người thắng cuộc và giá chốt của phiên đấu giá")
    public ResponseEntity<ApiResponse<AuctionSettlementResponse>> getSettlement(
            @PathVariable("auctionId") UUID auctionId) {
        AuctionSettlementResponse response = auctionSettlementService.getSettlementByAuctionId(auctionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
