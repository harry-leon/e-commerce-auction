package com.nexus.user.repository;

import com.nexus.user.entity.Privilege;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu đặc quyền (Privilege) từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface PrivilegeRepository extends JpaRepository<Privilege, UUID> {

    /**
     * Tìm kiếm đặc quyền theo mã code duy nhất (ví dụ: AUTH.LOGIN, PRODUCT.CREATE).
     *
     * @param code Mã code của đặc quyền
     * @return Đối tượng Optional chứa Privilege nếu tìm thấy
     */
    Optional<Privilege> findByCode(String code);

    /**
     * Kiểm tra xem đặc quyền với mã code đã tồn tại hay chưa.
     *
     * @param code Mã code cần kiểm tra
     * @return true nếu tồn tại, ngược lại false
     */
    boolean existsByCode(String code);
}
