package com.nexus.fulfillment.shipping.adapter;

import com.nexus.fulfillment.enums.ShipmentStatus;

/**
 * Giao diện Carrier Adapter chuẩn hóa giao tiếp giữa Fulfillment Service và các hãng vận chuyển thứ ba (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Đóng gói toàn bộ chi tiết API, header, schema và mã lỗi riêng của từng hãng (GHN, GHTK, ...)
 *   để tầng điều phối (ShippingService) không phụ thuộc vào logic đặc thù của hãng nào.
 * - Chuẩn hóa ánh xạ trạng thái đặc thù của hãng sang tập ShipmentStatus nội bộ thống nhất.
 * - Cho phép thêm mới hoặc thay thế hãng vận chuyển chỉ bằng cách đăng ký một Spring bean triển khai interface này.
 */
public interface ShippingCarrierAdapter {

    /** Mã định danh duy nhất của hãng vận chuyển (ví dụ: "GHN", "GHTK"). */
    String getCarrierCode();

    /** Yêu cầu báo giá cước và thời gian giao hàng dự kiến từ hãng. */
    CarrierQuoteResult quote(CarrierQuoteRequest request);

    /** Tạo vận đơn trên hệ thống hãng và nhận về mã vận đơn (tracking number). */
    CarrierCreateShipmentResult createShipment(CarrierCreateShipmentRequest request);

    /** Truy vấn tiến trình giao hàng thời gian thực theo mã vận đơn. */
    CarrierTrackingResult trackShipment(String trackingNumber);

    /** Ánh xạ mã hoặc chuỗi trạng thái đặc thù từ hãng sang ShipmentStatus nội bộ chuẩn hóa. */
    default ShipmentStatus mapCarrierStatus(String rawStatus) {
        return ShipmentStatus.IN_TRANSIT;
    }
}
