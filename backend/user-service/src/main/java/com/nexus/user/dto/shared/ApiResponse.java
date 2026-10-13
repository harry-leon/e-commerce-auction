package com.nexus.user.dto.shared;

import java.time.Instant;
import java.util.List;

/**
 * Định dạng response chuẩn bọc mọi dữ liệu trả về cho client theo SRS §4.2.
 * Tất cả service nên dùng wrapper này để đồng nhất cấu trúc API response.
 *
 * <p>Ví dụ khi thành công:
 * <pre>
 * {
 *   "success": true,
 *   "data": { ... },
 *   "errors": [],
 *   "timestamp": "2026-10-13T01:00:00Z"
 * }
 * </pre>
 *
 * <p>Ví dụ khi lỗi:
 * <pre>
 * {
 *   "success": false,
 *   "data": null,
 *   "errors": ["Email không hợp lệ"],
 *   "timestamp": "2026-10-13T01:00:00Z"
 * }
 * </pre>
 *
 * @param <T> Kiểu dữ liệu của payload
 * @author Vinh Khang
 * @since 13/10/2026
 */
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private List<String> errors;
    private Instant timestamp;

    /**
     * Khởi tạo đối tượng ApiResponse mặc định.
     */
    public ApiResponse() {
    }

    private ApiResponse(boolean success, T data, List<String> errors) {
        this.success = success;
        this.data = data;
        this.errors = errors != null ? errors : List.of();
        this.timestamp = Instant.now();
    }

    /**
     * Tạo response thành công với dữ liệu payload.
     *
     * @param data Dữ liệu cần trả về
     * @param <T>  Kiểu dữ liệu
     * @return ApiResponse với success=true
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, List.of());
    }

    /**
     * Tạo response lỗi với thông báo lỗi.
     *
     * @param errors Danh sách thông báo lỗi
     * @param <T>    Kiểu dữ liệu
     * @return ApiResponse với success=false
     */
    public static <T> ApiResponse<T> error(List<String> errors) {
        return new ApiResponse<>(false, null, errors);
    }

    /**
     * Tạo response lỗi với một thông báo lỗi duy nhất.
     *
     * @param message Thông báo lỗi
     * @param <T>     Kiểu dữ liệu
     * @return ApiResponse với success=false
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, List.of(message));
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
