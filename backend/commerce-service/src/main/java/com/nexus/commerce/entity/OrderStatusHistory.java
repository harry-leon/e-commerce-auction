package com.nexus.commerce.entity;

import com.nexus.commerce.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Entity đại diện cho bảng `order_status_history` trong schema commerce_svc.
 *
 * <p>Chức năng: Ghi nhận vết kiểm toán (Audit Trail) cho toàn bộ tiến trình biến đổi trạng thái của đơn hàng
 * (từ trạng thái ban đầu sang trạng thái mới, ai thực hiện, thời điểm và lý do/ghi chú).
 *
 * <p>Quy tắc và lý do:
 * <ul>
 *   <li>Tuân thủ DoD của task HG-S1-3: Lưu trữ (from_status → to_status, changed_by, timestamp, note/reason).</li>
 *   <li>Bất biến (Append-only): Bản ghi lịch sử một khi đã được tạo ra thì không được sửa đổi hoặc cập nhật.</li>
 * </ul>
 */
@Entity
@Table(name = "order_status_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusHistory {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;
}
