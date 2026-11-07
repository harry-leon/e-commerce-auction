package com.nexus.auction.config;

import com.nexus.common.security.ClientCredentialsToken;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cấp HTTP client nội bộ Auction → Commerce/Inventory bằng service account riêng.
 * Không tự thực thi settlement/order/reserve; nghiệp vụ gọi bean khi contract tương ứng được triển khai.
 * Thiếu secret thì lỗi khi gọi; timeout 5 giây, không retry mutation để tránh tạo dữ liệu trùng.
 */
@Configuration
public class AuctionServiceClients {
    /** Đọc account riêng từ môi trường; token chỉ được xin khi có request nghiệp vụ, không chặn boot khi IAM mất kết nối. */
    @Bean
    ClientCredentialsToken auctionServiceToken(
            @Value("${app.service-auth.token-url:}") String url,
            @Value("${app.service-auth.client-id:nexus-auction-service}") String id,
            @Value("${app.service-auth.client-secret:}") String secret) {
        return new ClientCredentialsToken(url, id, secret);
    }

    /** Client HTTP tới Inventory, dùng chung cache token Auction; không dùng lb:// vì đây không phải Gateway filter. */
    @Bean
    RestClient auctionInventoryClient(ClientCredentialsToken token,
            @Value("${FULFILLMENT_SERVICE_URL:http://localhost:8085}") String url) {
        return internalClient(url, token);
    }

    /** Client HTTP tới Commerce; chỉ chuẩn bị transport, chưa có endpoint tạo order từ settlement trong service này. */
    @Bean
    RestClient auctionCommerceClient(ClientCredentialsToken token,
            @Value("${COMMERCE_SERVICE_URL:http://localhost:8083}") String url) {
        return internalClient(url, token);
    }

    /**
     * Tạo client có connect/read timeout 5 giây, lấy bearer từ cache ngay trước HTTP execution.
     * Downstream 401 chỉ bỏ cache cho lần gọi sau; giữ response cho caller và không gửi lại mutation mù.
     * Caller chịu trách nhiệm idempotency/bù trừ và đóng response theo vòng đời RestClient.
     */
    private RestClient internalClient(String url, ClientCredentialsToken token) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(url).requestFactory(factory).requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token.get());
            var response = execution.execute(request, body);
            if (response.getStatusCode().value() == 401) { token.invalidate(); }
            return response;
        }).build();
    }
}
