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
    public CartItemResponse toItemResponse(CartItem item, SkuInfo skuInfo) {
        if (item == null) {
            return null;
        }

        // Prefer live price from Catalog; fallback to stored unit_price if Catalog price is missing
        BigDecimal unitPrice = (skuInfo != null && skuInfo.price() != null)
                ? skuInfo.price()
                : (item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);

        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
        BigDecimal itemSubtotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

        // Catalog không sở hữu tồn kho; null giữ nghĩa chưa kiểm tra Fulfillment, không giả thành hết hàng hoặc tồn 100.
        Integer stock = skuInfo == null ? null : skuInfo.stockQuantity();
        boolean isAvailable = skuInfo != null && Boolean.TRUE.equals(skuInfo.isActive())
                && (stock == null || stock >= quantity);

        return new CartItemResponse(
                item.getId(),
                item.getSkuId(),
                skuInfo != null ? skuInfo.skuCode() : "N/A",
                skuInfo != null ? skuInfo.productName() : "Unknown Product",
                skuInfo != null ? skuInfo.thumbnailUrl() : null,
                unitPrice,
                quantity,
                stock,
                isAvailable,
                itemSubtotal
        );
    }

    /**
     * Maps a Cart entity and a map of SKU details into a complete CartResponse DTO.
     * Computes aggregate metrics including subtotal, discount, tax, shipping, and estimated total.
     *
     * @param cart   The Cart entity from database
     * @param skuMap Map of SkuInfo looked up by skuId from Catalog Service
     * @return Aggregated CartResponse conforming to API contract specifications
     */
    public CartResponse toCartResponse(Cart cart, Map<UUID, SkuInfo> skuMap) {
        if (cart == null) {
            return null;
        }

        List<CartItem> items = cart.getItems() != null ? cart.getItems() : Collections.emptyList();

        // 1. Map individual items
        List<CartItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(item, skuMap != null ? skuMap.get(item.getSkuId()) : null))
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
}
