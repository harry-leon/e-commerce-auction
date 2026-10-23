package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho entity Order.
 *
 * <p>Cung cấp các thao tác truy vấn dữ liệu đơn hàng:
 * <ul>
 *   <li>Tra cứu theo mã đơn hàng orderNo.</li>
 *   <li>Tra cứu theo idempotencyKey để chống tạo trùng đơn hàng khi mạng bị retry.</li>
 *   <li>Tra cứu danh sách đơn hàng của người mua (kèm phân trang và lọc theo trạng thái).</li>
 * </ul>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Tìm đơn hàng theo mã đơn hiển thị (order_no).
     *
     * @param orderNo Mã đơn hàng định dạng duy nhất (ví dụ: ORD-20261023-XXXXXX)
     * @return Optional chứa Order nếu tìm thấy
     */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * Tìm đơn hàng dựa trên Idempotency Key gửi kèm trong header HTTP.
     *
     * @param idempotencyKey Chuỗi khóa chống trùng lặp do client sinh ra
     * @return Optional chứa Order đã tạo trước đó với key tương ứng
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Lấy danh sách đơn hàng của người mua có phân trang.
     *
     * @param buyerId  ID của người mua
     * @param pageable Thông tin phân trang và sắp xếp
     * @return Trang danh sách đơn hàng
     */
    Page<Order> findByBuyerId(UUID buyerId, Pageable pageable);

    /**
     * Lấy danh sách đơn hàng của người mua theo trạng thái cụ thể có phân trang.
     *
     * @param buyerId  ID của người mua
     * @param status   Trạng thái đơn hàng cần lọc
     * @param pageable Thông tin phân trang và sắp xếp
     * @return Trang danh sách đơn hàng
     */
    Page<Order> findByBuyerIdAndStatus(UUID buyerId, OrderStatus status, Pageable pageable);
}
