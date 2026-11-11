package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Inventory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository thao tác với bảng inventories kèm cơ chế khóa dòng đồng thời (Concurrency Control).
 *
 * Chức năng:
 * - Tra cứu và khóa ghi (PESSIMISTIC_WRITE) dòng tồn kho theo SKU và kho hàng để bảo đảm tính nguyên tử (Atomic).
 * - Cung cấp các hàm kiểm tra tồn kho hàng loạt cho Cart và Checkout.
 *
 * Quy tắc và lý do:
 * - Khóa Pessimistic Locking ngăn ngừa race-condition tuyệt đối khi nhiều khách hàng cùng tranh mua 1 SKU cuối cùng.
 * - Sắp xếp theo sku_id khi khóa nhiều mặt hàng cùng lúc để ngăn chặn Deadlock giữa các giao dịch đồng thời.
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    /**
     * Tra cứu thông tin tồn kho thông thường theo SKU và kho hàng (không khóa).
     */
    Optional<Inventory> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId);

    /**
     * Tra cứu tất cả bản ghi tồn kho của một SKU trên các kho hàng khác nhau.
     */
    List<Inventory> findBySkuId(UUID skuId);

    /**
     * Tra cứu danh sách tồn kho cho tập hợp SKU trên mọi kho hàng.
     */
    List<Inventory> findBySkuIdIn(Collection<UUID> skuIds);

    /**
     * Tra cứu tất cả bản ghi tồn kho theo danh sách seller_sku_id (dùng cho màn hình danh sách Seller SKUs).
     */
    @Query("SELECT i FROM Inventory i JOIN FETCH i.warehouse WHERE i.sellerSku.id IN :sellerSkuIds")
    List<Inventory> findBySellerSkuIdInWithWarehouse(@Param("sellerSkuIds") Collection<UUID> sellerSkuIds);

    /**
     * Tra cứu và khóa dòng theo seller_sku_id và warehouse_id trong luồng nhập kho mới.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.sellerSku.id = :sellerSkuId AND i.warehouse.id = :warehouseId")
    Optional<Inventory> findBySellerSkuIdAndWarehouseIdForUpdate(
            @Param("sellerSkuId") UUID sellerSkuId,
            @Param("warehouseId") UUID warehouseId
    );

    /**
     * Tra cứu và khóa dòng (SELECT ... FOR UPDATE) bản ghi tồn kho theo SKU và kho hàng.
     * Sử dụng khi thực hiện giữ hàng (Reserve), xuất kho (Outbound) hoặc hoàn kho (Release).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.skuId = :skuId AND i.warehouse.id = :warehouseId")
    Optional<Inventory> findBySkuIdAndWarehouseIdForUpdate(
            @Param("skuId") UUID skuId,
            @Param("warehouseId") UUID warehouseId
    );

    /**
     * Tra cứu và khóa dòng theo ID bản ghi tồn kho.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.id = :id")
    Optional<Inventory> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Tra cứu và khóa nhiều dòng tồn kho theo danh sách SKU trong cùng một kho, sắp xếp tăng dần theo sku_id.
     * Thứ tự sắp xếp nhất quán giúp tránh chu trình chờ khóa (Deadlock) giữa các luồng.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.skuId IN :skuIds AND i.warehouse.id = :warehouseId ORDER BY i.skuId ASC")
    List<Inventory> findBySkuIdsAndWarehouseIdForUpdateOrdered(
            @Param("skuIds") Collection<UUID> skuIds,
            @Param("warehouseId") UUID warehouseId
    );
}
