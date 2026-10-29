package com.nexus.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Snapshot SKU tại lúc mua; không phụ thuộc tên/giá Catalog thay đổi sau khi đơn được tạo. */
@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItem {
    @Id private UUID id;
    @Column(name = "sku_id", nullable = false) private UUID skuId;
    @Column(name = "product_name", nullable = false) private String productName;
    @Column(name = "sku_code", nullable = false, length = 100) private String skuCode;
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2) private BigDecimal unitPrice;
    @Column(nullable = false) private Integer quantity;
}
