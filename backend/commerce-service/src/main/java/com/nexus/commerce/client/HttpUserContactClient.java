package com.nexus.commerce.client;

import com.nexus.common.response.ApiResponse;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Tra cứu email và tên winner từ endpoint nội bộ User Service bằng token service account Commerce.
 * Dùng SimpleClientHttpRequestFactory với timeout ngắn và graceful fallback để lỗi User không chặn tạo đơn đấu giá.
 */
@Component
@Slf4j
public class HttpUserContactClient implements UserContactClient {

    private final RestClient client;
    private final InventoryServiceToken serviceToken;

    public HttpUserContactClient(
            @Value("${app.user.url:${USER_SERVICE_URL:http://localhost:8081}}") String url,
            InventoryServiceToken serviceToken) {
        this.serviceToken = serviceToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public Optional<UserContact> findContact(UUID userId) {
        if (userId == null || !serviceToken.configured()) {
            return Optional.empty();
        }
        try {
            ApiResponse<UserContact> response = client.get()
                    .uri("/api/v1/internal/users/{id}", userId)
                    .headers(h -> h.setBearerAuth(serviceToken.get()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<UserContact>>() { });
            if (response != null && response.success() && response.data() != null) {
                return Optional.of(response.data());
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                serviceToken.invalidate();
            }
            log.warn("Không tra được email người dùng [{}] từ User Service (HTTP {}): fallback thông báo IN_APP/PUSH",
                    userId, error.getStatusCode().value());
        } catch (RestClientException | IllegalStateException error) {
            log.warn("User Service không phản hồi khi tra liên hệ [{}]: {}; fallback thông báo IN_APP/PUSH",
                    userId, error.getClass().getSimpleName());
        }
        return Optional.empty();
    }
}
