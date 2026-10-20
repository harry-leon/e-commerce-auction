package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Metadata đính kèm trong response theo chuẩn Envelope Pattern của Nexus.
 *
 * Chức năng: Lưu trữ thông tin tracing, thời gian tạo phản hồi, phiên bản API và phân trang.
 *
 * @param timestamp Thời điểm tạo phản hồi
 * @param requestId Mã định danh request (dùng để trace log qua các microservices)
 * @param version   Phiên bản API (mặc định v1)
 * @param page      Trang hiện tại (nếu có phân trang)
 * @param pageSize  Kích thước trang (nếu có phân trang)
 * @param total     Tổng số bản ghi (nếu có phân trang)
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
