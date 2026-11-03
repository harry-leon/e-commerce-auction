package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Dữ liệu xác nhận checkout; không nhận giá/tổng từ client. Bỏ chọn item nghĩa là mua toàn bộ giỏ hiện tại. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 500) String shippingAddress,
        @NotBlank @Size(max = 255) String recipientName,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String recipientPhone,
        @Size(max = 50) String voucherCode,
        @NotBlank String paymentMethod,
        String sourceType,
        UUID auctionId,
        @Size(min = 1, max = 50) List<@NotNull UUID> selectedItemIds
) { }
