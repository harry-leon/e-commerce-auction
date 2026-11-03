package com.nexus.fulfillment.entity;

import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
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

/**
 * Thực thể lưu trữ phiếu giữ hàng tồn kho (Inventory Reservation) khi người dùng Checkout (SRS §3.6.1).
 *
 * Chức năng:
 * - Khóa tạm giữ số lượng hàng tồn khả dụng cho một đơn hàng (ORDER) hoặc phiên đấu giá (AUCTION).
 * - Lưu mốc thời gian hết hạn (expires_at) phục vụ cơ chế tự động giải phóng hoàn kho sau 30 phút.
 *
 * Luồng xử lý:
 * - Tạo mới với trạng thái PENDING khi Commerce Service gọi reserve.
 * - Chuyển sang CONFIRMED khi đơn hàng thanh toán thành công.
 * - Chuyển sang RELEASED nếu đơn bị hủy hoặc người dùng chủ động bỏ giỏ.
 * - Chuyển sang EXPIRED khi quá 30 phút mà chưa thanh toán, được hoàn trả bởi background scheduler.
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
    private Inventory inventory;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 20)
    private ReservationRefType refType;

    @Column(name = "ref_id", nullable = false)
    private UUID refId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public InventoryReservation() {
    }

    public InventoryReservation(UUID id, Inventory inventory, int quantity, ReservationRefType refType, UUID refId, ReservationStatus status, Instant expiresAt) {
        this.id = id;
        this.inventory = inventory;
        this.quantity = quantity;
        this.refType = refType;
        this.refId = refId;
        this.status = status;
        this.expiresAt = expiresAt;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public ReservationRefType getRefType() {
        return refType;
    }

    public void setRefType(ReservationRefType refType) {
        this.refType = refType;
    }

    public UUID getRefId() {
        return refId;
    }

    public void setRefId(UUID refId) {
        this.refId = refId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InventoryReservation that = (InventoryReservation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
