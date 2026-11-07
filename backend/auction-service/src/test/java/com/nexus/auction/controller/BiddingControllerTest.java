package com.nexus.auction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import com.nexus.auction.service.BiddingService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit Test tầng Controller cho BiddingController (HG-S2-3).
 *
 * Kiểm tra:
 * 1. POST /api/v1/auctions/{auctionId}/bids:
 *    - Thành công 201 Created khi đã authenticated.
 *    - Từ chối 403 Forbidden khi chưa authenticated (do PreAuthorize("isAuthenticated()") chặn).
 *    - Lỗi 422 khi validation thất bại (@DecimalMin / @NotNull).
 *    - Trả lỗi 409 Conflict khi bị tranh chấp đặt giá (BID_CONCURRENT_OUTBID).
 *    - Trả lỗi 422 khi Seller tự đặt giá (AUCTION_SELLER_CANNOT_BID).
 * 2. GET /api/v1/auctions/{auctionId}/bids:
 *    - Thành công 200 OK với phân trang và danh sách bids.
 */
@WebMvcTest(BiddingController.class)
@Import({com.nexus.auction.config.SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class BiddingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BiddingService biddingService;

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/bids thành công 201 Created khi gửi dữ liệu hợp lệ")
    void placeBid_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5200000.00"), "idemp-001");

        BidResponse bidResponse = new BidResponse();
        bidResponse.setId(UUID.randomUUID());
        bidResponse.setAuctionId(auctionId);
        bidResponse.setBidderId(bidderId);
        bidResponse.setBidderMask("9b1d***cb6d");
        bidResponse.setAmount(request.getAmount());
        bidResponse.setIdempotencyKey(request.getIdempotencyKey());
        bidResponse.setPlacedAt(Instant.now());

        PlaceBidResult result = new PlaceBidResult(
                bidResponse,
                request.getAmount(),
                1,
                Instant.now().plusSeconds(3600),
                false
        );

        when(biddingService.placeBid(eq(auctionId), eq(bidderId), any(PlaceBidRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .with(jwt().jwt(builder -> builder.subject(bidderId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currentBid").value(5200000.00))
                .andExpect(jsonPath("$.data.bidCount").value(1))
                .andExpect(jsonPath("$.data.extended").value(false))
                .andExpect(jsonPath("$.data.bid.bidderMask").value("9b1d***cb6d"));
    }

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/bids từ chối 403 Forbidden khi chưa đăng nhập")
    void placeBid_Forbidden_WithoutToken() throws Exception {
        UUID auctionId = UUID.randomUUID();
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5200000.00"), "idemp-001");

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/bids từ chối 422 Unprocessable Entity khi validation thất bại (amount <= 0)")
    void placeBid_ValidationFailed_NegativeAmount() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("-1000.00"), "idemp-001");

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .with(jwt().jwt(builder -> builder.subject(bidderId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/bids từ chối 409 Conflict khi bị tranh chấp đồng thời (BID_CONCURRENT_OUTBID)")
    void placeBid_Conflict_ConcurrentOutbid() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5200000.00"), "idemp-001");

        when(biddingService.placeBid(eq(auctionId), eq(bidderId), any(PlaceBidRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.BID_CONCURRENT_OUTBID, "Đã có người đặt mức giá này hoặc cao hơn ngay trước bạn"));

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .with(jwt().jwt(builder -> builder.subject(bidderId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BID_CONCURRENT_OUTBID"));
    }

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/bids từ chối 422 khi Seller tự đặt giá cho phiên của mình")
    void placeBid_SellerCannotBid_422() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5200000.00"), "idemp-001");

        when(biddingService.placeBid(eq(auctionId), eq(bidderId), any(PlaceBidRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.AUCTION_SELLER_CANNOT_BID, "Người tạo phiên không được tự đặt giá"));

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .with(jwt().jwt(builder -> builder.subject(bidderId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SELLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUCTION_SELLER_CANNOT_BID"));
    }

    @Test
    @DisplayName("GET /api/v1/auctions/{id}/bids cho phép xem lịch sử đặt giá công khai có phân trang")
    void getBidHistory_PublicAccess_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        BidResponse bidResponse = new BidResponse();
        bidResponse.setId(UUID.randomUUID());
        bidResponse.setAuctionId(auctionId);
        bidResponse.setBidderId(UUID.randomUUID());
        bidResponse.setBidderMask("b1d2***9999");
        bidResponse.setAmount(new BigDecimal("5000000.00"));
        bidResponse.setPlacedAt(Instant.now());

        when(biddingService.getBidHistory(eq(auctionId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.singletonList(bidResponse)));

        mockMvc.perform(get("/api/v1/auctions/{auctionId}/bids", auctionId)
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].amount").value(5000000.00))
                .andExpect(jsonPath("$.data[0].bidderMask").value("b1d2***9999"))
                .andExpect(jsonPath("$.meta.pagination.totalElements").value(1));
    }
}
