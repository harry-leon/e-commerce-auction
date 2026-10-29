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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Entity ánh xạ bảng orders trong cơ sở dữ liệu commerce_svc (V2__init_schema.sql).
 *
 * <p>Mô hình thực thể thuần (Anemic Domain Model) theo kiến trúc chuẩn của dự án Nexus:
 * <ul>
 *   <li>Chứa các trường ánh xạ bảng, mối quan hệ và phương thức tiện ích quản lý quan hệ hai chiều.</li>
 *   <li>Toàn bộ quy tắc xác thực máy trạng thái và phân quyền do OrderStatusStateMachine và OrderService đảm nhiệm.</li>
 *   <li>Tuân thủ nghiêm ngặt Hibernate ddl-auto: validate (khớp 100% cột vật lý trong PostgreSQL, không thêm trường ngoài ERD).</li>
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

    /** Mã định danh đơn hàng (Primary Key, UUID tự sinh). */
    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Mã tham chiếu đơn hàng thân thiện với người dùng (duy nhất, ví dụ: ORD-20261029-001). */
    @Column(name = "order_no", nullable = false, unique = true, length = 50)
    private String orderNo;

    /** ID của người mua (Keycloak User ID, không thể sửa đổi sau khi tạo). */
    @Column(name = "buyer_id", nullable = false, updatable = false)
    private UUID buyerId;

    /** ID của người bán hoặc chủ gian hàng (Keycloak User ID, không thể sửa đổi sau khi tạo). */
    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    /** Nguồn phát sinh đơn hàng: Mua thông thường (REGULAR) hoặc từ phiên đấu giá (AUCTION). */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    @Builder.Default
    private OrderSourceType sourceType = OrderSourceType.REGULAR;

    /** ID phiên đấu giá nếu đơn hàng xuất phát từ đấu giá (bắt buộc nếu sourceType là AUCTION). */
    @Column(name = "auction_id")
    private UUID auctionId;

    /** ID phiếu giảm giá được áp dụng cho đơn hàng (FK vouchers.id, có thể null). */
    @Column(name = "voucher_id")
    private UUID voucherId;

    /** Trạng thái hiện tại của đơn hàng theo máy trạng thái OrderStatus. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    /** Tổng giá trị tiền hàng trước giảm giá (subtotal >= 0). */
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    /** Cước phí vận chuyển (shipping_fee >= 0). */
    @Column(name = "shipping_fee", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    /** Số tiền được giảm giá qua voucher hoặc khuyến mãi (discount >= 0). */
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    /** Tổng số tiền người mua phải thanh toán: max(0, subtotal - discount) + shipping_fee. */
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    /** Địa chỉ giao hàng snapshot tại thời điểm đặt hàng (tên, SĐT, địa chỉ chi tiết, tối đa 500 ký tự). */
    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    /** Khóa chống trùng lặp request đặt hàng (Idempotency Key, duy nhất). */
    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    /** Thời điểm tạo đơn hàng (lưu timestamp UTC theo ERD). */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Danh sách các dòng sản phẩm snapshot thuộc đơn hàng. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    /** Lịch sử chuyển đổi trạng thái của đơn hàng, sắp xếp theo thời gian tăng dần. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    @Builder.Default
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    /**
     * Thêm dòng sản phẩm vào đơn hàng và thiết lập quan hệ hai chiều.
     *
     * @param item dòng sản phẩm cần thêm
     */
    public void addItem(OrderItem item) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(item);
        item.setOrder(this);
    }

    /**
     * Ghi nhận một bước chuyển trạng thái mới vào lịch sử đơn hàng và thiết lập quan hệ hai chiều.
     *
     * @param history bản ghi lịch sử trạng thái
     */
    public void addStatusHistory(OrderStatusHistory history) {
        if (statusHistory == null) {
            statusHistory = new ArrayList<>();
        }
        statusHistory.add(history);
        history.setOrder(this);
    }
}
