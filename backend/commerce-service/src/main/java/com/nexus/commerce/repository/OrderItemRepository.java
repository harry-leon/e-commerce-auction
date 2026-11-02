package com.nexus.commerce.repository;

import com.nexus.commerce.entity.OrderItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho OrderItem entity.
 * Ánh xạ bảng order_items trong PostgreSQL (schema commerce_svc).
 *
 * <p>Chức năng:
 * Lưu trữ và truy xuất các dòng sản phẩm snapshot tại thời điểm đặt hàng.
 * Phục vụ màn hình chi tiết đơn hàng (OrderDetailResponse) và các nghiệp vụ đối soát.
 *
 * <p>Quy tắc chỉ mục:
 * Phương thức findByOrderId tận dụng cột dẫn đầu order_id trong ràng buộc
 * duy nhất uq_order_sku UNIQUE (order_id, sku_id).
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /**
     * Lấy danh sách toàn bộ mặt hàng thuộc về một đơn hàng.
     *
     * @param orderId ID của đơn hàng
     * @return danh sách các mặt hàng trong đơn
     */
    List<OrderItem> findByOrderId(UUID orderId);

    /**
     * Tìm một mặt hàng cụ thể trong đơn hàng dựa trên orderId và skuId.
     * Tận dụng tối đa index duy nhất uq_order_sku (order_id, sku_id).
     *
     * @param orderId ID đơn hàng
     * @param skuId ID SKU sản phẩm
     * @return Optional chứa OrderItem nếu tồn tại
     */
    Optional<OrderItem> findByOrderIdAndSkuId(UUID orderId, UUID skuId);

    /**
     * Xóa toàn bộ các dòng sản phẩm thuộc đơn hàng khi xóa đơn (hỗ trợ kiểm thử và dọn dẹp dữ liệu).
     *
     * @param orderId ID đơn hàng cần xóa mặt hàng
     */
    void deleteByOrderId(UUID orderId);
}
