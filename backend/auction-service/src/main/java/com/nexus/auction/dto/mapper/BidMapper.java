package com.nexus.auction.dto.mapper;

import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.entity.Bid;
import java.util.UUID;

/**
 * Lớp mapper chuyển đổi giữa entity Bid và DTO BidResponse.
 *
 * Chức năng:
 * - Ánh xạ các trường id, auctionId, bidderId, amount, idempotencyKey, placedAt.
 * - Che danh tính người đặt giá (masking) theo định dạng an toàn bảo mật.
 */
public final class BidMapper {

    private BidMapper() {
    }

    /**
     * Chuyển đổi entity Bid thành BidResponse.
     */
    public static BidResponse toResponse(Bid bid) {
        if (bid == null) {
            return null;
        }
        BidResponse response = new BidResponse();
        response.setId(bid.getId());
        response.setAuctionId(bid.getAuctionId());
        response.setBidderId(bid.getBidderId());
        response.setBidderMask(maskIdentifier(bid.getBidderId()));
        response.setAmount(bid.getAmount());
        response.setIdempotencyKey(bid.getIdempotencyKey());
        response.setPlacedAt(bid.getPlacedAt());
        return response;
    }

    /**
     * Tạo chuỗi mask từ UUID hoặc chuỗi tên người dùng.
     * Quy tắc: lấy 4 ký tự đầu, nối với "***" và 4 ký tự cuối (ví dụ: "9b1d***cb6d").
     */
    public static String maskIdentifier(UUID id) {
        if (id == null) {
            return "Ẩn danh";
        }
        String str = id.toString();
        if (str.length() <= 8) {
            return str.charAt(0) + "***" + str.charAt(str.length() - 1);
        }
        return str.substring(0, 4) + "***" + str.substring(str.length() - 4);
    }
}

