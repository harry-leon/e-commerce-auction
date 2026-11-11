package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Mọi thao tác payment được service tuần tự hóa bằng khóa Order; index DB chặn trùng attempt và provider transaction. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    /** Lấy attempt mới nhất, không dùng Optional truy vấn toàn bộ vì một đơn có nhiều lần thanh toán. */
    Optional<Payment> findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(UUID orderId, String provider);
    /** Key do caller cung cấp thuộc phạm vi buyer/order; replay không tạo attempt khác dù lần cũ FAILED. */
    Optional<Payment> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);
    boolean existsByProviderAndProviderRefAndStatusIn(String provider, String reference, java.util.Collection<String> statuses);
    boolean existsByOrderIdAndStatusIn(UUID orderId, java.util.Collection<String> statuses);
    boolean existsByOrderIdAndInventoryStatus(UUID orderId, String inventoryStatus);
}
