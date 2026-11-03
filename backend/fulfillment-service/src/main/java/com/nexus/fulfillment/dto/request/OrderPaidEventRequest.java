package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload yêu cầu xác nhận trừ kho thực tế khi tiếp nhận sự kiện OrderPaid (SRS §3.6.1).
 *
 * Chức năng:
 * - Tiếp nhận webhook callback hoặc API đồng bộ từ payment gateway / commerce service khi thanh toán thành công.
 * - Cho phép truyền orderId (áp dụng cho toàn bộ đơn) hoặc reservationId cụ thể.
 */
public record OrderPaidEventRequest(
        @NotNull(message = "Mã đơn hàng orderId không được để trống")
        @Schema(description = "Mã đơn hàng duy nhất", example = "c56a4180-65aa-42ec-a945-5fd21dec0538")
        UUID orderId,

        @Schema(description = "Mã phiếu giữ kho cụ thể (tùy chọn)", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
        UUID reservationId,

        @Schema(description = "Mã giao dịch thanh toán thành công (tùy chọn)", example = "e33a4180-65aa-42ec-a945-5fd21dec0599")
        UUID paymentId
) {}
