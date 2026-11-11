package com.nexus.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Socket đã subscribe phải đóng 1008 khi JWT hết hạn dù client không gửi thêm frame.
 * Decoder fixture thay xác thực chữ ký; HTTP upgrade, STOMP, broker và timer chạy thật, không dùng DB.
 */
class NotificationWebSocketExpiryTest {
    private static final String USER = "00000000-0000-0000-0000-000000000001";

    /** Context transport độc lập, không quét service nghiệp vụ hoặc kết nối database của nhóm. */
    @TestConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({NotificationWebSocketConfig.class, NotificationStompInterceptor.class,
            ExpiringNotificationWebSocketSessions.class})
    static class Config {
        @Bean
        JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "test").subject(USER)
                    .expiresAt(java.time.Instant.now().plusSeconds(3)).build();
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }

    /** Nhận tin khi còn hạn, chờ policy close; broadcast mới không được tới session hết hạn. */
    @Test
    void closesIdleSubscriptionAfterJwtExpiry() throws Exception {
        var app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        try (var context = (ServletWebServerApplicationContext) app.run("--server.port=0",
                "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false", "--logging.level.root=WARN")) {
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            var closed = new CountDownLatch(1);
            var closeCode = new AtomicInteger();
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                    URI.create("ws://localhost:" + context.getWebServer().getPort() + "/ws/notifications"),
                    new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();
                        @Override
                        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
                            closeCode.set(code);
                            closed.countDown();
                            return null;
                        }
                        @Override
                        public void onOpen(WebSocket socket) { socket.request(1); }
                        @Override
                        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                frames.add(buffer.toString());
                                buffer.setLength(0);
                            }
                            socket.request(1);
                            return null;
                        }
                    }).join();
            try {
                ws.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer fixture\n\n\0", true).join();
                assertTrue(frames.poll(5, TimeUnit.SECONDS).contains("CONNECTED"));
                ws.sendText("SUBSCRIBE\nid:sub-1\ndestination:/user/queue/notifications\n\n\0", true).join();
                var broker = context.getBean(SimpMessagingTemplate.class);
                // Broker xử lý inbound bất đồng bộ; gửi lại tin kiểm chứng tới khi subscription sẵn sàng.
                String first = null;
                for (int i = 0; i < 10 && first == null; i++) {
                    broker.convertAndSendToUser(USER, "/queue/notifications", "before-expiry");
                    first = frames.poll(100, TimeUnit.MILLISECONDS);
                }
                assertTrue(first != null && first.contains("before-expiry"));
                assertTrue(closed.await(5, TimeUnit.SECONDS), "Socket hết hạn phải đóng dù không gửi frame");
                assertEquals(1008, closeCode.get());
                frames.clear();
                broker.convertAndSendToUser(USER, "/queue/notifications", "after-expiry");
                assertTrue(frames.poll(200, TimeUnit.MILLISECONDS) == null);
            } finally {
                ws.abort();
            }
        }
    }
}
