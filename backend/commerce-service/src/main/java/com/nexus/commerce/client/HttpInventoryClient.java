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

/**
 * HTTP kho thật, timeout hữu hạn và không retry reserve mù vì Fulfillment chưa idempotent theo refId.
 * Tham số token trên interface giữ tương thích caller cũ; mutation luôn tự lấy identity Commerce, không dùng JWT buyer.
 */
@Component
public class HttpInventoryClient implements InventoryClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final InventoryServiceToken serviceToken;

    public HttpInventoryClient(@Value("${FULFILLMENT_SERVICE_URL:http://localhost:8085}") String url,
            ObjectMapper mapper, InventoryServiceToken serviceToken) {
        this.mapper = mapper;
        this.serviceToken = serviceToken;
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

    /**
     * Đọc tồn khả dụng hàng loạt bằng endpoint công khai POST /api/v1/inventory/stocks/batch.
     * Giỏ hàng dùng kết quả này để hiển thị "còn lại" và chặn cộng dồn vượt tồn trước khi checkout.
     * SKU không có bản ghi tồn kho tại kho mặc định được trả 0 thay vì bỏ khỏi map, tránh hiểu nhầm là chưa kiểm tra.
     * Lỗi hạ tầng ném INVENTORY_UNAVAILABLE để caller biết chưa xác minh được thay vì coi như còn hàng.
     */
    @Override
    public Map<UUID, Integer> availability(List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinct = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            var response = client.post().uri("/api/v1/inventory/stocks/batch")
                    .body(Map.of("skuIds", distinct))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<List<BatchRow>>>() { });
            if (response == null || !response.success() || response.data() == null) {
                throw unavailable();
            }
            Map<UUID, Integer> result = new java.util.HashMap<>();
            for (BatchRow row : response.data()) {
                if (row != null && row.skuId() != null) {
                    result.put(row.skuId(), Math.max(0, row.availableQty()));
                }
            }
            for (UUID id : distinct) {
                result.putIfAbsent(id, 0);
            }
            return result;
        } catch (RestClientException error) {
            // Chức năng: Xử lý ngoại lệ HTTP / kết nối từ Fulfillment Service khi đọc tồn kho hàng loạt.
            // Đầu vào: Ngoại lệ RestClientException (bao gồm cả RestClientResponseException).
            // Kết quả & Lỗi: Ném ngoại lệ ApiException với mã INVENTORY_UNAVAILABLE để caller biết kho tạm thời chưa sẵn sàng.
            throw unavailable();
        }
    }

    /** Giữ kho 15 phút theo checkout draft; caller phải bù trừ nếu response mất hoặc transaction Order rollback. */
    @Override
    public List<UUID> reserve(UUID orderId, List<Item> items, String token) {
        var receipt = post("/reserve", Map.of("refType", "ORDER", "refId", orderId, "items", items, "timeoutMinutes", 15),
                new ParameterizedTypeReference<ApiResponse<Receipt>>() { });
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
        return receipt.items().stream().map(Reserved::reservationId).toList();
    }

    /** Timeout có thể xảy ra sau commit kho; lỗi ALREADY_COMMITTED chính xác được xem là replay thành công. */
    @Override
    public void confirm(UUID reservationId, String token) {
        try {
            post("/reservations/" + reservationId + "/confirm", Map.of(),
                    new ParameterizedTypeReference<ApiResponse<Object>>() { });
        } catch (ApiException error) {
            if (!"INVENTORY_RESERVATION_ALREADY_COMMITTED".equals(error.getCode())) { throw error; }
        }
    }

    /** Release theo ref hoàn toàn bộ items; API hiện có bỏ qua phiếu đã release nên có thể gọi bù trừ an toàn. */
    @Override
    public void release(UUID orderId, String token) {
        post("/release", Map.of("refType", "ORDER", "refId", orderId, "reason", "ORDER_OR_PAYMENT_FAILED"),
                new ParameterizedTypeReference<ApiResponse<Object>>() { });
    }

    /**
     * Chỉ nhận receipt CONFIRMED của đúng đơn và đúng tập phiếu đã bàn giao; response rỗng/sai giữ outbox để retry.
     * Timeout sau commit được gọi lại an toàn vì commitByOrder tại Fulfillment idempotent và khóa phiếu trước sweeper.
     */
    @Override
    public void commitOrder(UUID orderId, List<UUID> expectedReservations, String token) {
        var receipt = post("/orders/" + orderId + "/commit", Map.of(),
                new ParameterizedTypeReference<ApiResponse<Receipt>>() { });
        if (receipt == null || !orderId.equals(receipt.refId()) || !"ORDER".equals(receipt.refType())
                || !"CONFIRMED".equals(receipt.status()) || receipt.items() == null || expectedReservations.isEmpty()
                || receipt.items().size() != expectedReservations.size()
                || receipt.items().stream().anyMatch(r -> r == null || !"CONFIRMED".equals(r.status()) || r.reservationId() == null)
                || !new java.util.HashSet<>(expectedReservations).equals(receipt.items().stream()
                        .map(Reserved::reservationId).collect(java.util.stream.Collectors.toSet()))) {
            throw unavailable();
        }
    }

    /**
     * POST nghiệp vụ kho bằng token Commerce; dữ liệu nhận từ checkout/worker, không dùng JWT buyer của luồng cũ.
     * Downstream 401 bỏ cache cho lần gọi sau, lỗi 4xx giữ mã nghiệp vụ; không retry vì reserve có thể đã commit.
     * Thiếu cấu hình/không lấy được token trả 503; lỗi kết nối/response kho hỏng trả lỗi unavailable cho caller bù trừ.
     */
    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            var response = client.post().uri("/api/v1/inventory" + path).headers(h -> h.setBearerAuth(serviceToken.get()))
                    .body(body).retrieve().body(type);
            if (response == null || !response.success()) { throw unavailable(); }
            return response.data();
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) { serviceToken.invalidate(); }
            throw translate(error);
        } catch (IllegalStateException error) {
            throw new ApiException("INVENTORY_SERVICE_ACCOUNT_MISSING", HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình service account kho", null);
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

    /** Một dòng của POST /inventory/stocks/batch; chỉ dùng skuId và số khả dụng. */
    private record BatchRow(UUID skuId, int availableQty) { }

    /** Nhận đúng phần contract reserve cần xác minh; warehouse/expiry vẫn do Fulfillment quản lý. */
    private record Receipt(String refType, UUID refId, String status, List<Reserved> items) { }
    private record Reserved(UUID reservationId, UUID skuId, int quantity, String status) { }
}
