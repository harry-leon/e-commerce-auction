package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Order;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tra cứu đơn đã commit để replay idempotency và đếm hạn mức ngày; unique key DB là lớp bảo vệ cuối. */
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String key);
    long countByBuyerIdAndCreatedAtGreaterThanEqual(UUID buyerId, Instant start);
}
