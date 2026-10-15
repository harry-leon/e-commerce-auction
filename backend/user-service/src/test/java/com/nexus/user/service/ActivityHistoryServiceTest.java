package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.entity.AuditLog;
import com.nexus.user.entity.NotFoundException;
import com.nexus.user.entity.User;
import com.nexus.user.repository.AuditLogRepository;
import com.nexus.user.repository.RoleRepository;
import com.nexus.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Kiểm thử đơn vị cho chức năng Activity History trong UserService (KH-S1-1).
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
@ExtendWith(MockitoExtension.class)
class ActivityHistoryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuthService authService;

    @Mock
    private AuditService auditService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, auditLogRepository, authService, auditService);
    }

    @Test
    void testGetActivityHistory_returnsPagedLogs() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        AuditLog log1 = new AuditLog(UUID.randomUUID(), userId, "LOGIN_SUCCESS",
                "Đăng nhập thành công", "127.0.0.1", "Mozilla", "SUCCESS");
        AuditLog log2 = new AuditLog(UUID.randomUUID(), userId, "USER_UPDATED",
                "Cập nhật thông tin người dùng", null, null, "SUCCESS");

        Pageable pageable = PageRequest.of(0, 10);
        Page<AuditLog> auditPage = new PageImpl<>(List.of(log1, log2), pageable, 2);

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(auditLogRepository.findByUserId(userId, pageable)).thenReturn(auditPage);

        Page<ActivityHistoryResponse> result = userService.getActivityHistory(userId, pageable);

        assertNotNull(result);
        assertEquals(2, result.getTotalElements());
        assertEquals("LOGIN_SUCCESS", result.getContent().get(0).getAction());
        assertEquals("USER_UPDATED", result.getContent().get(1).getAction());
        assertEquals("SUCCESS", result.getContent().get(0).getStatus());
    }

    @Test
    void testGetActivityHistory_emptyWhenNoLogs() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        Pageable pageable = PageRequest.of(0, 10);
        Page<AuditLog> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(auditLogRepository.findByUserId(userId, pageable)).thenReturn(emptyPage);

        Page<ActivityHistoryResponse> result = userService.getActivityHistory(userId, pageable);

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void testGetActivityHistory_throwsWhenUserNotFound() {
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> userService.getActivityHistory(userId, pageable));
    }

    @Test
    void testActivityHistoryResponse_mapsAllFields() {
        UUID logId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        AuditLog log = new AuditLog(logId, userId, "LOGIN_SUCCESS",
                "Đăng nhập thành công", "10.0.0.1", "Chrome", "SUCCESS");
        log.setCreatedAt(now);

        User user = new User();
        user.setId(userId);

        Pageable pageable = PageRequest.of(0, 10);
        Page<AuditLog> auditPage = new PageImpl<>(List.of(log), pageable, 1);

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(auditLogRepository.findByUserId(userId, pageable)).thenReturn(auditPage);

        Page<ActivityHistoryResponse> result = userService.getActivityHistory(userId, pageable);

        ActivityHistoryResponse response = result.getContent().get(0);
        assertEquals(logId, response.getId());
        assertEquals("LOGIN_SUCCESS", response.getAction());
        assertEquals("Đăng nhập thành công", response.getDescription());
        assertEquals("SUCCESS", response.getStatus());
        assertEquals(now, response.getCreatedAt());
    }
}
