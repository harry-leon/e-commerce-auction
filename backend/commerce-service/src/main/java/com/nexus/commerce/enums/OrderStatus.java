package com.nexus.commerce.enums;

/**
 * Trạng thái của đơn hàng trong hệ thống Nexus theo ràng buộc chk_order_status (V2__init_schema.sql).
 *
 * <p>Vòng đời đơn hàng tuân thủ nghiêm ngặt máy trạng thái OrderStatusStateMachine (8 trạng thái):
 * <ul>
 *   <li>PENDING_PAYMENT: Chờ trả tiền hoặc xác nhận kho sau IPN winner; đơn thường hạn 15 phút, winner theo deadline settlement.</li>
 *   <li>PAID: Đã xác nhận thanh toán thành công (qua cổng thanh toán hoặc duyệt đơn COD). Tồn kho chính thức được trừ.</li>
 *   <li>PROCESSING: Người bán đã tiếp nhận đơn và đang thực hiện lấy hàng, đóng gói tại kho.</li>
 *   <li>SHIPPED: Đơn hàng đã bàn giao cho bên vận chuyển (đã có mã vận đơn) và đang trên đường giao.</li>
 *   <li>DELIVERED: Đơn vị vận chuyển đã giao hàng thành công đến tay người mua.</li>
 *   <li>COMPLETED: Trạng thái kết thúc thành công (người mua xác nhận nhận hàng hoặc tự động đóng sau 7 ngày).</li>
 *   <li>CANCELLED: Trạng thái kết thúc hủy (người mua hủy trước khi giao, quá hạn thanh toán hoặc giao thất bại).</li>
 *   <li>REFUNDED: Trạng thái kết thúc hoàn tiền (hết hàng sau thanh toán hoặc giải quyết khiếu nại thành công).</li>
 * </ul>
 */
public enum OrderStatus {

    /** Chờ thanh toán/xác nhận kho; winner chỉ chuyển PAID sau khi Fulfillment commit thành công. */
    PENDING_PAYMENT,

    /** Đã thanh toán thành công, đơn hàng sẵn sàng cho người bán xử lý. */
    PAID,

    /** Người bán đang chuẩn bị và đóng gói hàng tại kho. */
    PROCESSING,

    /** Đã bàn giao cho đơn vị vận chuyển, đang trên đường giao hàng. */
    SHIPPED,

    /** Đã giao hàng thành công đến tay người mua. */
    DELIVERED,

    /** Đơn hàng hoàn tất thành công (trạng thái kết thúc). */
    COMPLETED,

    /** Đơn hàng đã bị hủy (trạng thái kết thúc). */
    CANCELLED,

    /** Đơn hàng đã được hoàn tiền (trạng thái kết thúc). */
    REFUNDED;

    /**
     * Kiểm tra xem trạng thái hiện tại có phải là trạng thái kết thúc (Terminal State) hay không.
     * Các trạng thái kết thúc (COMPLETED, CANCELLED, REFUNDED) không cho phép chuyển tiếp sang bất kỳ trạng thái nào khác.
     *
     * @return true nếu đơn hàng đã ở trạng thái đóng (không thể cập nhật trạng thái tiếp), ngược lại false.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == REFUNDED;
    }

    /**
     * Kiểm tra xem trạng thái đơn hàng có thể bị người mua hủy hay không.
     * Người mua chỉ được phép chủ động hủy khi đơn hàng ở trạng thái PENDING_PAYMENT hoặc PAID (trước khi đóng gói).
     *
     * @return true nếu đơn hàng đủ điều kiện hủy từ phía Buyer, ngược lại false.
     */
    public boolean canBuyerCancel() {
        return this == PENDING_PAYMENT || this == PAID;
    }
}
