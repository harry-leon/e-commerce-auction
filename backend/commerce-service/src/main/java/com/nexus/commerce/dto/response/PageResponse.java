package com.nexus.commerce.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Standard pagination response wrapper.
 *
 * @param content       List of items in the current page
 * @param page          Current page index (0-based)
 * @param size          Current page size
 * @param totalElements Total count of elements across all pages
 * @param totalPages    Total number of pages
 * @param isFirst       Whether this is the first page
 * @param isLast        Whether this is the last page
 * @param <T>           Item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean isFirst,
        boolean isLast
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
