package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Chọn item thuộc giỏ người mua để kiểm tra giá/kho; preview không giữ hàng và không lưu đơn. */
public record CheckoutPreviewRequest(
        @Size(min = 1, max = 50) List<@NotNull UUID> itemIds,
        @NotBlank @Size(max = 500) String shippingAddress,
        @Size(max = 50) String voucherCode
) { }
