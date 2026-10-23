package com.nexus.user.service;

import com.nexus.user.dto.AuthResponse;
import com.nexus.user.dto.ChangePasswordRequest;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.dto.LoginRequest;
import com.nexus.user.dto.RefreshTokenRequest;
import com.nexus.user.dto.RegisterRequest;
import com.nexus.user.dto.ResetPasswordRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.entity.PasswordResetToken;
import com.nexus.user.entity.Privilege;
import com.nexus.user.entity.Reputation;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nexus.user.repository.PasswordResetTokenRepository;
import com.nexus.user.repository.ReputationRepository;
import com.nexus.user.repository.RoleRepository;
import com.nexus.user.repository.UserRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service xử lý các nghiệp vụ xác thực và phân quyền (IAM) chính của hệ thống.
 * Bao gồm: Đăng ký, Đăng nhập, Làm mới Token, Khóa tài khoản (MAX_LOGIN_ATTEMPTS=5),
 * Quên mật khẩu qua email, Đặt lại mật khẩu và Đổi mật khẩu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ReputationRepository reputationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;


    private final int maxLoginAttempts;
    private final long resetPasswordExpiryMinutes;

    /**
     * Khởi tạo AuthService với các dependency cần thiết.
     *
     * @param userRepository               Repository người dùng
     * @param roleRepository               Repository vai trò
     * @param reputationRepository         Repository uy tín
     * @param passwordResetTokenRepository Repository token đặt lại mật khẩu
     * @param jwtService                   Service quản lý JWT
     * @param auditService                 Service ghi log kiểm toán
     * @param emailService                 Service gửi email
     * @param passwordEncoder              Bộ mã hóa mật khẩu
     * @param maxLoginAttempts             Số lần thử đăng nhập tối đa trước khi khóa
     * @param resetPasswordExpiryMinutes   Thời hạn hiệu lực của mã reset password (phút)
     */
    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            ReputationRepository reputationRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            JwtService jwtService,
            AuditService auditService,
            EmailService emailService,
            PasswordEncoder passwordEncoder,

            @Value("${app.security.max-login-attempts:5}") int maxLoginAttempts,
            @Value("${app.security.reset-password-expiry-minutes:15}") long resetPasswordExpiryMinutes) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.reputationRepository = reputationRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;

        this.maxLoginAttempts = maxLoginAttempts;
        this.resetPasswordExpiryMinutes = resetPasswordExpiryMinutes;
    }

    /**
     * Đăng ký người dùng mới theo SRS mục 3.1.1.
     * Giải thuật:
     * 1. Kiểm tra email đã tồn tại hay chưa. Nếu có ném lỗi.
     * 2. Băm mật khẩu bằng BCryptPasswordEncoder.
     * 3. Gán Role mặc định BUYER (hoặc USER).
     * 4. Tạo User mới, lưu vào CSDL.
     * 5. Khởi tạo Reputation mặc định 100 điểm cho người dùng.
     * 6. Ghi Audit Log hành động REGISTER.
     * 7. Phát hành JWT (Access Token + Refresh Token).
     *
     * @param request   Thông tin đăng ký
     * @param ip        Địa chỉ IP của client
     * @param userAgent Trình duyệt / thiết bị của client
     * @return AuthResponse chứa token và thông tin người dùng
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String ip, String userAgent) {
        if (userRepository.existsByEmailAndIsDeletedFalse(request.getEmail())) {
            throw new IllegalArgumentException("Email đã được đăng ký trong hệ thống: " + request.getEmail());
        }

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setKeycloakId(UUID.randomUUID().toString());
        user.setPasswordHash(null);
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

        // Gán vai trò BUYER mặc định
        Optional<Role> defaultRole = roleRepository.findByCode("BUYER");
        if (defaultRole.isPresent()) {
            user.addRole(defaultRole.get());
        } else {
            Optional<Role> anyRole = roleRepository.findAll().stream().findFirst();
            anyRole.ifPresent(user::addRole);
        }

        User savedUser = userRepository.save(user);

        // Khởi tạo điểm uy tín mặc định 100
        Reputation reputation = new Reputation(savedUser.getId());
        reputationRepository.save(reputation);

        auditService.record(savedUser.getId(), "REGISTER", "Người dùng đăng ký tài khoản mới", ip, userAgent, "SUCCESS");

        String accessToken = jwtService.generateAccessToken(savedUser);
        String refreshToken = jwtService.generateRefreshToken(savedUser);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpirySeconds(),
                mapToUserResponse(savedUser)
        );
    }

    /**
     * Xác thực người dùng và đăng nhập hệ thống theo SRS mục 3.1.1.
     * Giải thuật:
     * 1. Tìm người dùng theo email.
     * 2. Nếu không tìm thấy -> ném BadCredentialsException, ghi log thất bại.
     * 3. Kiểm tra trạng thái tài khoản: Nếu LOCKED, kiểm tra thời gian khóa lockedUntil.
     *    Nếu đã hết hạn khóa thì tự động mở khóa về ACTIVE, ngược lại ném LockedException.
     * 4. Kiểm tra mật khẩu:
     *    - Nếu sai: Tăng failedLoginAttempts. Nếu đạt >= maxLoginAttempts (5 lần) thì khóa tài khoản và set lockedUntil 30 phút.
     *    - Nếu đúng: Reset failedLoginAttempts về 0, xóa lockedUntil, ghi log thành công và phát hành JWT token.
     *
     * @param request   Thông tin đăng nhập
     * @param ip        Địa chỉ IP của client
     * @param userAgent Thiết bị của client
     * @return AuthResponse chứa token
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String ip, String userAgent) {
        String email = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmailAndIsDeletedFalse(email);

        if (optionalUser.isEmpty()) {
            auditService.record(null, "LOGIN_FAILED", "Email không tồn tại: " + email, ip, userAgent, "FAILED");
            throw new BadCredentialsException("Email hoặc mật khẩu không chính xác");
        }

        User user = optionalUser.get();

        // Kiểm tra trạng thái tài khoản bị khóa
        if ("LOCKED".equalsIgnoreCase(user.getStatus())) {
            if (user.getLockedUntil() != null && LocalDateTime.now().isBefore(user.getLockedUntil())) {
                auditService.record(user.getId(), "LOGIN_BLOCKED", "Tài khoản đang bị khóa tạm thời", ip, userAgent, "FAILED");
                throw new LockedException("Tài khoản đã bị tạm khóa do nhập sai quá 5 lần. Vui lòng thử lại sau.");
            }
            // Đã hết thời hạn khóa, mở khóa lại
            user.setStatus("ACTIVE");
            user.setLockedUntil(null);
            user.resetFailedLoginAttempts();
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            auditService.record(user.getId(), "LOGIN_BLOCKED", "Tài khoản không hoạt động: " + user.getStatus(), ip, userAgent, "FAILED");
            throw new DisabledException("Tài khoản không ở trạng thái hoạt động");
        }

        // Kiểm tra mật khẩu
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.incrementFailedLoginAttempts();
            if (user.getFailedLoginAttempts() >= maxLoginAttempts) {
                user.setStatus("LOCKED");
                user.setLockedUntil(LocalDateTime.now().plusMinutes(30));
                userRepository.save(user);
                auditService.record(user.getId(), "ACCOUNT_LOCKED", "Khóa tài khoản do nhập sai quá " + maxLoginAttempts + " lần", ip, userAgent, "FAILED");
                throw new LockedException("Bạn đã nhập sai 5 lần liên tiếp. Tài khoản đã bị tạm khóa trong 30 phút.");
            }
            userRepository.save(user);
            int remaining = maxLoginAttempts - user.getFailedLoginAttempts();
            auditService.record(user.getId(), "LOGIN_FAILED", "Mật khẩu không đúng. Còn lại: " + remaining + " lần thử", ip, userAgent, "FAILED");
            throw new BadCredentialsException("Email hoặc mật khẩu không chính xác. Bạn còn " + remaining + " lần thử.");
        }

        // Đăng nhập thành công
        user.resetFailedLoginAttempts();
        user.setLockedUntil(null);
        userRepository.save(user);

        auditService.record(user.getId(), "LOGIN_SUCCESS", "Đăng nhập thành công", ip, userAgent, "SUCCESS");

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpirySeconds(),
                mapToUserResponse(user)
        );
    }

    /**
     * Cấp lại Access Token mới từ Refresh Token hợp lệ.
     * Giải thuật:
     * 1. Trích xuất claims và kiểm tra loại token là REFRESH.
     * 2. Lấy User từ subject, so khớp token_version trong token với token_version hiện tại trong CSDL.
     *    Nếu không khớp nghĩa là phiên đăng nhập đã bị vô hiệu hóa (do đổi pass / logout).
     * 3. Sinh cặp token mới (Token Rotation).
     *
     * @param request Yêu cầu chứa refresh token
     * @return AuthResponse chứa access token mới
     */
    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        try {
            JWTClaimsSet claims = jwtService.extractClaims(request.getRefreshToken());
            String tokenType = claims.getStringClaim("token_type");
            if (!"REFRESH".equals(tokenType)) {
                throw new IllegalArgumentException("Token cung cấp không phải là Refresh Token");
            }

            UUID userId = UUID.fromString(claims.getSubject());
            Long tokenVersionClaim = claims.getLongClaim("token_version");

            User user = userRepository.findByIdAndIsDeletedFalse(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Người dùng không tồn tại"));

            if (tokenVersionClaim == null || tokenVersionClaim.intValue() != user.getTokenVersion()) {
                throw new IllegalArgumentException("Refresh token đã hết hạn hoặc bị thu hồi");
            }

            if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
                throw new DisabledException("Tài khoản người dùng đang bị khóa hoặc ngưng hoạt động");
            }

            String newAccessToken = jwtService.generateAccessToken(user);
            String newRefreshToken = jwtService.generateRefreshToken(user);

            return new AuthResponse(
                    newAccessToken,
                    newRefreshToken,
                    jwtService.getAccessTokenExpirySeconds(),
                    mapToUserResponse(user)
            );
        } catch (ParseException e) {
            throw new IllegalArgumentException("Định dạng Refresh Token không hợp lệ", e);
        }
    }

    /**
     * Tiếp nhận yêu cầu quên mật khẩu qua email theo SRS mục 3.1.1.
     * Giải thuật:
     * 1. Tìm người dùng theo email.
     * 2. Nếu tìm thấy, tạo mã token ngẫu nhiên có thời hạn quy định (15 phút).
     * 3. Gửi email chứa liên kết/token xác nhận.
     * 4. Ghi Audit Log.
     *
     * @param request   Yêu cầu chứa email
     * @param ip        Địa chỉ IP của client
     * @param userAgent Thiết bị của client
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request, String ip, String userAgent) {
        String email = request.getEmail().trim().toLowerCase();
        Optional<User> optionalUser = userRepository.findByEmailAndIsDeletedFalse(email);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            passwordResetTokenRepository.deleteByUser(user);

            String rawToken = UUID.randomUUID().toString();
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(resetPasswordExpiryMinutes);

            PasswordResetToken resetToken = new PasswordResetToken(
                    UUID.randomUUID(),
                    user,
                    rawToken,
                    expiresAt
            );
            passwordResetTokenRepository.save(resetToken);

            emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
            auditService.record(user.getId(), "FORGOT_PASSWORD_REQUEST", "Yêu cầu mã đặt lại mật khẩu qua email", ip, userAgent, "SUCCESS");
        } else {
            auditService.record(null, "FORGOT_PASSWORD_REQUEST", "Yêu cầu đặt lại mật khẩu với email không tồn tại: " + email, ip, userAgent, "FAILED");
        }
    }

    /**
     * Đặt lại mật khẩu mới thông qua token xác thực theo SRS mục 3.1.1.
     * Giải thuật:
     * 1. Tìm bản ghi token trong CSDL.
     * 2. Kiểm tra token còn hạn và chưa sử dụng.
     * 3. Kiểm tra mật khẩu mới không trùng với mật khẩu cũ.
     * 4. Cập nhật mật khẩu mới (BCrypt), tăng tokenVersion để thu hồi các token cũ.
     * 5. Đánh dấu token đã được sử dụng.
     *
     * @param request   Yêu cầu chứa token và mật khẩu mới
     * @param ip        Địa chỉ IP của client
     * @param userAgent Thiết bị của client
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request, String ip, String userAgent) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedFalse(request.getToken())
                .orElseThrow(() -> new IllegalArgumentException("Mã token không hợp lệ hoặc đã qua sử dụng"));

        if (resetToken.isExpired()) {
            throw new IllegalArgumentException("Mã token đặt lại mật khẩu đã hết hạn");
        }

        User user = resetToken.getUser();

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới không được trùng với mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.incrementTokenVersion();
        user.resetFailedLoginAttempts();
        user.setStatus("ACTIVE");
        user.setLockedUntil(null);
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        auditService.record(user.getId(), "PASSWORD_RESET_SUCCESS", "Đặt lại mật khẩu thành công bằng token", ip, userAgent, "SUCCESS");
    }

    /**
     * Đổi mật khẩu cho người dùng hiện tại đang đăng nhập.
     *
     * @param userId  Mã định danh duy nhất của người dùng
     * @param request Yêu cầu chứa mật khẩu cũ và mới
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findByIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("Người dùng không tồn tại"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Mật khẩu hiện tại không chính xác");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới không được trùng với mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.incrementTokenVersion();
        userRepository.save(user);

        auditService.record(user.getId(), "CHANGE_PASSWORD_SUCCESS", "Đổi mật khẩu tài khoản thành công", null, null, "SUCCESS");
    }

    /**
     * Chuyển đổi đối tượng User Entity sang UserResponse DTO.
     *
     * @param user Đối tượng Entity
     * @return DTO chứa thông tin an toàn của người dùng
     */
    public UserResponse mapToUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setFullName(user.getFullName());
        response.setIdentifyNumber(user.getIdentifyNumber());
        response.setGender(user.getGender());
        response.setDateOfBirth(user.getDateOfBirth());
        response.setAddress(user.getAddress());
        response.setStatus(user.getStatus());
        response.setCreatedAt(user.getCreatedAt());

        Set<String> roleCodes = user.getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());
        response.setRoles(roleCodes);

        Set<String> privilegeCodes = new HashSet<>();
        for (Role role : user.getRoles()) {
            if (role.getPrivileges() != null) {
                for (Privilege p : role.getPrivileges()) {
                    privilegeCodes.add(p.getCode());
                }
            }
        }
        response.setPrivileges(privilegeCodes);

        return response;
    }
}
