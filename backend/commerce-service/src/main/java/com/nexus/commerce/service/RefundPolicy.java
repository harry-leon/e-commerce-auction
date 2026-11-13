package com.nexus.commerce.service;

import com.nexus.commerce.enums.RefundStatus;
import java.math.BigDecimal;

/**
 * Quy tắc thuần cho hạn mức và trạng thái refund, không gọi provider hoặc ghi DB.
 * Caller tương lai phải xác minh quyền/chữ ký và khóa payment trước khi tính tổng tiền đã hoàn/đang giữ.
 * Validation này không thay transaction, unique idempotency key hoặc đối soát provider.
 */
public final class RefundPolicy {
    private RefundPolicy() { }

    /**
     * Kiểm tra tiền VND nguyên: yêu cầu mới không vượt captured - succeeded - reserved.
     * reserved phải gồm PROCESSING/UNKNOWN/REQUIRES_REVIEW và yêu cầu đã nhận chưa gửi;
     * chỉ giải phóng hạn mức khi provider xác nhận FAILED dứt điểm, không coi timeout là thất bại.
     * Dữ liệu thiếu, âm hoặc tổng không nhất quán bị từ chối trước khi tạo yêu cầu.
     */
    public static void validateAmount(BigDecimal captured, BigDecimal succeeded,
            BigDecimal reserved, BigDecimal requested) {
        requireVnd(captured);
        requireVnd(succeeded);
        requireVnd(reserved);
        requireVnd(requested);
        if (captured.signum() <= 0 || requested.signum() <= 0
                || succeeded.add(reserved).add(requested).compareTo(captured) > 0) {
            throw new IllegalArgumentException("Số tiền hoàn vượt hạn mức hoặc không hợp lệ");
        }
    }

    /**
     * Trả quyết định chuyển trạng thái sau khi caller xác thực nguồn kết quả; replay cùng trạng thái không tạo side effect.
     * FAILED nhận success muộn chỉ vào REVIEW để điều tra, không tự tạo lần hoàn mới hoặc sửa đơn đã đóng.
     * SUCCEEDED không lùi về PROCESSING/FAILED khi callback cũ đến sau.
     */
    public static boolean permits(RefundStatus from, RefundStatus to) {
        if (from == null || to == null) { return false; }
        if (from == to) { return true; }
        return switch (from) {
            case REQUESTED -> to == RefundStatus.PROCESSING || to == RefundStatus.REQUIRES_REVIEW;
            case PROCESSING -> to == RefundStatus.SUCCEEDED || to == RefundStatus.FAILED
                    || to == RefundStatus.UNKNOWN || to == RefundStatus.REQUIRES_REVIEW;
            case UNKNOWN -> to == RefundStatus.SUCCEEDED || to == RefundStatus.FAILED
                    || to == RefundStatus.REQUIRES_REVIEW;
            case FAILED -> to == RefundStatus.REQUIRES_REVIEW;
            case REQUIRES_REVIEW -> to == RefundStatus.SUCCEEDED || to == RefundStatus.FAILED;
            case SUCCEEDED -> false;
        };
    }

    /** Không làm tròn số tiền: VND âm, thiếu hoặc có phần lẻ đều là dữ liệu không hợp lệ. */
    private static void requireVnd(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Số tiền phải là VND nguyên không âm");
        }
    }
}
