package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Phản hồi chuẩn bọc toàn bộ API response theo Envelope Pattern của toàn bộ hệ thống Nexus.
 *
 * Chức năng: Thống nhất cấu trúc payload trả về cho toàn bộ microservices (SRS §4.2).
 *
 * @param success Trạng thái thành công/thất bại của yêu cầu
 * @param data    Dữ liệu trả về (payload chính)
 * @param error   Chi tiết lỗi (chỉ có khi success = false)
 * @param meta    Metadata bổ sung (thời gian, phân trang, version v1)
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
     * Tạo phản hồi thành công có kèm dữ liệu.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, MetaInfo.of());
    }

    /**
     * Tạo phản hồi thành công không có dữ liệu trả về (chỉ báo thành công).
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, MetaInfo.of());
    }

    /**
     * Tạo phản hồi thành công cho danh sách phân trang từ Spring Data Page.
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
     * Tạo phản hồi thành công cho danh sách phân trang tùy biến.
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
     * Tạo phản hồi lỗi theo chuẩn Error Response Pattern.
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
     * Tạo phản hồi lỗi kèm chi tiết dữ liệu lỗi bổ sung.
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
