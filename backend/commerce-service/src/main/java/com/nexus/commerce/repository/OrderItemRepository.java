package com.nexus.commerce.repository;

import com.nexus.commerce.entity.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho entity OrderItem.
 *
 * <p>Cung cấp các thao tác truy vấn các dòng sản phẩm thuộc đơn hàng.
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /**
     * Lấy toàn bộ các dòng sản phẩm snapshot thuộc về một đơn hàng cụ thể.
     *
     * @param orderId ID định danh của đơn hàng
     * @return Danh sách các dòng OrderItem
     */
    List<OrderItem> findByOrderId(UUID orderId);
}
