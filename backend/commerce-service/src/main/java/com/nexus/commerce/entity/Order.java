package com.nexus.commerce.entity;

import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Entity đại diện cho bảng `orders` trong schema commerce_svc.
 *
 * <p>Chức năng: Quản lý thông tin đơn hàng, tổng giá trị, địa chỉ giao hàng và trạng thái đơn hàng.
 * Tuân thủ mô hình Anemic Domain Model theo kiến trúc chuẩn của Project Nexus:
 * chỉ chứa ánh xạ JPA, thuộc tính và liên kết quan hệ; toàn bộ logic nghiệp vụ được xử lý ở OrderService.
 *
 * <p>Quy tắc và lý do:
 * <ul>
 *   <li>Khoá ngoại và liên kết: Quản lý các dòng sản phẩm (order_items) và lịch sử trạng thái (order_status_history).</li>
 *   <li>Idempotency: `idempotency_key` đảm bảo tính bất biến (idempotent) khi người dùng hoặc mạng retry đơn hàng.</li>
 *   <li>Snapshot: Lưu địa chỉ giao hàng và thông tin người nhận tại thời điểm đặt đơn.</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_no", nullable = false, unique = true, length = 50)
    private String orderNo;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private UUID buyerId;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    @Builder.Default
    private OrderSourceType sourceType = OrderSourceType.REGULAR;

    @Column(name = "auction_id")
    private UUID auctionId;

    @Column(name = "voucher_id")
    private UUID voucherId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Min(0)
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Min(0)
    @Column(name = "shipping_fee", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Min(0)
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Min(0)
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    @Column(name = "recipient_name", length = 255)
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderStatusHistory> statusHistories = new ArrayList<>();

    /**
     * Phương thức helper để thêm một dòng chi tiết đơn hàng và đồng bộ hai chiều.
     */
    public void addItem(OrderItem item) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(item);
        item.setOrder(this);
    }

    /**
     * Phương thức helper để ghi nhận một bản ghi thay đổi trạng thái và đồng bộ hai chiều.
     */
    public void addStatusHistory(OrderStatusHistory history) {
        if (statusHistories == null) {
            statusHistories = new ArrayList<>();
        }
        statusHistories.add(history);
        history.setOrder(this);
    }
}
