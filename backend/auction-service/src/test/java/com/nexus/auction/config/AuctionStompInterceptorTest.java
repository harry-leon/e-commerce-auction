package com.nexus.auction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Bảo vệ quyền destination: buyer không giả broadcast, seller không tự bid, PRIVATE không lộ subscription. */
class AuctionStompInterceptorTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final AuctionRepository repository = mock(AuctionRepository.class);
    private final AuctionStompInterceptor interceptor = new AuctionStompInterceptor(decoder, repository);
    private final UUID id = UUID.randomUUID();

    private Jwt token(Instant expiry) {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").subject("buyer")
                .issuedAt(Instant.now().minusSeconds(20)).expiresAt(expiry)
                .claim("realm_access", Map.of("roles", List.of("AUCTION.BID"))).build();
    }

    private Message<?> frame(StompCommand command, String destination, String privilege) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setUser(new JwtAuthenticationToken(token(Instant.now().plusSeconds(60)),
                List.of(new SimpleGrantedAuthority(privilege))));
        accessor.setSessionAttributes(Map.of(ExpiringWebSocketSessions.EXPIRY, Instant.now().plusSeconds(60)));
        if (destination != null) { accessor.setDestination(destination); }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void auction(AuctionVisibility visibility, UUID seller) {
        var auction = new Auction();
        auction.setVisibility(visibility);
        auction.setSellerId(seller);
        when(repository.findById(id)).thenReturn(Optional.of(auction));
    }

    @Test
    void deniesAnonymousAndExpiredConnect() {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        var anonymous = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(anonymous, null));
        accessor.setUser(new JwtAuthenticationToken(token(Instant.now().minusSeconds(1)), List.of()));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(anonymous, null));
    }

    @Test
    void validatesBearerAndRemovesSecretFromFrame() {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer good");
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        when(decoder.decode("good")).thenReturn(token(Instant.now().plusSeconds(60)));
        assertSame(message, interceptor.preSend(message, null));
        assertEquals("buyer", accessor.getUser().getName());
        assertNull(accessor.getFirstNativeHeader("Authorization"));
        accessor.setNativeHeader("Authorization", "Bearer forged");
        when(decoder.decode("forged")).thenThrow(new JwtException("fixture invalid"));
        assertThrows(JwtException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void allowsPublicSubscriptionAndBidPrivilegeOnly() {
        auction(AuctionVisibility.PUBLIC, UUID.randomUUID());
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/auctions/" + id, "ROLE_BUYER"), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/auctions/" + id + "/bids", "AUCTION.BID"), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SEND, "/app/auctions/" + id + "/bids", "ROLE_SELLER"), null));
    }

    @Test
    void deniesBrokerInjectionWildcardAndPrivateSubscription() {
        auction(AuctionVisibility.PRIVATE, UUID.randomUUID());
        for (String destination : List.of("/topic/auctions/" + id, "/topic/auctions/*", "/queue/private")) {
            assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(StompCommand.SEND, destination, "AUCTION.BID"), null));
            assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, "AUCTION.BID"), null));
        }
    }

    /** Chủ phiên dù có AUCTION.BID vẫn không được đặt giá vào chính phiên mình. */
    @Test
    void deniesSellerBiddingOnOwnAuction() {
        UUID seller = UUID.randomUUID();
        auction(AuctionVisibility.PUBLIC, seller);
        var accessor = StompHeaderAccessor.create(StompCommand.SEND);
        var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject(seller.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        accessor.setUser(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("AUCTION.BID"))));
        accessor.setSessionAttributes(Map.of(ExpiringWebSocketSessions.EXPIRY, Instant.now().plusSeconds(60)));
        accessor.setDestination("/app/auctions/" + id + "/bids");
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, null));
    }

    /** Có cookie HTTP hợp lệ vẫn không được SUBSCRIBE trước STOMP CONNECT. */
    @Test
    void deniesSubscribeBeforeConnectEvenWithHttpPrincipal() {
        var message = frame(StompCommand.SUBSCRIBE, "/topic/auctions/" + id, "ROLE_BUYER");
        var accessor = org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        accessor.setSessionAttributes(new java.util.HashMap<>());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, null));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
