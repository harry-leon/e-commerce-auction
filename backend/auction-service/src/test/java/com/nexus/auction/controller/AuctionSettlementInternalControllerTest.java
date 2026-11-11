package com.nexus.auction.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.auction.config.SecurityConfig;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.service.AuctionSettlementService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra phân quyền ServiceIdentity và đồng bộ DEFAULTED khi đơn đấu giá quá hạn thanh toán 24h. */
@WebMvcTest(AuctionSettlementInternalController.class)
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class AuctionSettlementInternalControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuctionSettlementService auctionSettlementService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Chỉ service account nexus-commerce-service với quyền AUCTION.SETTLEMENT_SYNC mới được đồng bộ DEFAULTED. */
    @Test
    @DisplayName("Endpoint nội bộ đồng bộ DEFAULTED kiểm tra nghiêm ngặt ServiceIdentity")
    void enforcesServiceIdentityAndMarksDefaulted() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        AuctionSettlementResponse expected = new AuctionSettlementResponse();
        expected.setId(UUID.randomUUID());
        expected.setAuctionId(auctionId);
        expected.setWinnerId(UUID.randomUUID());
        expected.setFinalPrice(new BigDecimal("1500000"));
        expected.setResult(SettlementResult.DEFAULTED);
        expected.setOrderId(orderId);
        expected.setPaymentDeadline(Instant.now().minusSeconds(60));
        expected.setSettledAt(Instant.now().minusSeconds(86460));
        when(auctionSettlementService.markSettlementDefaulted(auctionId, orderId)).thenReturn(expected);

        String body = "{\"orderId\":\"" + orderId + "\"}";

        mvc.perform(post("/api/v1/internal/auctions/{id}/settlement/defaulted", auctionId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/internal/auctions/{id}/settlement/defaulted", auctionId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/internal/auctions/{id}/settlement/defaulted", auctionId)
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                                .authorities(new SimpleGrantedAuthority("AUCTION.SETTLEMENT_SYNC")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/internal/auctions/{id}/settlement/defaulted", auctionId)
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-commerce-service"))
                                .authorities(new SimpleGrantedAuthority("AUCTION.SETTLEMENT_SYNC")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("DEFAULTED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));
    }
}
