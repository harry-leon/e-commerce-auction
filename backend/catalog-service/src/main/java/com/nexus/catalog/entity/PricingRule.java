package com.nexus.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Quy tắc tính giá bán động từ giá nhập tối thiểu (Sku.originalPrice) của sản phẩm.
 * Hỗ trợ cấu hình toàn hệ thống (category = null) hoặc ghi đè theo danh mục và khung thời gian hiệu lực.
 */
@Entity
@Table(name = "pricing_rules")
public class PricingRule {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @NotBlank
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String name;

    /** NULL áp dụng cho mọi danh mục nếu không có quy tắc riêng ở danh mục sản phẩm hoặc cha. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @NotNull
    @DecimalMin(value = "0", inclusive = true, message = "marginRate must be >= 0")
    @DecimalMax(value = "10", inclusive = true, message = "marginRate must be <= 10")
    @Column(name = "margin_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal marginRate = BigDecimal.ZERO;

    @NotNull
    @DecimalMin(value = "-1", inclusive = true, message = "seasonFactor must be >= -1")
    @DecimalMax(value = "10", inclusive = true, message = "seasonFactor must be <= 10")
    @Column(name = "season_factor", nullable = false, precision = 7, scale = 4)
    private BigDecimal seasonFactor = BigDecimal.ZERO;

    @NotNull
    @DecimalMin(value = "-1", inclusive = true, message = "marketAdjustmentRate must be >= -1")
    @DecimalMax(value = "10", inclusive = true, message = "marketAdjustmentRate must be <= 10")
    @Column(name = "market_adjustment_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal marketAdjustmentRate = BigDecimal.ZERO;

    @NotNull
    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "start_date")
    // Lưu timestamp UTC không múi giờ đồng nhất với các bảng Catalog hiện hữu.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant startDate;

    @Column(name = "end_date")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant endDate;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant updatedAt;

    public PricingRule() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public BigDecimal getMarginRate() {
        return marginRate;
    }

    public void setMarginRate(BigDecimal marginRate) {
        this.marginRate = marginRate;
    }

    public BigDecimal getSeasonFactor() {
        return seasonFactor;
    }

    public void setSeasonFactor(BigDecimal seasonFactor) {
        this.seasonFactor = seasonFactor;
    }

    public BigDecimal getMarketAdjustmentRate() {
        return marketAdjustmentRate;
    }

    public void setMarketAdjustmentRate(BigDecimal marketAdjustmentRate) {
        this.marketAdjustmentRate = marketAdjustmentRate;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Instant getStartDate() {
        return startDate;
    }

    public void setStartDate(Instant startDate) {
        this.startDate = startDate;
    }

    public Instant getEndDate() {
        return endDate;
    }

    public void setEndDate(Instant endDate) {
        this.endDate = endDate;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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
}
