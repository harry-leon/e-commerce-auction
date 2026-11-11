package com.nexus.commerce.client;

import com.nexus.common.response.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client gọi nội bộ Reputation Service (trừ điểm uy tín) và Auction Service (đồng bộ DEFAULTED)
 * khi đơn thắng đấu giá quá hạn thanh toán 24 giờ.
 * Xóa cache OAuth nếu gặp HTTP 401 và truyền lỗi ra ngoài để worker outbox tự retry theo lịch backoff.
 */
@Component
public class HttpAuctionTimeoutSyncClient implements AuctionTimeoutSyncClient {

    private final RestClient reputationClient;
    private final RestClient auctionClient;
    private final InventoryServiceToken serviceToken;

    public HttpAuctionTimeoutSyncClient(
            @Value("${app.reputation.url:${REPUTATION_SERVICE_URL:http://localhost:8087}}") String reputationUrl,
            @Value("${app.auction.url:${AUCTION_SERVICE_URL:http://localhost:8084}}") String auctionUrl,
            InventoryServiceToken serviceToken) {
        this.serviceToken = serviceToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.reputationClient = RestClient.builder().baseUrl(reputationUrl).requestFactory(factory).build();
        this.auctionClient = RestClient.builder().baseUrl(auctionUrl).requestFactory(factory).build();
    }

    @Override
    public boolean configured() {
        return serviceToken.configured();
    }

    @Override
    public void applyTimeoutPenalty(UUID userId, UUID auctionId, UUID orderId, int penaltyScore, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("refType", "AUCTION");
        body.put("refId", auctionId);
        body.put("penaltyPoints", penaltyScore);
        body.put("reason", reason);
        try {
            ApiResponse<Map<String, Object>> response = reputationClient.post()
                    .uri("/api/v1/internal/reputations/penalties")
                    .headers(h -> h.setBearerAuth(serviceToken.get()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() { });
            if (response == null || !response.success() || response.data() == null) {
                throw new IllegalStateException("Reputation Service trả về phản hồi không hợp lệ");
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                serviceToken.invalidate();
            }
            throw error;
        }
    }

    @Override
    public void markAuctionDefaulted(UUID auctionId, UUID orderId) {
        Map<String, Object> body = Map.of("orderId", orderId);
        try {
            ApiResponse<Map<String, Object>> response = auctionClient.post()
                    .uri("/api/v1/internal/auctions/{auctionId}/settlement/defaulted", auctionId)
                    .headers(h -> h.setBearerAuth(serviceToken.get()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() { });
            if (response == null || !response.success() || response.data() == null) {
                throw new IllegalStateException("Auction Service không xác nhận đồng bộ DEFAULTED");
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                serviceToken.invalidate();
            }
            throw error;
        }
    }
}
