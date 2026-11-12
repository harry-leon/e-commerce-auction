package com.nexus.reputation.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.config.ReputationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Client HTTP gọi GET /api/v1/orders/{orderId} của commerce-service bằng JWT hiện tại của người dùng.
 * Quy tắc:
 * - Chuyển tiếp Bearer JWT từ SecurityContext để commerce-service kiểm tra đúng quyền sở hữu đơn.
 * - Fail-closed: ánh xạ 404 -> ORDER_NOT_FOUND, 403 -> REVIEW_NOT_OWNER, lỗi mạng/5xx -> BAD_GATEWAY.
 */
@Component
public class HttpOrderVerificationClient implements OrderVerificationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderVerificationClient.class);

    private final ReputationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpOrderVerificationClient(ReputationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public VerifiedOrder verifyOrder(UUID orderId) {
        String baseUrl = properties.getCommerceBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình địa chỉ commerce-service để xác thực đơn hàng");
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String endpoint = normalizedBase + "/api/v1/orders/" + orderId;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET();

        String bearerToken = extractBearerToken();
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "Yêu cầu xác thực đơn hàng bị gián đoạn");
        } catch (IOException ex) {
            log.warn("Không kết nối được commerce-service khi kiểm tra đơn [{}]: {}", orderId, ex.getMessage());
            throw new BusinessException(ErrorCode.BAD_GATEWAY,
                    "Không thể kết nối commerce-service để xác thực đơn hàng");
        }

        int statusCode = response.statusCode();
        if (statusCode == 404) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Không tìm thấy đơn hàng để đánh giá");
        }
        if (statusCode == 403) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_OWNER,
                    "Bạn không phải bên mua hoặc bên bán của đơn hàng này");
        }
        if (statusCode == 401) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "Phiên xác thực đơn hàng không hợp lệ");
        }
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("commerce-service trả HTTP {} khi kiểm tra đơn [{}]", statusCode, orderId);
            throw new BusinessException(ErrorCode.BAD_GATEWAY,
                    "commerce-service không xác thực được thông tin đơn hàng");
        }

        try {
            OrderEnvelope envelope = objectMapper.readValue(response.body(), OrderEnvelope.class);
            if (envelope == null || !envelope.success() || envelope.data() == null) {
                throw new BusinessException(ErrorCode.BAD_GATEWAY,
                        "Phản hồi xác thực đơn hàng từ commerce-service không hợp lệ");
            }
            OrderPayload data = envelope.data();
            return new VerifiedOrder(data.id(), data.buyerId(), data.sellerId(), data.status());
        } catch (IOException ex) {
            log.warn("Lỗi giải mã JSON đơn hàng [{}] từ commerce-service: {}", orderId, ex.getMessage());
            throw new BusinessException(ErrorCode.BAD_GATEWAY,
                    "Dữ liệu đơn hàng trả về từ commerce-service không hợp lệ");
        }
    }

    private String extractBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() != null) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderEnvelope(boolean success, OrderPayload data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderPayload(UUID id, UUID buyerId, UUID sellerId, String status) {
    }
}
