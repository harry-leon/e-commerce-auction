package com.nexus.fulfillment.entity;

import com.nexus.fulfillment.enums.LedgerMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Thực thể Sổ cái tồn kho bất biến (Immutable Inventory Ledger) theo SRS §3.6.1.
 *
 * Chức năng:
 * - Ghi lại chi tiết toàn bộ lịch sử biến động số lượng tồn kho (INBOUND, OUTBOUND, RESERVE, RELEASE, ADJUSTMENT).
 * - Lưu mốc thời gian, số lượng biến động, loại tham chiếu (ORDER/AUCTION) và mã tham chiếu nghiệp vụ (ref_id).
 *
 * Quy tắc và lý do:
 * - Bất biến tuyệt đối: sử dụng @Immutable của Hibernate để cấm hoàn toàn thao tác UPDATE.
 * - Không có API hoặc service method nào hỗ trợ UPDATE hoặc DELETE các bản ghi sổ cái.
 * - Khớp với CHECK constraint 'chk_ledger_quantity' (quantity <> 0) và 'chk_ledger_movement_type'.
 */
@Entity
@Immutable
@Table(name = "inventory_ledger")
public class InventoryLedger {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
    private Inventory inventory;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private LedgerMovementType movementType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "ref_type", nullable = false, length = 20)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Khóa chống lặp cho các thao tác nhập/điều chỉnh kho khi client retry cùng request.
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    // Lý do nghiệp vụ của biến động kho (kiểm kê, hư hỏng, hủy đơn, thanh toán thất bại...).
    @Column(name = "reason", length = 500)
    private String reason;

    // Định danh người thực hiện thao tác (seller/admin) nếu phát sinh từ người dùng xác thực.
    @Column(name = "actor_id")
    private UUID actorId;

    public InventoryLedger() {
    }

    public InventoryLedger(
            UUID id,
            Inventory inventory,
            LedgerMovementType movementType,
            int quantity,
            String refType,
            UUID refId,
            Instant createdAt
    ) {
        this(id, inventory, movementType, quantity, refType, refId, createdAt, null, null, null);
    }

    /**
     * Khởi tạo bản ghi sổ cái đầy đủ kèm khóa chống lặp, lý do và tác nhân thao tác.
     */
    public InventoryLedger(
            UUID id,
            Inventory inventory,
            LedgerMovementType movementType,
            int quantity,
            String refType,
            UUID refId,
            Instant createdAt,
            String idempotencyKey,
            String reason,
            UUID actorId
    ) {
        this.id = id;
        this.inventory = inventory;
        this.movementType = movementType;
        this.quantity = quantity;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = (createdAt != null) ? createdAt : Instant.now();
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.actorId = actorId;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public LedgerMovementType getMovementType() {
        return movementType;
    }

    public void setMovementType(LedgerMovementType movementType) {
        this.movementType = movementType;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getRefType() {
        return refType;
    }

    public void setRefType(String refType) {
        this.refType = refType;
    }

    public UUID getRefId() {
        return refId;
    }

    public void setRefId(UUID refId) {
        this.refId = refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InventoryLedger that = (InventoryLedger) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
