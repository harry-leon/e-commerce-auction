package com.nexus.user.controller;

import com.nexus.user.entity.NotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Bộ xử lý ngoại lệ tập trung (Global Exception Handler) cho toàn bộ user-service.
 * Đảm bảo mọi phản hồi lỗi đều tuân thủ định dạng ApiError chuẩn và không gây crash ứng dụng.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Xử lý ngoại lệ khi không tìm thấy tài nguyên trong hệ thống.
     *
     * @param ex Ngoại lệ NotFoundException
     * @return Phản hồi lỗi HTTP 404 NOT_FOUND
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ khi sai thông tin đăng nhập hoặc mật khẩu không khớp.
     *
     * @param ex Ngoại lệ BadCredentialsException
     * @return Phản hồi lỗi HTTP 401 UNAUTHORIZED
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("UNAUTHORIZED", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ khi tài khoản bị khóa do vượt quá 5 lần đăng nhập thất bại.
     *
     * @param ex Ngoại lệ LockedException
     * @return Phản hồi lỗi HTTP 423 LOCKED
     */
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiError> handleLocked(LockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiError.of("ACCOUNT_LOCKED", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ khi tài khoản bị vô hiệu hóa hoặc không ở trạng thái ACTIVE.
     *
     * @param ex Ngoại lệ DisabledException
     * @return Phản hồi lỗi HTTP 403 FORBIDDEN
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("ACCOUNT_DISABLED", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ khi người dùng không đủ quyền truy cập (Access Denied).
     *
     * @param ex Ngoại lệ AccessDeniedException
     * @return Phản hồi lỗi HTTP 403 FORBIDDEN
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này"));
    }

    /**
     * Xử lý ngoại lệ khi dữ liệu đầu vào không hợp lệ hoặc logic nghiệp vụ vi phạm.
     *
     * @param ex Ngoại lệ IllegalArgumentException
     * @return Phản hồi lỗi HTTP 400 BAD_REQUEST
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ khi trạng thái thực thi không hợp lệ.
     *
     * @param ex Ngoại lệ IllegalStateException
     * @return Phản hồi lỗi HTTP 400 BAD_REQUEST
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("ILLEGAL_STATE", ex.getMessage()));
    }

    /**
     * Xử lý ngoại lệ xác thực tính hợp lệ của DTO (Validation Errors).
     *
     * @param ex Ngoại lệ MethodArgumentNotValidException
     * @return Phản hồi lỗi HTTP 400 BAD_REQUEST với chi tiết từng trường
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", "Dữ liệu yêu cầu không hợp lệ", details));
    }

    /**
     * Xử lý tất cả các ngoại lệ bất ngờ chưa được bắt cụ thể.
     *
     * @param ex Ngoại lệ Exception
     * @return Phản hồi lỗi HTTP 500 INTERNAL_SERVER_ERROR
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        LOG.error("Lỗi hệ thống không mong muốn: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Đã xảy ra lỗi nội bộ trong hệ thống"));
    }
}
