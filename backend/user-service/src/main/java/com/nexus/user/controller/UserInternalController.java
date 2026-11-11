package com.nexus.user.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.user.dto.UserContactResponse;
import com.nexus.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ cung cấp thông tin liên hệ tối thiểu cho các microservice khác (ví dụ Commerce gửi email winner).
 * Bảo vệ tại SecurityConfig bằng ServiceIdentity (client 'nexus-commerce-service' + privilege 'USER.INTERNAL_READ').
 */
@RestController
@RequestMapping("/api/v1/internal/users")
@Tag(name = "Internal Users", description = "API nội bộ tra cứu thông tin liên hệ người dùng")
public class UserInternalController {

    private final UserService userService;

    public UserInternalController(UserService userService) {
        this.userService = userService;
    }

    /** Trả email và tên hiển thị theo userId hoặc keycloak_id để chuẩn bị snapshot email giao dịch. */
    @GetMapping("/{id}")
    @Operation(summary = "Tra cứu liên hệ người dùng nội bộ", description = "Chỉ cho phép service nội bộ có quyền USER.INTERNAL_READ")
    public ResponseEntity<ApiResponse<UserContactResponse>> getContact(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getInternalContact(id)));
    }
}
