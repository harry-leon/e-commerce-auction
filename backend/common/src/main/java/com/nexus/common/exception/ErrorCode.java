package com.nexus.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Danh mục mã lỗi toàn hệ thống theo Quy chuẩn API Response & Danh mục mã lỗi (v2).
 * Định dạng: MODULE_ERROR_CODE viết hoa, ổn định giữa các phiên bản.
 * Mỗi mã lỗi được liên kết trực tiếp với HTTP Status chuẩn và thông điệp mặc định tiếng Việt.
 */
public enum ErrorCode {

    // ==========================================
    // 1. Mã lỗi chung & Hạ tầng
    // ==========================================
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Cú pháp JSON sai hoặc thiếu tham số bắt buộc"),
    BUSINESS_ERROR(HttpStatus.BAD_REQUEST, "Yêu cầu vi phạm quy tắc nghiệp vụ"),
    INVALID_PARAM_TYPE(HttpStatus.BAD_REQUEST, "Tham số path/query sai kiểu dữ liệu hoặc sai định dạng UUID"),
    INVALID_PARAM_VALUE(HttpStatus.BAD_REQUEST, "Tham số hợp kiểu nhưng giá trị không hợp lệ"),
    INVALID_INCLUDE_PARAM(HttpStatus.BAD_REQUEST, "Truyền trường không hỗ trợ hoặc vượt quá độ sâu trong ?include="),
    MISSING_REQUIRED_HEADER(HttpStatus.BAD_REQUEST, "Thiếu header bắt buộc trong request"),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "Đường dẫn API không tồn tại"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên yêu cầu"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức HTTP không được hỗ trợ cho endpoint này"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Dung lượng dữ liệu hoặc tệp tải lên vượt quá giới hạn cho phép"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng Content-Type không được hỗ trợ"),
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "Dữ liệu yêu cầu không hợp lệ"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Vượt quá giới hạn tần suất yêu cầu, vui lòng thử lại sau"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi nội bộ không mong muốn trong hệ thống"),
    BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "Không kết nối được dịch vụ phụ thuộc hoặc bên thứ ba"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ đang tạm thời bảo trì hoặc quá tải"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Hết thời gian chờ phản hồi từ dịch vụ phụ thuộc"),

    // ==========================================
    // 2. Phân hệ Xác thực & Phân quyền (AUTH_)
    // ==========================================
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực nhưng client không truyền token"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Mã truy cập (Access Token) đã hết hạn"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Chữ ký hoặc cấu trúc token không hợp lệ"),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại"),
    AUTH_REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã bị thu hồi"),
    AUTH_CREDENTIALS_INVALID(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không chính xác"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    AUTH_ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Tài khoản tạm thời bị khóa do vi phạm"),
    AUTH_ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "Tài khoản đã bị cấm vĩnh viễn"),
    AUTH_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email đăng ký đã tồn tại trong hệ thống"),
    AUTH_PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Số điện thoại đã liên kết với tài khoản khác"),
    AUTH_PASSWORD_RESET_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Mã đặt lại mật khẩu không hợp lệ hoặc đã hết hạn"),
    AUTH_OLD_PASSWORD_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Mật khẩu cũ không chính xác"),

    // ==========================================
    // 3. Phân hệ Người dùng & Sổ địa chỉ (USER_, ROLE_)
    // ==========================================
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"),
    USER_PROFILE_INCOMPLETE(HttpStatus.UNPROCESSABLE_ENTITY, "Hồ sơ người dùng chưa đầy đủ thông tin để thực hiện thao tác"),
    USER_ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "Địa chỉ không tồn tại hoặc không thuộc quyền sở hữu của bạn"),
    USER_ADDRESS_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Số lượng địa chỉ lưu trữ đã vượt quá giới hạn tối đa"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy vai trò"),
    ROLE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Tên vai trò đã tồn tại trong hệ thống"),
    ROLE_CANNOT_DELETE_SYSTEM_ROLE(HttpStatus.CONFLICT, "Không thể xóa vai trò hệ thống cốt lõi"),

    // ==========================================
    // 4. Phân hệ Danh mục sản phẩm (CATEGORY_)
    // ==========================================
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy danh mục"),
    CATEGORY_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Mã danh mục đã tồn tại trong hệ thống"),
    CATEGORY_PARENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Danh mục cha chỉ định không tồn tại"),
    CATEGORY_MAX_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Độ sâu của cây danh mục vượt quá giới hạn tối đa cho phép"),
    CATEGORY_CIRCULAR_DEPENDENCY(HttpStatus.UNPROCESSABLE_ENTITY, "Không thể di chuyển danh mục tạo thành vòng lặp phụ thuộc cha-con"),
    CATEGORY_HAS_CHILDREN(HttpStatus.CONFLICT, "Không thể xóa danh mục đang có danh mục con"),
    CATEGORY_HAS_PRODUCTS(HttpStatus.CONFLICT, "Không thể xóa danh mục đang có sản phẩm liên kết"),

    // ==========================================
    // 5. Phân hệ Sản phẩm & Biến thể SKU (PRODUCT_, SKU_)
    // ==========================================
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm"),
    PRODUCT_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Mã code sản phẩm đã tồn tại"),
    PRODUCT_NOT_ACTIVE(HttpStatus.CONFLICT, "Sản phẩm hiện không ở trạng thái sẵn sàng kinh doanh"),
    PRODUCT_SELLER_MISMATCH(HttpStatus.FORBIDDEN, "Bạn không có quyền chỉnh sửa sản phẩm của cửa hàng khác"),
    PRODUCT_IMAGE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Số lượng hình ảnh tải lên vượt quá giới hạn tối đa cho phép"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy biến thể sản phẩm (SKU)"),
    SKU_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Mã biến thể SKU đã tồn tại"),
    SKU_INVALID_ATTRIBUTES(HttpStatus.UNPROCESSABLE_ENTITY, "Tập thuộc tính biến thể bị trùng lặp trong cùng một sản phẩm"),

    // ==========================================
    // 6. Phân hệ Giỏ hàng (CART_)
    // ==========================================
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy giỏ hàng của người dùng"),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Món hàng không tồn tại trong giỏ hàng"),
    CART_SKU_NOT_AVAILABLE(HttpStatus.CONFLICT, "Biến thể sản phẩm đã ngừng kinh doanh hoặc hết hàng"),
    CART_MAX_ITEMS_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Giỏ hàng đã vượt quá số lượng món tối đa cho phép"),
    CART_MERGE_CONFLICT(HttpStatus.CONFLICT, "Xung đột xảy ra khi gộp giỏ hàng khách vãng lai"),

    // ==========================================
    // 7. Phân hệ Đặt hàng & Checkout (ORDER_, CHECKOUT_)
    // ==========================================
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng"),
    ORDER_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Cùng Idempotency-Key nhưng nội dung yêu cầu khác nhau"),
    ORDER_PRICE_CHANGED(HttpStatus.CONFLICT, "Giá sản phẩm đã thay đổi giữa thời điểm xem giỏ và đặt hàng"),
    ORDER_CANNOT_CANCEL(HttpStatus.CONFLICT, "Đơn hàng đang giao hoặc đã hoàn tất, không thể hủy"),
    ORDER_STATUS_INVALID(HttpStatus.CONFLICT, "Vi phạm quy trình chuyển trạng thái đơn hàng"),
    ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT(HttpStatus.UNPROCESSABLE_ENTITY, "Người bán không được phép mua sản phẩm của chính mình"),

    // ==========================================
    // 8. Phân hệ Tồn kho & Giữ hàng (INVENTORY_)
    // ==========================================
    INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi tồn kho của SKU"),
    INVENTORY_INSUFFICIENT(HttpStatus.CONFLICT, "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua"),
    INVENTORY_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy lệnh giữ kho"),
    INVENTORY_RESERVATION_EXPIRED(HttpStatus.CONFLICT, "Lệnh giữ hàng tồn kho đã hết hạn"),
    INVENTORY_RESERVATION_ALREADY_COMMITTED(HttpStatus.CONFLICT, "Lệnh giữ hàng đã được xuất kho trước đó"),
    INVENTORY_CONCURRENT_LOCK_FAILED(HttpStatus.CONFLICT, "Xung đột khóa tài nguyên khi tranh mua tồn kho cuối cùng"),

    // ==========================================
    // 9. Phân hệ Thanh toán (PAYMENT_)
    // ==========================================
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch thanh toán"),
    PAYMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "Đơn hàng đã được thanh toán thành công trước đó"),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Giao dịch thanh toán bị từ chối"),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "Giao dịch đã quá thời hạn chờ thanh toán"),
    PAYMENT_METHOD_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "Phương thức thanh toán không được hỗ trợ"),
    PAYMENT_REFUND_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "Giao dịch không đủ điều kiện hoàn tiền"),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "Lỗi kết nối hoặc phản hồi không hợp lệ từ cổng thanh toán"),
    PAYMENT_GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Cổng thanh toán phản hồi quá chậm"),
    PAYMENT_WEBHOOK_SIGNATURE_INVALID(HttpStatus.FORBIDDEN, "Chữ ký webhook thanh toán không hợp lệ"),

    // ==========================================
    // 10. Phân hệ Đấu giá trực tuyến (AUCTION_, BID_)
    // ==========================================
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy phiên đấu giá"),
    AUCTION_NOT_ACTIVE(HttpStatus.CONFLICT, "Phiên đấu giá chưa mở, đã kết thúc hoặc đã bị hủy"),
    AUCTION_ALREADY_STARTED(HttpStatus.CONFLICT, "Không thể sửa thông số khi phiên đấu giá đã bắt đầu"),
    AUCTION_SELLER_CANNOT_BID(HttpStatus.UNPROCESSABLE_ENTITY, "Người tạo phiên không được tự đặt giá"),
    BID_AMOUNT_TOO_LOW(HttpStatus.UNPROCESSABLE_ENTITY, "Giá đặt phải lớn hơn giá cao nhất hiện tại ít nhất một bước giá"),
    BID_CONCURRENT_OUTBID(HttpStatus.CONFLICT, "Đã có người đặt mức giá này hoặc cao hơn ngay trước bạn"),
    BID_STEP_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Mức giá không đúng bội số của bước giá"),
    BID_BELOW_RESERVE(HttpStatus.UNPROCESSABLE_ENTITY, "Giá đặt thấp hơn giá sàn của phiên đấu giá"),
    BID_WALLET_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "Số dư hoặc tiền đặt cọc không đủ để tham gia đặt giá"),

    // ==========================================
    // 11. Phân hệ Vận chuyển & Giao hàng (SHIPMENT_)
    // ==========================================
    SHIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy vận đơn"),
    SHIPMENT_ALREADY_CREATED(HttpStatus.CONFLICT, "Đơn hàng đã có mã vận đơn"),
    SHIPMENT_CANNOT_CANCEL(HttpStatus.CONFLICT, "Vận đơn đã được lấy hàng, không thể hủy"),
    SHIPMENT_ADDRESS_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "Địa chỉ nhận nằm ngoài phạm vi giao hàng"),
    SHIPMENT_CARRIER_ERROR(HttpStatus.BAD_GATEWAY, "Không kết nối được đơn vị vận chuyển"),
    SHIPMENT_CARRIER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Đơn vị vận chuyển phản hồi quá chậm"),

    // ==========================================
    // 12. Phân hệ Đánh giá & Điểm uy tín (REVIEW_, REPUTATION_)
    // ==========================================
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi đánh giá"),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "Người mua đã đánh giá sản phẩm trong đơn này"),
    REVIEW_ORDER_NOT_DELIVERED(HttpStatus.CONFLICT, "Chỉ được đánh giá sau khi đơn chuyển sang DELIVERED"),
    REVIEW_NOT_OWNER(HttpStatus.FORBIDDEN, "Bạn không phải chủ sở hữu của đánh giá này"),
    REVIEW_EDIT_WINDOW_EXPIRED(HttpStatus.CONFLICT, "Đã quá thời gian cho phép chỉnh sửa đánh giá"),
    REPUTATION_PENALTY_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi xử phạt"),
    REPUTATION_ACTION_FORBIDDEN(HttpStatus.FORBIDDEN, "Bị giới hạn quyền thực hiện hành động do điểm uy tín dưới ngưỡng");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
