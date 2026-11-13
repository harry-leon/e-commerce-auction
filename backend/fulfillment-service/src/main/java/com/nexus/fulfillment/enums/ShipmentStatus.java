package com.nexus.fulfillment.enums;

import java.util.Set;

/**
 * Trạng thái nội bộ chuẩn hóa của vận đơn trong Fulfillment Service (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Chuẩn hóa các trạng thái vận chuyển từ các đối tác giao vận khác nhau (GHN, GHTK, ...)
 *   về một tập trạng thái thống nhất xuyên suốt hệ thống Project Nexus:
 *   CREATED, PENDING, PICKING, PACKED, PICKED_UP, SHIPPED, IN_TRANSIT, DELIVERED, RETURNED, FAILED, CANCELLED.
 * - Khớp với ràng buộc chk_shipment_status trong bảng shipments (Migration V8).
 *
 * Quy tắc và lý do:
 * - Nhóm 6 trạng thái chuẩn theo SRS §3.6.2 gồm: CREATED, PICKED_UP, IN_TRANSIT, DELIVERED, FAILED, RETURNED.
 * - Cung cấp helper method toStandardStatus() ánh xạ về đúng 6 trạng thái cốt lõi của SRS.
 * - Cung cấp helper method isValidTransition() để kiểm tra tính hợp lệ của luồng chuyển trạng thái,
 *   ngăn chặn nhận callback bị trễ (out-of-order delivery) ghi đè trạng thái cuối cùng (DELIVERED, RETURNED).
 */
public enum ShipmentStatus {
    /** Vận đơn vừa được tạo trên hệ thống Nexus hoặc đã đăng ký với hãng vận chuyển. */
    CREATED,

    /** Vận đơn đang chờ xử lý kho hoặc điều phối giao vận (đồng nghĩa ban đầu với CREATED). */
    PENDING,

    /** Nhân viên kho đang tiến hành lấy hàng từ kệ (Pick process). */
    PICKING,

    /** Hàng hóa đã được đóng gói hoàn tất vào kiện (Pack process). */
    PACKED,

    /** Đơn vị vận chuyển đã tiếp nhận kiện hàng từ kho (Carrier picked up). */
    PICKED_UP,

    /** Kiện hàng đã xuất kho bàn giao giao vận. */
    SHIPPED,

    /** Kiện hàng đang trên đường trung chuyển đến người nhận. */
    IN_TRANSIT,

    /** Giao hàng thành công đến tay người nhận (trạng thái kết thúc thành công). */
    DELIVERED,

    /** Giao hàng thất bại / hàng bị hoàn trả về kho xuất (trạng thái kết thúc). */
    RETURNED,

    /** Giao hàng thất bại do lỗi giao vận, hư hại hoặc mất kiện (trạng thái kết thúc). */
    FAILED,

    /** Vận đơn bị hủy bỏ trước khi giao vận thành công. */
    CANCELLED;

    private static final Set<ShipmentStatus> TERMINAL_STATUSES = Set.of(DELIVERED, RETURNED, FAILED, CANCELLED);

    /**
     * Kiểm tra trạng thái hiện tại có phải là trạng thái kết thúc (terminal state) hay không.
     * Khi đã ở trạng thái kết thúc, hệ thống sẽ bỏ qua các cập nhật trạng thái trung gian out-of-order.
     */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }

    /**
     * Chuẩn hóa trạng thái nội bộ về tập 6 trạng thái chuẩn theo SRS §3.6.2 (KH-S3-2):
     * CREATED, PICKED_UP, IN_TRANSIT, DELIVERED, FAILED, RETURNED.
     */
    public ShipmentStatus toStandardStatus() {
        return switch (this) {
            case CREATED, PENDING -> CREATED;
            case PICKING, PACKED, PICKED_UP, SHIPPED -> PICKED_UP;
            case IN_TRANSIT -> IN_TRANSIT;
            case DELIVERED -> DELIVERED;
            case FAILED -> FAILED;
            case RETURNED, CANCELLED -> RETURNED;
        };
    }

    /**
     * Kiểm tra tính hợp lệ của việc chuyển trạng thái từ trạng thái hiện tại sang trạng thái mới.
     * Quy tắc:
     * - Trạng thái giống nhau: không coi là lỗi nhưng không chuyển đổi (idempotent).
     * - Nếu hiện tại đã DELIVERED: chỉ cho phép chuyển sang RETURNED (khi khách trả hàng), cấm quay lại IN_TRANSIT/PICKING.
     * - Nếu hiện tại đã RETURNED, FAILED hoặc CANCELLED: không cho phép chuyển ngược lại trạng thái giao vận.
     */
    public boolean canTransitionTo(ShipmentStatus target) {
        if (target == null || this == target) {
            return false;
        }
        if (this == RETURNED || this == FAILED || this == CANCELLED) {
            return false;
        }
        if (this == DELIVERED) {
            return target == RETURNED;
        }
        return true;
    }
}
