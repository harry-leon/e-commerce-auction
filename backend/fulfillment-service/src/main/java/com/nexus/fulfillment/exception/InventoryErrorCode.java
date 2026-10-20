package com.nexus.fulfillment.exception;

/**
 * Hằng số mã lỗi nghiệp vụ chuẩn hóa cho Fulfillment Service (Inventory domain).
 *
 * Chức năng: Cung cấp mã lỗi dạng chuỗi cố định để các microservices (Commerce, Payment, Frontend)
 * có thể xử lý luồng lỗi theo nghiệp vụ thay vì phụ thuộc vào thông điệp văn bản (message).
 *
 * Quy chuẩn mã lỗi theo Sprint Task [KH-S1-1] & SRS §4.2:
 * - ERR_INSUFFICIENT_STOCK: Tồn kho khả dụng không đủ đáp ứng số lượng yêu cầu (HTTP 409 / 400).
 * - ERR_RESERVATION_EXPIRED: Phiếu giữ hàng đã quá thời gian hiệu lực (HTTP 410 / 400).
 * - ERR_SKU_NOT_FOUND: Không tìm thấy thông tin SKU trong hệ thống kho (HTTP 404).
 * - ERR_RESERVATION_NOT_FOUND: Không tìm thấy phiếu giữ hàng với ID yêu cầu (HTTP 404).
 * - ERR_INVALID_RESERVATION_STATUS: Trạng thái của phiếu giữ hàng không cho phép thao tác này (HTTP 400).
 * - ERR_WAREHOUSE_NOT_FOUND: Không tìm thấy kho hàng được chỉ định (HTTP 404).
 */
public final class InventoryErrorCode {

    /** Không đủ số lượng tồn kho khả dụng để thực hiện kiểm tra hoặc giữ hàng. */
    public static final String ERR_INSUFFICIENT_STOCK = "ERR_INSUFFICIENT_STOCK";

    /** Phiếu giữ hàng đã hết hạn hiệu lực (quá thời gian expires_at). */
    public static final String ERR_RESERVATION_EXPIRED = "ERR_RESERVATION_EXPIRED";

    /** Mã SKU yêu cầu không tồn tại trong hệ thống kho. */
    public static final String ERR_SKU_NOT_FOUND = "ERR_SKU_NOT_FOUND";

    /** Không tìm thấy phiếu giữ hàng theo mã định danh cung cấp. */
    public static final String ERR_RESERVATION_NOT_FOUND = "ERR_RESERVATION_NOT_FOUND";

    /** Trạng thái phiếu giữ hàng không hợp lệ để thực hiện thao tác (ví dụ đã CONFIRMED không thể commit lại). */
    public static final String ERR_INVALID_RESERVATION_STATUS = "ERR_INVALID_RESERVATION_STATUS";

    /** Kho hàng được chỉ định không tồn tại hoặc đã ngừng hoạt động. */
    public static final String ERR_WAREHOUSE_NOT_FOUND = "ERR_WAREHOUSE_NOT_FOUND";

    private InventoryErrorCode() {
        // Chặn khởi tạo class tiện ích hằng số
    }
}
