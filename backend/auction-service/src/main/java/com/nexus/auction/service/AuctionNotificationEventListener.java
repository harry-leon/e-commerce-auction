package com.nexus.auction.service;

import com.nexus.auction.event.AuctionOutbidEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Lắng nghe các sự kiện miền đấu giá và chuyển tiếp thông báo tới Notification Service.
 *
 * Chức năng:
 * - Khi nhận AuctionOutbidEvent, gửi thông báo Outbid qua POST /internal/commerce-notifications
 *   để người dùng bị vượt giá nhận được thông báo IN_APP và WebSocket PUSH thời gian thực.
 *
 * Quy tắc và lý do:
 * - Gọi Notification Service theo cơ chế best-effort và bọc try-catch để lỗi gửi thông báo không bao giờ
 *   ảnh hưởng hay làm rollback giao dịch đặt giá thành công của phiên đấu giá.
 */
@Component
public class AuctionNotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuctionNotificationEventListener.class);

    private final RestClient notificationClient;

    public AuctionNotificationEventListener(
            @Qualifier("auctionNotificationClient") RestClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    /**
     * Nhận sự kiện AuctionOutbidEvent sau khi đặt giá commit thành công.
     * Chuyển đổi thành payload Event và gửi sang Notification Service.
     */
    @EventListener
    public void onAuctionOutbid(AuctionOutbidEvent event) {
        if (event == null || event.outbidUserId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", UUID.randomUUID().toString());
            payload.put("userId", event.outbidUserId().toString());
            payload.put("type", "AUCTION_OUTBID");
            payload.put("amount", event.newAmount());
            payload.put("occurredAt", event.occurredAt().toString());
            payload.put("auctionId", event.auctionId().toString());
            payload.put("auctionTitle", event.auctionTitle());
            payload.put("paymentLink", "/auction-detail?id=" + event.auctionId());

            notificationClient.post()
                    .uri("/internal/commerce-notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Đã gửi thông báo Outbid thành công cho user [{}] phiên [{}]",
                    event.outbidUserId(), event.auctionId());
        } catch (Exception ex) {
            log.warn("Không thể gửi thông báo Outbid cho user [{}] phiên [{}]: {}",
                    event.outbidUserId(), event.auctionId(), ex.getMessage());
        }
    }
}
