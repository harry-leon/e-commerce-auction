package com.nexus.auction.client;

import com.nexus.auction.client.dto.ReputationEligibilityDto;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP Client gọi GET /api/v1/internal/reputations/{userId}/eligibility trên reputation-service
 * bằng service account nexus-auction-service (timeout 5s, fail-closed khi mất kết nối).
 */
@Component
public class HttpReputationEligibilityClient implements ReputationEligibilityClient {

    private static final Logger log = LoggerFactory.getLogger(HttpReputationEligibilityClient.class);

    private final RestClient client;

    public HttpReputationEligibilityClient(@Qualifier("auctionReputationClient") RestClient client) {
        this.client = client;
    }

    @Override
    public void assertEligible(UUID userId, String action) {
        if (userId == null || action == null || action.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE,
                    "Thiếu userId hoặc action khi kiểm tra điều kiện uy tín");
        }
        try {
            ApiResponse<ReputationEligibilityDto> response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/reputations/{userId}/eligibility")
                            .queryParam("action", action)
                            .build(userId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<ReputationEligibilityDto>>() {
                    });

            if (response == null || !response.success() || response.data() == null) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Phản hồi kiểm tra điều kiện uy tín không hợp lệ");
            }
            ReputationEligibilityDto dto = response.data();
            if (!dto.eligible()) {
                String message = (dto.reason() != null && !dto.reason().isBlank())
                        ? dto.reason()
                        : ErrorCode.REPUTATION_ACTION_FORBIDDEN.getDefaultMessage();
                throw new BusinessException(ErrorCode.REPUTATION_ACTION_FORBIDDEN, message);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (HttpClientErrorException | HttpServerErrorException | ResourceAccessException ex) {
            log.warn("Không xác minh được điều kiện uy tín cho user [{}] ({}): {}", userId, action, ex.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Không thể xác minh điều kiện điểm uy tín với reputation-service");
        } catch (RuntimeException ex) {
            log.warn("Lỗi bất ngờ khi kiểm tra điều kiện uy tín cho user [{}]: {}", userId, ex.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Không thể xác minh điều kiện điểm uy tín với reputation-service");
        }
    }
}
