package com.nexus.catalog.entity;

import com.nexus.catalog.enums.SkuStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Biến thể sản phẩm (SKU) thuộc Catalog Service.
 * Catalog chỉ sở hữu định danh UUID (id), thuộc tính biến thể, giá nhập tối thiểu (originalPrice)
 * và trạng thái; mã skuCode kho nội bộ do Fulfillment Service cấp phát riêng khi nhập kho.
 */
@Entity
@Table(name = "skus")
public class Sku {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String attributes;

    // SKU chỉ lưu giá nhập tối thiểu (minPrice); giá bán thực tế được tính động qua DynamicPricingService.
    @NotNull
    @DecimalMin(value = "0", inclusive = true, message = "original price must be >= 0")
    @Column(name = "original_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal originalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkuStatus status = SkuStatus.ACTIVE;

    public Sku() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public SkuStatus getStatus() { return status; }
    public void setStatus(SkuStatus status) { this.status = status; }
}
