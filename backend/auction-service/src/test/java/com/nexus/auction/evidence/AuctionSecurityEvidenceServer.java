package com.nexus.auction.evidence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.nexus.auction.config.AuctionStompInterceptor;
import com.nexus.auction.config.AuctionWebSocketConfig;
import com.nexus.auction.config.ExpiringWebSocketSessions;
import com.nexus.auction.config.SecurityConfig;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server chỉ có trong test classpath để browser test Gateway → Auction thật mà không cần DB/realm nhóm.
 * Hai phiên PUBLIC/PRIVATE được mock trong RAM; endpoint evidence chỉ phát event fixture, không ghi bid.
 * Profile security-evidence và không component scan ngăn fixture tham gia boot ứng dụng/test thông thường.
 * Script scripts/smoke-tt-s2-2-browser.mjs cấp JWKS/issuer cục bộ và dừng tiến trình sau khi chụp ảnh.
 */
@Configuration(proxyBeanMethods = false)
@Profile("security-evidence")
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@Import({SecurityConfig.class, AuctionWebSocketConfig.class, AuctionStompInterceptor.class,
        ExpiringWebSocketSessions.class, AuctionSecurityEvidenceServer.Probe.class})
public class AuctionSecurityEvidenceServer {
    public static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID PRIVATE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Chạy riêng test harness; không nạp .env hoặc cấu hình credential của hệ thống thật. */
    public static void main(String[] args) {
        var app = new SpringApplication(AuctionSecurityEvidenceServer.class);
        app.setAdditionalProfiles("security-evidence");
        app.run(args);
    }

    /** Dữ liệu cố định phục vụ quyền SUBSCRIBE; mọi mã khác trả rỗng để giữ kiểm tra not-found thật. */
    @Bean
    AuctionRepository evidenceAuctions() {
        var repository = mock(AuctionRepository.class);
        when(repository.findById(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            if (!PUBLIC_ID.equals(id) && !PRIVATE_ID.equals(id)) { return Optional.empty(); }
            var auction = new Auction();
            auction.setId(id);
            auction.setSellerId(UUID.fromString("33333333-3333-4333-8333-333333333333"));
            auction.setVisibility(PUBLIC_ID.equals(id) ? AuctionVisibility.PUBLIC : AuctionVisibility.PRIVATE);
            return Optional.of(auction);
        });
        return repository;
    }

    /** API fixture yêu cầu bearer HTTP qua SecurityConfig; chỉ có trong test classpath, không nằm trong JAR production. */
    @RestController
    @Profile("security-evidence")
    static class Probe {
        private final SimpMessagingTemplate messaging;
        private final SimpUserRegistry users;

        Probe(SimpMessagingTemplate messaging, SimpUserRegistry users) {
            this.messaging = messaging;
            this.users = users;
        }

        /** Đếm subscription thật để browser chờ broker hoàn tất trước khi gửi broadcast, tránh race trong test. */
        @GetMapping("/api/v1/auctions/evidence/subscriptions")
        Map<String, Integer> subscriptions() {
            int count = users.getUsers().stream().flatMap(user -> user.getSessions().stream())
                    .mapToInt(session -> session.getSubscriptions().size()).sum();
            return Map.of("subscriptions", count);
        }

        /** Publish dữ liệu test có nhãn fixture; không mô phỏng rằng nghiệp vụ bid hoặc transaction DB đã chạy. */
        @PostMapping("/api/v1/auctions/evidence/broadcast")
        Map<String, Object> broadcast() {
            var event = Map.<String, Object>of("type", "SECURITY_TEST_FIXTURE", "auctionId", PUBLIC_ID,
                    "fixture", true, "currentPrice", 125000);
            messaging.convertAndSend("/topic/auctions/" + PUBLIC_ID, event);
            return event;
        }
    }
}
