package com.nexus.commerce.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP kho thật, timeout hữu hạn và không retry reserve mù vì Fulfillment chưa idempotent theo refId. */
@Component
public class HttpInventoryClient implements InventoryClient {
    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpInventoryClient(@Value("${FULFILLMENT_SERVICE_URL:http://localhost:8085}") String url, ObjectMapper mapper) {
        this.mapper = mapper;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /** Preview chỉ kiểm tra kho mặc định; thiếu bản ghi kho được xem là không còn hàng. */
    @Override
    public int available(UUID skuId) {
        try {
            var response = client.get().uri("/api/v1/inventory/sku/{id}", skuId).retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Stock>>() { });
            if (response == null || !response.success() || response.data() == null) { throw unavailable(); }
            return response.data().availableQty();
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) { return 0; }
            throw translate(error);
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    /** Giữ kho 15 phút theo checkout draft; caller phải bù trừ nếu response mất hoặc transaction Order rollback. */
    @Override
    public void reserve(UUID orderId, List<Item> items, String token) {
        var receipt = post("/reserve", Map.of("refType", "ORDER", "refId", orderId, "items", items, "timeoutMinutes", 15),
                token, new ParameterizedTypeReference<ApiResponse<Receipt>>() { });
        // Chỉ chấp nhận đủ SKU/quantity của đúng đơn; success=true với payload rỗng không chứng minh kho đã được giữ.
        if (receipt == null || !orderId.equals(receipt.refId()) || !"ORDER".equals(receipt.refType())
                || !"PENDING".equals(receipt.status()) || receipt.items() == null || receipt.items().size() != items.size()) {
            throw unavailable();
        }
        for (Item item : items) {
            if (receipt.items().stream().filter(r -> r != null && item.skuId().equals(r.skuId())
                    && r.quantity() == item.quantity() && "PENDING".equals(r.status()) && r.reservationId() != null).count() != 1) {
                throw unavailable();
            }
        }
    }

    /** Release theo ref hoàn toàn bộ items; API hiện có bỏ qua phiếu đã release nên có thể gọi bù trừ an toàn. */
    @Override
    public void release(UUID orderId, String token) {
        post("/release", Map.of("refType", "ORDER", "refId", orderId, "reason", "ORDER_CREATION_FAILED"),
                token, new ParameterizedTypeReference<ApiResponse<Object>>() { });
    }

    /** Chuyển tiếp bearer token, không ghi log token và không fallback dữ liệu mock khi kho lỗi. */
    private <T> T post(String path, Object body, String token, ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            var response = client.post().uri("/api/v1/inventory" + path).headers(h -> h.setBearerAuth(token))
                    .body(body).retrieve().body(type);
            if (response == null || !response.success()) { throw unavailable(); }
            return response.data();
        } catch (RestClientResponseException error) {
            throw translate(error);
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    /** Giữ nguyên lỗi nghiệp vụ 4xx từ envelope, đặc biệt INVENTORY_INSUFFICIENT; hạ tầng trả 502. */
    private ApiException translate(RestClientResponseException error) {
        if (error.getStatusCode().is4xxClientError()) {
            try {
                var envelope = mapper.readValue(error.getResponseBodyAsByteArray(), ApiResponse.class);
                if (envelope.error() != null) {
                    return new ApiException(envelope.error().code(), HttpStatus.valueOf(error.getStatusCode().value()),
                            envelope.error().message(), envelope.error().details());
                }
            } catch (java.io.IOException ignored) {
                // Response proxy không đúng envelope: trả lỗi kho thay vì coi reserve thành công.
            }
        }
        return unavailable();
    }

    private ApiException unavailable() {
        return new ApiException("INVENTORY_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Kho tạm thời không phản hồi hợp lệ", null);
    }

    /** Chỉ đọc số khả dụng; các trường còn lại thuộc contract Fulfillment và được Jackson bỏ qua. */
    private record Stock(int availableQty) { }

    /** Nhận đúng phần contract reserve cần xác minh; warehouse/expiry vẫn do Fulfillment quản lý. */
    private record Receipt(String refType, UUID refId, String status, List<Reserved> items) { }
    private record Reserved(UUID reservationId, UUID skuId, int quantity, String status) { }
}
