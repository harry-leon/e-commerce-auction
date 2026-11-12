package com.nexus.fulfillment.enums;

/**
 * Trạng thái nội bộ chuẩn hóa của vận đơn trong Fulfillment Service (SRS §3.6.2).
 * Khớp với ràng buộc chk_shipment_status trong bảng shipments.
 */
public enum ShipmentStatus {
    PENDING,
    PICKING,
    PACKED,
    SHIPPED,
    IN_TRANSIT,
    DELIVERED,
    RETURNED,
    FAILED,
    CANCELLED
}
