package com.nexus.fulfillment.shipping.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter tích hợp API Giao Hàng Tiết Kiệm (GHTK) (SRS §3.6.2, KH-S3-1).
 *
 * Chức năng:
 * - Đóng gói hợp đồng HTTP của GHTK: header Token, endpoint /services/shipment/fee, /order và /v2/{label}.
 * - Chuẩn hóa mã trạng thái số của GHTK (1..11, -1) về ShipmentStatus nội bộ.
 * - Khi chưa cấu hình GHTK_API_TOKEN (local/dev), chạy chế độ sandbox mô phỏng xác định;
 *   khi có token hoặc RestClient được tiêm từ contract test sẽ thực thi HTTP theo đúng schema GHTK.
 */
@Component
public class GhtkCarrierAdapter implements ShippingCarrierAdapter {

    public static final String CARRIER_CODE = "GHTK";
    private static final String FEE_PATH = "/services/shipment/fee";
    private static final String CREATE_PATH = "/services/shipment/order";
    private static final String TRACK_PATH = "/services/shipment/v2/{trackingNumber}";

    private final RestClient restClient;
    private final FulfillmentProperties.CarrierEndpoint endpoint;
    private final int defaultDeliveryDays;

    @Autowired
    public GhtkCarrierAdapter(FulfillmentProperties properties) {
        this(
                buildRestClient(properties.getShipping().getGhtk()),
                properties.getShipping().getGhtk(),
                properties.getShipping().getDefaultDeliveryDays()
        );
    }

    /**
     * Constructor phục vụ contract test với MockRestServiceServer gắn trực tiếp vào RestClient.
     */
    public GhtkCarrierAdapter(
            RestClient restClient,
            FulfillmentProperties.CarrierEndpoint endpoint,
            int defaultDeliveryDays) {
        this.restClient = restClient;
        this.endpoint = endpoint;
        this.defaultDeliveryDays = defaultDeliveryDays > 0 ? defaultDeliveryDays : 3;
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
            // Cước sandbox chuẩn VNĐ: Gói Hỏa tốc = 30.000₫, Gói Tiêu chuẩn = 18.000₫ (thay vì 30.00 và 18.00)
            BigDecimal baseFee = "EXPRESS".equals(serviceType) ? new BigDecimal("30000") : new BigDecimal("18000");
            int weight = request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500;
            BigDecimal extra = BigDecimal.valueOf(Math.max(0, (weight - 500) / 500) * 3000L);
            int days = "EXPRESS".equals(serviceType) ? 2 : defaultDeliveryDays;
            return new CarrierQuoteResult(
                    CARRIER_CODE,
                    serviceType,
                    baseFee.add(extra),
                    days,
                    Instant.now().plus(days, ChronoUnit.DAYS)
            );
        }

        try {
            GhtkFeeResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(FEE_PATH)
                            .queryParam("pick_address", request.fromAddress() != null ? request.fromAddress() : "TP. Ho Chi Minh")
                            .queryParam("address", request.toAddress() != null ? request.toAddress() : "TP. Ho Chi Minh")
                            .queryParam("weight", request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500)
                            .queryParam("value", request.orderTotal() != null ? request.orderTotal().intValue() : 0)
                            .queryParam("deliver_option", "EXPRESS".equals(serviceType) ? "xteam" : "none")
                            .build())
                    .header("Token", endpoint.getToken() != null ? endpoint.getToken() : "")
                    .retrieve()
                    .body(new ParameterizedTypeReference<GhtkFeeResponse>() { });

            if (response == null || !Boolean.TRUE.equals(response.success()) || response.fee() == null) {
                throw mapGhtkError(response != null ? response.message() : null);
            }
            if (Boolean.FALSE.equals(response.fee().delivery())) {
                throw new ApiException(ErrorCode.SHIPMENT_ADDRESS_UNSUPPORTED, "GHTK không hỗ trợ giao tới địa chỉ này");
            }
            int days = "EXPRESS".equals(serviceType) ? 2 : defaultDeliveryDays;
            BigDecimal fee = response.fee().fee() != null ? response.fee().fee() : BigDecimal.ZERO;
            return new CarrierQuoteResult(
                    CARRIER_CODE,
                    serviceType,
                    fee,
                    days,
                    Instant.now().plus(days, ChronoUnit.DAYS)
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            throw translateNetworkException(ex);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "GHTK trả về lỗi HTTP " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Lỗi gọi API GHTK: " + ex.getMessage(), ex);
        }
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
                    serviceType
            ));
            String suffix = request.orderId() != null
                    ? request.orderId().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT)
                    : UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            String trackingNo = "GHTK-" + suffix;
            return new CarrierCreateShipmentResult(
                    CARRIER_CODE,
                    serviceType,
                    trackingNo,
                    quote.fee(),
                    quote.estimatedDeliveryDays(),
                    quote.estimatedDeliveryAt(),
                    "1",
                    ShipmentStatus.PENDING
            );
        }

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", request.orderId() != null ? request.orderId().toString() : UUID.randomUUID().toString());
        order.put("pick_name", "Nexus Warehouse");
        order.put("pick_address", request.fromAddress() != null ? request.fromAddress() : "TP. Ho Chi Minh");
        order.put("pick_tel", "0900000001");
        order.put("name", request.recipientName() != null ? request.recipientName() : "Nexus Customer");
        order.put("address", request.shippingAddress() != null ? request.shippingAddress() : "TP. Ho Chi Minh");
        order.put("tel", request.recipientPhone() != null ? request.recipientPhone() : "0900000000");
        order.put("pick_money", request.codAmount() != null ? request.codAmount().intValue() : 0);
        order.put("value", request.insuranceValue() != null ? request.insuranceValue().intValue() : 0);
        order.put("note", request.note() != null ? request.note() : "");

        Map<String, Object> payload = Map.of(
                "order", order,
                "products", List.of(Map.of(
                        "name", "Nexus Order Item",
                        "weight", (request.weightGrams() != null && request.weightGrams() > 0 ? request.weightGrams() : 500) / 1000.0,
                        "quantity", 1
                ))
        );

        try {
            GhtkCreateResponse response = restClient.post()
                    .uri(CREATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Token", endpoint.getToken() != null ? endpoint.getToken() : "")
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<GhtkCreateResponse>() { });

            if (response == null || !Boolean.TRUE.equals(response.success()) || response.order() == null
                    || response.order().label() == null || response.order().label().isBlank()) {
                throw mapGhtkError(response != null ? response.message() : null);
            }
            int days = "EXPRESS".equals(serviceType) ? 2 : defaultDeliveryDays;
            BigDecimal fee = response.order().fee() != null ? response.order().fee() : BigDecimal.ZERO;
            return new CarrierCreateShipmentResult(
                    CARRIER_CODE,
                    serviceType,
                    response.order().label(),
                    fee,
                    days,
                    Instant.now().plus(days, ChronoUnit.DAYS),
                    "1",
                    ShipmentStatus.PENDING
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            throw translateNetworkException(ex);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "GHTK trả về lỗi HTTP " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Lỗi gọi API GHTK: " + ex.getMessage(), ex);
        }
    }

    @Override
    public CarrierTrackingResult trackShipment(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_PARAM_VALUE, "Mã vận đơn GHTK không được để trống");
        }
        if (isLocalSandboxFallback()) {
            Instant now = Instant.now();
            List<CarrierTrackingEvent> events = List.of(
                    new CarrierTrackingEvent("1", ShipmentStatus.PENDING,
                            "Chưa tiếp nhận trên GHTK Sandbox", "Kho lấy hàng", now.minus(2, ChronoUnit.HOURS)),
                    new CarrierTrackingEvent("3", ShipmentStatus.PACKED,
                            "Đã lấy hàng/Đã nhập kho GHTK", "Bưu cục GHTK", now.minus(30, ChronoUnit.MINUTES))
            );
            return new CarrierTrackingResult(
                    CARRIER_CODE,
                    trackingNumber,
                    "3",
                    ShipmentStatus.PACKED,
                    "Bưu cục GHTK",
                    now,
                    events
            );
        }

        try {
            GhtkTrackResponse response = restClient.get()
                    .uri(TRACK_PATH, trackingNumber)
                    .header("Token", endpoint.getToken() != null ? endpoint.getToken() : "")
                    .retrieve()
                    .body(new ParameterizedTypeReference<GhtkTrackResponse>() { });

            if (response == null || !Boolean.TRUE.equals(response.success()) || response.order() == null) {
                throw mapGhtkError(response != null ? response.message() : null);
            }
            Integer statusCode = response.order().status();
            String rawStatus = statusCode != null ? String.valueOf(statusCode) : "1";
            ShipmentStatus mapped = mapGhtkStatus(statusCode);
            String desc = response.order().statusText() != null ? response.order().statusText() : "GHTK status " + rawStatus;
            List<CarrierTrackingEvent> events = List.of(
                    new CarrierTrackingEvent(rawStatus, mapped, desc, null, Instant.now())
            );
            return new CarrierTrackingResult(
                    CARRIER_CODE,
                    trackingNumber,
                    rawStatus,
                    mapped,
                    null,
                    Instant.now(),
                    events
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            throw translateNetworkException(ex);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "GHTK trả về lỗi HTTP " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Lỗi gọi API GHTK: " + ex.getMessage(), ex);
        }
    }

    private ApiException translateNetworkException(ResourceAccessException ex) {
        if (ex.getCause() instanceof SocketTimeoutException) {
            return new ApiException(ErrorCode.SHIPMENT_CARRIER_TIMEOUT, "Hết thời gian chờ phản hồi từ GHTK", ex);
        }
        return new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "Không kết nối được tới GHTK: " + ex.getMessage(), ex);
    }

    private ApiException mapGhtkError(String message) {
        String msg = message != null && !message.isBlank() ? message : "Phản hồi không hợp lệ từ GHTK";
        if (msg.toLowerCase(Locale.ROOT).contains("address")) {
            return new ApiException(ErrorCode.SHIPMENT_ADDRESS_UNSUPPORTED, msg);
        }
        return new ApiException(ErrorCode.SHIPMENT_CARRIER_ERROR, "GHTK từ chối yêu cầu: " + msg);
    }

    @Override
    public ShipmentStatus mapCarrierStatus(String rawStatus) {
        if (rawStatus == null) {
            return ShipmentStatus.PENDING;
        }
        try {
            int code = Integer.parseInt(rawStatus.trim());
            return mapGhtkStatus(code);
        } catch (NumberFormatException ex) {
            return switch (rawStatus.trim().toLowerCase(Locale.ROOT)) {
                case "created" -> ShipmentStatus.CREATED;
                case "picking" -> ShipmentStatus.PICKING;
                case "picked", "picked_up" -> ShipmentStatus.PICKED_UP;
                case "in_transit", "delivering" -> ShipmentStatus.IN_TRANSIT;
                case "delivered" -> ShipmentStatus.DELIVERED;
                case "failed", "cancel" -> ShipmentStatus.FAILED;
                case "returned" -> ShipmentStatus.RETURNED;
                default -> ShipmentStatus.IN_TRANSIT;
            };
        }
    }

    /** Ánh xạ mã trạng thái số của GHTK sang ShipmentStatus nội bộ chuẩn hóa (SRS §3.6.2, KH-S3-2). */
    public static ShipmentStatus mapGhtkStatus(Integer status) {
        if (status == null) {
            return ShipmentStatus.PENDING;
        }
        return switch (status) {
            case -1 -> ShipmentStatus.CANCELLED;
            case 1 -> ShipmentStatus.PENDING;
            case 2, 12 -> ShipmentStatus.PICKING;
            case 3 -> ShipmentStatus.PICKED_UP;
            case 4 -> ShipmentStatus.IN_TRANSIT;
            case 5, 6 -> ShipmentStatus.DELIVERED;
            case 7, 8, 10, 410 -> ShipmentStatus.FAILED;
            case 9, 11, 20, 21 -> ShipmentStatus.RETURNED;
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

    record GhtkFeeResponse(Boolean success, String message, GhtkFeeData fee) {
    }

    record GhtkFeeData(BigDecimal fee, Boolean delivery) {
    }

    record GhtkCreateResponse(Boolean success, String message, GhtkCreateOrder order) {
    }

    record GhtkCreateOrder(
            String label,
            BigDecimal fee,
            @JsonProperty("estimated_deliver_time") String estimatedDeliverTime) {
    }

    record GhtkTrackResponse(Boolean success, String message, GhtkTrackOrder order) {
    }

    record GhtkTrackOrder(
            @JsonProperty("label_id") String labelId,
            Integer status,
            @JsonProperty("status_text") String statusText) {
    }
}
