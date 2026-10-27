package com.nexus.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** Lưu đánh giá/ phản hồi theo ERD; các mốc thời gian dùng timestamp UTC thay vì timestamptz. */
@Entity
@Table(name = "product_reviews")
public class ProductReview {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sku_id")
    private Sku sku;

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotNull
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @NotNull
    @Min(1)
    @Max(5)
    @Column(nullable = false)
    private Integer score;

    @Column(columnDefinition = "text")
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_urls", columnDefinition = "jsonb")
    private String mediaUrls;

    @Column(name = "seller_reply", columnDefinition = "text")
    private String sellerReply;

    @Column(name = "seller_replied_at")
    // ERD dùng timestamp không múi giờ; JDBC ghi/đọc UTC, API vẫn trả Instant.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant sellerRepliedAt;

    @Column(name = "is_hidden", nullable = false)
    private Boolean isHidden = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    // ERD dùng timestamp không múi giờ; JDBC ghi/đọc UTC, API vẫn trả Instant.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    public ProductReview() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Sku getSku() { return sku; }
    public void setSku(Sku sku) { this.sku = sku; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getMediaUrls() { return mediaUrls; }
    public void setMediaUrls(String mediaUrls) { this.mediaUrls = mediaUrls; }
    public String getSellerReply() { return sellerReply; }
    public void setSellerReply(String sellerReply) { this.sellerReply = sellerReply; }
    public Instant getSellerRepliedAt() { return sellerRepliedAt; }
    public void setSellerRepliedAt(Instant sellerRepliedAt) { this.sellerRepliedAt = sellerRepliedAt; }
    public Boolean getIsHidden() { return isHidden; }
    public void setIsHidden(Boolean isHidden) { this.isHidden = isHidden; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
