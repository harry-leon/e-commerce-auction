package com.nexus.user.repository;

import com.nexus.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu người dùng (User) từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Subject Keycloak được tra riêng để hỗ trợ profile có id nghiệp vụ khác sub. */
    Optional<User> findByKeycloakIdAndIsDeletedFalse(UUID keycloakId);

    /** Bao gồm cả hồ sơ đã xóa mềm để đồng bộ không tạo lại identity đã có ràng buộc duy nhất. */
    Optional<User> findByKeycloakId(UUID keycloakId);

    /** Email unique kể cả hồ sơ đã xóa mềm; tránh đụng constraint khi đăng ký lại. */
    boolean existsByEmail(String email);

    /**
     * Tìm kiếm người dùng theo địa chỉ email và chưa bị đánh dấu xóa mềm.
     *
     * @param email Địa chỉ email của người dùng
     * @return Đối tượng Optional chứa User nếu tìm thấy, ngược lại rỗng
     */
    Optional<User> findByEmailAndIsDeletedFalse(String email);

    /**
     * Kiểm tra xem địa chỉ email đã tồn tại trong hệ thống hay chưa.
     *
     * @param email Địa chỉ email cần kiểm tra
     * @return true nếu đã tồn tại, ngược lại false
     */
    boolean existsByEmailAndIsDeletedFalse(String email);

    /**
     * Tìm kiếm người dùng theo ID và chưa bị xóa mềm.
     *
     * @param id Mã định danh duy nhất của người dùng
     * @return Đối tượng Optional chứa User nếu tìm thấy
     */
    Optional<User> findByIdAndIsDeletedFalse(UUID id);

    /**
     * Tìm kiếm phân trang danh sách người dùng với từ khóa tìm kiếm theo tên hoặc email.
     *
     * @param keyword  Từ khóa tìm kiếm (tên hoặc email)
     * @param pageable Thông tin phân trang và sắp xếp
     * @return Trang danh sách người dùng thỏa điều kiện
     */
    @Query("SELECT u FROM User u WHERE u.isDeleted = false AND ("
            + "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<User> searchUsers(@Param("keyword") String keyword, Pageable pageable);
}
