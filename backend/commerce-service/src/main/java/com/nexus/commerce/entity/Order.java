package com.nexus.commerce.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Đơn một seller và snapshot giá; lưu items bằng cascade trong cùng transaction, thời gian UTC theo ERD. */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {
    /** Các phiếu kho đã giữ cho đơn; payment outbox dùng ID này để confirm qua API có sẵn của Fulfillment. */
    @ElementCollection
    @CollectionTable(name = "order_reservations", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "reservation_id", nullable = false)
    private List<UUID> reservationIds = new ArrayList<>();
    @Id private UUID id;
    @Column(name = "order_no", nullable = false, length = 50) private String orderNo;
    @Column(name = "buyer_id", nullable = false) private UUID buyerId;
    @Column(name = "seller_id", nullable = false) private UUID sellerId;
    @Column(name = "source_type", nullable = false, length = 20) private String sourceType;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal subtotal;
    @Column(name = "shipping_fee", nullable = false, precision = 15, scale = 2) private BigDecimal shippingFee;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal discount;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal total;
    @Column(name = "shipping_address", nullable = false, length = 500) private String shippingAddress;
    @Column(name = "recipient_name") private String recipientName;
    @Column(name = "recipient_phone", length = 30) private String recipientPhone;
    @Column(name = "payment_method", length = 20) private String paymentMethod;
    @Column(name = "idempotency_key", unique = true, length = 100) private String idempotencyKey;
    @Column(name = "request_hash", length = 64) private String requestHash;
    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItem> items = new ArrayList<>();
}
