package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

/**
 * Spring Data JPA Repository cho Order entity.
 * Ánh xạ bảng orders trong PostgreSQL (schema commerce_svc).
 *
 * <p>Chức năng:
 * Cung cấp các phương thức truy vấn danh sách đơn hàng cho Buyer, Seller,
 * tra cứu đơn hàng theo mã công khai (orderNo) và khóa idempotent (idempotencyKey).
 * Kế thừa JpaSpecificationExecutor để hỗ trợ lọc động đa điều kiện (status, date range, keyword).
 *
 * <p>Quy tắc chỉ mục:
 * <ul>
 *   <li>findByBuyerId*: tận dụng chỉ mục idx_orders_buyer trên cột buyer_id.</li>
 *   <li>findBySellerId*: tận dụng chỉ mục idx_orders_seller trên cột seller_id.</li>
 *   <li>findByOrderNo: tận dụng unique index trên cột order_no.</li>
 *   <li>findByIdempotencyKey: tận dụng unique index trên cột idempotency_key.</li>
 * </ul>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
    /** Kh?a chung cho IPN, h?y v? chuy?n tr?ng th?i; ??c l?i sau khi ch? ?? kh?ng ghi ?? tr?ng th?i c?a transaction kh?c. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForPayment(UUID id);

    /** ??m ??n theo ng?y Vi?t Nam do CheckoutService t?nh bi?n; buyer lock b?o v? gi?i h?n khi checkout ??ng th?i. */
    long countByBuyerIdAndCreatedAtGreaterThanEqual(UUID buyer, Instant start);


    /**
     * Tìm đơn hàng theo mã hiển thị công khai (orderNo).
     *
     * @param orderNo mã định danh đơn hàng hiển thị (ví dụ: ORD-20261029-001)
     * @return Optional chứa Order nếu tìm thấy
     */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * Kiểm tra sự tồn tại của mã đơn hàng.
     *
     * @param orderNo mã định danh đơn hàng
     * @return true nếu mã đơn đã tồn tại
     */
    boolean existsByOrderNo(String orderNo);

    /**
     * Tìm đơn hàng theo khóa Idempotency do client cung cấp qua HTTP header.
     * Dùng để ngăn chặn trùng lặp đơn hàng khi có network retry (R4 / SRS §3.4.3.7).
     *
     * @param idempotencyKey khóa idempotent duy nhất
     * @return Optional chứa Order đã xử lý trước đó nếu có
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Kiểm tra sự tồn tại của khóa Idempotency.
     *
     * @param idempotencyKey khóa idempotent
     * @return true nếu khóa đã được sử dụng
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Lấy toàn bộ đơn hàng của một người mua (phân trang).
     * Sử dụng index idx_orders_buyer.
     *
     * @param buyerId ID người mua
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByBuyerId(UUID buyerId, Pageable pageable);

    /**
     * Lấy đơn hàng của người mua được lọc theo trạng thái cụ thể (phân trang).
     * Sử dụng kết hợp index idx_orders_buyer và idx_orders_status.
     *
     * @param buyerId ID người mua
     * @param status trạng thái đơn hàng cần lọc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng thỏa mãn điều kiện
     */
    Page<Order> findByBuyerIdAndStatus(UUID buyerId, OrderStatus status, Pageable pageable);

    /**
     * Lấy đơn hàng của người mua trong khoảng thời gian tạo đơn (phân trang).
     * Sử dụng index idx_orders_buyer và quét điều kiện createdAt.
     *
     * @param buyerId ID người mua
     * @param start mốc thời gian bắt đầu (bao gồm)
     * @param end mốc thời gian kết thúc (bao gồm)
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByBuyerIdAndCreatedAtBetween(
            UUID buyerId,
            Instant start,
            Instant end,
            Pageable pageable
    );

    /**
     * Lấy đơn hàng của người mua kết hợp đồng thời bộ lọc trạng thái và khoảng thời gian tạo đơn.
     *
     * @param buyerId ID người mua
     * @param status trạng thái đơn hàng
     * @param start mốc thời gian bắt đầu
     * @param end mốc thời gian kết thúc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByBuyerIdAndStatusAndCreatedAtBetween(
            UUID buyerId,
            OrderStatus status,
            Instant start,
            Instant end,
            Pageable pageable
    );

    /**
     * Lấy toàn bộ đơn hàng thuộc về một người bán (phân trang).
     * Sử dụng index idx_orders_seller.
     *
     * @param sellerId ID người bán
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findBySellerId(UUID sellerId, Pageable pageable);

    /**
     * Lấy đơn hàng của người bán được lọc theo trạng thái cụ thể (phân trang).
     * Sử dụng kết hợp index idx_orders_seller và idx_orders_status.
     *
     * @param sellerId ID người bán
     * @param status trạng thái đơn hàng
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findBySellerIdAndStatus(UUID sellerId, OrderStatus status, Pageable pageable);

    /**
     * Lấy đơn hàng của người bán trong khoảng thời gian tạo đơn (phân trang).
     *
     * @param sellerId ID người bán
     * @param start mốc thời gian bắt đầu
     * @param end mốc thời gian kết thúc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findBySellerIdAndCreatedAtBetween(
            UUID sellerId,
            Instant start,
            Instant end,
            Pageable pageable
    );

    /**
     * Lấy đơn hàng của người bán kết hợp cả bộ lọc trạng thái và khoảng thời gian tạo đơn.
     *
     * @param sellerId ID người bán
     * @param status trạng thái đơn hàng
     * @param start mốc thời gian bắt đầu
     * @param end mốc thời gian kết thúc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findBySellerIdAndStatusAndCreatedAtBetween(
            UUID sellerId,
            OrderStatus status,
            Instant start,
            Instant end,
            Pageable pageable
    );

    /**
     * Lấy toàn bộ đơn hàng toàn hệ thống theo trạng thái (dành cho Admin quản trị, có phân trang).
     *
     * @param status trạng thái đơn hàng
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Lấy toàn bộ đơn hàng toàn hệ thống theo khoảng thời gian tạo (dành cho Admin, có phân trang).
     *
     * @param start mốc thời gian bắt đầu
     * @param end mốc thời gian kết thúc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByCreatedAtBetween(
            Instant start,
            Instant end,
            Pageable pageable
    );

    /**
     * Lấy toàn bộ đơn hàng toàn hệ thống kết hợp trạng thái và khoảng thời gian (dành cho Admin, có phân trang).
     *
     * @param status trạng thái đơn hàng
     * @param start mốc thời gian bắt đầu
     * @param end mốc thời gian kết thúc
     * @param pageable thông tin phân trang
     * @return trang danh sách đơn hàng
     */
    Page<Order> findByStatusAndCreatedAtBetween(
            OrderStatus status,
            Instant start,
            Instant end,
            Pageable pageable
    );
}
