package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Mua trực tiếp một SKU/số lượng; giá và quyền mua luôn được kiểm tra lại từ Catalog, không dùng giỏ. */
public record BuyNowRequest(@NotNull UUID skuId, @Min(1) int quantity) { }
