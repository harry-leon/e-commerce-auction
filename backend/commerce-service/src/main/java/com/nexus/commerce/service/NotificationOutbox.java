package com.nexus.commerce.service;

import com.nexus.commerce.entity.Order;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ghi snapshot buyer/đơn từ DB trong transaction nghiệp vụ; không nhận người nhận hoặc số tiền từ browser. */
@Component
public class NotificationOutbox {
    private final JdbcTemplate jdbc;

    public NotificationOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Rollback đơn/IPN cũng rollback sự kiện; unique(order,type) chặn replay, lỗi gửi chỉ xảy ra tại worker. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, String type) {
        jdbc.update("INSERT INTO commerce_notification_outbox(id,order_id,user_id,event_type,order_no,amount,email_payload) "
                + "VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT (order_id,event_type) DO NOTHING",
                UUID.randomUUID(), order.getId(), order.getBuyerId(), type, order.getOrderNo(),
                order.isPriceFinalized() ? order.getTotal() : order.getEstimatedTotal(), emailSnapshot(order));
    }
    /** Serialize snapshot server; email trống giữ null để không gửi tới địa chỉ demo. */
    private String emailSnapshot(Order order) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("recipientEmail", order.getBuyerEmail());
        payload.put("recipientName", order.getRecipientName());
        payload.put("paymentMethod", order.getPaymentMethod());
        // Đơn chưa thu tiền gửi giá dự kiến và cờ phân biệt; không dùng Map.of với giá chính thức null.
        payload.put("priceFinalized", order.isPriceFinalized());
        payload.put("items", order.getItems().stream().map(item -> java.util.Map.of(
                "productName", item.getProductName(), "skuCode", item.getSkuCode(),
                "quantity", item.getQuantity(), "unitPrice", order.isPriceFinalized() ? item.getUnitPrice() : item.getEstimatedUnitPrice())).toList());
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Không lưu được snapshot email", error); }
    }
}
