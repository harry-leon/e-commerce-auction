package com.nexus.commerce.service;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.enums.OrderStatus;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi ý định bàn giao VNPAY sau xác nhận kho, không gửi HTTP hoặc tự tạo vận đơn.
 * Caller là worker đang giữ khóa Order; row WAITING_PARCEL không thay thế quyền dispatch hoặc kiện thực.
 * Mặc định tắt, không backfill đơn cũ; rollback nghiệp vụ phải rollback cả tác vụ bàn giao.
 */
@Component
public class ShippingHandoffOutbox {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public ShippingHandoffOutbox(JdbcTemplate jdbc, @Value("${app.shipping.handoff-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /**
     * Nhận entity vừa chốt PAID/CONFIRMED trong transaction worker; tiền lấy snapshot server, không nhận từ client.
     * Skip refund/review/đã có shipment; cùng Order chỉ một ý định, replay không đổi id/thời điểm/số tiền cũ.
     * Lỗi DB lan ra proxy MANDATORY để đánh dấu rollback cả transaction, không để PAID mất tác vụ bàn giao.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, Payment payment) {
        if (!enabled) { return; }
        if (order == null || payment == null || !order.getId().equals(payment.getOrderId())
                || order.getStatus() != OrderStatus.PAID || !order.isPriceFinalized()
                || !"VNPAY".equals(order.getPaymentMethod()) || !"VNPAY".equals(payment.getProvider())
                || !"SUCCESS".equals(payment.getStatus()) || !"CONFIRMED".equals(payment.getInventoryStatus())
                || "REVIEW".equals(order.getFulfillmentStatus()) || payment.getPriceSnapshot() == null
                || payment.getAmount() == null || payment.getAmount().signum() <= 0
                || payment.getAmount().stripTrailingZeros().scale() > 0 || order.getTotal() == null
                || payment.getPriceSnapshot().total() == null || payment.getAmount().compareTo(order.getTotal()) != 0
                || payment.getAmount().compareTo(payment.getPriceSnapshot().total()) != 0
                || !"00".equals(payment.getResponseCode()) || !"00".equals(payment.getTransactionStatus())
                || payment.getCompletedAt() == null || blank(payment.getProviderRef())
                || blank(payment.getMerchantCode()) || "DEMO_ONLY".equals(payment.getMerchantCode())
                || blank(order.getRecipientName()) || blank(order.getRecipientPhone()) || blank(order.getShippingAddress())) { return; }
        // Khóa Order của caller tuần tự hóa với tạo refund; không đọc DB service khác hoặc giữ khóa mạng mới.
        Boolean blocked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM refund_requests WHERE order_id=? AND status<>'FAILED')
                  OR EXISTS (SELECT 1 FROM refund_payment_holds h JOIN payments p ON p.id=h.payment_id WHERE p.order_id=?)
                  OR EXISTS (SELECT 1 FROM payments WHERE order_id=? AND (status='REVIEW' OR inventory_status='REVIEW'))
                  OR (SELECT count(*) FROM payments WHERE order_id=? AND status='SUCCESS')<>1
                  OR EXISTS (SELECT 1 FROM shipping_order_bindings WHERE order_id=?)
                """, Boolean.class, order.getId(), order.getId(), order.getId(), order.getId(), order.getId());
        if (!Boolean.FALSE.equals(blocked)) { return; }
        jdbc.update("INSERT INTO shipping_handoff_tasks(id,order_id,payment_id,amount) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), order.getId(), payment.getId(), payment.getAmount());
    }

    /** Dữ liệu checkout cũ thiếu người nhận phải chờ đối soát, không điền PII demo hoặc suy đoán người nhận. */
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
