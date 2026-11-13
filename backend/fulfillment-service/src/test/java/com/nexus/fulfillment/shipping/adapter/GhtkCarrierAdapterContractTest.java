package com.nexus.fulfillment.shipping.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Contract test xác minh hợp đồng request/response, header và mã lỗi của GhtkCarrierAdapter (KH-S3-1).
 */
class GhtkCarrierAdapterContractTest {

    private MockRestServiceServer server;
    private GhtkCarrierAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ghtk-sandbox.test");
        server = MockRestServiceServer.bindTo(builder).build();
        FulfillmentProperties.CarrierEndpoint endpoint =
                new FulfillmentProperties.CarrierEndpoint("https://ghtk-sandbox.test", "ghtk-token-456", "");
        adapter = new GhtkCarrierAdapter(builder.build(), endpoint, 3);
    }

    @Test
    @DisplayName("GHTK Quote Contract: gửi GET với query params và header Token, parse fee")
    void quote_contractSuccess() {
        server.expect(requestTo(containsString("https://ghtk-sandbox.test/services/shipment/fee")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "ghtk-token-456"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"message\":\"OK\",\"fee\":{\"fee\":19500,\"delivery\":true}}",
                        MediaType.APPLICATION_JSON));

        CarrierQuoteResult result = adapter.quote(new CarrierQuoteRequest(
                UUID.randomUUID(), UUID.randomUUID(), "TP.HCM", "Ha Noi", null, null,
                900, new BigDecimal("60"), "STANDARD"));

        assertThat(result.carrierCode()).isEqualTo("GHTK");
        assertThat(result.fee()).isEqualByComparingTo("19500");
        assertThat(result.estimatedDeliveryDays()).isEqualTo(3);
        server.verify();
    }

    @Test
    @DisplayName("GHTK Create Shipment Contract: gửi POST schema order/products và nhận label tracking")
    void createShipment_contractSuccess() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("https://ghtk-sandbox.test/services/shipment/order"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "ghtk-token-456"))
                .andExpect(jsonPath("$.order.id").value(orderId.toString()))
                .andExpect(jsonPath("$.order.name").value("Tran Thi B"))
                .andExpect(jsonPath("$.products[0].quantity").value(1))
                .andRespond(withSuccess(
                        "{\"success\":true,\"message\":\"OK\",\"order\":{\"label\":\"S1.A1.123456\",\"fee\":21000,\"estimated_deliver_time\":\"2026-11-16\"}}",
                        MediaType.APPLICATION_JSON));

        CarrierCreateShipmentResult result = adapter.createShipment(new CarrierCreateShipmentRequest(
                orderId, UUID.randomUUID(), "Kho WH_001", "Tran Thi B", "0912345678",
                "45 Nguyen Hue", null, null, 1000, BigDecimal.ZERO, new BigDecimal("90"),
                "STANDARD", "GHTK test"));

        assertThat(result.carrierCode()).isEqualTo("GHTK");
        assertThat(result.trackingNumber()).isEqualTo("S1.A1.123456");
        assertThat(result.fee()).isEqualByComparingTo("21000");
        assertThat(result.mappedStatus()).isEqualTo(ShipmentStatus.PENDING);
        server.verify();
    }

    @Test
    @DisplayName("GHTK Tracking Contract: truy vấn v2/{label} và ánh xạ status=5 -> DELIVERED")
    void trackShipment_contractSuccess() {
        server.expect(requestTo("https://ghtk-sandbox.test/services/shipment/v2/S1.A1.123456"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "ghtk-token-456"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"order\":{\"label_id\":\"S1.A1.123456\",\"status\":5,\"status_text\":\"Da giao hang\"}}",
                        MediaType.APPLICATION_JSON));

        CarrierTrackingResult result = adapter.trackShipment("S1.A1.123456");

        assertThat(result.carrierCode()).isEqualTo("GHTK");
        assertThat(result.carrierStatus()).isEqualTo("5");
        assertThat(result.mappedStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("GHTK Error Contract: delivery=false -> SHIPMENT_ADDRESS_UNSUPPORTED, 401 -> SHIPMENT_CARRIER_ERROR, timeout -> SHIPMENT_CARRIER_TIMEOUT")
    void ghtkErrors_mappedCorrectly() {
        server.expect(requestTo(containsString("https://ghtk-sandbox.test/services/shipment/fee")))
                .andRespond(withSuccess(
                        "{\"success\":true,\"fee\":{\"fee\":0,\"delivery\":false}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.quote(new CarrierQuoteRequest(
                UUID.randomUUID(), UUID.randomUUID(), "HCM", "Island", null, null,
                500, new BigDecimal("50"), "STANDARD")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_ADDRESS_UNSUPPORTED.name());

        server.reset();
        server.expect(requestTo("https://ghtk-sandbox.test/services/shipment/v2/S1"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> adapter.trackShipment("S1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_ERROR.name());

        server.reset();
        server.expect(requestTo("https://ghtk-sandbox.test/services/shipment/v2/S1"))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("timeout"));
                });

        assertThatThrownBy(() -> adapter.trackShipment("S1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_TIMEOUT.name());
    }
}
