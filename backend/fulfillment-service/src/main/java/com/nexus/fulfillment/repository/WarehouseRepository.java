package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Warehouse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu kho hàng (Warehouse).
 *
 * Chức năng:
 * - Tra cứu kho hàng theo ID hoặc theo mã định danh duy nhất (ví dụ 'WH_001').
 */
@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    /**
     * Tra cứu kho hàng theo mã code nghiệp vụ (như WH_001).
     *
     * @param code Mã kho hàng
     * @return Warehouse tương ứng nếu tìm thấy
     */
    Optional<Warehouse> findByCode(String code);

    /**
     * Tra cứu kho hàng còn hoạt động theo ID.
     *
     * @param id Khóa chính của kho
     * @return Kho hàng nếu tồn tại và isActive = true
     */
    Optional<Warehouse> findByIdAndIsActiveTrue(UUID id);
}
