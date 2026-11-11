package com.nexus.notification.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Cấu hình WebSocket STOMP cho kênh đẩy thông báo thời gian thực (PUSH_NOTIFICATION_ENABLED = true).
 * Kiểm tra chặt chẽ CORS origin ở handshake và ủy quyền kiểm tra JWT + subject cho NotificationStompInterceptor.
 */
@Configuration
@EnableWebSocketMessageBroker
public class NotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final NotificationStompInterceptor stompInterceptor;
    private final String[] allowedOrigins;
    private final ExpiringNotificationWebSocketSessions sessions;

    public NotificationWebSocketConfig(
            NotificationStompInterceptor stompInterceptor,
            ExpiringNotificationWebSocketSessions sessions,
            @Value("${app.web.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}") String origins) {
        this.stompInterceptor = stompInterceptor;
        this.sessions = sessions;
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
        // Heartbeat phát hiện kết nối mất mạng; timer transport đóng cả socket im lặng khi JWT hết hạn.
        registry.enableSimpleBroker("/topic", "/queue").setTaskScheduler(notificationBrokerScheduler())
                .setHeartbeatValue(new long[] {10_000, 10_000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompInterceptor);
    }

    /** Giới hạn frame/buffer và chờ CONNECT; hủy timer khi disconnect hoặc Spring context đóng. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(64 * 1024)
                .setSendTimeLimit(10_000).setTimeToFirstMessage(10_000);
        registration.addDecoratorFactory(sessions);
    }

    /** Scheduler do Spring quản lý giữ heartbeat hoạt động và được dọn khi shutdown. */
    @Bean
    public ThreadPoolTaskScheduler notificationBrokerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-broker-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
