package com.nexus.fulfillment.service;

import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.client.CommerceShippingClient;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Ghép dữ liệu nhận hàng từ Commerce với kiện đã cân/định tuyến; chỉ preview, chưa lưu hoặc gọi carrier. */
@Service
public class ShipmentPreparationService {
    private final CommerceShippingClient commerce;

    public ShipmentPreparationService(CommerceShippingClient commerce) { this.commerce = commerce; }

    /** Chỉ hỗ trợ đơn VNPAY PAID/PROCESSING đã chốt; thiếu thông tin hoặc kho đang REVIEW phải dừng, không dùng mặc định 500g. */
    public CreateShipmentRequest prepare(UUID orderId, Parcel parcel) {
        if (parcel == null || parcel.warehouseId() == null || parcel.weightGrams() == null || parcel.weightGrams() <= 0
                || parcel.toDistrictId() == null || parcel.toDistrictId() <= 0 || blank(parcel.toWardCode())
                || blank(parcel.carrier()) || blank(parcel.serviceType())) { throw invalid(); }
        var order = commerce.get(orderId);
        if (!order.priceFinalized() || order.total() == null || order.total().signum() < 0
                || !"VNPAY".equals(order.paymentMethod()) || !("PAID".equals(order.status()) || "PROCESSING".equals(order.status()))
                || "REVIEW".equals(order.fulfillmentStatus()) || blank(order.recipientName()) || blank(order.recipientPhone())
                || blank(order.shippingAddress())) { throw invalid(); }
        return new CreateShipmentRequest(order.orderId(), parcel.warehouseId(), parcel.carrier(), parcel.serviceType(),
                order.recipientName(), order.recipientPhone(), order.shippingAddress(), parcel.toDistrictId(),
                parcel.toWardCode(), parcel.weightGrams(), order.total(), BigDecimal.ZERO, "Prepared from Commerce snapshot");
    }

    /** Chặn dữ liệu rỗng, không tự lấy địa chỉ hoặc cân nặng từ giá trị demo. */
    private boolean blank(String value) { return value == null || value.isBlank(); }
    /** Không trả snapshot PII trong lỗi; caller cần bổ sung hoặc đối soát trước khi thử chuẩn bị lại. */
    private ApiException invalid() {
        return new ApiException("SHIPMENT_NOT_READY", HttpStatus.CONFLICT, "Đơn hoặc dữ liệu đóng gói chưa đủ điều kiện chuẩn bị giao hàng", null);
    }
    /** Nhân viên nhập dữ liệu kiện thực; chưa chứng minh kho tồn tại hoặc carrier hỗ trợ tuyến, kiểm tra đó thuộc dispatch. */
    public record Parcel(UUID warehouseId, String carrier, String serviceType, Integer weightGrams, Integer toDistrictId, String toWardCode) { }
}
