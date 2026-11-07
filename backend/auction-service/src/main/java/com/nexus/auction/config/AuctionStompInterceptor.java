package com.nexus.auction.config;

import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.common.security.KeycloakAuthorities;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Xác minh CONNECT bằng JWT Keycloak hoặc principal HTTP đã xác minh từ cookie Gateway.
 * Mỗi frame kiểm tra expiry và quyền destination; không tin userId/login client gửi.
 * PRIVATE chưa có hợp đồng membership nên từ chối; broker không nhận SEND trực tiếp từ client.
 */
@Component
public class AuctionStompInterceptor implements ChannelInterceptor {
    private static final Pattern TOPIC = Pattern.compile("/topic/auctions/([0-9a-fA-F-]{36})");
    private static final Pattern BID = Pattern.compile("/app/auctions/([0-9a-fA-F-]{36})/bids");
    private final JwtDecoder decoder;
    private final AuctionRepository auctions;

    public AuctionStompInterceptor(JwtDecoder decoder, AuctionRepository auctions) {
        this.decoder = decoder;
        this.auctions = auctions;
    }

    /**
     * Nhận frame inbound trước khi broker/controller xử lý; CONNECT tạo principal từ JWT đã xác minh.
     * Frame tiếp theo dùng principal do Spring gắn với session, kiểm tra expiry và dấu CONNECT trước đó.
     * SUBSCRIBE tra AuctionRepository để chặn phiên không tồn tại/PRIVATE; SEND còn kiểm tra AUCTION.BID
     * và sellerId. Chỉ kiểm tra quyền transport, không thay validation/transaction của bid engine.
     * Sai token/quyền ném lỗi để STOMP gửi ERROR và đóng socket; không retry hoặc ghi bearer vào log.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) { throw denied(); }
        var command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) { return message; }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            String header = accessor.getFirstNativeHeader("Authorization");
            // Xóa trước decode để lỗi JWT không làm log/ERROR chứa bearer gốc.
            accessor.removeNativeHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ") || header.length() <= 7) { throw denied(); }
                var jwt = decoder.decode(header.substring(7));
                accessor.setUser(new JwtAuthenticationToken(jwt, new KeycloakAuthorities().convert(jwt)));
            }
        }
        if (!(accessor.getUser() instanceof JwtAuthenticationToken user) || !user.isAuthenticated()
                || user.getToken().getExpiresAt() == null
                || !Instant.now().isBefore(user.getToken().getExpiresAt())) { throw denied(); }
        // Principal HTTP hợp lệ vẫn phải qua CONNECT; không nhận SUBSCRIBE/SEND trước bước xác thực STOMP.
        if (command != StompCommand.CONNECT && command != StompCommand.STOMP
                && (accessor.getSessionAttributes() == null
                    || !accessor.getSessionAttributes().containsKey(ExpiringWebSocketSessions.EXPIRY))) { throw denied(); }
        if ((command == StompCommand.CONNECT || command == StompCommand.STOMP) && accessor.getSessionAttributes() != null) {
            accessor.getSessionAttributes().put(ExpiringWebSocketSessions.EXPIRY, user.getToken().getExpiresAt());
        }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP
                || command == StompCommand.UNSUBSCRIBE || command == null) { return message; }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
            // Whitelist destination đầy đủ, không cho wildcard hoặc SEND thẳng vào kênh broadcast của server.
            var pattern = command == StompCommand.SUBSCRIBE ? TOPIC : BID;
            var matcher = pattern.matcher(accessor.getDestination() == null ? "" : accessor.getDestination());
            if (!matcher.matches()) { throw denied(); }
            UUID id;
            try { id = UUID.fromString(matcher.group(1)); }
            catch (IllegalArgumentException error) { throw denied(); }
            // Tra dữ liệu server thay vì tin visibility/sellerId trong payload client; chưa hỗ trợ membership PRIVATE.
            var auction = auctions.findById(id).orElseThrow(AuctionStompInterceptor::denied);
            if (auction.getVisibility() != AuctionVisibility.PUBLIC) { throw denied(); }
            if (command == StompCommand.SEND && (user.getAuthorities().stream()
                    .noneMatch(a -> a.getAuthority().equals("AUCTION.BID"))
                    || user.getName().equals(String.valueOf(auction.getSellerId())))) { throw denied(); }
            return message;
        }
        // Không hỗ trợ transaction/ACK client hay destination ngoài hợp đồng.
        throw denied();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("STOMP không được phép"); }
}
