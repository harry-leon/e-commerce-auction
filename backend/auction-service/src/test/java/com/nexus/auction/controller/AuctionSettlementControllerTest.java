package com.nexus.auction.controller;

import com.nexus.auction.config.SecurityConfig;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.service.AuctionSettlementService;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test cho AuctionSettlementController.
 * Kiểm tra các endpoint REST, phân quyền và chuẩn hóa phản hồi ApiResponse.
 */
@WebMvcTest(AuctionSettlementController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class AuctionSettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuctionSettlementService auctionSettlementService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /settle: Admin gọi kích hoạt settle thành công -> 200 OK với ApiResponse success")
    void testSettleAuction_AsAdmin_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();

        AuctionSettlementResponse response = new AuctionSettlementResponse();
        response.setId(UUID.randomUUID());
        response.setAuctionId(auctionId);
        response.setWinnerId(winnerId);
        response.setFinalPrice(new BigDecimal("500000.00"));
        response.setResult(SettlementResult.SUCCESS);
        response.setSettledAt(Instant.now());

        when(auctionSettlementService.settleAuction(auctionId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/settle", auctionId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionId").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.winnerId").value(winnerId.toString()))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("POST /settle: Seller gọi kích hoạt settle thành công -> 200 OK với ApiResponse success")
    void testSettleAuction_AsSeller_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();

        AuctionSettlementResponse response = new AuctionSettlementResponse();
        response.setId(UUID.randomUUID());
        response.setAuctionId(auctionId);
        response.setWinnerId(winnerId);
        response.setFinalPrice(new BigDecimal("750000.00"));
        response.setResult(SettlementResult.SUCCESS);
        response.setSettledAt(Instant.now());

        when(auctionSettlementService.settleAuction(auctionId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/settle", auctionId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionId").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.winnerId").value(winnerId.toString()))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));
    }

    @Test
    @WithMockUser(roles = "BUYER")
    @DisplayName("POST /settle: Buyer gọi bị từ chối 403 Forbidden")
    void testSettleAuction_AsBuyer_Forbidden() throws Exception {
        UUID auctionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/settle", auctionId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUYER")
    @DisplayName("GET /settlement: Tra cứu kết quả thành công -> 200 OK")
    void testGetSettlement_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();

        AuctionSettlementResponse response = new AuctionSettlementResponse();
        response.setId(UUID.randomUUID());
        response.setAuctionId(auctionId);
        response.setResult(SettlementResult.FAILED);
        response.setSettledAt(Instant.now());

        when(auctionSettlementService.getSettlementByAuctionId(auctionId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/auctions/{auctionId}/settlement", auctionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("FAILED"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("GET /settlement: Khi chưa có settlement -> 404 NOT_FOUND")
    void testGetSettlement_NotFound() throws Exception {
        UUID auctionId = UUID.randomUUID();

        when(auctionSettlementService.getSettlementByAuctionId(auctionId))
                .thenThrow(new ResourceNotFoundException("Chưa có kết quả quyết toán cho phiên: " + auctionId));

        mockMvc.perform(get("/api/v1/auctions/{auctionId}/settlement", auctionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * Kiểm tra yêu cầu kích hoạt quyết toán từ client chưa xác thực.
     * Đầu vào: Request POST không có Bearer token.
     * Kết quả mong đợi: 403 Forbidden do @PreAuthorize chặn người dùng ẩn danh khi đi qua filter permitAll.
     */
    @Test
    @DisplayName("POST /settle: Người dùng chưa đăng nhập gọi bị từ chối 403 Forbidden")
    void testSettleAuction_Unauthenticated_Forbidden() throws Exception {
        UUID auctionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auctions/{auctionId}/settle", auctionId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * Kiểm tra yêu cầu tra cứu quyết toán từ client chưa xác thực.
     * Đầu vào: Request GET không có Bearer token.
     * Kết quả mong đợi: 403 Forbidden do @PreAuthorize("isAuthenticated()") chặn người dùng ẩn danh.
     */
    @Test
    @DisplayName("GET /settlement: Người dùng chưa đăng nhập gọi bị từ chối 403 Forbidden")
    void testGetSettlement_Unauthenticated_Forbidden() throws Exception {
        UUID auctionId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/auctions/{auctionId}/settlement", auctionId))
                .andExpect(status().isForbidden());
    }
}
