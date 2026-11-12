package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;

/**
 * DTO nhận dữ liệu webhook/callback cập nhật hành trình từ các đối tác giao vận (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Chuẩn hóa payload callback từ nhiều định dạng đối tác (GHN webhook, GHTK webhook, ...).
 * - Cung cấp eventId/transactionId để tầng điều phối thực hiện kiểm tra idempotent chống xử lý lặp sự kiện.
 */
public record CarrierWebhookRequest(
        /** Mã sự kiện hoặc mã giao dịch duy nhất từ hãng vận chuyển (dùng để deduplicate idempotent). */
        String eventId,

        /** Mã vận đơn tra cứu (bắt buộc). */
        @NotBlank(message = "Mã vận đơn trackingNumber không được để trống")
        String trackingNumber,

        /** Trạng thái gốc từ carrier (ví dụ: 'delivering', 'delivered', '4', 'damage'). */
        @NotBlank(message = "Trạng thái status không được để trống")
        String status,

        /** Mã định danh hãng vận chuyển (ví dụ: GHN, GHTK - có thể nhận qua path hoặc body). */
        String carrierCode,

        /** Vị trí hiện tại của kiện hàng nếu carrier cung cấp. */
        String location,

        /** Mô tả chi tiết hoặc lý do giao thất bại / hoàn hàng. */
        String description,

        /** Thời điểm diễn ra sự kiện theo ghi nhận của carrier. */
        Instant occurredAt,

        /** Toàn bộ dữ liệu thô gốc từ webhook để truy vết và kiểm toán khi cần. */
        Map<String, Object> rawPayload
) {
    public CarrierWebhookRequest(String eventId, String trackingNumber, String status, String carrierCode, String location, String description, Instant occurredAt) {
        this(eventId, trackingNumber, status, carrierCode, location, description, occurredAt, null);
    }
}
