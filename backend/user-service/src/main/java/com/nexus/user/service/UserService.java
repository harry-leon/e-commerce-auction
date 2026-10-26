package com.nexus.user.service;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.dto.CreateUserProfileRequest;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.entity.User;
import com.nexus.user.repository.AuditLogRepository;
import com.nexus.user.repository.UserRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quản lý profile/audit trong DB riêng; mọi thao tác IAM được ủy thác Keycloak qua API. */
@Service
public class UserService {
    private final UserRepository users;
    private final AuditLogRepository logs;
    private final AuditService audit;
    private final KeycloakClient keycloak;

    public UserService(UserRepository users, AuditLogRepository logs, AuditService audit, KeycloakClient keycloak) {
        this.users = users;
        this.logs = logs;
        this.audit = audit;
        this.keycloak = keycloak;
    }

    /** Tra profile theo id nghiệp vụ; loại hồ sơ xóa mềm. */
    public User getEntity(UUID id) {
        return users.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ"));
    }

    /** JWT sub là keycloak_id; không giả định nó luôn bằng id của hồ sơ cũ. */
    public User findBySubject(String subject) {
        User user = users.findByKeycloakIdAndIsDeletedFalse(UUID.fromString(subject))
                .orElseThrow(() -> new ResourceNotFoundException("Tài khoản chưa có hồ sơ được liên kết"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new org.springframework.security.authentication.DisabledException("Hồ sơ không hoạt động");
        }
        return user;
    }

    /** Trả profile và vai trò hiện hành từ Keycloak, không đọc bảng roles đã bị V4 xóa. */
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) { return toResponse(getEntity(id)); }

    /** DTO giữ roles/privileges để tương thích UI; nguồn quyền duy nhất là Keycloak. */
    public UserResponse toResponse(User user) {
        UserResponse result = new UserResponse();
        result.setId(user.getId());
        result.setEmail(user.getEmail());
        result.setPhone(user.getPhone());
        result.setFullName(user.getFullName());
        result.setIdentifyNumber(user.getIdentifyNumber());
        result.setGender(user.getGender());
        result.setDateOfBirth(user.getDateOfBirth());
        result.setAddress(user.getAddress());
        result.setStatus(user.getStatus());
        result.setCreatedAt(user.getCreatedAt());
        result.setUpdatedAt(user.getUpdatedAt());
        Set<String> authorities = user.getKeycloakId() == null ? Set.of() : keycloak.authorities(user.getKeycloakId());
        result.setRoles(authorities.stream().filter(code -> code.startsWith("ROLE_"))
                .map(code -> code.substring(5)).collect(java.util.stream.Collectors.toSet()));
        result.setPrivileges(authorities.stream().filter(code -> code.contains("."))
                .collect(java.util.stream.Collectors.toSet()));
        return result;
    }

    /** Sửa profile và ghi audit trong transaction; mật khẩu/email đăng nhập vẫn do IAM quản lý. */
    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = getEntity(id);
        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone());
        user.setIdentifyNumber(request.getIdentifyNumber());
        user.setGender(request.getGender());
        user.setDateOfBirth(request.getDateOfBirth());
        user.setAddress(request.getAddress());
        users.save(user);
        audit.record(id, "USER_UPDATED", "Cập nhật hồ sơ", null, null, "SUCCESS");
        return toResponse(user);
    }

    /** Khóa IAM trước khi xóa mềm profile; lỗi IAM dừng thao tác, không giả vờ thu hồi token bằng token_version cũ. */
    @Transactional
    public void deleteUser(UUID id) {
        User user = getEntity(id);
        if (user.getKeycloakId() != null) { keycloak.disable(user.getKeycloakId()); }
        user.setDeleted(true);
        user.setStatus("INACTIVE");
        users.save(user);
        audit.record(id, "USER_DELETED", "Xóa mềm hồ sơ; IAM đã bị khóa", null, null, "SUCCESS");
    }

    /** Phân trang hồ sơ chưa xóa; keyword rỗng không làm lộ hồ sơ đã xóa mềm. */
    @Transactional(readOnly = true)
    public Page<UserResponse> searchUsers(String keyword, Pageable pageable) {
        return users.searchUsers(keyword == null ? "" : keyword.trim(), pageable).map(this::toResponse);
    }

    /** Giữ endpoint gán role; cập nhật realm mappings tại Keycloak, không ghi DB IAM cũ. */
    public UserResponse assignRoles(UUID id, Set<String> roleCodes) {
        User user = getEntity(id);
        keycloak.assignRoles(user.getKeycloakId(), roleCodes);
        audit.record(id, "USER_ROLES_ASSIGNED", "Cập nhật vai trò Keycloak", null, null, "SUCCESS");
        return toResponse(user);
    }

    /** Lịch sử audit của profile được phân trang; không bao gồm log login nội bộ Keycloak. */
    @Transactional(readOnly = true)
    public Page<ActivityHistoryResponse> getActivityHistory(UUID id, Pageable pageable) {
        getEntity(id);
        return logs.findByUserId(id, pageable).map(log -> new ActivityHistoryResponse(log.getId(),
                log.getAction(), log.getDescription(), log.getStatus(), log.getCreatedAt()));
    }

    /** Tạo profile từ service account của Gateway; subject hợp lệ là bắt buộc, không tạo profile mồ côi. */
    @Transactional
    public UserResponse createUserProfile(String subject, CreateUserProfileRequest request) {
        UUID keycloakId = UUID.fromString(subject);
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email) || users.findByKeycloakIdAndIsDeletedFalse(keycloakId).isPresent()) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
        }
        User user = new User();
        user.setId(keycloakId);
        user.setKeycloakId(keycloakId);
        user.setEmail(email);
        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone());
        user.setIdentifyNumber(request.getIdentifyNumber());
        user.setGender(request.getGender());
        user.setDateOfBirth(request.getDateOfBirth());
        user.setAddress(request.getAddress());
        users.save(user);
        audit.record(user.getId(), "CREATE_PROFILE", "Tạo hồ sơ sau đăng ký Keycloak", null, null, "SUCCESS");
        return toResponse(user);
    }
}
