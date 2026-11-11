package com.nexus.commerce.service;

import com.nexus.commerce.entity.PaymentPriceSnapshot;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Tính giá tin cậy ở thời điểm bắt đầu attempt: đơn thường đọc Catalog mới, đấu giá giữ giá settlement.
 * Hiện chỉ có giá SKU và phí ship cấu hình; giờ cao điểm/Promotion/nhà sản xuất chờ BA/Catalog chốt contract,
 * không tự gán mức tăng giảm hoặc nhận kết quả tính tiền từ FE.
 */
@Service
@RequiredArgsConstructor
public class PaymentPricingService {
    private final CatalogClient catalog;
    private final CommerceProperties properties;

    /** Trả snapshot đầy đủ; SKU ngừng bán/sai seller/giá lỗi bị từ chối trước khi giữ kho hoặc tạo URL. */
    public PaymentPriceSnapshot quote(Order order) {
        if (order.getItems().isEmpty()) { throw invalid("Đơn hàng không có sản phẩm"); }
        boolean auction = order.getSourceType() == OrderSourceType.AUCTION;
        var live = auction ? java.util.Map.<java.util.UUID, com.nexus.commerce.client.dto.SkuInfo>of()
                : catalog.getSkuDetails(order.getItems().stream().map(OrderItem::getSkuId).toList());
        List<PaymentPriceSnapshot.Line> lines = order.getItems().stream().map(item -> {
            BigDecimal price = item.getEstimatedUnitPrice();
            if (!auction) {
                var sku = live.get(item.getSkuId());
                if (sku == null || !Boolean.TRUE.equals(sku.isActive()) || !order.getSellerId().equals(sku.sellerId())) {
                    throw invalid("SKU không còn được bán bởi shop của đơn hàng");
                }
                price = sku.price();
            }
            if (price == null || price.signum() < 0 || item.getQuantity() == null || item.getQuantity() < 1) {
                throw invalid("Giá hoặc số lượng SKU không hợp lệ");
            }
            price = price.setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            if (price.precision() > 15 || total.precision() > 15) { throw invalid("Giá trị SKU vượt giới hạn"); }
            return new PaymentPriceSnapshot.Line(item.getId(), item.getSkuId(), item.getQuantity(), price, total);
        }).toList();
        BigDecimal subtotal = lines.stream().map(PaymentPriceSnapshot.Line::itemTotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
        BigDecimal shipping = auction ? order.getEstimatedShippingFee() : properties.getOrder().getShippingFee();
        if (shipping == null || shipping.signum() < 0) { throw invalid("Phí vận chuyển không hợp lệ"); }
        shipping = shipping.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(shipping);
        if (total.precision() > 15) { throw invalid("Tổng tiền vượt giới hạn"); }
        return new PaymentPriceSnapshot(subtotal, shipping, new BigDecimal("0.00"), total, lines);
    }

    /** Áp dụng duy nhất snapshot của giao dịch đã xác thực, kiểm tra đủ dòng để lỗi phải rollback toàn bộ IPN. */
    public static void finalizePrices(Order order, PaymentPriceSnapshot snapshot) {
        if (snapshot == null || snapshot.items().size() != order.getItems().size()
                || !snapshot.total().equals(snapshot.subtotal().subtract(snapshot.discount()).add(snapshot.shippingFee()))) {
            throw invalid("Snapshot thanh toán thiếu hoặc tổng không khớp");
        }
        java.util.Set<java.util.UUID> matched = new java.util.HashSet<>();
        BigDecimal subtotal = new BigDecimal("0.00");
        for (var item : order.getItems()) {
            var line = snapshot.items().stream().filter(value -> value.orderItemId().equals(item.getId())).findFirst().orElseThrow(
                    () -> invalid("Snapshot không khớp dòng đơn hàng"));
            if (!matched.add(line.orderItemId()) || !line.skuId().equals(item.getSkuId()) || line.quantity() != item.getQuantity()
                    || line.unitPrice().signum() < 0
                    || line.itemTotal().compareTo(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))) != 0) {
                throw invalid("Snapshot không khớp SKU/số lượng/thành tiền");
            }
            item.setUnitPrice(line.unitPrice());
            subtotal = subtotal.add(line.itemTotal());
        }
        if (subtotal.compareTo(snapshot.subtotal()) != 0) { throw invalid("Snapshot không khớp tổng tiền hàng"); }
        order.setSubtotal(snapshot.subtotal());
        order.setShippingFee(snapshot.shippingFee());
        order.setDiscount(snapshot.discount());
        order.setTotal(snapshot.total());
        order.setPriceFinalized(true);
    }

    /** COD/đấu giá dùng giá đã thỏa thuận, không tự lấy giá Catalog mới khi xác nhận đã thu tiền. */
    public static PaymentPriceSnapshot estimated(Order order) {
        return new PaymentPriceSnapshot(order.getEstimatedSubtotal(), order.getEstimatedShippingFee(),
                order.getEstimatedDiscount(), order.getEstimatedTotal(), order.getItems().stream().map(item ->
                new PaymentPriceSnapshot.Line(item.getId(), item.getSkuId(), item.getQuantity(), item.getEstimatedUnitPrice(),
                        item.getEstimatedUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))).toList());
    }

    private static ApiException invalid(String message) {
        return new ApiException("PAYMENT_PRICE_INVALID", HttpStatus.CONFLICT, message, null);
    }
}
