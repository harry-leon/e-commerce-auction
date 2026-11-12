package com.nexus.commerce.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Data Transfer Object representing SKU variant details fetched from Catalog Service.
 * Bỏ qua các trường mở rộng không sử dụng (như productId, originalPrice) từ Catalog Service.
 *
 * @param skuId         Unique identifier of the SKU variant
 * @param skuCode       Mã SKU tùy chọn (có thể null sau HG-S2-6 khi Catalog không còn lưu sku_code)
 * @param productName   Name of the product
 * @param thumbnailUrl  URL link to the primary thumbnail image
 * @param price         Current unit listing price
 * @param stockQuantity Available inventory quantity in stock
 * @param isActive      Whether the SKU variant is currently active for purchase
 * @param sellerId      Chủ shop từ Catalog; Checkout không chấp nhận giá trị do client tự khai báo
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuInfo(
        UUID skuId,
        String skuCode,
        String productName,
        String thumbnailUrl,
        BigDecimal price,
        Integer stockQuantity,
        Boolean isActive,
        UUID sellerId
) {
    /** Giữ tương thích caller Cart cũ; Checkout yêu cầu sellerId thật và từ chối snapshot thiếu người bán. */
    public SkuInfo(UUID skuId, String skuCode, String productName, String thumbnailUrl,
                   BigDecimal price, Integer stockQuantity, Boolean isActive) {
        this(skuId, skuCode, productName, thumbnailUrl, price, stockQuantity, isActive, null);
    }

    /**
     * Trả về mã SKU hiển thị: dùng skuCode nếu có, hoặc sinh mã dự phòng từ skuId khi Catalog không còn cột sku_code.
     */
    public static String resolveSkuCode(UUID skuId, String skuCode) {
        if (skuCode != null && !skuCode.isBlank()) {
            return skuCode;
        }
        if (skuId != null) {
            return "SKU-" + skuId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        return "N/A";
    }
}
