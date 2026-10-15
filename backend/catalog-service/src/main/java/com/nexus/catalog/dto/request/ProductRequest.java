package com.nexus.catalog.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull UUID categoryId,
        Boolean auctionEnabled,
        @NotNull @Size(min = 1) List<@Valid SkuRequest> skus,
        @Size(max = 5, message = "Product may have at most 5 images")
        List<@Valid ImageRequest> images) {
}
