package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * DTO đại diện cho một mốc lịch sử chuyển trạng thái trong dòng thời gian đơn hàng (Order Timeline Audit Log).
 *
 * <p>Chức năng:
 * Cung cấp thông tin kiểm toán minh bạch cho từng bước thay đổi trạng thái của đơn hàng, phục vụ hiển thị
 * tiến trình đơn hàng (order tracking) cho Người mua, Người bán và Quản trị viên.
 *
 * <p>Đầu vào và kết quả:
 * Nhận dữ liệu từ thực thể OrderStatusHistory, kết hợp giải mã trường actor từ cột reason thông qua OrderMapper,
 * trả về một mốc thời gian bất biến.
 *
 * <p>Luồng xử lý:
 * OrderService truy xuất lịch sử qua OrderStatusHistoryRepository -> OrderMapper bóc tách thẻ [ROLE] hoặc suy luận
 * tác nhân -> ánh xạ thành OrderStatusHistoryResponse.
 *
 * <p>Quy tắc và lý do:
 * Do bảng order_status_history trong database PostgreSQL không có cột vật lý actor để giữ tính tối giản theo schema V2,
 * thông tin tác nhân được trích xuất động hoặc suy luận theo quy tắc nghiệp vụ giúp client hiển thị rõ ràng ai thực hiện.
 *
 * @param id Mã định danh bản ghi lịch sử trạng thái (UUID).
 * @param fromStatus Trạng thái trước khi chuyển (null đối với bước khởi tạo ban đầu).
 * @param toStatus Trạng thái mới được chuyển đến (bắt buộc).
 * @param reason Lý do hoặc ghi chú giải thích việc chuyển trạng thái.
 * @param changedAt Thời điểm ghi nhận bước chuyển đổi (UTC).
 * @param actor Tác nhân hoặc vai trò thực hiện thao tác (BUYER, SELLER, ADMIN, SYSTEM, CARRIER...).
 */
@Builder
public record OrderStatusHistoryResponse(
        UUID id,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String reason,
        Instant changedAt,
        String actor
) {
}
