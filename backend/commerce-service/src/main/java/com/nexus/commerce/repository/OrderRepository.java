package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Order;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Tra cứu đơn đã commit để replay idempotency và đếm hạn mức ngày; unique key DB là lớp bảo vệ cuối. */
public interface OrderRepository extends JpaRepository<Order, UUID> {
    /** Khóa Order trước Payment trong cả khởi tạo/IPN để callback đồng thời chỉ chuyển trạng thái một lần. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForPayment(@Param("id") UUID id);
    Optional<Order> findByIdempotencyKey(String key);
    long countByBuyerIdAndCreatedAtGreaterThanEqual(UUID buyerId, Instant start);
}
