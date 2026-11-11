package com.nexus.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entity ánh xạ bảng order_items trong cơ sở dữ liệu commerce_svc (V2__init_schema.sql).
 *
 * <p>Đại diện cho dòng sản phẩm snapshot trong đơn hàng:
 * <ul>
 *   <li>Lưu vết cố định tên sản phẩm, mã SKU, và đơn giá tại thời điểm đặt hàng.</li>
 *   <li>Ràng buộc duy nhất uq_order_sku đảm bảo một SKU chỉ xuất hiện một lần trong một đơn hàng.</li>
 *   <li>Số lượng quantity phải lớn hơn 0 và đơn giá unitPrice không âm.</li>
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

    /** Khóa chính dòng sản phẩm (UUID tự sinh). */
    @Id
    @Column(nullable = false, updatable = false)
    // UUID cấp tại ứng dụng trước reserve; không dùng generated-ID để Hibernate nhận đúng entity mới.
    @Builder.Default
    private UUID id = UUID.randomUUID();

    /** Đơn hàng cha chứa dòng sản phẩm này. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** ID của biến thể SKU từ Catalog Service (không thay đổi sau khi tạo). */
    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    /** Tên sản phẩm được lưu snapshot tại thời điểm đặt mua. */
    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    /** Mã định danh biến thể SKU được lưu snapshot. */
    @Column(name = "sku_code", nullable = false, length = 100)
    private String skuCode;

    /** Giá dự kiến lúc tạo đơn; với AUCTION đây là giá settlement, không đọc lại giá bán thường. */
    @Column(name = "estimated_unit_price", precision = 15, scale = 2)
    private BigDecimal estimatedUnitPrice;

    /** Giá chính thức, null trước khi xác thực thu tiền; chỉ chốt bằng snapshot Payment hoặc xác nhận COD. */
    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    /** Số lượng sản phẩm đặt mua (quantity > 0 theo ràng buộc chk_order_item_quantity). */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * Tính thành tiền của dòng sản phẩm (đơn giá nhân số lượng).
     *
     * @return thành tiền chính thức; null nếu chưa có đơn giá hoặc số lượng.
     */
    public BigDecimal getSubtotal() {
        if (unitPrice == null || quantity == null) {
            // null nghĩa là chưa chốt giá; không đổi thành 0 vì có thể bị hiểu nhầm là sản phẩm miễn phí.
            return null;
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
