package com.nexus.common.response;

import com.nexus.common.filter.RequestIdContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * Phản hồi chuẩn bọc toàn bộ JSON response của Backend theo Envelope Pattern (v2).
 * Bắt buộc luôn có đủ 4 key cấp cao nhất: success, data, error, meta.
 *
 * @param success Trạng thái thành công (true cho HTTP 2xx, false cho 4xx/5xx)
 * @param data    Dữ liệu phản hồi (Object hoặc Array khi thành công; luôn null khi thất bại)
 * @param error   Chi tiết lỗi (null khi thành công; có {code, message, details} khi thất bại)
 * @param meta    Metadata bổ sung (luôn có requestId và timestamp; pagination chỉ có khi phân trang)
 * @param <T>     Kiểu dữ liệu của payload
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error,
        MetaInfo meta
) {

    /**
     * Phản hồi thành công với payload dữ liệu (Object hoặc Array).
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, MetaInfo.of(RequestIdContext.get()));
    }

    /**
     * Phản hồi thành công không có payload (chỉ thông báo kết quả thực thi).
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, MetaInfo.of(RequestIdContext.get()));
    }

    /**
     * Phản hồi thành công cho danh sách dữ liệu thường (không phân trang).
     * Đảm bảo không bao giờ trả data là null (nếu danh sách null thì tự ép về mảng rỗng []).
     */
    public static <T> ApiResponse<List<T>> ok(List<T> list) {
        List<T> safeList = (list != null) ? list : Collections.emptyList();
        return new ApiResponse<>(true, safeList, null, MetaInfo.of(RequestIdContext.get()));
    }

    /**
     * Phản hồi thành công cho danh sách phân trang chuẩn từ Spring Data Page.
     * Quy tắc: data là JSON Array (rỗng là []). meta.pagination chứa page, size, totalElements, ...
     */
    public static <T> ApiResponse<List<T>> ok(Page<T> page) {
        List<T> content = (page != null) ? page.getContent() : Collections.emptyList();
        PaginationInfo pagination;
        if (page != null) {
            pagination = PaginationInfo.of(
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.hasNext(),
                    page.hasPrevious()
            );
        } else {
            pagination = PaginationInfo.empty(20);
        }
        return new ApiResponse<>(
                true,
                content,
                null,
                MetaInfo.ofPagination(RequestIdContext.get(), pagination)
        );
    }

    /**
     * Phản hồi thành công với payload và thông tin phân trang tùy biến.
     */
    public static <T> ApiResponse<T> ok(T data, PaginationInfo pagination) {
        return new ApiResponse<>(
                true,
                data,
                null,
                MetaInfo.ofPagination(RequestIdContext.get(), pagination)
        );
    }

    /**
     * Phản hồi thành công tương thích ngược với tham số phân trang cơ bản.
     */
    public static <T> ApiResponse<T> ok(T data, int page, int pageSize, long total) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        boolean hasNext = (page + 1) < totalPages;
        boolean hasPrevious = page > 0;
        PaginationInfo pagination = PaginationInfo.of(page, pageSize, total, totalPages, hasNext, hasPrevious);
        return new ApiResponse<>(
                true,
                data,
                null,
                MetaInfo.ofPagination(RequestIdContext.get(), pagination)
        );
    }

    /**
     * Phản hồi thành công với đầy đủ phân trang, bộ lọc và sắp xếp.
     */
    public static <T> ApiResponse<T> ok(T data, PaginationInfo pagination, String sort, Map<String, Object> filters) {
        return new ApiResponse<>(
                true,
                data,
                null,
                MetaInfo.of(RequestIdContext.get(), pagination, sort, filters)
        );
    }

    /**
     * Phản hồi thành công khi tạo mới tài nguyên (HTTP 201 Created).
     */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, data, null, MetaInfo.of(RequestIdContext.get()));
    }

    /**
     * Phản hồi tiếp nhận xử lý bất đồng bộ (HTTP 202 Accepted).
     */
    public static <T> ApiResponse<T> accepted(T asyncJobData) {
        return new ApiResponse<>(true, asyncJobData, null, MetaInfo.of(RequestIdContext.get()));
    }

    /**
     * Phản hồi lỗi cơ bản theo mã lỗi chuẩn và thông điệp.
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message, null),
                MetaInfo.of(RequestIdContext.get())
        );
    }

    /**
     * Phản hồi lỗi có kèm dữ liệu chi tiết (Object hoặc List ValidationErrorDetail).
     */
    public static <T> ApiResponse<T> error(String code, String message, Object details) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message, details),
                MetaInfo.of(RequestIdContext.get())
        );
    }
}
