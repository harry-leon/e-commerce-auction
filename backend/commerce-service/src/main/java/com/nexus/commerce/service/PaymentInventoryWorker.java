package com.nexus.commerce.service;

import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.common.exception.ApiException;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.PaymentRepository;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đẩy outbox đã commit sang kho bằng service account; SKIP LOCKED chia tác vụ an toàn giữa nhiều instance.
 * Remote thành công nhưng worker chết trước commit sẽ được retry: confirm ALREADY_COMMITTED/release lặp là idempotent.
 */
@Component
@Slf4j
public class PaymentInventoryWorker {
    private final JdbcTemplate jdbc;
    private final InventoryClient inventory;
    private final InventoryServiceToken tokens;
    private final TransactionTemplate transaction;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final NotificationOutbox notifications;
    private final ShippingHandoffOutbox shipping;

    public PaymentInventoryWorker(JdbcTemplate jdbc, InventoryClient inventory, InventoryServiceToken tokens,
                                    PlatformTransactionManager manager, OrderRepository orders,
                                    PaymentRepository payments, NotificationOutbox notifications, ShippingHandoffOutbox shipping) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.tokens = tokens;
        transaction = new TransactionTemplate(manager);
        this.orders = orders;
        this.payments = payments;
        this.notifications = notifications;
        this.shipping = shipping;
    }

    /** Mỗi tick xử lý tối đa 10 tác vụ, từng transaction riêng; thiếu config không đánh dấu hoàn thành giả. */
    @Scheduled(fixedDelayString = "${app.payment.inventory.poll-ms:5000}")
    public void run() {
        if (!tokens.configured()) { return; }
        for (int i = 0; i < 10; i++) {
            Boolean found = transaction.execute(status -> processOne());
            if (!Boolean.TRUE.equals(found)) { break; }
        }
    }

    private boolean processOne() {
        var rows = jdbc.queryForList("SELECT * FROM payment_inventory_tasks WHERE NOT done AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) { return false; }
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        try {
            String token = tokens.get();
            if ("COMMIT".equals(row.get("action"))) {
                completePaidOrder((UUID) row.get("order_id"), (UUID) row.get("target_id"), token);
            }
            else if ("COD_COMMIT".equals(row.get("action"))) { completeCodOrder((UUID) row.get("order_id"), token); }
            else if ("CONFIRM".equals(row.get("action"))) { inventory.confirm((UUID) row.get("target_id"), token); }
            else if ("RELEASE".equals(row.get("action"))) { inventory.release((UUID) row.get("target_id"), token); }
            else { throw new IllegalStateException("Tác vụ kho không hợp lệ"); }
            jdbc.update("UPDATE payment_inventory_tasks SET done=true, attempts=attempts+1 WHERE id=?", id);
        } catch (RuntimeException error) {
            if (error instanceof ApiException api && api.getHttpStatus().value() == 401) { tokens.invalidate(); }
            // Retry vô hạn với khoảng chờ tối đa 10 phút; phiếu EXPIRED cần quản trị đối soát/refund, không reserve lại âm thầm.
            jdbc.update("UPDATE payment_inventory_tasks SET attempts=attempts+1, "
                    + "next_attempt_at=CURRENT_TIMESTAMP + least(attempts+1,60) * interval '10 seconds' WHERE id=?", id);
            log.warn("Outbox kho chưa hoàn tất: task={}, order={}, errorType={}; cần theo dõi attempts", id, row.get("order_id"), error.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * COD đã xác nhận thu tiền chỉ PAID sau commit kho nguyên tử; timeout giữ PENDING để retry đúng ref.
     * Phiếu hết hạn/không tồn tại là lỗi vĩnh viễn: lưu REVIEW, đóng tác vụ thay vì retry vô hạn hoặc giả hoàn tiền.
     * Giá chính thức được giữ để đối soát tiền đã thu; Order lock tuần tự hóa với command lặp và thao tác giao hàng.
     */
    private void completeCodOrder(UUID orderId, String token) {
        var order = orders.findForPaymentWithItems(orderId).orElseThrow();
        if (!"COD".equals(order.getPaymentMethod()) || !order.isPriceFinalized()) {
            throw new IllegalStateException("Task COD chưa có xác nhận thu tiền");
        }
        if ("CONFIRMED".equals(order.getFulfillmentStatus()) || "REVIEW".equals(order.getFulfillmentStatus())) { return; }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            order.setFulfillmentStatus("REVIEW");
            return;
        }
        try {
            inventory.commitOrder(orderId, java.util.List.copyOf(order.getReservationIds()), token);
        } catch (ApiException error) {
            if (!java.util.Set.of("INVENTORY_RESERVATION_EXPIRED", "INVENTORY_RESERVATION_NOT_FOUND").contains(error.getCode())) {
                throw error;
            }
            order.setFulfillmentStatus("REVIEW");
            order.setStatus(OrderStatus.CANCELLED);
            history(orderId, "CANCELLED", "COD_FUNDS_RECEIVED_INVENTORY_UNAVAILABLE");
            return;
        }
        order.setFulfillmentStatus("CONFIRMED");
        order.setStatus(OrderStatus.PAID);
        history(orderId, "PAID", "COD_INVENTORY_CONFIRMED");
        notifications.enqueue(order, "PAYMENT_SUCCESS");
    }

    /**
     * Giữ khóa Order xuyên suốt commit kho; Fulfillment dùng khóa phiếu chung với sweeper để chọn đúng một kết quả.
     * Mất response/rollback Commerce được retry bằng cùng orderId; phiếu CONFIRMED không bị sweeper hoặc release nhả lại.
     * Áp dụng cho REGULAR và AUCTION; tiền đã thu nhưng kho EXPIRED/RELEASED lưu REVIEW và yêu cầu đối soát thủ công.
     */
    private void completePaidOrder(UUID orderId, UUID targetId, String token) {
        var order = orders.findForPaymentWithItems(orderId).orElseThrow();
        // Task mới target=paymentId; task AUCTION đã tồn tại trước migration target=orderId vẫn được tiếp tục.
        var payment = targetId.equals(orderId)
                ? payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(orderId, "VNPAY").orElseThrow()
                : payments.findById(targetId).orElseThrow();
        if (!orderId.equals(payment.getOrderId())) { throw new IllegalStateException("Task không khớp đơn thanh toán"); }
        if (!"SUCCESS".equals(payment.getStatus())) { throw new IllegalStateException("Chưa có IPN thu tiền hợp lệ"); }
        if ("CONFIRMED".equals(payment.getInventoryStatus()) || "REVIEW".equals(payment.getInventoryStatus())) { return; }
        // Tiền thu muộn từ attempt cũ yêu cầu đối soát toàn đơn trước khi tiếp tục giao hàng tự động.
        if (payments.existsByOrderIdAndInventoryStatus(orderId, "REVIEW")) {
            payment.setInventoryStatus("REVIEW");
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            payment.setInventoryStatus("REVIEW");
            return;
        }
        try {
            // Snapshot collection khi session còn mở; không chuyển proxy JPA ra HTTP client hoặc lưu qua lần retry.
            inventory.commitOrder(payment.getInventoryRefId(), java.util.List.copyOf(order.getReservationIds()), token);
        } catch (ApiException error) {
            if (!"INVENTORY_RESERVATION_EXPIRED".equals(error.getCode())) { throw error; }
            payment.setInventoryStatus("REVIEW");
            order.setStatus(OrderStatus.CANCELLED);
            history(orderId, "CANCELLED", "VNPAY_FUNDS_RECEIVED_INVENTORY_UNAVAILABLE");
            return;
        }
        payment.setInventoryStatus("CONFIRMED");
        order.setStatus(OrderStatus.PAID);
        // Chỉ sau thu tiền + commit kho: ghi ý định WAITING_PARCEL cùng transaction, chưa gửi carrier.
        // Proxy MANDATORY đánh dấu rollback nếu ghi lỗi, kể cả processOne bắt exception để ghi retry.
        shipping.enqueue(order, payment);
        // Chỉ log định danh server sau xác nhận kho, không ghi token, URL ký hoặc payload IPN.
        org.slf4j.LoggerFactory.getLogger(PaymentInventoryWorker.class).atInfo()
                .addKeyValue("orderId", orderId).addKeyValue("paymentId", payment.getId())
                .addKeyValue("auctionId", order.getAuctionId()).log("Kho đã xác nhận đơn VNPAY");
        history(orderId, "PAID", "VNPAY_INVENTORY_CONFIRMED");
        notifications.enqueue(order, "PAYMENT_SUCCESS");
    }

    /** Lịch sử do worker tạo phải ghi SYSTEM và timestamp UTC, tránh bị suy luận thành buyer hủy hoặc lệch giờ DB. */
    private void history(UUID orderId, String status, String reason) {
        jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason,changed_at) "
                + "VALUES (?,?,'PENDING_PAYMENT',?,?,(CURRENT_TIMESTAMP AT TIME ZONE 'UTC'))",
                UUID.randomUUID(), orderId, status, "[SYSTEM] " + reason);
    }
}
