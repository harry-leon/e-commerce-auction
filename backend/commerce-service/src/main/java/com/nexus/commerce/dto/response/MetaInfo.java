package com.nexus.commerce.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Metadata attached to API responses conforming to Envelope Pattern.
 *
 * @param timestamp Time of response creation
 * @param requestId Request tracing ID (optional)
 * @param version   API version (defaults to v1)
 * @param page      Current page index for pagination (0-based)
 * @param pageSize  Page size for pagination
 * @param total     Total element count for pagination
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetaInfo(
        Instant timestamp,
        String requestId,
        String version,
        Integer page,
        Integer pageSize,
        Long total
) {

    public static MetaInfo of() {
        return new MetaInfo(Instant.now(), null, "v1", null, null, null);
    }

    public static MetaInfo of(String requestId) {
        return new MetaInfo(Instant.now(), requestId, "v1", null, null, null);
    }

    public static MetaInfo ofPagination(int page, int pageSize, long total) {
        return new MetaInfo(Instant.now(), null, "v1", page, pageSize, total);
    }

    public static MetaInfo ofPagination(int page, int pageSize, long total, String requestId) {
        return new MetaInfo(Instant.now(), requestId, "v1", page, pageSize, total);
    }
}
