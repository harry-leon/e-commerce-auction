package com.nexus.auction.dto.mapper;

import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.entity.AuctionSettlement;

/**
 * Mapper chuyển đổi giữa entity AuctionSettlement và DTO AuctionSettlementResponse.
 *
 * Chức năng:
 * - Tách biệt lớp lưu trữ cơ sở dữ liệu với dữ liệu giao tiếp API.
 */
public final class AuctionSettlementMapper {

    private AuctionSettlementMapper() {
    }

    /**
     * Ánh xạ từ thực thể cơ sở dữ liệu sang DTO phản hồi.
     */
    public static AuctionSettlementResponse toResponse(AuctionSettlement entity) {
        if (entity == null) {
            return null;
        }
        AuctionSettlementResponse response = new AuctionSettlementResponse();
        response.setId(entity.getId());
        response.setAuctionId(entity.getAuctionId());
        response.setWinnerId(entity.getWinnerId());
        response.setFinalPrice(entity.getFinalPrice());
        response.setResult(entity.getResult());
        response.setOrderId(entity.getOrderId());
        response.setPaymentDeadline(entity.getPaymentDeadline());
        response.setSettledAt(entity.getSettledAt());
        return response;
    }
}

