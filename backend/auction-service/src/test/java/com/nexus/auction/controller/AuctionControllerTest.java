package com.nexus.auction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auction.dto.request.CancelAuctionRequest;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.UpdateAuctionRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.service.AuctionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller MockMvc Test cho AuctionController.
 * Kiểm tra các endpoint REST, phân quyền và chuẩn hóa phản hồi ApiResponse.
 */
@WebMvcTest(AuctionController.class)
@Import({com.nexus.auction.config.SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class AuctionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuctionService auctionService;

    @Test
    @DisplayName("POST /api/v1/auctions thành công khi có vai trò ROLE_SELLER và dữ liệu hợp lệ")
    void createAuction_Success() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Instant now = Instant.now();
        CreateAuctionRequest request = new CreateAuctionRequest();
        request.setProductId(productId);
        request.setStartPrice(new BigDecimal("5000000.00"));
        request.setBidIncrement(new BigDecimal("100000.00"));
        request.setStartTime(now.plus(1, ChronoUnit.HOURS));
        request.setEndTime(now.plus(3, ChronoUnit.HOURS));
        request.setVisibility(AuctionVisibility.PUBLIC);

        AuctionResponse response = new AuctionResponse();
        response.setId(auctionId);
        response.setProductId(productId);
        response.setSellerId(sellerId);
        response.setStatus(AuctionStatus.SCHEDULED);
        response.setStartPrice(request.getStartPrice());
        response.setBidIncrement(request.getBidIncrement());

        when(auctionService.createAuction(eq(sellerId), any(CreateAuctionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auctions")
                        .with(jwt().jwt(j -> j.subject(sellerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SELLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("POST /api/v1/auctions từ chối 403 khi người dùng không có ROLE_SELLER")
    void createAuction_Forbidden_WhenNotSeller() throws Exception {
        UUID buyerId = UUID.randomUUID();
        CreateAuctionRequest request = new CreateAuctionRequest();
        request.setProductId(UUID.randomUUID());
        request.setStartPrice(new BigDecimal("100000.00"));
        request.setBidIncrement(new BigDecimal("10000.00"));
        request.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS));
        request.setEndTime(Instant.now().plus(3, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/v1/auctions")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/auctions cho phép truy cập công khai và trả về danh sách phân trang")
    void getAuctions_Success_Public() throws Exception {
        AuctionResponse response = new AuctionResponse();
        response.setId(UUID.randomUUID());
        response.setStatus(AuctionStatus.ACTIVE);

        when(auctionService.getAuctions(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.singletonList(response)));

        mockMvc.perform(get("/api/v1/auctions")
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.pagination.page").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/auctions/{id} cho phép truy cập công khai và trả về chi tiết phiên")
    void getAuctionById_Success_Public() throws Exception {
        UUID auctionId = UUID.randomUUID();
        AuctionResponse response = new AuctionResponse();
        response.setId(auctionId);
        response.setStatus(AuctionStatus.SCHEDULED);

        when(auctionService.getAuctionById(auctionId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/auctions/{auctionId}", auctionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(auctionId.toString()));
    }

    @Test
    @DisplayName("PUT /api/v1/auctions/{id} cập nhật thành công khi có vai trò ROLE_SELLER")
    void updateAuction_Success() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();

        UpdateAuctionRequest request = new UpdateAuctionRequest();
        request.setStartPrice(new BigDecimal("200000.00"));

        AuctionResponse response = new AuctionResponse();
        response.setId(auctionId);
        response.setStartPrice(request.getStartPrice());

        when(auctionService.updateAuction(eq(auctionId), eq(sellerId), anyBoolean(), any(UpdateAuctionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/auctions/{auctionId}", auctionId)
                        .with(jwt().jwt(j -> j.subject(sellerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SELLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.startPrice").value(200000.00));
    }

    @Test
    @DisplayName("POST /api/v1/auctions/{id}/cancel hủy thành công khi có vai trò ROLE_SELLER hoặc ROLE_ADMIN")
    void cancelAuction_Success() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();

        CancelAuctionRequest request = new CancelAuctionRequest("Lý do hủy hợp lệ");
        AuctionResponse response = new AuctionResponse();
        response.setId(auctionId);
        response.setStatus(AuctionStatus.CANCELLED);

        when(auctionService.cancelAuction(eq(auctionId), eq(sellerId), anyBoolean(), any(CancelAuctionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/cancel", auctionId)
                        .with(jwt().jwt(j -> j.subject(sellerId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SELLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
