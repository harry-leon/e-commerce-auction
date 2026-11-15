package com.nexus.commerce.service;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc đúng snapshot checkout, không gọi carrier, lấy lại giá Catalog hoặc suy diễn trạng thái đã thu tiền. */
@Service
@RequiredArgsConstructor
public class ShippingOrderSnapshotService {
    private final OrderRepository orders;

    /**
     * Read-only trả trạng thái hiện tại và thông tin đã lưu; 404 khi đơn không tồn tại.
     * Giá chưa chốt trả total=null; thông tin đơn cũ thiếu giữ nguyên null, không tạo dữ liệu giả để giao hàng.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_READ')")
    public Snapshot get(UUID id) {
        var order = orders.findById(id).orElseThrow(() ->
                new ApiException("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng", null));
        return new Snapshot(order.getId(), order.getOrderNo(), order.getStatus(), order.getRecipientName(),
                order.getRecipientPhone(), order.getShippingAddress(), order.getPaymentMethod(), order.isPriceFinalized(),
                order.isPriceFinalized() ? order.getTotal() : null, order.getFulfillmentStatus());
    }

    /** Không phải shipping-ready contract: district/ward/weight chưa được Order lưu, phải lấy từ bước chuẩn bị kiện. */
    public record Snapshot(UUID orderId, String orderNo, OrderStatus status, String recipientName, String recipientPhone,
            String shippingAddress, String paymentMethod, boolean priceFinalized, BigDecimal total, String fulfillmentStatus) { }
}
