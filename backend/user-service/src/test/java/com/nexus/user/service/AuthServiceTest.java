package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.user.dto.AuthResponse;
import com.nexus.user.dto.ChangePasswordRequest;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.dto.LoginRequest;
import com.nexus.user.dto.RegisterRequest;
import com.nexus.user.dto.ResetPasswordRequest;
import com.nexus.user.entity.PasswordResetToken;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nexus.user.repository.PasswordResetTokenRepository;
import com.nexus.user.repository.ReputationRepository;
import com.nexus.user.repository.RoleRepository;
import com.nexus.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Kiểm thử đơn vị cho AuthService.
 * Bao gồm: Đăng ký, Đăng nhập, Khóa tài khoản sau 5 lần sai (MAX_LOGIN_ATTEMPTS=5),
 * Quên mật khẩu và Đổi mật khẩu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private ReputationRepository reputationRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuditService auditService;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                roleRepository,
                reputationRepository,
                passwordResetTokenRepository,
                jwtService,
                auditService,
                emailService,
                passwordEncoder,
                5,
                15
        );
    }

    @Test
    void testRegisterSuccess() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@nexus.com");
        request.setPassword("Password123");
        request.setFullName("Nguyen Van B");

        when(userRepository.existsByEmailAndIsDeletedFalse(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_pass");

        Role role = new Role(UUID.randomUUID(), "BUYER", "Buyer", "Desc");
        when(roleRepository.findByCode("BUYER")).thenReturn(Optional.of(role));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("mock_access_token");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("mock_refresh_token");
        when(jwtService.getAccessTokenExpirySeconds()).thenReturn(3600L);

        AuthResponse response = authService.register(request, "127.0.0.1", "JUnit");

        assertNotNull(response);
        assertEquals("mock_access_token", response.getAccessToken());
        assertEquals("mock_refresh_token", response.getRefreshToken());
        assertEquals("test@nexus.com", response.getUser().getEmail());
        verify(userRepository).save(any(User.class));
        verify(reputationRepository).save(any());
    }

    @Test
    void testRegisterDuplicateEmailThrows() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("existing@nexus.com");
        request.setPassword("Password123");

        when(userRepository.existsByEmailAndIsDeletedFalse(anyString())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                authService.register(request, "127.0.0.1", "JUnit"));
    }

    @Test
    void testLoginSuccess() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@nexus.com");
        user.setPasswordHash("hashed_pass");
        user.setStatus("ACTIVE");
        user.setFailedLoginAttempts(2);

        when(userRepository.findByEmailAndIsDeletedFalse("user@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashed_pass")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access_token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh_token");

        LoginRequest request = new LoginRequest("user@nexus.com", "pass123");
        AuthResponse response = authService.login(request, "127.0.0.1", "JUnit");

        assertNotNull(response);
        assertEquals(0, user.getFailedLoginAttempts());
        verify(userRepository).save(user);
    }

    @Test
    void testLoginWrongPasswordIncrementsAttempts() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@nexus.com");
        user.setPasswordHash("hashed_pass");
        user.setStatus("ACTIVE");
        user.setFailedLoginAttempts(1);

        when(userRepository.findByEmailAndIsDeletedFalse("user@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong_pass", "hashed_pass")).thenReturn(false);

        LoginRequest request = new LoginRequest("user@nexus.com", "wrong_pass");

        assertThrows(BadCredentialsException.class, () ->
                authService.login(request, "127.0.0.1", "JUnit"));

        assertEquals(2, user.getFailedLoginAttempts());
        verify(userRepository).save(user);
    }

    @Test
    void testLoginMaxFailedAttemptsLocksAccount() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@nexus.com");
        user.setPasswordHash("hashed_pass");
        user.setStatus("ACTIVE");
        user.setFailedLoginAttempts(4); // Lần thử thứ 5 sẽ bị khóa

        when(userRepository.findByEmailAndIsDeletedFalse("user@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong_pass", "hashed_pass")).thenReturn(false);

        LoginRequest request = new LoginRequest("user@nexus.com", "wrong_pass");

        assertThrows(LockedException.class, () ->
                authService.login(request, "127.0.0.1", "JUnit"));

        assertEquals(5, user.getFailedLoginAttempts());
        assertEquals("LOCKED", user.getStatus());
        assertNotNull(user.getLockedUntil());
        verify(userRepository).save(user);
    }

    @Test
    void testForgotPassword() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@nexus.com");

        when(userRepository.findByEmailAndIsDeletedFalse("user@nexus.com")).thenReturn(Optional.of(user));

        ForgotPasswordRequest request = new ForgotPasswordRequest("user@nexus.com");
        authService.forgotPassword(request, "127.0.0.1", "JUnit");

        verify(emailService).sendPasswordResetEmail(eq("user@nexus.com"), anyString());
        verify(passwordResetTokenRepository).save(any());
    }

    @Test
    void testResetPasswordSuccess() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setPasswordHash("old_hashed_pass");
        user.setTokenVersion(1);

        PasswordResetToken resetToken = new PasswordResetToken(
                UUID.randomUUID(),
                user,
                "valid_token",
                LocalDateTime.now().plusMinutes(10)
        );

        when(passwordResetTokenRepository.findByTokenHashAndUsedFalse("valid_token"))
                .thenReturn(Optional.of(resetToken));
        when(passwordEncoder.matches("NewPass123", "old_hashed_pass")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new_hashed_pass");

        ResetPasswordRequest request = new ResetPasswordRequest("valid_token", "NewPass123");
        authService.resetPassword(request, "127.0.0.1", "JUnit");

        assertEquals("new_hashed_pass", user.getPasswordHash());
        assertEquals(2, user.getTokenVersion());
        verify(passwordResetTokenRepository).save(resetToken);
        verify(userRepository).save(user);
    }

    @Test
    void testChangePasswordSuccess() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setPasswordHash("old_hashed_pass");
        user.setTokenVersion(1);

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPass", "old_hashed_pass")).thenReturn(true);
        when(passwordEncoder.matches("newPass", "old_hashed_pass")).thenReturn(false);
        when(passwordEncoder.encode("newPass")).thenReturn("new_hashed_pass");

        ChangePasswordRequest request = new ChangePasswordRequest("oldPass", "newPass");
        authService.changePassword(userId, request);

        assertEquals("new_hashed_pass", user.getPasswordHash());
        assertEquals(2, user.getTokenVersion());
        verify(userRepository).save(user);
    }
}
