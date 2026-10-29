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
        jdbc.update("INSERT INTO commerce_notification_outbox(id,order_id,user_id,event_type,order_no,amount) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT (order_id,event_type) DO NOTHING",
                UUID.randomUUID(), order.getId(), order.getBuyerId(), type, order.getOrderNo(), order.getTotal());
    }
}
