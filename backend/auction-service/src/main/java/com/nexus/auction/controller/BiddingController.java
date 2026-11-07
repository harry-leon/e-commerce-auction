package com.nexus.auction.controller;

import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import com.nexus.auction.service.BiddingService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import com.nexus.common.response.PaginationInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cung cấp REST API cho quy trình đặt giá thầu và tra cứu lịch sử đặt giá (HG-S2-3).
 *
 * Chức năng:
 * - POST /api/v1/auctions/{auctionId}/bids: Đặt giá thầu cho phiên đấu giá (Bidding Engine).
 * - GET /api/v1/auctions/{auctionId}/bids: Xem lịch sử các lượt đặt giá của phiên đấu giá (Bid History).
 *
 * Quy tắc và lý do:
 * - Khớp cả 2 pattern route đã dành tại Gateway: `/api/v1/auctions/**` và frontend-web post `/auctions/{id}/bids`.
 * - Yêu cầu xác thực tài khoản có vai trò BUYER (hoặc ADMIN/SELLER nếu có tài khoản người dùng hợp lệ, nhưng Seller không được bid phiên mình).
 * - Trích xuất bidderId trực tiếp từ JWT Subject để chống IDOR và giả mạo người đặt giá.
 * - Tuân thủ định dạng ApiResponse chuẩn của Project Nexus.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
@Tag(name = "Auction Bidding Engine", description = "API đặt giá thầu thời gian thực và lịch sử đặt giá")
public class BiddingController {

    private final BiddingService biddingService;

    public BiddingController(BiddingService biddingService) {
        this.biddingService = biddingService;
    }

    /**
     * Đặt giá thầu cho phiên đấu giá.
     * Quy tắc phân quyền: Người dùng đã xác thực (yêu cầu vai trò BUYER hoặc người dùng đăng nhập hợp lệ).
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Đặt giá thầu (Place Bid)", description = "Gửi mức giá mới kèm khóa chống trùng lặp idempotencyKey")
    public ResponseEntity<ApiResponse<PlaceBidResult>> placeBid(
            @PathVariable("auctionId") UUID auctionId,
            @Valid @RequestBody PlaceBidRequest request,
            Authentication authentication) {
        UUID bidderId = extractUserId(authentication);
        PlaceBidResult result = biddingService.placeBid(auctionId, bidderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
    }

    /**
     * Tra cứu danh sách lịch sử đặt giá của phiên có phân trang.
     * Quy tắc phân quyền: Công khai (Public) hoặc Người dùng đã xác thực.
     */
    @GetMapping
    @Operation(summary = "Xem lịch sử đặt giá (Bid History)", description = "Lấy danh sách các lượt đặt giá của phiên theo thứ tự mới nhất")
    public ResponseEntity<ApiResponse<List<BidResponse>>> getBidHistory(
            @PathVariable("auctionId") UUID auctionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "sortBy", defaultValue = "placedAt") String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "DESC") String sortDir) {

        int zeroIndexedPage = Math.max(0, page - 1);
        int validLimit = Math.max(1, Math.min(limit, 100));
        Sort sort = "ASC".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(zeroIndexedPage, validLimit, sort);

        Page<BidResponse> resultPage = biddingService.getBidHistory(auctionId, pageable);

        PaginationInfo pagination = PaginationInfo.of(
                zeroIndexedPage,
                validLimit,
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.hasNext(),
                resultPage.hasPrevious()
        );

        return ResponseEntity.ok(ApiResponse.ok(resultPage.getContent(), pagination));
    }

    /**
     * Trích xuất UUID của người dùng từ SecurityContext Authentication.
     */
    private UUID extractUserId(Authentication authentication) {
        if (authentication == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "Yêu cầu đăng nhập để thực hiện thao tác");
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Định danh người dùng trong token không hợp lệ");
            }
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Định danh người dùng không hợp lệ");
        }
    }
}
