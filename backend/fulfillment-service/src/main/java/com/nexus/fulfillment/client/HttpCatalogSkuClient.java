package com.nexus.fulfillment.client;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.common.response.ApiResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client HTTP chuẩn gọi Catalog Service (GET /api/v1/skus/{id}) với timeout hữu hạn.
 * Phân tách rõ lỗi nghiệp vụ 404 (SKU không tồn tại) và lỗi hạ tầng 502 (Catalog gián đoạn).
 */
@Component
public class HttpCatalogSkuClient implements CatalogSkuClient {

    private final RestClient client;

    @Autowired
    public HttpCatalogSkuClient(@Value("${CATALOG_SERVICE_URL:http://localhost:8082}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Constructor dùng trực tiếp RestClient/RestClient.Builder trong kiểm thử MockRestServiceServer. */
    public HttpCatalogSkuClient(RestClient client) {
        this.client = client;
    }

    @Override
    public CatalogSkuSnapshot getSku(UUID skuId) {
        if (skuId == null) {
            throw new ResourceNotFoundException("skuId không được để trống");
        }
        try {
            ApiResponse<CatalogSkuSnapshot> response = client.get()
                    .uri("/api/v1/skus/{id}", skuId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<CatalogSkuSnapshot>>() { });
            if (response == null || !response.success() || response.data() == null
                    || response.data().sellerId() == null) {
                throw new ApiException(
                        "CATALOG_INVALID_RESPONSE",
                        HttpStatus.BAD_GATEWAY,
                        "Catalog trả về dữ liệu SKU không hợp lệ",
                        null);
            }
            return response.data();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Không tìm thấy SKU trên Catalog: " + skuId);
        } catch (RestClientException ex) {
            throw new ApiException(
                    "CATALOG_UNAVAILABLE",
                    HttpStatus.BAD_GATEWAY,
                    "Không thể kết nối Catalog Service để xác thực SKU",
                    null);
        }
    }
}
