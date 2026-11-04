package com.nexus.auction.enums;

/**
 * Kết quả quyết toán của phiên đấu giá theo schema bảng auction_settlements.
 *
 * Chức năng:
 * - SUCCESS: Có người thắng cuộc với mức giá hợp lệ cao nhất.
 * - FAILED: Không có lượt bid hợp lệ nào khi phiên kết thúc.
 * - CANCELLED: Phiên bị hủy bỏ trước hoặc trong quá trình đấu giá.
 * - DEFAULTED: Người thắng cuộc không hoàn tất thanh toán trước hạn định.
 */
public enum SettlementResult {
    SUCCESS,
    FAILED,
    CANCELLED,
    DEFAULTED
}

