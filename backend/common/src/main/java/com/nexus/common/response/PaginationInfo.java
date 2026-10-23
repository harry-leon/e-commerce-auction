package com.nexus.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cấu trúc phân trang trong metadata (meta.pagination) theo quy chuẩn v2.
 * Hỗ trợ cả phân trang offset (Spring Data Page) và phân trang con trỏ (cursor pagination).
 *
 * @param page          Chỉ số trang hiện tại (bắt đầu từ 0)
 * @param size          Kích thước trang được yêu cầu (mặc định 20, tối đa 100)
 * @param totalElements Tổng số phần tử thỏa mãn bộ lọc
 * @param totalPages    Tổng số trang
 * @param hasNext       Có trang tiếp theo hay không
 * @param hasPrevious   Có trang trước đó hay không
 * @param nextCursor    Con trỏ đến trang tiếp theo (dành cho infinite scroll/large dataset)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaginationInfo(
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages,
        Boolean hasNext,
        Boolean hasPrevious,
        String nextCursor
) {

    /**
     * Khởi tạo thông tin phân trang chuẩn từ Spring Data Page hoặc dữ liệu đếm được.
     */
    public static PaginationInfo of(int page, int size, long totalElements, int totalPages, boolean hasNext, boolean hasPrevious) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return new PaginationInfo(page, boundedSize, totalElements, totalPages, hasNext, hasPrevious, null);
    }

    /**
     * Khởi tạo thông tin phân trang rỗng (khi danh sách không có dữ liệu).
     */
    public static PaginationInfo empty(int size) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return new PaginationInfo(0, boundedSize, 0L, 0, false, false, null);
    }

    /**
     * Khởi tạo thông tin phân trang dạng con trỏ (cursor pagination) cho dataset lớn hoặc infinite scroll.
     */
    public static PaginationInfo ofCursor(int size, String nextCursor, boolean hasNext) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return new PaginationInfo(null, boundedSize, null, null, hasNext, null, nextCursor);
    }
}
