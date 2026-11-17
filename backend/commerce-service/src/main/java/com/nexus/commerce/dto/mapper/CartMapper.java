package com.nexus.commerce.dto.mapper;

import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.response.CartItemResponse;
import com.nexus.commerce.dto.response.CartResponse;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Component responsible for mapping between Shopping Cart domain entities and API response DTOs.
 * Merges persisted database entities with real-time catalog snapshots and calculates pricing totals.
 */
@Component
public class CartMapper {

    /**
     * Maps a CartItem entity and its corresponding SkuInfo into a CartItemResponse DTO.
     *
     * @param item    The CartItem entity stored in database
     * @param skuInfo The real-time SKU details retrieved from Catalog Service
     * @return CartItemResponse with calculated line subtotal and stock availability status
     */
    /**
     * Ánh xạ CartItem entity và SkuInfo kèm tồn kho khả dụng thành CartItemResponse DTO.
     * Chức năng: Tính toán subtotal dòng hàng, gán số lượng tồn khả dụng từ Fulfillment và xác định isAvailable.
     * Đầu vào: item (CartItem), skuInfo (SkuInfo từ Catalog), stockAvailable (tồn khả dụng từ Fulfillment, có thể null).
     * Kết quả: CartItemResponse chứa đầy đủ giá, số lượng, tồn kho khả dụng và trạng thái có thể đặt hàng.
     * Quy tắc: isAvailable = true khi SKU active và tồn kho khả dụng >= số lượng đặt trong giỏ (nếu đã kiểm tra được kho).
     */
    public CartItemResponse toItemResponse(CartItem item, SkuInfo skuInfo, Integer stockAvailable) {
        if (item == null) {
            return null;
        }

        // Prefer live price from Catalog; fallback to stored unit_price if Catalog price is missing
        BigDecimal unitPrice = (skuInfo != null && skuInfo.price() != null)
                ? skuInfo.price()
                : (item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);

        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
        BigDecimal itemSubtotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

        // Đọc tồn kho từ Fulfillment (stockAvailable), dự phòng từ Catalog nếu có; null là chưa kiểm tra được hạ tầng kho.
        Integer stock = stockAvailable != null ? stockAvailable : (skuInfo == null ? null : skuInfo.stockQuantity());
        boolean isAvailable = skuInfo != null && Boolean.TRUE.equals(skuInfo.isActive())
                && (stock == null || (stock > 0 && stock >= quantity));

        return new CartItemResponse(
                item.getId(),
                item.getSkuId(),
                skuInfo != null ? SkuInfo.resolveSkuCode(item.getSkuId(), skuInfo.skuCode()) : "N/A",
                skuInfo != null ? skuInfo.productName() : "Unknown Product",
                skuInfo != null ? skuInfo.thumbnailUrl() : null,
                unitPrice,
                quantity,
                stock,
                isAvailable,
                itemSubtotal
        );
    }

    public CartItemResponse toItemResponse(CartItem item, SkuInfo skuInfo) {
        return toItemResponse(item, skuInfo, null);
    }

    /**
     * Maps a Cart entity and a map of SKU details into a complete CartResponse DTO.
     * Computes aggregate metrics including subtotal, discount, tax, shipping, and estimated total.
     *
     * @param cart     The Cart entity from database
     * @param skuMap   Map of SkuInfo looked up by skuId from Catalog Service
     * @param stockMap Map of available inventory quantities looked up by skuId from Fulfillment Service
     * @return Aggregated CartResponse conforming to API contract specifications
     */
    public CartResponse toCartResponse(Cart cart, Map<UUID, SkuInfo> skuMap, Map<UUID, Integer> stockMap) {
        if (cart == null) {
            return null;
        }

        List<CartItem> items = cart.getItems() != null ? cart.getItems() : Collections.emptyList();

        // 1. Map individual items kèm tồn kho khả dụng
        List<CartItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(
                        item,
                        skuMap != null ? skuMap.get(item.getSkuId()) : null,
                        stockMap != null ? stockMap.get(item.getSkuId()) : null))
                .toList();

        // 2. Compute aggregate totals
        int totalItems = itemResponses.size();
        int totalQuantity = itemResponses.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();

        BigDecimal subtotal = itemResponses.stream()
                .map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // In Shopping Cart stage, discounts, taxes, and shipping are estimated
        BigDecimal discountAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal shippingFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalAmount = subtotal
                .subtract(discountAmount)
                .max(BigDecimal.ZERO)
                .add(shippingFee)
                .add(taxAmount)
                .setScale(2, RoundingMode.HALF_UP);

        return new CartResponse(
                cart.getId(),
                cart.getUserId(),
                cart.getStatus(),
                itemResponses,
                totalItems,
                totalQuantity,
                subtotal,
                discountAmount,
                shippingFee,
                taxAmount,
                totalAmount,
                cart.getExpiresAt()
        );
    }

    public CartResponse toCartResponse(Cart cart, Map<UUID, SkuInfo> skuMap) {
        return toCartResponse(cart, skuMap, null);
    }
}
