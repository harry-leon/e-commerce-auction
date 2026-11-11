package com.nexus.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.notification.config.NotificationProperties;
import com.nexus.notification.config.SecurityConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm tra phát Push thời gian thực qua WebSocket và cấu hình PUSH_NOTIFICATION_ENABLED
 * cho các sự kiện đấu giá AUCTION_WON, AUCTION_PAYMENT_REMINDER, AUCTION_PAYMENT_TIMEOUT.
 */
@WebMvcTest(CommerceNotificationController.class)
@Import({SecurityConfig.class, CommerceNotificationControllerPushTest.Config.class,
        com.nexus.common.exception.GlobalExceptionHandler.class})
class CommerceNotificationControllerPushTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbc;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private NotificationProperties properties;

    static class Config {
        @Bean
        NotificationProperties notificationProperties() {
            NotificationProperties props = new NotificationProperties();
            props.setPushEnabled(true);
            props.setFrontendUrl("http://localhost:3000");
            return props;
        }
    }

    /** Khi PUSH_NOTIFICATION_ENABLED = true, gửi đẩy WebSocket kèm link thanh toán tới đúng winner. */
    @Test
    void dispatchesRealTimePushWithSafePaymentLinkForAuctionWinner() throws Exception {
        properties.setPushEnabled(true);
        UUID eventId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String body = """
                {
                  "id": "%s",
                  "userId": "%s",
                  "orderId": "%s",
                  "orderNo": "ORD-AUC-01",
                  "type": "AUCTION_WON",
                  "amount": 2500000,
                  "occurredAt": "2026-11-11T10:00:00Z",
                  "auctionId": "%s",
                  "paymentDeadline": "2026-11-12T10:00:00Z",
                  "paymentLink": "https://evil.example/phish"
                }
                """.formatted(eventId, winnerId, orderId, auctionId);

        mvc.perform(post("/internal/commerce-notifications")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_NOTIFICATION_WRITER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(messagingTemplate).convertAndSend(
                eq("/topic/users/" + winnerId + "/notifications"),
                any(Map.class));
    }

    /** Khi tắt PUSH_NOTIFICATION_ENABLED, không bắn WebSocket push nhưng vẫn giữ ghi nhận IN_APP. */
    @Test
    void skipsWebSocketPushWhenFlagDisabled() throws Exception {
        properties.setPushEnabled(false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String body = """
                {
                  "id": "%s",
                  "userId": "%s",
                  "orderId": "%s",
                  "orderNo": "ORD-AUC-02",
                  "type": "AUCTION_PAYMENT_TIMEOUT",
                  "amount": 1500000,
                  "occurredAt": "2026-11-11T10:00:00Z",
                  "penaltyScore": 10
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/internal/commerce-notifications")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_NOTIFICATION_WRITER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verifyNoInteractions(messagingTemplate);
    }
}
