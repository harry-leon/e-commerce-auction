package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.commerce.enums.RefundStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Bảo vệ hạn mức khi refund cũ chưa rõ kết quả và callback đến muộn; không dùng provider/DB giả làm tiền thật. */
class RefundPolicyTest {
    /** Giữ tiền cho yêu cầu đang xử lý: không cấp lại hạn mức chỉ vì caller chưa nhận được ACK. */
    @Test
    void reservesUncertainFundsAndAllowsExactRemainingAmount() {
        assertDoesNotThrow(() -> RefundPolicy.validateAmount(
                new BigDecimal("100000"), new BigDecimal("20000"), new BigDecimal("50000"), new BigDecimal("30000")));
        assertThrows(IllegalArgumentException.class, () -> RefundPolicy.validateAmount(
                new BigDecimal("100000"), new BigDecimal("20000"), new BigDecimal("50000"), new BigDecimal("30001")));
    }

    /** Chặn tiền lẻ/âm/thiếu và yêu cầu 0 trước khi caller tạo bản ghi hoặc gửi cổng. */
    @Test
    void rejectsInvalidVndAndInconsistentTotals() {
        for (BigDecimal invalid : new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("0.1")}) {
            assertThrows(IllegalArgumentException.class, () -> RefundPolicy.validateAmount(
                    new BigDecimal("100"), BigDecimal.ZERO, invalid, BigDecimal.ONE));
        }
        assertThrows(IllegalArgumentException.class, () -> RefundPolicy.validateAmount(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RefundPolicy.validateAmount(
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE));
    }

    /** Timeout không mở lại đường gửi mới; chỉ kết quả đã xác minh mới đóng yêu cầu hoặc chuyển đối soát. */
    @Test
    void uncertainResultRequiresReconciliationBeforeAnyNewSubmission() {
        assertTrue(RefundPolicy.permits(RefundStatus.PROCESSING, RefundStatus.UNKNOWN));
        assertFalse(RefundPolicy.permits(RefundStatus.UNKNOWN, RefundStatus.PROCESSING));
        assertFalse(RefundPolicy.permits(RefundStatus.UNKNOWN, RefundStatus.REQUESTED));
        assertTrue(RefundPolicy.permits(RefundStatus.UNKNOWN, RefundStatus.SUCCEEDED));
        assertTrue(RefundPolicy.permits(RefundStatus.FAILED, RefundStatus.REQUIRES_REVIEW));
        assertFalse(RefundPolicy.permits(RefundStatus.FAILED, RefundStatus.SUCCEEDED));
    }

    /** Callback lặp có thể ACK, callback cũ không đảo trạng thái đã hoàn tiền thành công. */
    @Test
    void successfulRefundIsMonotonicAndDuplicateIsHarmless() {
        for (RefundStatus next : RefundStatus.values()) {
            if (next == RefundStatus.SUCCEEDED) {
                assertTrue(RefundPolicy.permits(RefundStatus.SUCCEEDED, next));
            } else {
                assertFalse(RefundPolicy.permits(RefundStatus.SUCCEEDED, next));
            }
        }
        assertFalse(RefundPolicy.permits(null, RefundStatus.REQUESTED));
        assertFalse(RefundPolicy.permits(RefundStatus.REQUESTED, RefundStatus.SUCCEEDED));
    }
}
