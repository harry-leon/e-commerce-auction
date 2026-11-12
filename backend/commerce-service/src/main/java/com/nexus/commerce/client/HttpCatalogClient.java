package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Client mặc định lấy giá/trạng thái Catalog qua HTTP, không đọc DB service khác hoặc fallback sang mock. */
@Component
@Profile("!catalog-mock")
public class HttpCatalogClient implements CatalogClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpCatalogClient.class);
    private final RestClient client;

    public HttpCatalogClient(@Value("${CATALOG_SERVICE_URL:http://localhost:8082}") String url) {
        // Tăng timeout kết nối lên 10s và đọc lên 30s để đáp ứng độ trễ mạng khi Catalog Service
        // truy vấn cơ sở dữ liệu Supabase tại Tokyo; tránh lỗi 502 CATALOG_UNAVAILABLE khi thêm giỏ hàng.
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /** SKU 404 trả null để Cart báo unavailable; lỗi hạ tầng trả 502, không tạo dữ liệu bán hàng giả. */
    @Override
    public SkuInfo getSkuInfo(UUID id) {
        try {
            ApiResponse<SkuInfo> response = client.get().uri("/api/v1/products/skus/{id}", id).retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<SkuInfo>>() { });
            if (response == null || !response.success()) {
                throw new ApiException("CATALOG_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY, "Catalog trả dữ liệu không hợp lệ", null);
            }
            return response.data();
        } catch (HttpClientErrorException.NotFound error) {
            return null;
        } catch (RestClientException error) {
            // Ghi log chi tiết lỗi khi giao tiếp Catalog Service (timeout, connection refused, parse error)
            // để hỗ trợ chẩn đoán chính xác nguyên nhân lỗi 502 trong luồng thêm giỏ hàng.
            LOG.error("Lỗi khi gọi Catalog Service cho SKU {}: {}", id, error.getMessage(), error);
            throw new ApiException("CATALOG_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Catalog tạm thời không phản hồi: " + error.getMessage(), null);
        }
    }

    /** Chỉ tải mỗi SKU một lần; tạm dùng request tuần tự, chưa có batch API liên service. */
    @Override
    public Map<UUID, SkuInfo> getSkuDetails(List<UUID> ids) {
        Map<UUID, SkuInfo> result = new HashMap<>();
        if (ids != null) {
            for (UUID id : ids.stream().distinct().toList()) {
                SkuInfo item = getSkuInfo(id);
                if (item != null) { result.put(id, item); }
            }
        }
        return result;
    }
}
