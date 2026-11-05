package com.nexus.auction.service;

import com.nexus.auction.dto.request.CancelAuctionRequest;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.UpdateAuctionRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Interface dịch vụ quản lý phiên đấu giá (Auction Session Management).
 *
 * Chức năng:
 * - Cung cấp các nghiệp vụ tạo phiên đấu giá, xem danh sách có phân trang và bộ lọc,
 *   xem chi tiết phiên, cập nhật thông số trước giờ mở và hủy phiên.
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5, Epic 1 (US-AUC-01) và các ràng buộc toàn vẹn dữ liệu.
 */
public interface AuctionService {

    /**
     * Người bán tạo một phiên đấu giá mới.
     * Đầu vào: sellerId (trích xuất từ JWT principal), payload CreateAuctionRequest.
     * Kết quả: DTO AuctionResponse ở trạng thái SCHEDULED.
     */
    AuctionResponse createAuction(UUID sellerId, CreateAuctionRequest request);

    /**
     * Tra cứu danh sách phiên đấu giá theo các tiêu chí lọc và phân trang.
     * Đầu vào: status, sellerId, visibility, pageable.
     * Kết quả: Trang kết quả Page<AuctionResponse>.
     */
    Page<AuctionResponse> getAuctions(AuctionStatus status, UUID sellerId, AuctionVisibility visibility, Pageable pageable);

    /**
     * Lấy thông tin chi tiết của một phiên đấu giá theo ID.
     * Đầu vào: auctionId.
     * Kết quả: DTO AuctionResponse hoặc ném ResourceNotFoundException nếu không tìm thấy.
     */
    AuctionResponse getAuctionById(UUID auctionId);

    /**
     * Cập nhật thông số phiên đấu giá trước giờ bắt đầu.
     * Đầu vào: auctionId, currentUserId, isAdmin, payload UpdateAuctionRequest.
     * Kết quả: DTO AuctionResponse đã được cập nhật.
     */
    AuctionResponse updateAuction(UUID auctionId, UUID currentUserId, boolean isAdmin, UpdateAuctionRequest request);

    /**
     * Hủy phiên đấu giá theo quy định nghiệp vụ.
     * Đầu vào: auctionId, currentUserId, isAdmin, payload CancelAuctionRequest.
     * Kết quả: DTO AuctionResponse ở trạng thái CANCELLED.
     */
    AuctionResponse cancelAuction(UUID auctionId, UUID currentUserId, boolean isAdmin, CancelAuctionRequest request);
}

