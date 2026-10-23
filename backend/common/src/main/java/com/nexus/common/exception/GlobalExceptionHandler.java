package com.nexus.common.exception;

import com.nexus.common.filter.RequestIdContext;
import com.nexus.common.response.ApiResponse;
import com.nexus.common.response.ValidationErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Bộ xử lý ngoại lệ toàn cục tập trung (Global Exception Handler) theo Quy chuẩn API Response (v2).
 * Đảm bảo:
 * 1. Mọi phản hồi lỗi đều được bọc trong Envelope chuẩn 4 key: { success: false, data: null, error: {...}, meta: {...} }.
 * 2. Phân loại chuẩn HTTP status:
 *    - 400: Lỗi cú pháp JSON, sai kiểu dữ liệu query/path, thiếu header/param bắt buộc.
 *    - 422: Lỗi validation dữ liệu (VALIDATION_ERROR) với mảng details chi tiết.
 *    - 404: Không tìm thấy route hoặc tài nguyên.
 *    - 405: Sai HTTP method.
 *    - 413: Dữ liệu tải lên vượt quá giới hạn.
 *    - 415: Không hỗ trợ Media Type.
 *    - 401: Lỗi xác thực tài khoản/token.
 *    - 403: Lỗi không đủ quyền hạn.
 *    - 409: Xung đột trạng thái / trùng dữ liệu nghiệp vụ.
 *    - 500: Lỗi máy chủ không mong muốn (ghi log đầy đủ, không để lộ stack trace ra ngoài).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Xử lý ngoại lệ không tìm thấy tài nguyên (ResourceNotFoundException).
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        HttpStatus status = ex.getHttpStatus() != null ? ex.getHttpStatus() : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * Xử lý ngoại lệ vi phạm quy tắc nghiệp vụ (BusinessException).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = ex.getHttpStatus() != null ? ex.getHttpStatus() : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * Xử lý ngoại lệ nghiệp vụ tùy chỉnh (ApiException).
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = ex.getHttpStatus() != null ? ex.getHttpStatus() : HttpStatus.BAD_REQUEST;
        String uri = (request != null) ? request.getRequestURI() : "N/A";
        LOG.warn("Xử lý ApiException [{}] tại {}: {}", ex.getCode(), uri, ex.getMessage());
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * Xử lý lỗi validation dữ liệu từ @Valid @RequestBody (MethodArgumentNotValidException).
     * Bắt buộc trả HTTP 422 Unprocessable Content với error.code = VALIDATION_ERROR và details là danh sách lỗi từng field.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ValidationErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::mapFieldError)
                .toList();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR.name(), "Dữ liệu yêu cầu không hợp lệ", details));
    }

    /**
     * Xử lý lỗi validation phương thức Spring 6 / Validation (HandlerMethodValidationException).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        ex.getValueResults().forEach(result -> {
            String paramName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                String message = error.getDefaultMessage() != null ? error.getDefaultMessage() : "Giá trị không hợp lệ";
                details.add(ValidationErrorDetail.of(paramName != null ? paramName : "param", ValidationCode.INVALID_FORMAT, message));
            });
        });

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR.name(), "Dữ liệu yêu cầu không hợp lệ", details));
    }

    /**
     * Xử lý vi phạm ràng buộc ConstraintViolationException.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<ValidationErrorDetail> details = ex.getConstraintViolations().stream()
                .map(this::mapConstraintViolation)
                .toList();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR.name(), "Dữ liệu yêu cầu không hợp lệ", details));
    }

    /**
     * Xử lý lỗi JSON sai cú pháp hoặc body không đọc được (HTTP 400 BAD_REQUEST).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        LOG.warn("Malformed JSON request tại {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.name(), "Cú pháp JSON sai hoặc dữ liệu yêu cầu không đọc được"));
    }

    /**
     * Xử lý lỗi tham số path/query sai kiểu dữ liệu hoặc sai định dạng UUID (HTTP 400 INVALID_PARAM_TYPE).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String message = "Tham số '" + paramName + "' không đúng kiểu dữ liệu yêu cầu";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM_TYPE.name(), message));
    }

    /**
     * Xử lý lỗi thiếu query param bắt buộc (HTTP 400 BAD_REQUEST).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        String message = "Thiếu tham số bắt buộc: " + ex.getParameterName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.name(), message));
    }

    /**
     * Xử lý lỗi thiếu header bắt buộc (HTTP 400 MISSING_REQUIRED_HEADER).
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        String message = "Thiếu header bắt buộc: " + ex.getHeaderName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.MISSING_REQUIRED_HEADER.name(), message));
    }

    /**
     * Xử lý lỗi đường dẫn endpoint không tồn tại trong Spring Boot 3 (HTTP 404 ENDPOINT_NOT_FOUND).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.ENDPOINT_NOT_FOUND.name(), "Đường dẫn API không tồn tại: " + request.getRequestURI()));
    }

    /**
     * Xử lý lỗi phương thức HTTP không được hỗ trợ (HTTP 405 METHOD_NOT_ALLOWED).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        String message = "Phương thức HTTP " + ex.getMethod() + " không được hỗ trợ cho endpoint này";
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED.name(), message));
    }

    /**
     * Xử lý lỗi Content-Type không được hỗ trợ (HTTP 415 UNSUPPORTED_MEDIA_TYPE).
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name(), "Định dạng Content-Type không được hỗ trợ"));
    }

    /**
     * Xử lý lỗi vượt quá dung lượng upload cho phép (HTTP 413 PAYLOAD_TOO_LARGE).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(ErrorCode.PAYLOAD_TOO_LARGE.name(), "Dung lượng dữ liệu hoặc tệp tải lên vượt quá giới hạn"));
    }

    /**
     * Xử lý lỗi người dùng không đủ quyền thực hiện thao tác (HTTP 403 AUTH_FORBIDDEN).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.AUTH_FORBIDDEN.name(), "Bạn không có quyền thực hiện thao tác này"));
    }

    /**
     * Xử lý lỗi sai mật khẩu hoặc thông tin đăng nhập không hợp lệ (HTTP 401 AUTH_CREDENTIALS_INVALID).
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.AUTH_CREDENTIALS_INVALID.name(), ex.getMessage()));
    }

    /**
     * Xử lý lỗi tài khoản bị khóa (HTTP 403 AUTH_ACCOUNT_SUSPENDED).
     */
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocked(LockedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.AUTH_ACCOUNT_SUSPENDED.name(), ex.getMessage()));
    }

    /**
     * Xử lý lỗi tài khoản bị vô hiệu hóa (HTTP 403 AUTH_ACCOUNT_BANNED).
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(DisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.AUTH_ACCOUNT_BANNED.name(), ex.getMessage()));
    }

    /**
     * Xử lý lỗi thông tin xác thực sai hoặc thiếu (HTTP 401 AUTH_CREDENTIALS_INVALID / AUTH_UNAUTHORIZED).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.AUTH_CREDENTIALS_INVALID.name(), ex.getMessage()));
    }

    /**
     * Xử lý lỗi IllegalArgumentException hoặc IllegalStateException nghiệp vụ đơn giản.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.name(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getMessage()));
    }

    /**
     * Fallback cho Exception không mong muốn (hỗ trợ kiểm thử trực tiếp).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        return handleUnexpected((Throwable) ex, null);
    }

    /**
     * Fallback cho toàn bộ ngoại lệ hệ thống không lường trước (HTTP 500 INTERNAL_SERVER_ERROR).
     * Tuyệt đối không để lộ stack trace ra phía client; ghi log đầy đủ kèm requestId để tra cứu.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Throwable ex, HttpServletRequest request) {
        String reqId = RequestIdContext.get();
        String uri = (request != null) ? request.getRequestURI() : "N/A";
        LOG.error("Lỗi hệ thống không lường trước [requestId={}, URI={}]: {}", reqId, uri, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.name(), "Đã xảy ra lỗi nội bộ không mong muốn trong hệ thống"));
    }

    // ==========================================
    // Helper Methods hỗ trợ phân tích Field Errors
    // ==========================================

    private ValidationErrorDetail mapFieldError(FieldError fieldError) {
        String field = fieldError.getField();
        String message = fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Giá trị không hợp lệ";
        String constraintCode = fieldError.getCode();
        String validationCode = resolveValidationCode(constraintCode);

        Map<String, Object> params = new LinkedHashMap<>();
        Object[] args = fieldError.getArguments();
        if (args != null && args.length > 1) {
            if (ValidationCode.MIN_VALUE.equals(validationCode) && args.length > 1) {
                params.put("min", args[1]);
            } else if (ValidationCode.MAX_VALUE.equals(validationCode) && args.length > 1) {
                params.put("max", args[1]);
            } else if ((ValidationCode.MIN_SIZE.equals(validationCode) || ValidationCode.MAX_SIZE.equals(validationCode)) && args.length > 2) {
                params.put("min", args[2]);
                params.put("max", args[1]);
            }
        }

        return ValidationErrorDetail.of(field, validationCode, message, params.isEmpty() ? null : params);
    }

    private ValidationErrorDetail mapConstraintViolation(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath().toString();
        String message = violation.getMessage();
        String annotation = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        String validationCode = resolveValidationCode(annotation);

        Map<String, Object> attributes = violation.getConstraintDescriptor().getAttributes();
        Map<String, Object> params = new LinkedHashMap<>();
        if (ValidationCode.MIN_VALUE.equals(validationCode) && attributes.containsKey("value")) {
            params.put("min", attributes.get("value"));
        } else if (ValidationCode.MAX_VALUE.equals(validationCode) && attributes.containsKey("value")) {
            params.put("max", attributes.get("value"));
        } else if (ValidationCode.MIN_SIZE.equals(validationCode) || ValidationCode.MAX_SIZE.equals(validationCode)) {
            if (attributes.containsKey("min")) params.put("min", attributes.get("min"));
            if (attributes.containsKey("max")) params.put("max", attributes.get("max"));
        }

        return ValidationErrorDetail.of(field, validationCode, message, params.isEmpty() ? null : params);
    }

    private String resolveValidationCode(String constraint) {
        if (constraint == null) return ValidationCode.INVALID_FORMAT;
        return switch (constraint) {
            case "NotBlank" -> ValidationCode.NOT_BLANK;
            case "NotNull" -> ValidationCode.NOT_NULL;
            case "Positive" -> ValidationCode.MUST_BE_POSITIVE;
            case "PositiveOrZero" -> ValidationCode.MUST_BE_NON_NEGATIVE;
            case "Min", "DecimalMin" -> ValidationCode.MIN_VALUE;
            case "Max", "DecimalMax" -> ValidationCode.MAX_VALUE;
            case "Size", "Length" -> ValidationCode.MIN_SIZE;
            case "Email" -> ValidationCode.INVALID_EMAIL;
            case "Pattern" -> ValidationCode.INVALID_FORMAT;
            case "Future", "FutureOrPresent" -> ValidationCode.FUTURE_DATE;
            case "Past", "PastOrPresent" -> ValidationCode.PAST_DATE;
            default -> constraint.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
        };
    }
}
