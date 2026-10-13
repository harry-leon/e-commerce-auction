package com.nexus.user.repository;

import com.nexus.user.entity.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu vai trò (Role) từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    /**
     * Tìm kiếm vai trò theo mã code duy nhất (ví dụ: BUYER, SELLER, ADMIN).
     *
     * @param code Mã code của vai trò
     * @return Đối tượng Optional chứa Role nếu tìm thấy
     */
    Optional<Role> findByCode(String code);

    /**
     * Kiểm tra xem vai trò với mã code đã tồn tại hay chưa.
     *
     * @param code Mã code cần kiểm tra
     * @return true nếu tồn tại, ngược lại false
     */
    boolean existsByCode(String code);
}
