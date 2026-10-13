package com.nexus.user.repository;

import com.nexus.user.entity.PasswordResetToken;
import com.nexus.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất token đặt lại mật khẩu từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Tìm kiếm token đặt lại mật khẩu theo chuỗi mã băm token và chưa sử dụng.
     *
     * @param tokenHash Mã token
     * @return Đối tượng Optional chứa PasswordResetToken nếu tìm thấy
     */
    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    /**
     * Xóa các token cũ của một người dùng cụ thể.
     *
     * @param user Đối tượng người dùng
     */
    void deleteByUser(User user);
}
