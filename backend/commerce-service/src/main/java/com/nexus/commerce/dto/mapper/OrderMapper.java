package com.nexus.commerce.dto.mapper;

import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderItemResponse;
import com.nexus.commerce.dto.response.OrderStatusHistoryResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.entity.OrderStatusHistory;
import com.nexus.commerce.enums.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Component chuyển đổi dữ liệu (Mapper) giữa các thực thể Order và các DTO phản
 * hồi API.
 *
 * <p>
 * Chức năng:
 * <ul>
 * <li>Chuyển đổi thực thể Order sang OrderSummaryResponse phục vụ danh sách tóm
 * tắt có phân trang.</li>
 * <li>Chuyển đổi thực thể Order sang OrderDetailResponse kèm snapshot mặt hàng
 * và dòng thời gian lịch sử trạng thái.</li>
 * <li>Trích xuất hoặc suy luận tác nhân thực hiện (actor) từ nội dung cột
 * reason trong order_status_history.</li>
 * </ul>
 *
 * <p>
 * Đầu vào và kết quả:
 * <ul>
 * <li>Đầu vào: Thực thể Order, OrderItem, OrderStatusHistory từ tầng cơ sở dữ
 * liệu.</li>
 * <li>Kết quả: Các DTO bất biến (record) tương ứng, định dạng chuẩn tiền tệ và
 * thời gian UTC.</li>
 * </ul>
 *
 * <p>
 * Luồng xử lý:
 * Nhận entity từ Service -> kiểm tra null an toàn -> chuẩn hóa BigDecimal scale
 * 2, RoundingMode.HALF_UP ->
 * ánh xạ danh sách con -> giải mã actor -> khởi tạo DTO record.
 *
 * <p>
 * Quy tắc và lý do:
 * Đặt trong package com.nexus.commerce.dto.mapper đồng bộ với CartMapper và
 * chuẩn kiến trúc Nexus.
 * Sử dụng Spring @Component thuần Java 21 tương thích hoàn hảo với Java record.
 * Đảm bảo an toàn null tuyệt đối
 * trên mọi phương thức để tránh NullPointerException khi quan hệ Lazy chưa được
 * nạp.
 */
@Component
public class OrderMapper {

    private static final Pattern ACTOR_TAG_PATTERN = Pattern.compile("^\\[([A-Za-z0-9_]+)\\]");

    /**
     * Chức năng: Chuyển đổi thực thể Order thành OrderSummaryResponse DTO cho danh sách đơn hàng.
     * Đầu vào và kết quả: Nhận Order entity từ DB, trả về OrderSummaryResponse tóm tắt (hoặc null nếu order null).
     * Luồng xử lý: Kiểm tra null an toàn -> kiểm tra proxy Hibernate đã nạp trước khi đếm số item -> làm tròn tiền tệ scale 2 -> tạo record DTO.
     * Quy tắc và lý do: Dùng try-catch kết hợp Hibernate.isInitialized() để tuyệt đối không gây LazyInitializationException
     * khi danh sách đơn hàng được truy vấn mà không join fetch bảng items.
     * Lỗi và giới hạn: Nếu proxy items chưa nạp, itemCount mặc định là 0.
     */
    public OrderSummaryResponse toSummaryResponse(Order order) {
        if (order == null) {
            return null;
        }

        int itemCount = 0;
        try {
            if (order.getItems() != null && org.hibernate.Hibernate.isInitialized(order.getItems())) {
                itemCount = order.getItems().size();
            }
        } catch (Exception ignored) {
            itemCount = 0;
        }

        BigDecimal total = order.getTotal() != null
                ? order.getTotal().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNo(),
                order.getBuyerId(),
                order.getSellerId(),
                order.getSourceType(),
                order.getStatus(),
                total,
                itemCount,
                order.getShippingAddress(),
                order.getCreatedAt());
    }

    /**
     * Chuyển đổi thực thể Order thành OrderDetailResponse DTO chi tiết sử dụng lịch
     * sử trạng thái sẵn có trong entity.
     *
     * @param order Thực thể đơn hàng từ cơ sở dữ liệu
     * @return OrderDetailResponse đầy đủ thông tin hoặc null nếu order là null
     */
    public OrderDetailResponse toDetailResponse(Order order) {
        if (order == null) {
            return null;
        }
        return toDetailResponse(order, order.getStatusHistory());
    }

    /**
     * Chuyển đổi thực thể Order và danh sách lịch sử trạng thái chỉ định thành
     * OrderDetailResponse DTO chi tiết.
     *
     * @param order   Thực thể đơn hàng từ cơ sở dữ liệu
     * @param history Danh sách bản ghi lịch sử trạng thái (có thể null, nếu null sẽ
     *                dùng từ order.getStatusHistory())
     * @return OrderDetailResponse đầy đủ thông tin đơn hàng, snapshot mặt hàng và
     *         lịch sử trạng thái
     */
    public OrderDetailResponse toDetailResponse(Order order, List<OrderStatusHistory> history) {
        if (order == null) {
            return null;
        }

        List<OrderItemResponse> itemResponses = toItemResponseList(order.getItems());
        List<OrderStatusHistory> effectiveHistory = (history != null) ? history : order.getStatusHistory();
        List<OrderStatusHistoryResponse> historyResponses = toStatusHistoryResponseList(effectiveHistory);

        BigDecimal subtotal = order.getSubtotal() != null
                ? order.getSubtotal().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal shippingFee = order.getShippingFee() != null
                ? order.getShippingFee().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal discount = order.getDiscount() != null
                ? order.getDiscount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = order.getTotal() != null
                ? order.getTotal().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNo(),
                order.getBuyerId(),
                order.getSellerId(),
                order.getSourceType(),
                order.getAuctionId(),
                order.getVoucherId(),
                order.getStatus(),
                subtotal,
                shippingFee,
                discount,
                total,
                order.getShippingAddress(),
                order.getCreatedAt(),
                itemResponses,
                historyResponses);
    }

    /**
     * Chuyển đổi thực thể OrderItem thành OrderItemResponse DTO.
     *
     * @param item Thực thể dòng sản phẩm snapshot
     * @return OrderItemResponse hoặc null nếu item là null
     */
    public OrderItemResponse toItemResponse(OrderItem item) {
        if (item == null) {
            return null;
        }

        BigDecimal unitPrice = item.getUnitPrice() != null
                ? item.getUnitPrice().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;

        BigDecimal subtotal = item.getSubtotal() != null
                ? item.getSubtotal().setScale(2, RoundingMode.HALF_UP)
                : unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

        return new OrderItemResponse(
                item.getId(),
                item.getSkuId(),
                item.getProductName(),
                item.getSkuCode(),
                unitPrice,
                quantity,
                subtotal);
    }

    /**
     * Chuyển đổi danh sách OrderItem entity thành danh sách OrderItemResponse DTO.
     *
     * @param items Danh sách dòng sản phẩm
     * @return Danh sách OrderItemResponse (không bao giờ null, trả về danh sách
     *         rỗng nếu null/empty)
     */
    public List<OrderItemResponse> toItemResponseList(List<OrderItem> items) {
        if (items == null || !org.hibernate.Hibernate.isInitialized(items) || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream()
                .map(this::toItemResponse)
                .toList();
    }

    /**
     * Chuyển đổi thực thể OrderStatusHistory thành OrderStatusHistoryResponse DTO.
     *
     * @param history Bản ghi lịch sử trạng thái
     * @return OrderStatusHistoryResponse hoặc null nếu history là null
     */
    public OrderStatusHistoryResponse toStatusHistoryResponse(OrderStatusHistory history) {
        if (history == null) {
            return null;
        }

        String actor = resolveActor(history);

        return new OrderStatusHistoryResponse(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getChangedAt(),
                actor);
    }

    /**
     * Chuyển đổi danh sách OrderStatusHistory entity thành danh sách
     * OrderStatusHistoryResponse DTO.
     *
     * @param historyList Danh sách bản ghi lịch sử trạng thái
     * @return Danh sách OrderStatusHistoryResponse (không bao giờ null, trả về danh
     *         sách rỗng nếu null/empty)
     */
    public List<OrderStatusHistoryResponse> toStatusHistoryResponseList(List<OrderStatusHistory> historyList) {
        if (historyList == null || !org.hibernate.Hibernate.isInitialized(historyList) || historyList.isEmpty()) {
            return Collections.emptyList();
        }
        return historyList.stream()
                .map(this::toStatusHistoryResponse)
                .toList();
    }

    /**
     * Trích xuất hoặc suy luận tác nhân thực hiện (actor) từ bản ghi lịch sử trạng
     * thái.
     *
     * <p>
     * Thuật toán:
     * <ol>
     * <li>Nếu cột reason bắt đầu bằng thẻ tiền tố [ROLE] (ví dụ: "[BUYER]",
     * "[SELLER]", "[ADMIN]"),
     * trích xuất trực tiếp giá trị trong thẻ làm actor.</li>
     * <li>Nếu không có thẻ, áp dụng ma trận suy luận dựa trên trạng thái đích
     * (toStatus) và từ khóa trong reason:
     * <ul>
     * <li>Khởi tạo ban đầu (fromStatus == null và toStatus == PENDING_PAYMENT) ->
     * BUYER.</li>
     * <li>PAID -> SYSTEM.</li>
     * <li>PROCESSING, SHIPPED -> SELLER.</li>
     * <li>DELIVERED -> CARRIER.</li>
     * <li>COMPLETED -> BUYER.</li>
     * <li>REFUNDED -> ADMIN.</li>
     * <li>CANCELLED -> Nếu reason chứa "buyer", "khách", "customer" hoặc hủy từ
     * PENDING_PAYMENT -> BUYER;
     * nếu chứa "seller", "người bán" -> SELLER; nếu chứa "admin" -> ADMIN; mặc định
     * -> SYSTEM.</li>
     * </ul>
     * </li>
     * <li>Giá trị dự phòng mặc định (fallback): "SYSTEM".</li>
     * </ol>
     *
     * @param history Bản ghi lịch sử trạng thái
     * @return Tên vai trò hoặc tác nhân thực hiện (BUYER, SELLER, ADMIN, SYSTEM,
     *         CARRIER...)
     */
    public String resolveActor(OrderStatusHistory history) {
        if (history == null) {
            return "SYSTEM";
        }

        String reason = history.getReason();
        if (reason != null && !reason.isBlank()) {
            Matcher matcher = ACTOR_TAG_PATTERN.matcher(reason.trim());
            if (matcher.find()) {
                return matcher.group(1).toUpperCase(Locale.ROOT);
            }
        }

        OrderStatus to = history.getToStatus();
        OrderStatus from = history.getFromStatus();

        if (from == null && to == OrderStatus.PENDING_PAYMENT) {
            return "BUYER";
        }

        if (to == null) {
            return "SYSTEM";
        }

        return switch (to) {
            case PAID -> "SYSTEM";
            case PROCESSING, SHIPPED -> "SELLER";
            case DELIVERED -> "CARRIER";
            case COMPLETED -> "BUYER";
            case REFUNDED -> "ADMIN";
            case CANCELLED -> {
                if (reason != null) {
                    String lowerReason = reason.toLowerCase(Locale.ROOT);
                    if (lowerReason.contains("buyer") || lowerReason.contains("khách")
                            || lowerReason.contains("customer")) {
                        yield "BUYER";
                    }
                    if (lowerReason.contains("seller") || lowerReason.contains("người bán")) {
                        yield "SELLER";
                    }
                    if (lowerReason.contains("admin")) {
                        yield "ADMIN";
                    }
                }
                if (from == OrderStatus.PENDING_PAYMENT) {
                    yield "BUYER";
                }
                yield "SYSTEM";
            }
            default -> "SYSTEM";
        };
    }
}
