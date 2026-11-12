package com.nexus.fulfillment.shipping.adapter;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kết quả tạo vận đơn chuẩn hóa từ Carrier Adapter, bao gồm mã tracking và trạng thái ban đầu.
 */
public record CarrierCreateShipmentResult(
        String carrierCode,
        String serviceType,
        String trackingNumber,
        BigDecimal fee,
        int estimatedDeliveryDays,
        Instant estimatedDeliveryAt,
        String carrierStatus,
        ShipmentStatus mappedStatus
) {
}
