package com.nexus.notification.service;

import com.nexus.notification.dto.NotificationResponse;
import com.nexus.notification.dto.OrderEmailRequest;
import java.util.concurrent.CompletableFuture;

/**
 * Giao diện dịch vụ gửi email giao dịch bắt buộc trong E-Commerce (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Tiếp nhận các sự kiện OrderCreated, OrderPaid, OrderPaymentFailed để gửi email xác nhận.
 * - Hỗ trợ xử lý bất đồng bộ (Asynchronous) hoàn toàn tách biệt khỏi tiến trình thanh toán đơn hàng.
 * - Tự động thử lại tối đa 3 lần (NOTIFICATION_RETRY_LIMIT = 3) khi gặp lỗi mạng tạm thời.
 * - Cô lập lỗi (§5.2 SRS): Đảm bảo sự cố từ SMTP / mạng không bao giờ làm hỏng hay rollback tiến trình đặt/trừ kho.
 */
public interface TransactionalEmailService {

    /**
     * Xử lý gửi email giao dịch bất đồng bộ (Asynchronous).
     *
     * @param request Dữ liệu sự kiện đơn hàng và thông tin người nhận
     * @return CompletableFuture chứa kết quả phản hồi của thông báo
     */
    CompletableFuture<NotificationResponse> sendOrderTransactionalEmail(OrderEmailRequest request);

    /**
     * Xử lý gửi email giao dịch đồng bộ (dùng trong kịch bản kiểm thử tích hợp hoặc yêu cầu đồng bộ trực tiếp).
     *
     * @param request Dữ liệu sự kiện đơn hàng và thông tin người nhận
     * @return Kết quả phản hồi trạng thái thông báo (SENT, FAILED)
     */
    NotificationResponse processOrderTransactionalEmail(OrderEmailRequest request);
}
