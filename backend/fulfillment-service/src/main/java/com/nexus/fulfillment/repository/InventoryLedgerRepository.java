package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.InventoryLedger;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Repository bất biến dành riêng cho Sổ cái tồn kho (Immutable Inventory Ledger).
 *
 * Chức năng:
 * - Chỉ cho phép THÊM MỚI (INSERT/save) và ĐỌC (SELECT).
 * - Tuyệt đối không kế thừa các hàm UPDATE hoặc DELETE từ JpaRepository/CrudRepository để bảo đảm tính bất biến theo Accept Criteria.
 *
 * Quy tắc và lý do:
 * - Kế thừa trực tiếp từ org.springframework.data.repository.Repository thay vì JpaRepository để loại bỏ hoàn toàn các phương thức delete, deleteAll, update.
 * - Mọi biến động kho sau khi ghi vào sổ cái là vĩnh viễn không thể chỉnh sửa hay hoàn tác.
 */
@org.springframework.stereotype.Repository
public interface InventoryLedgerRepository extends Repository<InventoryLedger, UUID> {

    /**
     * Thêm mới một bản ghi biến động vào sổ cái tồn kho.
     *
     * @param entity Bản ghi biến động mới
     * @return Bản ghi đã lưu vào DB
     */
    InventoryLedger save(InventoryLedger entity);

    /**
     * Tra cứu bản ghi sổ cái theo ID.
     */
    Optional<InventoryLedger> findById(UUID id);

    /**
     * Tra cứu toàn bộ lịch sử sổ cái có phân trang, sắp xếp theo thời gian mới nhất trước.
     */
    Page<InventoryLedger> findAll(Pageable pageable);

    /**
     * Tra cứu lịch sử biến động sổ cái theo SKU ID có phân trang.
     */
    @Query("SELECT l FROM InventoryLedger l WHERE l.inventory.skuId = :skuId ORDER BY l.createdAt DESC")
    Page<InventoryLedger> findBySkuId(@Param("skuId") UUID skuId, Pageable pageable);

    /**
     * Tra cứu lịch sử biến động sổ cái theo mã tham chiếu đơn hàng/phiên đấu giá (ref_id).
     */
    @Query("SELECT l FROM InventoryLedger l WHERE l.refId = :refId ORDER BY l.createdAt DESC")
    Page<InventoryLedger> findByRefId(@Param("refId") UUID refId, Pageable pageable);

    /**
     * Tra cứu lọc kết hợp theo SKU và ref_id (nếu truyền).
     */
    @Query("SELECT l FROM InventoryLedger l WHERE "
            + "(:skuId IS NULL OR l.inventory.skuId = :skuId) AND "
            + "(:refId IS NULL OR l.refId = :refId) "
            + "ORDER BY l.createdAt DESC")
    Page<InventoryLedger> searchLedger(
            @Param("skuId") UUID skuId,
            @Param("refId") UUID refId,
            Pageable pageable
    );
}
