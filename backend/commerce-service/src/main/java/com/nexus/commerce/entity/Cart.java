package com.nexus.commerce.entity;

import com.nexus.commerce.enums.CartStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Theo ERD, giỏ hàng chỉ lưu expires_at; không tạo hay đọc cột created_at.
 * Entity representing the carts table in commerce_svc database schema.
 * Pure JPA Entity (Anemic Domain Model) adhering to project clean architecture:
 * Contains only mapping fields, relationships, and basic getters/setters.
 * All business logic (expiration, item addition/removal) is handled in CartService.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CartStatus status = CartStatus.ACTIVE;

    @Column(name = "expires_at")
    // ERD dùng timestamp không múi giờ; JDBC ghi/đọc UTC, API vẫn trả Instant.
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant expiresAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();
}
