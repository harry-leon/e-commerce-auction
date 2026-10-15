package com.nexus.catalog.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImageRequest(
        @NotBlank @Size(max = 500) String url,
        @Min(0) Integer sortOrder) {
}
