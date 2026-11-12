package com.nexus.reputation.enums;

/**
 * Hành động đấu giá cần kiểm tra điều kiện điểm uy tín trước khi cho phép thực hiện (SRS §3.5.3).
 */
public enum EligibilityAction {
    /** Đặt giá thầu tham gia phiên đấu giá (ngưỡng tối thiểu mặc định 40). */
    BID,
    /** Tạo phiên đấu giá mới với tư cách người bán (ngưỡng tối thiểu mặc định 50). */
    CREATE_AUCTION
}
