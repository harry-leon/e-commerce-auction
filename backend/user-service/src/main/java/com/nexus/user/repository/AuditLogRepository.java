package com.nexus.user.repository;

import com.nexus.user.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu nhật ký kiểm toán (AuditLog) từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Tìm kiếm nhật ký kiểm toán phân trang theo mã người dùng.
     *
     * @param userId   Mã định danh duy nhất của người dùng
     * @param pageable Thông tin phân trang
     * @return Trang danh sách nhật ký kiểm toán của người dùng
     */
    Page<AuditLog> findByUserId(UUID userId, Pageable pageable);

    /**
     * Tìm kiếm nhật ký kiểm toán phân trang theo tên hành động.
     *
     * @param action   Tên hành động (LOGIN, REGISTER...)
     * @param pageable Thông tin phân trang
     * @return Trang danh sách nhật ký kiểm toán theo hành động
     */
    Page<AuditLog> findByAction(String action, Pageable pageable);
}
