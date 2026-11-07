package com.nexus.auction.config;

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
 * Endpoint WebSocket thuần cho browser qua Gateway; không bật SockJS hay token query string.
 * Simple broker lưu subscription trong RAM, chỉ hỗ trợ một Auction instance.
 * Bid engine phải bổ sung handler /app/auctions/{id}/bids và publish sau commit; chưa tạo bid tại đây.
 */
@Configuration
@EnableWebSocketMessageBroker
public class AuctionWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final AuctionStompInterceptor authentication;
    private final String[] origins;
    private final ExpiringWebSocketSessions sessions;

    public AuctionWebSocketConfig(AuctionStompInterceptor authentication, ExpiringWebSocketSessions sessions,
            @Value("${app.web.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}") String origins) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
        if (this.origins.length == 0 || Arrays.stream(this.origins).anyMatch(s -> s.isBlank() || s.contains("*"))) {
            throw new IllegalArgumentException("WebSocket yêu cầu origin cụ thể");
        }
    }

    /** Origin được kiểm tra ở handshake kể cả khi browser tự gửi cookie đăng nhập. */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/auctions").setAllowedOrigins(origins);
    }

    /** Server publish topic, client chỉ SEND tới app; inbound interceptor chặn giả broadcast. */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic/auctions").setTaskScheduler(auctionBrokerScheduler())
                .setHeartbeatValue(new long[] {10_000, 10_000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Kiểm tra trước khi frame vào simple broker/controller; không chỉ bảo vệ REST handshake bằng JWT.
        registration.interceptors(authentication);
    }

    /** Heartbeat giữ proxy khỏi timeout và phát hiện client mất mạng; Spring dọn scheduler khi shutdown. */
    @Bean
    public ThreadPoolTaskScheduler auctionBrokerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auction-broker-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** Giới hạn bộ nhớ và thời gian chờ CONNECT để socket ẩn danh không giữ tài nguyên vô hạn. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(64 * 1024)
                .setSendTimeLimit(10_000).setTimeToFirstMessage(10_000);
        registration.addDecoratorFactory(sessions);
    }
}
