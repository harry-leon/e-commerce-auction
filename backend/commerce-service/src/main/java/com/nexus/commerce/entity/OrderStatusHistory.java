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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Entity ánh xạ bảng order_status_history trong cơ sở dữ liệu commerce_svc (V2__init_schema.sql).
 * Lưu vết kiểm toán bất biến (immutable audit log) cho từng bước chuyển đổi trạng thái của đơn hàng.
 *
 * <p>LƯU Ý QUAN TRỌNG VỀ RÀNG BUỘC SCHEMA DATABASE:
 * <ul>
 *   <li>Bảng vật lý order_status_history CHỈ CÓ 6 CỘT: id, order_id, from_status, to_status, reason, changed_at.</li>
 *   <li>TUYỆT ĐỐI KHÔNG KHAI BÁO CỘT actor/actor_id/actor_role vì ddl-auto: validate sẽ báo lỗi thiếu cột ngay lập tức.</li>
 *   <li>Mọi thông tin về vai trò hoặc người thực hiện (ví dụ: "[BUYER]", "[SELLER]", "[ADMIN]") được định dạng trong cột reason.</li>
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

    /** Khóa chính bản ghi lịch sử trạng thái (UUID tự sinh). */
    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Đơn hàng cha của bản ghi lịch sử này. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Trạng thái trước khi chuyển (null đối với bản ghi đầu tiên khi tạo đơn). */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    /** Trạng thái mới được chuyển tới (bắt buộc NOT NULL). */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    /** Lý do hoặc ghi chú chuyển trạng thái (chứa context actor nếu có, tối đa 500 ký tự). */
    @Column(length = 500)
    private String reason;

    /** Thời điểm ghi nhận bước chuyển trạng thái (lưu timestamp UTC theo ERD). */
    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Builder.Default
    private Instant changedAt = Instant.now();
}
