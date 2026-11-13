package com.nexus.reputation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Yêu cầu nội bộ cập nhật thống kê giao dịch (thành công hoặc thất bại) lên bảng reputations.
 */
public record RecordTransactionRequest(
        @NotNull(message = "Mã người dùng không được để trống")
        UUID userId,
        @NotNull(message = "Kết quả giao dịch không được để trống")
        TransactionOutcome outcome,
        boolean auction
) {

    /** Phân loại kết quả hoàn tất hay thất bại của giao dịch/đấu giá. */
    public enum TransactionOutcome {
        SUCCESS,
        FAILED
    }
}
