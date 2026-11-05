package com.nexus.auction.dto.request;

import jakarta.validation.constraints.Size;

/**
 * DTO yêu cầu hủy phiên đấu giá.
 *
 * Chức năng:
 * - Tiếp nhận lý do hủy phiên đấu giá từ Seller hoặc Quản trị viên (Admin).
 *
 * Quy tắc và lý do:
 * - Lý do hủy là thông tin tùy chọn hoặc giới hạn tối đa 500 ký tự để phục vụ audit/log.
 */
public class CancelAuctionRequest {

    @Size(max = 500, message = "Lý do hủy không được vượt quá 500 ký tự")
    private String reason;

    public CancelAuctionRequest() {
    }

    public CancelAuctionRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

