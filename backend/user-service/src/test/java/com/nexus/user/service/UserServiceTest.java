package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.user.dto.CreateUserProfileRequest;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.entity.User;
import com.nexus.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Bảo vệ luồng profile sau V4: tách subject/profile ID, không đọc IAM table và không lộ hồ sơ đã xóa. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock private UserRepository users;
    @Mock private AuditService audit;
    @Mock private KeycloakClient keycloak;
    private UserService service;
    private User user;

    @BeforeEach
    void setup() {
        service = new UserService(users, audit, keycloak);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setKeycloakId(UUID.randomUUID());
        user.setEmail("profile@example.com");
        user.setFullName("Hồ sơ");
    }

    @Test
    void resolvesSubjectSeparatelyAndRejectsInactiveProfiles() {
        when(users.findByKeycloakIdAndIsDeletedFalse(user.getKeycloakId())).thenReturn(Optional.of(user));
        assertEquals(user.getId(), service.findBySubject(user.getKeycloakId().toString()).getId());
        user.setStatus("INACTIVE");
        assertThrows(org.springframework.security.authentication.DisabledException.class,
                () -> service.findBySubject(user.getKeycloakId().toString()));
    }

    @Test
    void createsNormalizedProfileAndMapsIamAuthorities() {
        var request = new CreateUserProfileRequest();
        request.setEmail(" NEW@EXAMPLE.COM ");
        request.setFullName(" Người dùng ");
        request.setPhone("0901234567");
        request.setIdentifyNumber("123");
        request.setGender("OTHER");
        request.setDateOfBirth(LocalDate.of(2000, 1, 1));
        request.setAddress("HCM");
        when(keycloak.authorities(user.getKeycloakId())).thenReturn(Set.of("ROLE_BUYER", "PROFILE.VIEW"));
        var response = service.createUserProfile(user.getKeycloakId().toString(), request);
        assertEquals("new@example.com", response.getEmail());
        assertEquals("Người dùng", response.getFullName());
        assertEquals(Set.of("BUYER"), response.getRoles());
        assertEquals(Set.of("PROFILE.VIEW"), response.getPrivileges());
        assertEquals("HCM", response.getAddress());
        assertEquals("123", response.getIdentifyNumber());
        assertEquals("OTHER", response.getGender());
        assertEquals("0901234567", response.getPhone());
        assertEquals(request.getDateOfBirth(), response.getDateOfBirth());
        assertEquals("ACTIVE", response.getStatus());
        verify(users).save(any(User.class));
    }

    @Test
    void rejectsDuplicateAndMissingSubject() {
        var request = new CreateUserProfileRequest();
        request.setEmail("profile@example.com");
        request.setFullName("User");
        when(users.existsByEmail(request.getEmail())).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.createUserProfile(user.getKeycloakId().toString(), request));
        assertThrows(IllegalArgumentException.class, () -> service.createUserProfile("invalid-subject", request));
    }

    @Test
    void updatesProfileAndPreservesCreationTime() {
        when(users.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        var request = new UpdateUserRequest();
        request.setFullName(" Updated ");
        request.setPhone("0901234567");
        request.setIdentifyNumber("abc");
        request.setGender("MALE");
        request.setDateOfBirth(LocalDate.of(1999, 1, 1));
        request.setAddress("New address");
        user.onCreate();
        var created = user.getCreatedAt();
        assertEquals("Updated", service.updateUser(user.getId(), request).getFullName());
        user.onUpdate();
        assertEquals(created, user.getCreatedAt());
        assertTrue(!user.getUpdatedAt().isBefore(created));
        assertEquals("New address", user.getAddress());
    }

    @Test
    void disablesIamBeforeSoftDeleteAndDelegatesRoles() {
        when(users.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        service.assignRoles(user.getId(), Set.of("SELLER"));
        verify(keycloak).assignRoles(user.getKeycloakId(), Set.of("SELLER"));
        service.deleteUser(user.getId());
        verify(keycloak).disable(user.getKeycloakId());
        assertTrue(user.isDeleted());
        assertEquals("INACTIVE", user.getStatus());
    }

    @Test
    void searchesAndPagesProfileActivity() {
        when(users.searchUsers(eq(""), any())).thenReturn(new PageImpl<>(List.of(user)));
        assertEquals(1, service.searchUsers(null, PageRequest.of(0, 10)).getTotalElements());
        when(users.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        // Lịch sử phải được ủy thác notification-service, không phụ thuộc bảng ngoài ERD trong DB User.
        var log = new ActivityHistoryResponse(UUID.randomUUID(), "USER_UPDATED", "Cập nhật", "SUCCESS", java.time.LocalDateTime.now());
        when(audit.history(eq(user.getId()), any())).thenReturn(new PageImpl<>(List.of(log)));
        assertEquals("USER_UPDATED", service.getActivityHistory(user.getId(), PageRequest.of(0, 10)).getContent().getFirst().getAction());
        assertEquals(user.getId(), service.getUserById(user.getId()).getId());
    }

    @Test
    void rejectsUnknownProfile() {
        assertThrows(ResourceNotFoundException.class, () -> service.getUserById(UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> service.findBySubject(UUID.randomUUID().toString()));
    }

    /** Tra cứu liên hệ nội bộ hỗ trợ cả user.id lẫn keycloak_id đã liên kết. */
    @Test
    void resolvesInternalContactByIdOrKeycloakSubject() {
        when(users.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        var byProfile = service.getInternalContact(user.getId());
        assertEquals("profile@example.com", byProfile.email());

        when(users.findByIdAndIsDeletedFalse(user.getKeycloakId())).thenReturn(Optional.empty());
        when(users.findByKeycloakIdAndIsDeletedFalse(user.getKeycloakId())).thenReturn(Optional.of(user));
        var bySubject = service.getInternalContact(user.getKeycloakId());
        assertEquals("Hồ sơ", bySubject.fullName());
    }
}
