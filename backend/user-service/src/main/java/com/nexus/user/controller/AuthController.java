package com.nexus.user.controller;

import com.nexus.user.dto.ChangePasswordRequest;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.service.KeycloakClient;
import com.nexus.user.service.UserService;
import com.nexus.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Adapter mật khẩu giữ endpoint UI; token reset và credential hoàn toàn do Keycloak quản lý. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService users;
    private final UserRepository profiles;
    private final KeycloakClient keycloak;

    public AuthController(UserService users, UserRepository profiles, KeycloakClient keycloak) {
        this.users = users;
        this.profiles = profiles;
        this.keycloak = keycloak;
    }

    /** Email không tồn tại vẫn trả cùng thông báo; Keycloak gửi action email khi tìm được profile liên kết. */
    @PostMapping("/forgot-password")
    @Operation(summary = "Gửi email reset qua Keycloak")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        profiles.findByEmailAndIsDeletedFalse(request.getEmail().trim().toLowerCase(java.util.Locale.ROOT))
                .filter(user -> user.getKeycloakId() != null)
                .ifPresent(user -> keycloak.sendResetEmail(user.getKeycloakId()));
        return Map.of("message", "Nếu tài khoản tồn tại, hãy làm theo liên kết đặt lại mật khẩu trong email.");
    }

    /** Token reset nội bộ cũ đã ngừng hỗ trợ; email mới dẫn tới trang Keycloak, không báo thành công giả. */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("success", false, "error",
                Map.of("code", "AUTH_RESET_FLOW_CHANGED", "message", "Hãy yêu cầu email mới và đặt mật khẩu tại trang Keycloak.")));
    }

    /** Xác thực mật khẩu hiện tại tại IAM, cập nhật credential và logout session; UI phải đăng nhập lại. */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody ChangePasswordRequest request) {
        var user = users.findBySubject(jwt.getSubject());
        keycloak.changePassword(new KeycloakClient.UserIdentity(user.getKeycloakId(), user.getEmail()),
                request.getCurrentPassword(), request.getNewPassword());
        return Map.of("message", "Đổi mật khẩu thành công. Vui lòng đăng nhập lại.");
    }
}
