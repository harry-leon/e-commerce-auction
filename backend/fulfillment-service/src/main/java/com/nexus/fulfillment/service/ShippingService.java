package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.CarrierWebhookRequest;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.CarrierInfoResponse;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.util.List;
import java.util.UUID;

/**
 * Dịch vụ điều phối giao vận (Shipping Orchestrator) trong Fulfillment Service (SRS §3.6.2, KH-S3-1, KH-S3-2).
 *
 * Chức năng:
 * - Báo giá cước vận chuyển từ các Carrier Adapter được hỗ trợ và áp dụng ngưỡng miễn phí vận chuyển.
 * - Tạo vận đơn (thủ công hoặc tự động khi nhận OrderPaid), lưu tracking number và phát sự kiện ShipmentCreated.
 * - Tra cứu thông tin vận đơn và hành trình giao hàng thời gian thực từ hãng.
 * - Tiếp nhận callback/webhook từ hãng vận chuyển và định kỳ polling trạng thái các vận đơn active (KH-S3-2).
 * - Xử lý chuyển đổi trạng thái idempotent (bỏ qua trùng lặp theo eventId/transactionId) và phát domain events tương ứng:
 *   ShipmentPickedUp, ShipmentInTransit, ShipmentDelivered, ShipmentFailed, ShipmentReturned.
 */
public interface ShippingService {

    /** Báo giá phí vận chuyển cho các hãng được hỗ trợ hoặc hãng chỉ định. */
    ShippingQuoteResponse quoteShipping(ShippingQuoteRequest request);

    /** Tạo vận đơn mới qua Carrier Adapter (hỗ trợ idempotency qua Idempotency-Key). */
    ShipmentResponse createShipment(CreateShipmentRequest request, String idempotencyKey);

    /** Tự động tạo vận đơn idempotent khi đơn hàng thanh toán thành công (OrderPaid). */
    ShipmentResponse createShipmentForPaidOrder(UUID orderId);

    /** Lấy thông tin chi tiết vận đơn theo ID. */
    ShipmentResponse getShipment(UUID shipmentId);

    /** Lấy thông tin vận đơn theo mã đơn hàng (orderId). */
    ShipmentResponse getShipmentByOrderId(UUID orderId);

    /** Truy vấn hành trình giao hàng thời gian thực theo ID vận đơn và đồng bộ lịch sử trạng thái. */
    ShipmentTrackingResponse trackShipment(UUID shipmentId);

    /** Truy vấn hành trình giao hàng thời gian thực theo mã vận đơn (trackingNumber). */
    ShipmentTrackingResponse trackByTrackingNumber(String trackingNumber);

    /** Liệt kê danh sách hãng vận chuyển đang được cấu hình hỗ trợ. */
    List<CarrierInfoResponse> listCarriers();

    /**
     * Tiếp nhận và xử lý webhook/callback từ hãng vận chuyển theo cơ chế idempotent (KH-S3-2).
     * Bỏ qua nếu eventId đã được xử lý trước đó; cập nhật trạng thái chuẩn và phát sự kiện miền nếu trạng thái hợp lệ.
     */
    ShipmentResponse processCarrierWebhook(String carrierCode, CarrierWebhookRequest request);

    /**
     * Cập nhật trạng thái vận đơn với kiểm tra tính hợp lệ của luồng trạng thái và ghi lịch sử (KH-S3-2).
     */
    ShipmentResponse updateShipmentStatus(UUID shipmentId, ShipmentStatus newStatus, String carrierStatus, String eventId, String notes);

    /**
     * Quét và thăm dò trạng thái (polling) cho toàn bộ các vận đơn đang ở trạng thái active (KH-S3-2).
     * @return số lượng vận đơn đã được kiểm tra và đồng bộ
     */
    int pollActiveShipments();
}
