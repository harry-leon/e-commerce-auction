package com.nexus.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Snapshot giao dịch VNPAY; UUID là TxnRef, providerRef chỉ lưu transactionNo đã được xác minh qua IPN. */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {
    @Id private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(nullable = false, length = 30) private String provider;
    @Column(name = "provider_ref", length = 100) private String providerRef;
    @Column(nullable = false, length = 30) private String method;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 20) private String status;
    /** Kết quả kho của tiền winner đã thu: PENDING chờ worker, CONFIRMED được giao, REVIEW cần đối soát/hoàn tiền thủ công. */
    @Column(name = "inventory_status", length = 20) private String inventoryStatus;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "idempotency_key", length = 100) private String idempotencyKey;
    @Column(name = "payment_url", columnDefinition = "text") private String paymentUrl;
    @Column(name = "merchant_code", length = 100) private String merchantCode;
    @Column(name = "response_code", length = 2) private String responseCode;
    @Column(name = "transaction_status", length = 2) private String transactionStatus;
    @Column(name = "created_at", nullable = false) @JdbcTypeCode(SqlTypes.TIMESTAMP) private Instant createdAt;
    @Column(name = "expires_at") @JdbcTypeCode(SqlTypes.TIMESTAMP) private Instant expiresAt;
    @Column(name = "completed_at") @JdbcTypeCode(SqlTypes.TIMESTAMP) private Instant completedAt;
}
