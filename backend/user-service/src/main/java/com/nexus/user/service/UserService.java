package com.nexus.user.service;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.dto.CreateUserProfileRequest;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserContactResponse;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.entity.User;
import com.nexus.user.repository.UserRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quản lý profile trong DB riêng, audit qua notification-service; mọi thao tác IAM được ủy thác Keycloak qua API. */
@Service
public class UserService {
    private final UserRepository users;
    private final AuditService audit;
    private final KeycloakClient keycloak;

    /** Kết quả một lượt đồng bộ có giới hạn; skipped gồm tài khoản đã liên kết hoặc thiếu email hợp lệ. */
    public record ProfileSyncResult(int scanned, int created, int skipped) { }

    public UserService(UserRepository users, AuditService audit, KeycloakClient keycloak) {
        this.users = users;
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

    /**
     * Tra cứu email và họ tên người dùng cho service nội bộ khi gửi thông báo giao dịch/đấu giá.
     * Đầu vào: UUID tài khoản (chấp nhận cả user.id hoặc keycloak_id đã liên kết).
     * Kết quả: UserContactResponse chỉ gồm id, email, fullName; không gọi Keycloak hay trả trường nhạy cảm.
     */
    @Transactional(readOnly = true)
    public UserContactResponse getInternalContact(UUID id) {
        User user = users.findByIdAndIsDeletedFalse(id)
                .or(() -> users.findByKeycloakIdAndIsDeletedFalse(id))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ"));
        return new UserContactResponse(user.getId(), user.getEmail(), user.getFullName());
    }

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

    /** Sửa profile trong transaction và gửi audit sau commit; mật khẩu/email đăng nhập vẫn do IAM quản lý. */
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

    /**
     * Đồng bộ profile còn thiếu từ Keycloak theo lát cắt offset/limit.
     * Không ghi đè profile đã tồn tại; identity thiếu email hoặc trùng email với profile khác được bỏ qua.
     */
    public ProfileSyncResult syncKeycloakProfiles(int first, int max) {
        var realmUsers = keycloak.users(first, max);
        int created = 0;
        for (KeycloakClient.RealmUser realmUser : realmUsers) {
            if (realmUser.id() == null || realmUser.email() == null || realmUser.email().isBlank()) { continue; }
            UUID keycloakId;
            try { keycloakId = UUID.fromString(realmUser.id()); }
            catch (IllegalArgumentException exception) { continue; }
            String email = realmUser.email().trim().toLowerCase(Locale.ROOT);
            if (users.findByKeycloakId(keycloakId).isPresent() || users.existsByEmail(email)) { continue; }

            String name = ((realmUser.firstName() == null ? "" : realmUser.firstName().trim()) + " "
                    + (realmUser.lastName() == null ? "" : realmUser.lastName().trim())).trim();
            if (name.isBlank()) { name = realmUser.username() == null || realmUser.username().isBlank()
                    ? email.substring(0, email.indexOf('@')) : realmUser.username().trim(); }
            User profile = new User();
            profile.setId(keycloakId);
            profile.setKeycloakId(keycloakId);
            profile.setEmail(email);
            profile.setFullName(name.length() > 255 ? name.substring(0, 255) : name);
            profile.setStatus(realmUser.enabled() ? "ACTIVE" : "INACTIVE");
            users.save(profile);
            audit.record(keycloakId, "PROFILE_SYNCED_FROM_KEYCLOAK", "Tạo hồ sơ còn thiếu từ Keycloak", null, null, "SUCCESS");
            created++;
        }
        return new ProfileSyncResult(realmUsers.size(), created, realmUsers.size() - created);
    }

    /** Khóa/mở khóa đồng nhất giữa Keycloak và profile; chỉ cập nhật DB sau khi IAM xác nhận thành công. */
    public UserResponse setUserLocked(UUID id, boolean locked, UUID actorId) {
        User user = getEntity(id);
        if (locked && actorId.equals(user.getKeycloakId())) {
            throw new com.nexus.common.exception.ApiException("USER_SELF_LOCK_FORBIDDEN", org.springframework.http.HttpStatus.CONFLICT,
                    "Không thể tự khóa tài khoản đang đăng nhập", null);
        }
        if (user.getKeycloakId() == null) {
            throw new com.nexus.common.exception.ApiException("IAM_USER_NOT_LINKED", org.springframework.http.HttpStatus.CONFLICT,
                    "Hồ sơ chưa liên kết với tài khoản Keycloak", null);
        }
        keycloak.setEnabled(user.getKeycloakId(), !locked);
        user.setStatus(locked ? "INACTIVE" : "ACTIVE");
        users.save(user);
        audit.record(id, locked ? "USER_LOCKED" : "USER_UNLOCKED",
                locked ? "Khóa tài khoản Keycloak" : "Mở khóa tài khoản Keycloak", null, null, "SUCCESS");
        return toResponse(user);
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

    /** Kiểm tra profile còn tồn tại rồi phân trang audit từ notification_svc.event_logs; không đọc bảng audit_logs cũ. */
    @Transactional(readOnly = true)
    public Page<ActivityHistoryResponse> getActivityHistory(UUID id, Pageable pageable) {
        getEntity(id);
        return audit.history(id, pageable);
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
