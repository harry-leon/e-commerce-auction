package com.nexus.notification.config;

import com.nexus.common.security.KeycloakAuthorities;
import java.time.Instant;
import java.util.regex.Matcher;
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
 * Xác thực frame STOMP cho kênh thông báo đẩy cá nhân `/topic/users/{userId}/notifications`.
 * Chặn ẩn danh và ngăn người dùng này đăng ký nghe thông báo của người dùng khác (chống IDOR).
 */
@Component
public class NotificationStompInterceptor implements ChannelInterceptor {

    private static final Pattern USER_TOPIC = Pattern.compile("/topic/users/([0-9a-fA-F-]{36})/notifications");
    private static final String SESSION_EXPIRY_KEY = ExpiringNotificationWebSocketSessions.EXPIRY;

    private final JwtDecoder jwtDecoder;

    public NotificationStompInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            throw denied();
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) {
            return message;
        }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            String header = accessor.getFirstNativeHeader("Authorization");
            accessor.removeNativeHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ") || header.length() <= 7) {
                    throw denied();
                }
                var jwt = jwtDecoder.decode(header.substring(7));
                accessor.setUser(new JwtAuthenticationToken(jwt, new KeycloakAuthorities().convert(jwt)));
            }
        }
        if (!(accessor.getUser() instanceof JwtAuthenticationToken user) || !user.isAuthenticated()
                || user.getToken().getExpiresAt() == null
                || !Instant.now().isBefore(user.getToken().getExpiresAt())) {
            throw denied();
        }
        if (command != StompCommand.CONNECT && command != StompCommand.STOMP
                && (accessor.getSessionAttributes() == null
                || !accessor.getSessionAttributes().containsKey(SESSION_EXPIRY_KEY))) {
            throw denied();
        }
        if ((command == StompCommand.CONNECT || command == StompCommand.STOMP)
                && accessor.getSessionAttributes() != null) {
            accessor.getSessionAttributes().put(SESSION_EXPIRY_KEY, user.getToken().getExpiresAt());
            return message;
        }
        if (command == StompCommand.UNSUBSCRIBE || command == null) {
            return message;
        }
        if (command == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination() != null ? accessor.getDestination() : "";
            // Spring resolve /user theo principal JWT của session, không dùng profile id hoặc userId do browser chọn.
            if ("/user/queue/notifications".equals(destination)) {
                return message;
            }
            Matcher matcher = USER_TOPIC.matcher(destination);
            if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(user.getToken().getSubject())) {
                throw denied();
            }
            return message;
        }
        throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Kết nối STOMP thông báo không được phép");
    }
}
