package com.nexus.fulfillment.entity;

import com.nexus.fulfillment.enums.SellerSkuStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Thực thể ánh xạ bảng seller_skus trong Fulfillment Service.
 * Quản lý mã skuCode nội bộ do Fulfillment sinh lần đầu khi người bán nhập kho,
 * tách biệt với skuId tham chiếu sang Catalog Service.
 */
@Entity
@Table(
        name = "seller_skus",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_seller_skus_sku_id", columnNames = {"sku_id"}),
                @UniqueConstraint(name = "uq_seller_skus_seller_code", columnNames = {"seller_id", "sku_code"})
        }
)
public class SellerSku {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    @Column(name = "sku_code", nullable = false, length = 100)
    private String skuCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerSkuStatus status = SellerSkuStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant updatedAt;

    public SellerSku() {
    }

    public SellerSku(UUID id, UUID sellerId, UUID skuId, String skuCode, SellerSkuStatus status) {
        this.id = id;
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.skuCode = skuCode;
        this.status = status != null ? status : SellerSkuStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public SellerSkuStatus getStatus() {
        return status;
    }

    public void setStatus(SellerSkuStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SellerSku sellerSku = (SellerSku) o;
        return Objects.equals(id, sellerSku.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
