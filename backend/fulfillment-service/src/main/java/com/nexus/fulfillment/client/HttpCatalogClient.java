package com.nexus.fulfillment.client;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cài đặt RestClient thống nhất gọi Catalog Service với timeout hữu hạn (5 giây).
 * Luồng: gọi GET /api/v1/products/skus/{id} hoặc GET /api/v1/products/seller/{sellerId}.
 * Quy tắc: 404 trả Optional.empty(); response thiếu sellerId hoặc 5xx/lỗi mạng trả 502 BAD_GATEWAY để tránh bỏ qua kiểm tra chủ sở hữu.
 */
@Component
public class HttpCatalogClient implements CatalogClient {

    private static final int MAX_SELLER_PAGES = 50;
    private static final int SELLER_PAGE_SIZE = 100;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final RestClient client;

    /**
     * Dùng SimpleClientHttpRequestFactory thay JdkClientHttpRequestFactory để không mở loopback Selector
     * ngay khi khởi tạo bean, giúp chạy ổn định cả trong môi trường test/sandbox bị giới hạn socket.
     */
    @Autowired
    public HttpCatalogClient(@Value("${CATALOG_SERVICE_URL:http://localhost:8082}") String baseUrl) {
        this(createDefaultClient(baseUrl));
    }

    /**
     * Constructor phục vụ unit test tiêm RestClient đã gắn MockRestServiceServer.
     */
    public HttpCatalogClient(RestClient client) {
        this.client = client;
    }

    private static RestClient createDefaultClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Optional<SkuOwnershipInfo> findSkuById(UUID skuId) {
        if (skuId == null) {
            return Optional.empty();
        }
        return fetchSnapshot("/api/v1/products/skus/{id}", skuId);
    }

    @Override
    public List<SkuOwnershipInfo> findSkusBySeller(UUID sellerId) {
        if (sellerId == null) {
            return List.of();
        }
        List<SkuOwnershipInfo> collected = new ArrayList<>();
        try {
            for (int page = 0; page < MAX_SELLER_PAGES; page++) {
                final int currentPage = page;
                ApiResponse<List<ProductPayload>> response = client.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/v1/products/seller/{sellerId}")
                                .queryParam("page", currentPage)
                                .queryParam("size", SELLER_PAGE_SIZE)
                                .build(sellerId))
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<List<ProductPayload>>>() { });

                if (response == null || !response.success()) {
                    throw catalogInvalidResponse();
                }
                List<ProductPayload> products = response.data();
                if (products != null) {
                    for (ProductPayload product : products) {
                        if (product == null || product.skus() == null) {
                            continue;
                        }
                        UUID ownerId = (product.sellerId() != null) ? product.sellerId() : sellerId;
                        boolean productActive = "ACTIVE".equalsIgnoreCase(product.status());
                        for (SkuPayload sku : product.skus()) {
                            if (sku == null || sku.id() == null) {
                                continue;
                            }
                            boolean active = productActive && "ACTIVE".equalsIgnoreCase(sku.status());
                            collected.add(new SkuOwnershipInfo(
                                    sku.id(),
                                    product.id(),
                                    product.name(),
                                    sku.price(),
                                    sku.originalPrice(),
                                    ownerId,
                                    active
                            ));
                        }
                    }
                }

                boolean hasNext = response.meta() != null
                        && response.meta().pagination() != null
                        && Boolean.TRUE.equals(response.meta().pagination().hasNext());
                if (!hasNext) {
                    break;
                }
            }
            return collected;
        } catch (HttpClientErrorException.NotFound ignored) {
            return List.of();
        } catch (RestClientException ex) {
            throw catalogUnavailable(ex);
        }
    }

    private Optional<SkuOwnershipInfo> fetchSnapshot(String uriTemplate, Object param) {
        try {
            ApiResponse<SkuOwnershipInfo> response = client.get()
                    .uri(uriTemplate, param)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<SkuOwnershipInfo>>() { });
            if (response == null || !response.success()
                    || response.data() == null || response.data().sellerId() == null) {
                throw catalogInvalidResponse();
            }
            return Optional.of(response.data());
        } catch (HttpClientErrorException.NotFound ignored) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw catalogUnavailable(ex);
        }
    }

    private ApiException catalogInvalidResponse() {
        return new ApiException(
                "CATALOG_INVALID_RESPONSE",
                HttpStatus.BAD_GATEWAY,
                "Catalog Service trả về dữ liệu SKU không hợp lệ",
                null
        );
    }

    private ApiException catalogUnavailable(RestClientException ex) {
        return new ApiException(
                "CATALOG_UNAVAILABLE",
                HttpStatus.BAD_GATEWAY,
                "Không thể kết nối Catalog Service để xác minh thông tin SKU",
                ex.getMessage()
        );
    }

    private record ProductPayload(UUID id, UUID sellerId, String name, String status, List<SkuPayload> skus) {
    }

    private record SkuPayload(UUID id, BigDecimal price, BigDecimal originalPrice, String status) {
    }
}
