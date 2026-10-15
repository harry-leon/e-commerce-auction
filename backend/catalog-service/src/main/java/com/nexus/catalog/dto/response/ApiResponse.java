package com.nexus.catalog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;
import java.util.List;

/**
 * Phản hồi chuẩn bọc toàn bộ API response theo Envelope Pattern.
 *
 * @param success Trạng thái thành công/thất bại
 * @param data    Dữ liệu trả về (payload)
 * @param error   Chi tiết lỗi (nếu success = false)
 * @param meta    Metadata bổ sung (timestamp, pagination, requestId)
 * @param <T>     Kiểu dữ liệu của payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error,
        MetaInfo meta
) {

    /**
     * Phản hồi thành công với payload dữ liệu.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, MetaInfo.of());
    }

    /**
     * Phản hồi thành công không có payload (chỉ báo thành công).
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, MetaInfo.of());
    }

    /**
     * Phản hồi thành công cho danh sách phân trang từ Spring Page.
     */
    public static <T> ApiResponse<List<T>> ok(Page<T> page) {
        return new ApiResponse<>(
                true,
                page.getContent(),
                null,
                MetaInfo.ofPagination(page.getNumber(), page.getSize(), page.getTotalElements())
        );
    }

    /**
     * Phản hồi thành công cho dữ liệu phân trang tùy biến.
     */
    public static <T> ApiResponse<T> ok(T data, int page, int pageSize, long total) {
        return new ApiResponse<>(
                true,
                data,
                null,
                MetaInfo.ofPagination(page, pageSize, total)
        );
    }

    /**
     * Phản hồi lỗi theo chuẩn Error Response Pattern.
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message),
                MetaInfo.of()
        );
    }

    /**
     * Phản hồi lỗi kèm thông tin chi tiết context/validation.
     */
    public static <T> ApiResponse<T> error(String code, String message, Object details) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message, details),
                MetaInfo.of()
        );
    }
}
