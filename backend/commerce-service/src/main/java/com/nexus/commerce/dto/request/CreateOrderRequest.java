package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Không nhận giá client. Có buyNow thì mua trực tiếp; nếu không có, bỏ chọn item nghĩa là mua toàn bộ giỏ. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 500) String shippingAddress,
        @NotBlank @Size(max = 255) String recipientName,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String recipientPhone,
        @Size(max = 50) String voucherCode,
        @NotBlank String paymentMethod,
        String sourceType,
        UUID auctionId,
        @Size(min = 1, max = 50) List<@NotNull UUID> selectedItemIds,
        @jakarta.validation.Valid BuyNowRequest buyNow
) {
    /** Giữ tương thích caller checkout giỏ hiện có; buyNow chỉ dùng cho mua trực tiếp. */
    public CreateOrderRequest(String address, String name, String phone, String voucher, String method,
            String source, UUID auction, List<UUID> items) {
        this(address, name, phone, voucher, method, source, auction, items, null);
    }
}
