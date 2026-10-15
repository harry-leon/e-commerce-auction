package com.nexus.catalog.dto.response;

import java.util.UUID;

public record ImageResponse(UUID id, String url, Integer sortOrder) {
}
