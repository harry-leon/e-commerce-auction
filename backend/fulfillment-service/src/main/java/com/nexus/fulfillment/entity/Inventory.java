package com.nexus.fulfillment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

/**
 * Thực thể quản lý tồn kho chi tiết cấp SKU biến thể theo từng kho hàng (SRS §3.6.1).
 *
 * Chức năng:
 * - Theo dõi chính xác 4 trạng thái tồn kho:
 *   1. total_qty: Tổng tồn kho thực tế trong kho.
 *   2. available_qty: Tồn khả dụng có thể đặt mua = total_qty - reserved_qty - unavailable_qty.
 *   3. reserved_qty: Số lượng đang bị tạm giữ bởi các yêu cầu Checkout chưa thanh toán.
 *   4. unavailable_qty: Số lượng hàng lỗi, hỏng, đang cách ly không cho phép bán.
 *
 * Quy tắc và lý do:
 * - Tuyệt đối không quản lý tồn ở cấp Product cha; mỗi bản ghi gắn chặt với một sku_id duy nhất tại một warehouse.
 * - Áp dụng @Version (Optimistic Locking) ngăn ngừa lost updates khi có nhiều luồng cập nhật.
 * - Hạn chế bán vượt tồn kho (ALLOW_OVERSELL = false) được bảo vệ bằng CHECK constraint trong DB và logic ở Service.
 */
@Entity
@Table(
        name = "inventories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_inventory_sku_warehouse", columnNames = {"sku_id", "warehouse_id"}),
                @UniqueConstraint(name = "uq_inventories_seller_sku_warehouse", columnNames = {"seller_sku_id", "warehouse_id"})
        }
)
public class Inventory {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    // Giữ đồng bộ sku_id trong giai đoạn chuyển tiếp cùng khóa ngoại seller_sku_id mới.
    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_sku_id", nullable = false)
    private SellerSku sellerSku;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "total_qty", nullable = false)
    private int totalQty = 0;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty = 0;

    @Column(name = "unavailable_qty", nullable = false)
    private int unavailableQty = 0;

    @Version
    @Column(name = "version", nullable = false)
    private int version = 1;

    public Inventory() {
    }

    public Inventory(UUID id, UUID skuId, Warehouse warehouse, int totalQty, int reservedQty, int unavailableQty) {
        this.id = id;
        this.skuId = skuId;
        this.warehouse = warehouse;
        this.totalQty = totalQty;
        this.reservedQty = reservedQty;
        this.unavailableQty = unavailableQty;
    }

    /**
     * Tính toán số lượng tồn kho khả dụng để bán theo công thức nghiệp vụ:
     * available_qty = total_qty - reserved_qty - unavailable_qty.
     * Đảm bảo không âm.
     */
    public int getAvailableQty() {
        return Math.max(0, totalQty - reservedQty - unavailableQty);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public SellerSku getSellerSku() {
        return sellerSku;
    }

    public void setSellerSku(SellerSku sellerSku) {
        this.sellerSku = sellerSku;
        if (sellerSku != null && this.skuId == null) {
            this.skuId = sellerSku.getSkuId();
        }
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public int getTotalQty() {
        return totalQty;
    }

    public void setTotalQty(int totalQty) {
        this.totalQty = totalQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }

    public void setReservedQty(int reservedQty) {
        this.reservedQty = reservedQty;
    }

    public int getUnavailableQty() {
        return unavailableQty;
    }

    public void setUnavailableQty(int unavailableQty) {
        this.unavailableQty = unavailableQty;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Inventory inventory = (Inventory) o;
        return Objects.equals(id, inventory.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
