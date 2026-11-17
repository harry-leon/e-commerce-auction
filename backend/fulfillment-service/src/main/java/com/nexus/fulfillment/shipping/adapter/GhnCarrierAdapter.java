package com.nexus.fulfillment.shipping.adapter;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter tích hợp API Giao Hàng Nhanh (GHN) (SRS §3.6.2, KH-S3-1).
 *
 * Chức năng:
 * - Đóng gói hợp đồng HTTP của GHN: header Token/ShopId, endpoint tính phí, tạo
 * đơn và tra cứu hành trình.
 * - Chuẩn hóa trạng thái GHN (ready_to_pick, picking, delivering, delivered,
 * ...) về ShipmentStatus nội bộ.
 * - Khi chưa cấu hình GHN_API_TOKEN (môi trường local/dev), tự động chạy chế độ
 * sandbox mô phỏng xác định
 * để luồng nghiệp vụ vẫn kiểm thử được; khi có token hoặc inject RestClient
 * (contract test) sẽ gọi HTTP thật.
 */
@Component
public class GhnCarrierAdapter implements ShippingCarrierAdapter {

    public static final String CARRIER_CODE = "GHN";
    private static final String FEE_PATH = "/shiip/public-api/v2/shipping-order/fee";
    private static final String CREATE_PATH = "/shiip/public-api/v2/shipping-order/create";
    private static final String DETAIL_PATH = "/shiip/public-api/v2/shipping-order/detail";

    private final RestClient restClient;
    private final FulfillmentProperties.CarrierEndpoint endpoint;
    private final int defaultDeliveryDays;

    @Autowired
    public GhnCarrierAdapter(FulfillmentProperties properties) {
        this(
                buildRestClient(properties.getShipping().getGhn()),
                properties.getShipping().getGhn(),
                properties.getShipping().getDefaultDeliveryDays());
    }

    /**
     * Constructor phục vụ contract test với MockRestServiceServer gắn trực tiếp vào
     * RestClient.
     */
    public GhnCarrierAdapter(
            RestClient restClient,
            FulfillmentProperties.CarrierEndpoint endpoint,
            int defaultDeliveryDays) {
        this.restClient = restClient;
        this.endpoint = endpoint;
        this.defaultDeliveryDays = defaultDeliveryDays > 0 ? defaultDeliveryDays : 2;
    }

    private static RestClient buildRestClient(FulfillmentProperties.CarrierEndpoint endpoint) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoint.getConnectTimeoutMs());
        factory.setReadTimeout(endpoint.getReadTimeoutMs());
        return RestClient.builder()
                .baseUrl(endpoint.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public CarrierQuoteResult quote(CarrierQuoteRequest request) {
        String serviceType = normalizeServiceType(request.serviceType());
        if (isLocalSandboxFallback()) {
            // Cước sandbox chuẩn VNĐ: Gói Hỏa tốc = 35.000₫, Gói Tiêu chuẩn = 22.000₫ (thay
            // vì 35.00 và 22.00)
            BigDecimal baseFee = "EXPRESS".equals(serviceType) ? new BigDecimal("35000") : new BigDecimal("22000");
            int weight = request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500;
            BigDecimal extra = BigDecimal.valueOf(Math.max(0, (weight - 500) / 500) * 5000L);
            int days = "EXPRESS".equals(serviceType) ? 1 : Math.min(defaultDeliveryDays, 2);
            return new CarrierQuoteResult(
                    CARRIER_CODE,
                    serviceType,
                    baseFee.add(extra),
                    days,
                    Instant.now().plus(days, ChronoUnit.DAYS));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service_type_id", "EXPRESS".equals(serviceType) ? 2 : 5);
        payload.put("to_district_id", request.toDistrictId() != null ? request.toDistrictId() : 1454);
        payload.put("to_ward_code", request.toWardCode() != null ? request.toWardCode() : "21211");
        payload.put("weight", request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500);
        payload.put("insurance_value", request.orderTotal() != null ? request.orderTotal().intValue() : 0);

        GhnEnvelope<GhnFeeData> response = executePost(
                FEE_PATH,
                payload,
                new org.springframework.core.ParameterizedTypeReference<GhnEnvelope<GhnFeeData>>() {
                });

        if (response == null || response.code() == null || response.code() != 200 || response.data() == null) {
            throw mapGhnBusinessError(response);
        }
        int days = "EXPRESS".equals(serviceType) ? 1 : Math.min(defaultDeliveryDays, 2);
        BigDecimal fee = response.data().total() != null ? response.data().total() : BigDecimal.ZERO;
        return new CarrierQuoteResult(
                CARRIER_CODE,
                serviceType,
                fee,
                days,
                Instant.now().plus(days, ChronoUnit.DAYS));
    }

    @Override
    public CarrierCreateShipmentResult createShipment(CarrierCreateShipmentRequest request) {
        String serviceType = normalizeServiceType(request.serviceType());
        if (isLocalSandboxFallback()) {
            CarrierQuoteResult quote = quote(new CarrierQuoteRequest(
                    request.orderId(),
                    request.warehouseId(),
                    request.fromAddress(),
                    request.shippingAddress(),
                    request.toDistrictId(),
                    request.toWardCode(),
                    request.weightGrams(),
                    request.insuranceValue(),
                    serviceType));
            String suffix = request.orderId() != null
                    ? request.orderId().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT)
                    : UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            String trackingNo = "GHN-" + suffix;
            return new CarrierCreateShipmentResult(
                    CARRIER_CODE,
                    serviceType,
                    trackingNo,
                    quote.fee(),
                    quote.estimatedDeliveryDays(),
                    quote.estimatedDeliveryAt(),
                    "ready_to_pick",
                    ShipmentStatus.PENDING);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_type_id", 1);
        payload.put("note", request.note() != null ? request.note() : "Nexus order " + request.orderId());
        payload.put("required_note", "KHONGCHOXEMHANG");
        payload.put("to_name", request.recipientName() != null ? request.recipientName() : "Nexus Customer");
        payload.put("to_phone", request.recipientPhone() != null ? request.recipientPhone() : "0900000000");
        payload.put("to_address", request.shippingAddress() != null ? request.shippingAddress() : "TP. Ho Chi Minh");
        payload.put("to_ward_code", request.toWardCode() != null ? request.toWardCode() : "21211");
        payload.put("to_district_id", request.toDistrictId() != null ? request.toDistrictId() : 1454);
        payload.put("weight", request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500);
        payload.put("service_type_id", "EXPRESS".equals(serviceType) ? 2 : 5);
        payload.put("cod_amount", request.codAmount() != null ? request.codAmount().intValue() : 0);
        payload.put("client_order_code", request.orderId() != null ? request.orderId().toString() : null);

        GhnEnvelope<GhnCreateData> response = executePost(
                CREATE_PATH,
                payload,
                new org.springframework.core.ParameterizedTypeReference<GhnEnvelope<GhnCreateData>>() {
                });

        if (response == null || response.code() == null || response.code() != 200 || response.data() == null
                || response.data().orderCode() == null || response.data().orderCode().isBlank()) {
            throw mapGhnBusinessError(response);
        }

        int days = "EXPRESS".equals(serviceType) ? 1 : Math.min(defaultDeliveryDays, 2);
        Instant eta = parseInstantOrDefault(response.data().expectedDeliveryTime(),
                Instant.now().plus(days, ChronoUnit.DAYS));
        BigDecimal fee = response.data().totalFee() != null ? response.data().totalFee() : BigDecimal.ZERO;
        return new CarrierCreateShipmentResult(
                CARRIER_CODE,
                serviceType,
                response.data().orderCode(),
                fee,
                days,
                eta,
                "ready_to_pick",
                ShipmentStatus.PENDING);
    }

    @Override
    public CarrierTrackingResult trackShipment(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_PARAM_VALUE, "Mã vận đơn GHN không được để trống");
        }
        if (isLocalSandboxFallback()) {
            Instant now = Instant.now();
            List<CarrierTrackingEvent> events = List.of(
                    new CarrierTrackingEvent("ready_to_pick", ShipmentStatus.PENDING,
                            "Đơn hàng đã tạo trên GHN Sandbox", "Kho lấy hàng", now.minus(2, ChronoUnit.HOURS)),
                    new CarrierTrackingEvent("picking", ShipmentStatus.PICKING,
                            "Bưu tá GHN đang đến lấy hàng", "Kho lấy hàng", now.minus(1, ChronoUnit.HOURS)));
            return new CarrierTrackingResult(
                    CARRIER_CODE,
                    trackingNumber,
                    "picking",
                    ShipmentStatus.PICKING,
                    "Kho lấy hàng",
                    now,
                    events);
        }

        Map<String, Object> payload = Map.of("order_code", trackingNumber);
        GhnEnvelope<GhnDetailData> response = executePost(
                DETAIL_PATH,
                payload,
                new org.springframework.core.ParameterizedTypeReference<GhnEnvelope<GhnDetailData>>() {
                });

        if (response == null || response.code() == null || response.code() != 200 || response.data() == null) {
            throw mapGhnBusinessError(response);
        }

        String rawStatus = response.data().status();
        ShipmentStatus mapped = mapGhnStatus(rawStatus);
        List<CarrierTrackingEvent> events = new ArrayList<>();
        if (response.data().log() != null) {
            for (GhnLogItem item : response.data().log()) {
                if (item != null) {
                    events.add(new CarrierTrackingEvent(
                            item.status(),
                            mapGhnStatus(item.status()),
                            "GHN status: " + item.status(),
                            null,
                            parseInstantOrDefault(item.updatedDate(), Instant.now())));
                }
            }
        }
        return new CarrierTrackingResult(
                CARRIER_CODE,
                trackingNumber,
                rawStatus,
                mapped,
                null,
                Instant.now(),
                events);
    }

    private <T> T executePost(
            String path,
            Object payload,
            org.springframework.core.ParameterizedTypeReference<T> responseType) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Token", endpoint.getToken() != null ? endpoint.getToken() : "");
            if (endpoint.getShopId() != null && !endpoint.getShopId().isBlank()) {
                spec = spec.header("ShopId", endpoint.getShopId());
            }
            return spec.body(payload).retrieve().body(responseType);
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new ApiException(ErrorCode.SHIPMENT_CARRIER_TIMEOUT, "Hết thời gian chờ phản hồi từ GHN", ex);
            }
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Không kết nối được tới GHN: " + ex.getMessage(),
                    ex);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            throw new ApiException(
                    ErrorCode.SHIPMENT_CARRIER_ERROR,
                    "GHN trả về lỗi HTTP " + ex.getStatusCode().value(),
                    ex);
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Lỗi gọi API GHN: " + ex.getMessage(), ex);
        }
    }

    private ApiException mapGhnBusinessError(GhnEnvelope<?> response) {
        String message = response != null && response.message() != null
                ? response.message()
                : "Phản hồi không hợp lệ từ GHN";
        if (response != null && response.code() != null && response.code() == 400
                && message.toLowerCase(Locale.ROOT).contains("address")) {
            return new ApiException(ErrorCode.SHIPMENT_ADDRESS_UNSUPPORTED, message);
        }
        return new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "GHN từ chối yêu cầu: " + message);
    }

    @Override
    public ShipmentStatus mapCarrierStatus(String rawStatus) {
        return mapGhnStatus(rawStatus);
    }

    /** Ánh xạ trạng thái gốc của GHN sang ShipmentStatus nội bộ chuẩn hóa (SRS §3.6.2, KH-S3-2). */
    public static ShipmentStatus mapGhnStatus(String ghnStatus) {
        if (ghnStatus == null) {
            return ShipmentStatus.PENDING;
        }
        return switch (ghnStatus.trim().toLowerCase(Locale.ROOT)) {
            case "created" -> ShipmentStatus.CREATED;
            case "ready_to_pick" -> ShipmentStatus.PENDING;
            case "picking", "money_collect_picking" -> ShipmentStatus.PICKING;
            case "picked", "storing", "sorting", "picked_up" -> ShipmentStatus.PICKED_UP;
            case "transporting" -> ShipmentStatus.SHIPPED;
            case "delivering", "money_collect_delivering", "in_transit" -> ShipmentStatus.IN_TRANSIT;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "return", "returning", "returned", "return_transporting", "return_sorting", "waiting_to_return" ->
                    ShipmentStatus.RETURNED;
            case "delivery_fail", "damage", "lost", "exception", "failed" -> ShipmentStatus.FAILED;
            case "cancel", "cancelled" -> ShipmentStatus.CANCELLED;
            default -> ShipmentStatus.IN_TRANSIT;
        };
    }

    private boolean isLocalSandboxFallback() {
        return endpoint.getToken() == null || endpoint.getToken().isBlank();
    }

    private String normalizeServiceType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return "STANDARD";
        }
        return serviceType.trim().toUpperCase(Locale.ROOT);
    }

    private Instant parseInstantOrDefault(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    record GhnEnvelope<T>(Integer code, String message, T data) {
    }

    record GhnFeeData(BigDecimal total, BigDecimal service_fee) {
    }

    record GhnCreateData(
            @com.fasterxml.jackson.annotation.JsonProperty("order_code") String orderCode,
            @com.fasterxml.jackson.annotation.JsonProperty("total_fee") BigDecimal totalFee,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_delivery_time") String expectedDeliveryTime) {
    }

    record GhnDetailData(
            @com.fasterxml.jackson.annotation.JsonProperty("order_code") String orderCode,
            String status,
            List<GhnLogItem> log) {
    }

    record GhnLogItem(
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("updated_date") String updatedDate) {
    }
}
