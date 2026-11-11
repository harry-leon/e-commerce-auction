package com.nexus.notification.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Cấu hình WebSocket STOMP cho kênh đẩy thông báo thời gian thực (PUSH_NOTIFICATION_ENABLED = true).
 * Kiểm tra chặt chẽ CORS origin ở handshake và ủy quyền kiểm tra JWT + subject cho NotificationStompInterceptor.
 */
@Configuration
@EnableWebSocketMessageBroker
public class NotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final NotificationStompInterceptor stompInterceptor;
    private final String[] allowedOrigins;

    public NotificationWebSocketConfig(
            NotificationStompInterceptor stompInterceptor,
            @Value("${app.web.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}") String origins) {
        this.stompInterceptor = stompInterceptor;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
        if (this.allowedOrigins.length == 0
                || Arrays.stream(this.allowedOrigins).anyMatch(origin -> origin.isBlank() || origin.contains("*"))) {
            throw new IllegalArgumentException("WebSocket yêu cầu cấu hình origin cụ thể");
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompInterceptor);
    }
}
