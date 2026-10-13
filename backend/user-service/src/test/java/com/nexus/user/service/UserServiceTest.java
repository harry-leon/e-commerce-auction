package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nexus.user.repository.RoleRepository;
import com.nexus.user.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử đơn vị cho UserService.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuthService authService;

    @Mock
    private AuditService auditService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, authService, auditService);
    }

    @Test
    void testGetUserById() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("user@nexus.com");

        UserResponse userResponse = new UserResponse();
        userResponse.setId(userId);
        userResponse.setEmail("user@nexus.com");

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(authService.mapToUserResponse(user)).thenReturn(userResponse);

        UserResponse result = userService.getUserById(userId);

        assertNotNull(result);
        assertEquals("user@nexus.com", result.getEmail());
    }

    @Test
    void testUpdateUser() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setFullName("Old Name");

        UserResponse userResponse = new UserResponse();
        userResponse.setId(userId);
        userResponse.setFullName("New Name");

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(authService.mapToUserResponse(any(User.class))).thenReturn(userResponse);

        UpdateUserRequest request = new UpdateUserRequest();
        request.setFullName("New Name");

        UserResponse result = userService.updateUser(userId, request);

        assertNotNull(result);
        assertEquals("New Name", result.getFullName());
        verify(userRepository).save(user);
    }

    @Test
    void testDeleteUserSoftDelete() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setDeleted(false);
        user.setStatus("ACTIVE");

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));

        userService.deleteUser(userId);

        assertTrue(user.isDeleted());
        assertEquals("INACTIVE", user.getStatus());
        verify(userRepository).save(user);
    }

    @Test
    void testAssignRoles() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        Role role = new Role(UUID.randomUUID(), "SELLER", "Seller", "Desc");
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByCode("SELLER")).thenReturn(Optional.of(role));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UserResponse userResponse = new UserResponse();
        userResponse.setRoles(Set.of("SELLER"));
        when(authService.mapToUserResponse(any(User.class))).thenReturn(userResponse);

        UserResponse result = userService.assignRoles(userId, Set.of("SELLER"));

        assertNotNull(result);
        assertTrue(result.getRoles().contains("SELLER"));
        verify(userRepository).save(user);
    }
}
