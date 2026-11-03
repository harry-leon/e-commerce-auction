package com.nexus.auction.enums;

/**
 * Trạng thái vòng đời của phiên đấu giá theo thiết kế ERD và SRS §3.5.
 *
 * Chức năng:
 * - Đại diện cho các bước trong máy trạng thái đấu giá (Auction State Machine).
 * - Kiểm soát điều kiện đặt giá (chỉ ACTIVE mới nhận bid) và điều kiện quyết toán.
 */
public enum AuctionStatus {
    /** Phiên đang được Người bán soạn thảo, chưa công bố. */
    DRAFT,

    /** Phiên đã được lên lịch, chờ đến startTime để tự động mở. */
    SCHEDULED,

    /** Phiên đang mở cho Người mua tham gia đặt giá. */
    ACTIVE,

    /** Phiên đã kết thúc (hết thời gian endTime hoặc bị khóa để settlement). */
    ENDED,

    /** Phiên đã quyết toán thành công và xác định được người chiến thắng. */
    SETTLED,

    /** Phiên bị hủy bởi Người bán/Admin trước khi mở hoặc khi không hợp lệ. */
    CANCELLED
}

