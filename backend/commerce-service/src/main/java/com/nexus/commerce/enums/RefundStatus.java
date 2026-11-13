package com.nexus.commerce.enums;

/**
 * Trạng thái nghiệp vụ refund, tách khỏi Payment/Order để không suy diễn đã hoàn tiền từ yêu cầu người dùng.
 * UNKNOWN giữ kết quả chưa rõ sau timeout; REQUIRES_REVIEW chặn gửi lại trước khi đối soát.
 * Chỉ là contract domain, chưa có endpoint, persistence hoặc adapter provider sử dụng enum này.
 */
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    REQUIRES_REVIEW
}
