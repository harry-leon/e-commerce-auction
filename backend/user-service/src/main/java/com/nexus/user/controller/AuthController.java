package com.nexus.user.controller;

import com.nexus.user.dto.AuthResponse;
import com.nexus.user.dto.ChangePasswordRequest;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.dto.LoginRequest;
import com.nexus.user.dto.RefreshTokenRequest;
import com.nexus.user.dto.RegisterRequest;
import com.nexus.user.dto.ResetPasswordRequest;
import com.nexus.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller xử lý các yêu cầu xác thực, cấp phát JWT và quản lý mật khẩu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication & IAM", description = "Các API xác thực, đăng nhập, cấp JWT, quên mật khẩu")
public class AuthController {

    private final AuthService authService;

    /**
     * Khởi tạo AuthController với AuthService.
     *
     * @param authService Service xử lý nghiệp vụ xác thực
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Đăng ký tài khoản người dùng mới vào hệ thống.
     *
     * @param request     Dữ liệu đăng ký
     * @param httpRequest Đối tượng HttpServletRequest lấy IP và User-Agent
     * @return AuthResponse kèm HTTP 201 Created
     */
    @PostMapping("/register")
    @Operation(summary = "Đăng ký tài khoản mới", description = "Tạo tài khoản người dùng và tự động phát hành JWT")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        AuthResponse response = authService.register(request, ip, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Đăng nhập vào hệ thống bằng Email và Mật khẩu.
     *
     * @param request     Thông tin đăng nhập
     * @param httpRequest Đối tượng HttpServletRequest
     * @return AuthResponse chứa Access Token và Refresh Token
     */
    @PostMapping("/login")
    @Operation(summary = "Đăng nhập hệ thống", description = "Xác thực email và mật khẩu, kiểm tra giới hạn 5 lần đăng nhập sai")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        AuthResponse response = authService.login(request, ip, userAgent);
        return ResponseEntity.ok(response);
    }

    /**
     * Làm mới Access Token khi hết hạn bằng Refresh Token hợp lệ.
     *
     * @param request Yêu cầu chứa refresh token
     * @return AuthResponse chứa Access Token và Refresh Token mới
     */
    @PostMapping("/refresh")
    @Operation(summary = "Làm mới Access Token", description = "Cấp Access Token mới từ Refresh Token hợp lệ")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Gửi yêu cầu quên mật khẩu qua địa chỉ email đăng ký.
     *
     * @param request     Yêu cầu chứa email
     * @param httpRequest Đối tượng HttpServletRequest
     * @return Thông báo phản hồi kết quả
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Yêu cầu quên mật khẩu qua email", description = "Sinh mã xác nhận và gửi email đặt lại mật khẩu")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        authService.forgotPassword(request, ip, userAgent);
        return ResponseEntity.ok(Map.of("message", "Nếu email tồn tại trong hệ thống, hướng dẫn đặt lại mật khẩu đã được gửi"));
    }

    /**
     * Đặt lại mật khẩu mới thông qua mã token xác thực nhận từ email.
     *
     * @param request     Yêu cầu chứa token và mật khẩu mới
     * @param httpRequest Đối tượng HttpServletRequest
     * @return Thông báo kết quả
     */
    @PostMapping("/reset-password")
    @Operation(summary = "Đặt lại mật khẩu bằng mã token", description = "Cập nhật mật khẩu mới và hủy hiệu lực token cũ")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        authService.resetPassword(request, ip, userAgent);
        return ResponseEntity.ok(Map.of("message", "Mật khẩu đã được đặt lại thành công. Vui lòng đăng nhập lại."));
    }

    /**
     * Đổi mật khẩu cho người dùng hiện tại đang đăng nhập.
     *
     * @param jwt     Thông tin JWT xác thực của người dùng
     * @param request Yêu cầu chứa mật khẩu cũ và mới
     * @return Thông báo kết quả
     */
    @PostMapping("/change-password")
    @Operation(summary = "Đổi mật khẩu", description = "Thay đổi mật khẩu cho người dùng đang đăng nhập")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        authService.changePassword(userId, request);
        return ResponseEntity.ok(Map.of("message", "Mật khẩu đã được thay đổi thành công"));
    }

    /**
     * Trích xuất địa chỉ IP thực tế của client từ header của proxy / gateway.
     *
     * @param request Đối tượng HttpServletRequest
     * @return Địa chỉ IP
     */
    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
