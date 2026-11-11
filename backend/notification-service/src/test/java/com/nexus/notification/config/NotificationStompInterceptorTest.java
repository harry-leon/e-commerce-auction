package com.nexus.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Kiểm tra bảo mật kênh WebSocket STOMP push: chỉ chủ sở hữu JWT hợp lệ mới được subscribe kênh cá nhân. */
class NotificationStompInterceptorTest {

    @Test
    void authenticatesConnectAndBlocksCrossUserSubscribe() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwtA = Jwt.withTokenValue("token-a")
                .header("alg", "none")
                .subject(userA.toString())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(decoder.decode("token-a")).thenReturn(jwtA);

        NotificationStompInterceptor interceptor = new NotificationStompInterceptor(decoder);
        Map<String, Object> sessionAttrs = new HashMap<>();

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setNativeHeader("Authorization", "Bearer token-a");
        connect.setSessionAttributes(sessionAttrs);
        connect.setLeaveMutable(true);
        Message<?> connectMsg = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
        Message<?> authedConnect = interceptor.preSend(connectMsg, mock(MessageChannel.class));

        StompHeaderAccessor authedAccessor = StompHeaderAccessor.wrap(authedConnect);
        assertEquals(userA.toString(), authedAccessor.getUser().getName());

        StompHeaderAccessor subOwn = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subOwn.setDestination("/topic/users/" + userA + "/notifications");
        subOwn.setUser(authedAccessor.getUser());
        subOwn.setSessionAttributes(sessionAttrs);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], subOwn.getMessageHeaders()), mock(MessageChannel.class));

        // Kênh /user cố định dựa vào principal server; không cần biết profile id hay đọc token từ browser.
        StompHeaderAccessor personal = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        personal.setDestination("/user/queue/notifications");
        personal.setUser(authedAccessor.getUser());
        personal.setSessionAttributes(sessionAttrs);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], personal.getMessageHeaders()), mock(MessageChannel.class));

        StompHeaderAccessor subOther = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subOther.setDestination("/topic/users/" + userB + "/notifications");
        subOther.setUser(authedAccessor.getUser());
        subOther.setSessionAttributes(sessionAttrs);
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], subOther.getMessageHeaders()), mock(MessageChannel.class)));
    }
}
