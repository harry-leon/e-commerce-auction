package com.nexus.user.service;

import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.dto.CreateUserProfileRequest;
import java.time.LocalDateTime;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.entity.AuditLog;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nexus.user.repository.AuditLogRepository;
import com.nexus.user.repository.RoleRepository;
import com.nexus.user.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service quản lý thông tin tài khoản người dùng theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuthService authService;
    private final AuditService auditService;

    /**
     * Khởi tạo UserService với các dependency cần thiết.
     *
     * @param userRepository    Repository người dùng
     * @param roleRepository    Repository vai trò
     * @param auditLogRepository Repository lịch sử kiểm toán
     * @param authService       Service xác thực (để map DTO)
     * @param auditService      Service ghi log kiểm toán
     */
    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       AuditLogRepository auditLogRepository,
                       AuthService authService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditLogRepository = auditLogRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    /**
     * Lấy thông tin chi tiết của một người dùng theo ID.
     *
     * @param id Mã định danh duy nhất của người dùng
     * @return DTO UserResponse
     */
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + id));
        return authService.mapToUserResponse(user);
    }

    /**
     * Cập nhật thông tin cá nhân của người dùng.
     *
     * @param id      Mã định danh duy nhất của người dùng
     * @param request Thông tin cập nhật
     * @return DTO UserResponse sau khi cập nhật
     */
    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + id));

        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone());
        user.setIdentifyNumber(request.getIdentifyNumber());
        user.setGender(request.getGender());
        user.setDateOfBirth(request.getDateOfBirth());
        user.setAddress(request.getAddress());

        User updatedUser = userRepository.save(user);
        auditService.record(id, "USER_UPDATED", "Cập nhật thông tin người dùng", null, null, "SUCCESS");

        return authService.mapToUserResponse(updatedUser);
    }

    /**
     * Xóa mềm tài khoản người dùng khỏi hệ thống theo SRS mục 3.1.1.
     *
     * @param id Mã định danh duy nhất của người dùng
     */
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + id));

        user.setDeleted(true);
        user.setStatus("INACTIVE");
        user.incrementTokenVersion();
        userRepository.save(user);

        auditService.record(id, "USER_DELETED", "Xóa mềm tài khoản người dùng", null, null, "SUCCESS");
    }

    /**
     * Tìm kiếm và phân trang danh sách người dùng.
     *
     * @param keyword  Từ khóa tìm kiếm theo tên hoặc email
     * @param pageable Thông tin phân trang
     * @return Trang danh sách UserResponse
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> searchUsers(String keyword, Pageable pageable) {
        Page<User> usersPage;
        if (keyword != null && !keyword.trim().isEmpty()) {
            usersPage = userRepository.searchUsers(keyword.trim(), pageable);
        } else {
            usersPage = userRepository.findAll(pageable);
        }
        return usersPage.map(authService::mapToUserResponse);
    }

    /**
     * Gán vai trò cho người dùng (chỉ dành cho Admin).
     *
     * @param userId    Mã người dùng
     * @param roleCodes Tập hợp mã vai trò cần gán
     * @return DTO UserResponse sau khi cập nhật vai trò
     */
    @Transactional
    public UserResponse assignRoles(UUID userId, Set<String> roleCodes) {
        User user = userRepository.findByIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + userId));

        Set<Role> roles = new HashSet<>();
        for (String code : roleCodes) {
            roleRepository.findByCode(code.trim().toUpperCase()).ifPresent(roles::add);
        }
        user.setRoles(roles);
        user.incrementTokenVersion(); // Cập nhật token version để buộc login lại nhận quyền mới

        User savedUser = userRepository.save(user);
        auditService.record(userId, "USER_ROLES_ASSIGNED", "Gán vai trò mới cho người dùng", null, null, "SUCCESS");

        return authService.mapToUserResponse(savedUser);
    }

    /**
     * Lấy lịch sử hoạt động phân trang của một người dùng theo SRS §3.1.1.
     * Truy vấn bảng audit_logs theo userId, sắp xếp mới nhất trước.
     *
     * @param userId   Mã định danh duy nhất của người dùng
     * @param pageable Thông tin phân trang
     * @return Trang danh sách ActivityHistoryResponse
     */
    @Transactional(readOnly = true)
    public Page<ActivityHistoryResponse> getActivityHistory(UUID userId, Pageable pageable) {
        // Kiểm tra người dùng tồn tại trước khi truy vấn lịch sử
        if (!userRepository.findByIdAndIsDeletedFalse(userId).isPresent()) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + userId);
        }
        return auditLogRepository.findByUserId(userId, pageable)
                .map(this::mapToActivityHistoryResponse);
    }

    /**
     * Chuyển đổi đối tượng AuditLog sang DTO ActivityHistoryResponse.
     *
     * @param log Bản ghi audit log
     * @return DTO lịch sử hoạt động
     */
    /**
     * Tạo hồ sơ người dùng mới sau khi tài khoản Keycloak được tạo thành công bởi API Gateway.
     * Chức năng: Lưu trữ thông tin cá nhân và liên kết với keycloak_id, hoàn toàn không lưu mật khẩu.
     *
     * @param keycloakId Mã định danh Keycloak (UUID) được chuyển tiếp từ header X-User-Id của Gateway
     * @param request    Dữ liệu thông tin cá nhân của người dùng
     * @return DTO UserResponse thông tin hồ sơ đã tạo
     */
    @Transactional
    public UserResponse createUserProfile(String keycloakId, CreateUserProfileRequest request) {
        if (userRepository.existsByEmailAndIsDeletedFalse(request.getEmail())) {
            throw new IllegalArgumentException("Email đã được đăng ký trong hệ thống: " + request.getEmail());
        }

        User user = new User();
        user.setId(keycloakId != null ? UUID.fromString(keycloakId) : UUID.randomUUID());
        user.setKeycloakId(keycloakId);
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone());
        user.setIdentifyNumber(request.getIdentifyNumber());
        user.setGender(request.getGender());
        user.setDateOfBirth(request.getDateOfBirth());
        user.setAddress(request.getAddress());
        user.setStatus("ACTIVE");
        user.setFailedLoginAttempts(0);
        user.setTokenVersion(1);
        user.setCreatedAt(LocalDateTime.now());

        roleRepository.findByCode("BUYER").ifPresent(user::addRole);

        User savedUser = userRepository.save(user);
        auditService.record(savedUser.getId(), "CREATE_PROFILE", "Hồ sơ người dùng mới được tạo từ Gateway", "GATEWAY", "GATEWAY", "SUCCESS");

        return authService.mapToUserResponse(savedUser);
    }

    private ActivityHistoryResponse mapToActivityHistoryResponse(AuditLog log) {
        return new ActivityHistoryResponse(
                log.getId(),
                log.getAction(),
                log.getDescription(),
                log.getStatus(),
                log.getCreatedAt()
        );
    }
}
