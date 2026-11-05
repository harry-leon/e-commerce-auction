package com.nexus.auction.controller;

import com.nexus.auction.dto.request.CancelAuctionRequest;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.UpdateAuctionRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.service.AuctionService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import com.nexus.common.response.PaginationInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cung cấp các REST API cho quản lý phiên đấu giá (Auction Session Management).
 *
 * Chức năng:
 * - POST /api/v1/auctions: Người bán tạo phiên đấu giá mới.
 * - GET /api/v1/auctions: Xem danh sách phiên đấu giá có phân trang, lọc theo trạng thái/người bán.
 * - GET /api/v1/auctions/{auctionId}: Xem chi tiết một phiên đấu giá.
 * - PUT /api/v1/auctions/{auctionId}: Chỉnh sửa thông số phiên đấu giá trước giờ bắt đầu.
 * - POST /api/v1/auctions/{auctionId}/cancel: Hủy phiên đấu giá (Seller sở hữu hoặc Admin).
 *
 * Quy tắc và lý do:
 * - Tuân thủ định dạng phản hồi chuẩn ApiResponse v2 (nexus-common).
 * - Trích xuất sellerId trực tiếp từ JWT Subject để ngăn chặn tấn công giả mạo danh tính (IDOR).
 * - Sử dụng @PreAuthorize đảm bảo chặt chẽ quyền truy cập theo từng vai trò (ROLE_SELLER, ROLE_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/auctions")
@Tag(name = "Auction Session Management", description = "API quản lý vòng đời phiên đấu giá trực tuyến")
public class AuctionController {

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    /**
     * Tạo một phiên đấu giá mới.
     * Quy tắc phân quyền: Yêu cầu vai trò ROLE_SELLER.
     */
    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    @Operation(summary = "Tạo phiên đấu giá mới", description = "Người bán thiết lập sản phẩm, giá khởi điểm, bước giá và khung thời gian")
    public ResponseEntity<ApiResponse<AuctionResponse>> createAuction(
            @Valid @RequestBody CreateAuctionRequest request,
            Authentication authentication) {
        UUID sellerId = extractUserId(authentication);
        AuctionResponse response = auctionService.createAuction(sellerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    /**
     * Tra cứu danh sách phiên đấu giá theo các tiêu chí lọc và phân trang.
     * Quy tắc phân quyền: Public (khách vãng lai, buyer, seller đều có thể xem).
     */
    @GetMapping
    @Operation(summary = "Lấy danh sách phiên đấu giá", description = "Hỗ trợ lọc theo trạng thái, người bán, chế độ hiển thị và phân trang")
    public ResponseEntity<ApiResponse<java.util.List<AuctionResponse>>> getAuctions(
            @RequestParam(value = "status", required = false) AuctionStatus status,
            @RequestParam(value = "sellerId", required = false) UUID sellerId,
            @RequestParam(value = "visibility", required = false) AuctionVisibility visibility,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "sortBy", defaultValue = "startTime") String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "DESC") String sortDir) {

        int zeroIndexedPage = Math.max(0, page - 1);
        int validLimit = Math.max(1, Math.min(limit, 100));
        Sort sort = "ASC".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(zeroIndexedPage, validLimit, sort);

        Page<AuctionResponse> resultPage = auctionService.getAuctions(status, sellerId, visibility, pageable);

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
     * Lấy thông tin chi tiết của một phiên đấu giá theo ID.
     * Quy tắc phân quyền: Public.
     */
    @GetMapping("/{auctionId}")
    @Operation(summary = "Xem chi tiết phiên đấu giá", description = "Lấy thông số chi tiết của phiên đấu giá theo mã định danh")
    public ResponseEntity<ApiResponse<AuctionResponse>> getAuctionById(
            @PathVariable("auctionId") UUID auctionId) {
        AuctionResponse response = auctionService.getAuctionById(auctionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Cập nhật thông số phiên đấu giá trước khi bắt đầu.
     * Quy tắc phân quyền: Yêu cầu vai trò ROLE_SELLER hoặc ROLE_ADMIN.
     */
    @PutMapping("/{auctionId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    @Operation(summary = "Cập nhật phiên đấu giá trước giờ mở", description = "Chỉ cho phép khi phiên ở trạng thái SCHEDULED và chưa đến thời gian bắt đầu")
    public ResponseEntity<ApiResponse<AuctionResponse>> updateAuction(
            @PathVariable("auctionId") UUID auctionId,
            @Valid @RequestBody UpdateAuctionRequest request,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        AuctionResponse response = auctionService.updateAuction(auctionId, currentUserId, isAdmin, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Hủy phiên đấu giá theo quy định nghiệp vụ.
     * Quy tắc phân quyền: Yêu cầu vai trò ROLE_SELLER hoặc ROLE_ADMIN.
     */
    @PostMapping("/{auctionId}/cancel")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    @Operation(summary = "Hủy phiên đấu giá", description = "Người bán hoặc Quản trị viên hủy phiên trước khi kết thúc")
    public ResponseEntity<ApiResponse<AuctionResponse>> cancelAuction(
            @PathVariable("auctionId") UUID auctionId,
            @Valid @RequestBody(required = false) CancelAuctionRequest request,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        AuctionResponse response = auctionService.cancelAuction(auctionId, currentUserId, isAdmin, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
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

    /**
     * Kiểm tra xem người dùng hiện tại có vai trò chỉ định hay không.
     */
    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(role));
    }
}

