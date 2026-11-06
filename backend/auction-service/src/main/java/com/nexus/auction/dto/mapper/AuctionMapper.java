package com.nexus.auction.dto.mapper;

import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.entity.Auction;

/**
 * Mapper chuyển đổi giữa Entity Auction và DTO AuctionResponse.
 *
 * Chức năng:
 * - Chuyển đổi an toàn dữ liệu từ thực thể JPA sang DTO trả về cho client.
 *
 * Quy tắc và lý do:
 * - Ánh xạ tường minh bằng Java thuần để kiểm soát tuyệt đối hiệu năng và tính đúng đắn, không dùng reflection.
 */
public final class AuctionMapper {

    private AuctionMapper() {
    }

    /**
     * Chuyển đổi một thực thể Auction sang AuctionResponse.
     * Đầu vào: entity Auction.
     * Kết quả: DTO AuctionResponse đầy đủ các trường, hoặc null nếu entity null.
     */
    public static AuctionResponse toResponse(Auction auction) {
        if (auction == null) {
            return null;
        }

        AuctionResponse response = new AuctionResponse();
        response.setId(auction.getId());
        response.setProductId(auction.getProductId());
        response.setSkuId(auction.getSkuId());
        response.setSellerId(auction.getSellerId());
        response.setStartPrice(auction.getStartPrice());
        response.setBidIncrement(auction.getBidIncrement());
        response.setCurrentPrice(auction.getCurrentPrice());
        response.setHighestBidderId(auction.getHighestBidderId());
        response.setVisibility(auction.getVisibility());
        response.setStatus(auction.getStatus());
        response.setStartTime(auction.getStartTime());
        response.setEndTime(auction.getEndTime());
        response.setExtensionCount(auction.getExtensionCount());
        response.setVersion(auction.getVersion());
        response.setCreatedAt(auction.getCreatedAt());

        return response;
    }
}

