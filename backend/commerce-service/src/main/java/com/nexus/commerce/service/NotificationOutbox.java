package com.nexus.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.entity.Order;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi snapshot buyer/đơn/liên kết thanh toán từ DB trong transaction nghiệp vụ;
 * không nhận người nhận hoặc số tiền từ browser.
 */
@Component
public class NotificationOutbox {

    private static final int DEFAULT_TIMEOUT_PENALTY_SCORE = 10;

    private final JdbcTemplate jdbc;
    private final String frontendUrl;
    private final int timeoutPenaltyScore;

    public NotificationOutbox(JdbcTemplate jdbc) {
        this(jdbc, "http://localhost:3000", DEFAULT_TIMEOUT_PENALTY_SCORE);
    }

    @Autowired
    public NotificationOutbox(
            JdbcTemplate jdbc,
            @Value("${app.frontend-url:${FRONTEND_URL:http://localhost:3000}}") String frontendUrl,
            @Value("${app.payment.auction.timeout-penalty-score:${PAYMENT_TIMEOUT_PENALTY_SCORE:10}}") int timeoutPenaltyScore) {
        this.jdbc = jdbc;
        this.frontendUrl = frontendUrl != null && !frontendUrl.isBlank()
                ? frontendUrl.replaceAll("/+$", "")
                : "http://localhost:3000";
        this.timeoutPenaltyScore = timeoutPenaltyScore > 0 ? timeoutPenaltyScore : DEFAULT_TIMEOUT_PENALTY_SCORE;
    }

    /** Rollback đơn/IPN cũng rollback sự kiện; unique(order,type) chặn replay, lỗi gửi chỉ xảy ra tại worker. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, String type) {
        // Đơn chưa chốt thu tiền dùng estimatedTotal để outbox vẫn giữ tổng dự kiến thay vì null.
        jdbc.update("INSERT INTO commerce_notification_outbox(id,order_id,user_id,event_type,order_no,amount,email_payload) "
                + "VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT (order_id,event_type) DO NOTHING",
                UUID.randomUUID(), order.getId(), order.getBuyerId(), type, order.getOrderNo(),
                order.isPriceFinalized() ? order.getTotal() : order.getEstimatedTotal(),
                emailSnapshot(order, type));
    }

    /** Serialize snapshot server; email trống giữ null để worker vẫn gửi IN_APP/PUSH không cần đoán email. */
    private String emailSnapshot(Order order, String type) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("recipientEmail", order.getBuyerEmail());
        payload.put("recipientName", order.getRecipientName());
        payload.put("paymentMethod", order.getPaymentMethod());
        // Đơn chưa thu tiền gửi giá dự kiến và cờ phân biệt; dùng LinkedHashMap tránh lỗi khi giá chính thức null.
        payload.put("priceFinalized", order.isPriceFinalized());
        if (order.getAuctionId() != null) {
            payload.put("auctionId", order.getAuctionId());
        }
        if (order.getPaymentDeadline() != null) {
            payload.put("paymentDeadline", order.getPaymentDeadline().toString());
        }
        if ("AUCTION_WON".equals(type) || "AUCTION_PAYMENT_REMINDER".equals(type)) {
            payload.put("paymentLink", frontendUrl + "/order-detail?id=" + order.getId());
        }
        if ("AUCTION_PAYMENT_REMINDER".equals(type) || "AUCTION_PAYMENT_TIMEOUT".equals(type)) {
            payload.put("penaltyScore", timeoutPenaltyScore);
        }
        payload.put("items", order.getItems().stream().map(item -> {
            var itemPayload = new LinkedHashMap<String, Object>();
            itemPayload.put("productName", item.getProductName());
            itemPayload.put("skuCode", item.getSkuCode());
            itemPayload.put("quantity", item.getQuantity());
            itemPayload.put("unitPrice", order.isPriceFinalized() ? item.getUnitPrice() : item.getEstimatedUnitPrice());
            return itemPayload;
        }).toList());
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Không lưu được snapshot email", error);
        }
    }
}
