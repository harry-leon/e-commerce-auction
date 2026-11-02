package com.nexus.notification.dto;

import com.nexus.notification.enums.NotificationChannel;
import com.nexus.notification.enums.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Phản hồi trạng thái xử lý gửi thông báo (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Trả về kết quả sau khi tiếp nhận hoặc thực thi gửi email (SENT, PENDING, FAILED).
 * - Cung cấp số lần đã thử (attempts) và thời điểm gửi thành công (sentAt).
 */
public record NotificationResponse(
        UUID notificationId,
        UUID userId,
        NotificationChannel channel,
        NotificationStatus status,
        int attempts,
        String message,
        Instant sentAt
) {
}
