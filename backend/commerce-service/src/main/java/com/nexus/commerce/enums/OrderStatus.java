package com.nexus.commerce.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Định nghĩa trạng thái vòng đời của đơn hàng trong Project Nexus.
 *
 * <p>Quy chuẩn theo ràng buộc cơ sở dữ liệu `chk_order_status` trong `nexus_schema.sql`
 * và máy trạng thái đơn hàng (Order State Machine) quy định tại `API_SPEC_CART_ORDER.md` §6.
 *
 * <p>Luồng chuyển đổi trạng thái hợp lệ:
 * <ul>
 *   <li>PENDING_PAYMENT -> PAID, CANCELLED</li>
 *   <li>PAID -> PROCESSING, REFUNDED</li>
 *   <li>PROCESSING -> SHIPPED, CANCELLED</li>
 *   <li>SHIPPED -> DELIVERED, CANCELLED</li>
 *   <li>DELIVERED -> COMPLETED, REFUNDED</li>
 *   <li>COMPLETED -> (kết thúc)</li>
 *   <li>CANCELLED -> (kết thúc)</li>
 *   <li>REFUNDED -> (kết thúc)</li>
 * </ul>
 */
public enum OrderStatus {

    /**
     * Đơn hàng vừa tạo thành công từ giỏ hàng hoặc đấu giá, đang chờ thanh toán (giữ kho 15 phút).
     */
    PENDING_PAYMENT,

    /**
     * Đơn hàng đã được thanh toán thành công (qua cổng thanh toán hoặc xác nhận COD).
     */
    PAID,

    /**
     * Người bán xác nhận đơn hàng và đang chuẩn bị/đóng gói hàng.
     */
    PROCESSING,

    /**
     * Đơn hàng đã bàn giao cho đơn vị vận chuyển (Fulfillment) và đang trên đường giao.
     */
    SHIPPED,

    /**
     * Đơn vị vận chuyển đã giao hàng thành công tới tay người mua.
     */
    DELIVERED,

    /**
     * Đơn hàng hoàn tất trọn vẹn (người mua bấm xác nhận hoặc tự động sau 7 ngày).
     */
    COMPLETED,

    /**
     * Đơn hàng bị hủy (do quá hạn thanh toán 15 phút, người mua chủ động hủy, hoặc giao thất bại).
     */
    CANCELLED,

    /**
     * Đơn hàng đã được xử lý hoàn tiền lại cho người mua.
     */
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(PROCESSING, REFUNDED),
            PROCESSING, EnumSet.of(SHIPPED, CANCELLED),
            SHIPPED, EnumSet.of(DELIVERED, CANCELLED),
            DELIVERED, EnumSet.of(COMPLETED, REFUNDED),
            COMPLETED, Collections.emptySet(),
            CANCELLED, Collections.emptySet(),
            REFUNDED, Collections.emptySet()
    );

    /**
     * Kiểm tra xem việc chuyển từ trạng thái hiện tại sang trạng thái đích có hợp lệ theo State Machine hay không.
     *
     * @param targetStatus Trạng thái đích muốn chuyển sang
     * @return true nếu chuyển đổi hợp lệ, ngược lại false
     */
    public boolean canTransitionTo(OrderStatus targetStatus) {
        if (targetStatus == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(targetStatus);
    }
}
