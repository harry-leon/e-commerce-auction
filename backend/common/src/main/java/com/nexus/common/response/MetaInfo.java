package com.nexus.common.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Metadata đính kèm trong response theo quy chuẩn Envelope v2.
 * Luôn có requestId và timestamp. Các trường pagination, sort, filters, notFoundIds
 * chỉ xuất hiện khi có dữ liệu (không hiển thị null trong JSON).
 *
 * @param requestId   Mã truy vết request (đồng bộ với header X-Request-Id)
 * @param timestamp   Thời gian sinh phản hồi theo chuẩn ISO-8601 UTC
 * @param pagination  Thông tin phân trang (chỉ có khi là response danh sách phân trang)
 * @param sort        Thông tin sắp xếp đã áp dụng (ví dụ: "createdAt,desc")
 * @param filters     Tập tham số lọc đã áp dụng
 * @param notFoundIds Danh sách ID không tìm thấy khi truy vấn theo mảng ID
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetaInfo(
        String requestId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant timestamp,
        PaginationInfo pagination,
        String sort,
        Map<String, Object> filters,
        List<String> notFoundIds
) {

    /**
     * Tạo MetaInfo cơ bản chỉ gồm requestId và timestamp hiện tại.
     */
    public static MetaInfo of(String requestId) {
        return new MetaInfo(requestId, Instant.now(), null, null, null, null);
    }

    /**
     * Tạo MetaInfo kèm thông tin phân trang chuẩn.
     */
    public static MetaInfo ofPagination(String requestId, PaginationInfo pagination) {
        return new MetaInfo(requestId, Instant.now(), pagination, null, null, null);
    }

    /**
     * Tạo MetaInfo đầy đủ với phân trang, bộ lọc và sắp xếp.
     */
    public static MetaInfo of(String requestId, PaginationInfo pagination, String sort, Map<String, Object> filters) {
        return new MetaInfo(requestId, Instant.now(), pagination, sort, filters, null);
    }
}
