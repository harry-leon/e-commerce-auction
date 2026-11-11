package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Warehouse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu kho hàng (Warehouse).
 *
 * Chức năng:
 * - Tra cứu kho hàng theo ID, mã định danh duy nhất (ví dụ 'WH_001') và hỗ trợ lọc/phân trang danh sách kho.
 */
@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {

    /**
     * Tra cứu kho hàng theo mã code nghiệp vụ (như WH_001).
     *
     * @param code Mã kho hàng
     * @return Warehouse tương ứng nếu tìm thấy
     */
    Optional<Warehouse> findByCode(String code);

    /**
     * Tra cứu kho hàng theo mã code không phân biệt hoa thường phục vụ API tìm theo mã kho.
     */
    Optional<Warehouse> findByCodeIgnoreCase(String code);

    /**
     * Kiểm tra trùng mã kho trước khi tạo kho mới.
     */
    boolean existsByCodeIgnoreCase(String code);

    /**
     * Lấy danh sách kho đang hoạt động sắp xếp theo mã kho tăng dần.
     */
    List<Warehouse> findByIsActiveTrueOrderByCodeAsc();

    /**
     * Tra cứu kho hàng còn hoạt động theo ID.
     *
     * @param id Khóa chính của kho
     * @return Kho hàng nếu tồn tại và isActive = true
     */
    Optional<Warehouse> findByIdAndIsActiveTrue(UUID id);
}
