package com.nexus.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Entity đại diện cho bảng `order_items` trong schema commerce_svc.
 *
 * <p>Chức năng: Lưu trữ snapshot của từng sản phẩm trong đơn hàng tại thời điểm đặt,
 * bao gồm tên sản phẩm, mã SKU, đơn giá mua và số lượng.
 *
 * <p>Quy tắc và lý do:
 * <ul>
 *   <li>Snapshot bất biến: Dù sau này Catalog Service có cập nhật giá hay đổi tên sản phẩm,
 *       thông tin trong order_items vẫn giữ nguyên để phục vụ hóa đơn và đối soát thanh toán.</li>
 *   <li>Ràng buộc uq_order_sku: Trong 1 đơn hàng không được có 2 dòng chứa cùng 1 SKU.</li>
 * </ul>
 */
@Entity
@Table(
        name = "order_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_order_sku", columnNames = {"order_id", "sku_id"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "sku_code", nullable = false, length = 100)
    private String skuCode;

    @Min(0)
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Min(1)
    @Column(nullable = false)
    private Integer quantity;
}
