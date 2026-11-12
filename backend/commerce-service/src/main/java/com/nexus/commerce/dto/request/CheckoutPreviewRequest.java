package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Preview item giỏ hoặc một buyNow độc lập; kiểm tra giá/kho nhưng không giữ hàng, sửa giỏ hoặc lưu đơn. */
public record CheckoutPreviewRequest(
        @Size(min = 1, max = 50) List<@NotNull UUID> itemIds,
        @NotBlank @Size(max = 500) String shippingAddress,
        @Size(max = 50) String voucherCode,
        @jakarta.validation.Valid BuyNowRequest buyNow
) {
    /** Caller preview giỏ cũ không truyền buyNow vẫn giữ nguyên hành vi. */
    public CheckoutPreviewRequest(List<UUID> items, String address, String voucher) {
        this(items, address, voucher, null);
    }
}
