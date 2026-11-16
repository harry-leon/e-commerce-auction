package com.nexus.user.controller;

import com.nexus.common.response.ApiResponse;
import java.util.List;
import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.dto.CreateUserProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * REST Controller quản lý thông tin tài khoản người dùng theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@RestController
@Validated
@RequestMapping("/api/v1/users")
@Tag(name = "User Management", description = "Các API quản lý tài khoản người dùng")
public class UserController {

    private final UserService userService;

    /**
     * Khởi tạo UserController với UserService.
     *
     * @param userService Service xử lý tài khoản
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Lấy thông tin hồ sơ của chính người dùng đang đăng nhập.
     *
     * @param jwt Thông tin JWT xác thực
     * @return DTO UserResponse
     */
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('PROFILE.VIEW')")
    @Operation(summary = "Xem hồ sơ cá nhân", description = "Lấy thông tin tài khoản của chính mình")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        // Tra JWT sub qua keycloak_id, hỗ trợ profile cũ có id riêng.
        UUID userId = userService.findBySubject(jwt.getSubject()).getId();
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserById(userId)));
    }

    /**
     * Cập nhật thông tin hồ sơ của chính mình.
     *
     * @param jwt     Thông tin JWT xác thực
     * @param request Dữ liệu cập nhật
     * @return DTO UserResponse sau cập nhật
     */
    @PutMapping("/me")
    @PreAuthorize("hasAuthority('PROFILE.UPDATE')")
    @Operation(summary = "Cập nhật hồ sơ cá nhân", description = "Cập nhật thông tin tài khoản của chính mình")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateUserRequest request) {
        // Tra subject Keycloak thay vì giả định bằng profile id.
        UUID userId = userService.findBySubject(jwt.getSubject()).getId();
        return ResponseEntity.ok(ApiResponse.ok(userService.updateUser(userId, request)));
    }

    /**
     * Lấy chi tiết thông tin một người dùng theo ID (Dành cho Admin/Support).
     *
     * @param id Mã định danh của người dùng
     * @return DTO UserResponse
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER.VIEW')")
    @Operation(summary = "Xem chi tiết người dùng", description = "Lấy thông tin người dùng theo ID")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserById(id)));
    }

    /**
     * Tìm kiếm và lấy danh sách phân trang người dùng (Dành cho Admin/Support).
     *
     * @param keyword  Từ khóa tìm kiếm theo tên hoặc email
     * @param pageable Thông tin phân trang (mặc định sort theo fullName)
     * @return Trang danh sách UserResponse
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER.LIST')")
    @Operation(summary = "Danh sách người dùng", description = "Tìm kiếm và phân trang người dùng trong hệ thống")
    public ResponseEntity<ApiResponse<List<UserResponse>>> listUsers(
            @RequestParam(required = false) String keyword,
            @PageableDefault(sort = "fullName", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(userService.searchUsers(keyword, pageable)));
    }

    /** Chỉ Admin được đồng bộ một lát cắt Keycloak; API không đọc DB IAM và không ghi đè hồ sơ đã có. */
    @PostMapping("/sync-keycloak")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Đồng bộ profile còn thiếu từ Keycloak", description = "Tạo profile cho tài khoản Keycloak chưa liên kết; tối đa 100 tài khoản mỗi lượt")
    public ResponseEntity<ApiResponse<UserService.ProfileSyncResult>> syncKeycloakProfiles(
            @RequestParam(defaultValue = "0") @Min(0) int first,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int max) {
        return ResponseEntity.ok(ApiResponse.ok(userService.syncKeycloakProfiles(first, max)));
    }

    /** Khóa IAM trước, sau đó đồng bộ trạng thái profile; chỉ Admin được thay đổi quyền đăng nhập. */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Khóa tài khoản người dùng")
    public ResponseEntity<ApiResponse<UserResponse>> lockUser(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt actor) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setUserLocked(id, true, UUID.fromString(actor.getSubject()))));
    }

    /** Mở khóa IAM và profile qua cùng service; không thay đổi mật khẩu hoặc role hiện tại. */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mở khóa tài khoản người dùng")
    public ResponseEntity<ApiResponse<UserResponse>> unlockUser(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt actor) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setUserLocked(id, false, UUID.fromString(actor.getSubject()))));
    }

    /**
     * Cập nhật thông tin người dùng theo ID (Dành cho Admin/Support).
     *
     * @param id      Mã người dùng
     * @param request Dữ liệu cập nhật
     * @return DTO UserResponse
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER.UPDATE')")
    @Operation(summary = "Cập nhật người dùng", description = "Admin/Support cập nhật thông tin người dùng")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateUser(id, request)));
    }

    /**
     * Xóa mềm tài khoản người dùng theo ID (Dành cho Admin).
     *
     * @param id Mã người dùng
     * @return Thông báo kết quả
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USER.DELETE')")
    @Operation(summary = "Xóa người dùng", description = "Xóa mềm tài khoản người dùng khỏi hệ thống")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã xóa người dùng thành công")));
    }

    /**
     * Phân quyền và gán vai trò cho người dùng (Dành cho Admin).
     *
     * @param id        Mã người dùng
     * @param roleCodes Danh sách mã vai trò cần gán
     * @return DTO UserResponse sau cập nhật vai trò
     */
    @PutMapping("/{id}/roles")
    // USER.UPDATE của Support không được dùng để tự cấp ADMIN; chỉ quản trị viên thay đổi role.
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Gán vai trò cho người dùng", description = "Gán danh sách vai trò cho người dùng")
    public ResponseEntity<ApiResponse<UserResponse>> assignRoles(
            @PathVariable UUID id,
            @RequestBody Set<String> roleCodes) {
        return ResponseEntity.ok(ApiResponse.ok(userService.assignRoles(id, roleCodes)));
    }

    /**
     * Xem lịch sử hoạt động của chính người dùng đang đăng nhập (theo SRS §3.1.1).
     *
     * @param jwt      Thông tin JWT xác thực
     * @param pageable Thông tin phân trang (mặc định sort theo createdAt giảm dần)
     * @return Trang danh sách lịch sử hoạt động
     */
    @GetMapping("/me/activity-history")
    @PreAuthorize("hasAuthority('PROFILE.VIEW')")
    @Operation(summary = "Xem lịch sử hoạt động cá nhân",
            description = "Lấy lịch sử các thao tác của tài khoản mình (login, update...)"
    )
    public ResponseEntity<ApiResponse<List<ActivityHistoryResponse>>> getMyActivityHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // Tra subject Keycloak thay vì giả định bằng profile id.
        UUID userId = userService.findBySubject(jwt.getSubject()).getId();
        return ResponseEntity.ok(ApiResponse.ok(userService.getActivityHistory(userId, pageable)));
    }

    /**
     * Xem lịch sử hoạt động của người dùng theo ID (Dành cho Admin/Support).
     *
     * @param id       Mã người dùng
     * @param pageable Thông tin phân trang
     * @return Trang danh sách lịch sử hoạt động
     */
    @GetMapping("/{id}/activity-history")
    @PreAuthorize("hasAuthority('USER.VIEW')")
    @Operation(summary = "Xem lịch sử hoạt động người dùng",
            description = "Admin/Support xem lịch sử thao tác của một người dùng"
    )
    public ResponseEntity<ApiResponse<List<ActivityHistoryResponse>>> getUserActivityHistory(
            @PathVariable UUID id,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getActivityHistory(id, pageable)));
    }

    /**
     * Tạo hồ sơ người dùng mới trong cơ sở dữ liệu sau khi tài khoản Keycloak đã được tạo.
     * Chức năng: Lưu trữ thông tin cá nhân (họ tên, email, số điện thoại...) và liên kết keycloak_id.
     * Đầu vào: Header nội bộ X-Profile-Subject chứa keycloak_id (UUID), body chứa CreateUserProfileRequest không có mật khẩu.
     * Kết quả: Trả về DTO UserResponse cùng mã trạng thái 201 Created.
     * Luồng: service account có ROLE_PROFILE_WRITER gọi tạo profile; role BUYER đã được Keycloak quản lý.
     */
    @PostMapping
    @PreAuthorize("hasRole('PROFILE_WRITER')")
    @Operation(summary = "Tạo hồ sơ người dùng mới", description = "Được gọi từ API Gateway kèm token service account và header X-Profile-Subject sau khi Keycloak đã tạo tài khoản")
    public ResponseEntity<ApiResponse<UserResponse>> createUserProfile(
            @RequestHeader(value = "X-Profile-Subject", required = true) String headerUserId,
            @Valid @RequestBody CreateUserProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(userService.createUserProfile(headerUserId, request)));
    }
}
