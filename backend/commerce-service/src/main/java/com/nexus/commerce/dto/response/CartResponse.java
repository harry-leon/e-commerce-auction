package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.CartStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing the overall shopping cart of a buyer.
 * Includes detailed pricing breakdowns conforming to SRS §3.4.1 and API_SPEC_CART_ORDER.md.
 *
 * @param cartId         Identifier of the cart
 * @param userId         Identifier of the owner user
 * @param status         Status of the cart (ACTIVE, MERGED, EXPIRED, CONVERTED)
 * @param items          List of cart item lines
 * @param totalItems     Total number of distinct items in the cart
 * @param totalQuantity  Total sum of quantities across all items
 * @param subtotal       Calculated subtotal amount of items before discounts/shipping
 * @param discountAmount Estimated voucher discount amount (default 0.00 at cart stage)
 * @param shippingFee    Estimated shipping fee
 * @param taxAmount      Estimated tax/VAT amount
 * @param totalAmount    Estimated total payable amount (subtotal - discount + shipping + tax)
 * @param expiresAt      Timestamp when the cart expires (7-day sliding window)
 */
public record CartResponse(
        UUID cartId,
        UUID userId,
        CartStatus status,
        List<CartItemResponse> items,
        Integer totalItems,
        Integer totalQuantity,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        Instant expiresAt
) {
}
