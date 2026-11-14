package com.nexus.fulfillment.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.security.ClientCredentialsToken;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handoff → claim cùng key → preflight qua service account riêng; không retry HTTP ngầm hoặc theo redirect.
 * Deadline 5 giây, bỏ response/cause nhạy cảm; giới hạn 64 KiB được kiểm tra sau nhận byte[].
 * Không lưu token/PII vào job; client chỉ trả snapshot đã đối chiếu định danh/giá cho worker.
 */
@Component
public class DispatchCommerceClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ClientCredentialsToken tokens;
    private final String base;
    private final boolean configured;

    public DispatchCommerceClient(@Value("${app.shipping.commerce.url:http://localhost:8083}") String url,
            @Value("${app.shipping.commerce.token-url:}") String tokenUrl,
            @Value("${app.shipping.commerce.client-id:nexus-fulfillment-service}") String client,
            @Value("${app.shipping.commerce.client-secret:}") String secret) {
        base = url.replaceAll("/+$", "");
        tokens = new ClientCredentialsToken(tokenUrl, client, secret);
        configured = !url.isBlank() && !tokenUrl.isBlank() && !client.isBlank() && !secret.isBlank();
    }

    public boolean configured() { return configured; }

    /** 404 handoff là chờ tiền/kho; không tự tạo task hoặc lấy amount từ dữ liệu của browser. */
    public Handoff handoff(UUID order) {
        var result = call("/shipping-handoffs/" + order, null, null, Handoff.class);
        if (result.id() == null || result.state() == null || !order.equals(result.orderId())
                || !java.util.Set.of("WAITING_PARCEL", "CLAIMED").contains(result.state())) { throw new Fault(422); }
        return result;
    }

    /** Key là job UUID đã commit trước HTTP; response mất ACK được gửi lại đúng key/parcel, không tạo claim khác. */
    public Claim claim(UUID order, UUID handoff, UUID parcel, UUID key) {
        var result = call("/shipping-dispatch-claims", Map.of("handoffId", handoff, "parcelId", parcel), key, Claim.class);
        if (result.id() == null || result.createdAt() == null || !order.equals(result.orderId())
                || !handoff.equals(result.handoffId()) || !parcel.equals(result.parcelId())) { throw new Fault(422); }
        return result;
    }

    /** Preflight phải khớp claim và snapshot VNPAY đầy đủ; replay claim cũ không thay thế kiểm tra này. */
    public Authorization preflight(Claim claim) {
        var result = call("/shipping-dispatch-claims/" + claim.id() + "/preflight", Map.of(), null, Authorization.class);
        var order = result.snapshot();
        if (!claim.equals(result.claim()) || order == null || !claim.orderId().equals(order.orderId())
                || !order.priceFinalized() || !"VNPAY".equals(order.paymentMethod()) || order.total() == null
                || order.total().signum() <= 0 || order.total().stripTrailingZeros().scale() > 0
                || order.status() == null || !java.util.Set.of("PAID", "PROCESSING").contains(order.status()) || "REVIEW".equals(order.fulfillmentStatus())
                || blank(order.recipientName()) || blank(order.recipientPhone()) || blank(order.shippingAddress())) { throw new Fault(422); }
        return result;
    }

    /** Không log request/response; hủy HTTP đang chờ khi timeout để scheduler không treo hoặc theo redirect tới host khác. */
    private <T> T call(String path, Object body, UUID key, Class<T> type) {
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (!configured) { throw new Fault(503); }
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/commerce" + path))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + tokens.get());
            if (body == null) { request.GET(); }
            else { request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))); }
            if (key != null) { request.header("Idempotency-Key", key.toString()); }
            pending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401) { tokens.invalidate(); }
            if (response.statusCode() != 200 && response.statusCode() != 201) { throw new Fault(response.statusCode()); }
            if (response.body().length > 65536) { throw new Fault(422); }
            var envelope = mapper.readTree(response.body());
            if (!envelope.path("success").asBoolean(false) || !envelope.path("data").isObject()) { throw new Fault(422); }
            return mapper.treeToValue(envelope.get("data"), type);
        } catch (Fault error) { throw error; }
        catch (Exception error) {
            if (pending != null) { pending.cancel(true); }
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new Fault(0);
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    @PreDestroy
    public void shutdown() { http.shutdownNow(); }

    /** Metadata chỉ dùng để liên kết, không có merchant/secret hoặc dữ liệu do client tự tính giá. */
    public record Handoff(UUID id, UUID orderId, String state) { }
    public record Claim(UUID id, UUID orderId, UUID handoffId, UUID parcelId, Instant createdAt) { }
    public record Authorization(Claim claim, CommerceShippingClient.Snapshot snapshot) { }
    /** Status/code an toàn để phân loại retry, không nối body/URL/cause có credential hoặc PII. */
    public static class Fault extends RuntimeException {
        private final int status;
        public Fault(int status) { super("Commerce dispatch chưa xác minh được kết quả"); this.status = status; }
        public int status() { return status; }
    }
}
