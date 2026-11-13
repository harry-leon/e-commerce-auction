package com.nexus.fulfillment.shipping.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
 * Contract test xác minh hợp đồng request/response, header và xử lý mã lỗi của GhnCarrierAdapter (KH-S3-1).
 *
 * Bảo vệ:
 * - Đảm bảo header Token, ShopId và schema JSON gửi sang GHN không bị sai lệch khi refactor.
 * - Xác nhận ánh xạ trạng thái GHN -> ShipmentStatus nội bộ và chuyển đổi mã lỗi HTTP/timeout chính xác.
 */
class GhnCarrierAdapterContractTest {

    private MockRestServiceServer server;
    private GhnCarrierAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ghn-sandbox.test");
        server = MockRestServiceServer.bindTo(builder).build();
        FulfillmentProperties.CarrierEndpoint endpoint =
                new FulfillmentProperties.CarrierEndpoint("https://ghn-sandbox.test", "ghn-token-123", "998877");
        adapter = new GhnCarrierAdapter(builder.build(), endpoint, 2);
    }

    @Test
    @DisplayName("GHN Quote Contract: gửi đúng header Token/ShopId, schema body và parse cước phí")
    void quote_contractSuccess() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/fee"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "ghn-token-123"))
                .andExpect(header("ShopId", "998877"))
                .andExpect(jsonPath("$.service_type_id").value(2))
                .andExpect(jsonPath("$.to_district_id").value(1454))
                .andExpect(jsonPath("$.to_ward_code").value("21211"))
                .andExpect(jsonPath("$.weight").value(1200))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"Success\",\"data\":{\"total\":32000,\"service_fee\":32000}}",
                        MediaType.APPLICATION_JSON));

        CarrierQuoteResult result = adapter.quote(new CarrierQuoteRequest(
                orderId, UUID.randomUUID(), "Kho WH_001", "Q1, TP.HCM", 1454, "21211",
                1200, new BigDecimal("80"), "EXPRESS"));

        assertThat(result.carrierCode()).isEqualTo("GHN");
        assertThat(result.serviceType()).isEqualTo("EXPRESS");
        assertThat(result.fee()).isEqualByComparingTo("32000");
        assertThat(result.estimatedDeliveryDays()).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("GHN Create Shipment Contract: gửi đúng schema tạo vận đơn và nhận order_code tracking")
    void createShipment_contractSuccess() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "ghn-token-123"))
                .andExpect(header("ShopId", "998877"))
                .andExpect(jsonPath("$.to_name").value("Nguyen Van A"))
                .andExpect(jsonPath("$.to_phone").value("0901234567"))
                .andExpect(jsonPath("$.client_order_code").value(orderId.toString()))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"Success\",\"data\":{\"order_code\":\"GHN888999\",\"total_fee\":25000,\"expected_delivery_time\":\"2026-11-15T10:00:00Z\"}}",
                        MediaType.APPLICATION_JSON));

        CarrierCreateShipmentResult result = adapter.createShipment(new CarrierCreateShipmentRequest(
                orderId, UUID.randomUUID(), "Kho WH_001", "Nguyen Van A", "0901234567",
                "123 Le Loi, Q1", 1454, "21211", 800, BigDecimal.ZERO, new BigDecimal("50"),
                "STANDARD", "Giao gio hanh chinh"));

        assertThat(result.carrierCode()).isEqualTo("GHN");
        assertThat(result.trackingNumber()).isEqualTo("GHN888999");
        assertThat(result.fee()).isEqualByComparingTo("25000");
        assertThat(result.mappedStatus()).isEqualTo(ShipmentStatus.PENDING);
        server.verify();
    }

    @Test
    @DisplayName("GHN Tracking Contract: truy vấn detail và ánh xạ trạng thái delivering -> IN_TRANSIT")
    void trackShipment_contractSuccess() {
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/detail"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "ghn-token-123"))
                .andExpect(jsonPath("$.order_code").value("GHN888999"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"Success\",\"data\":{\"order_code\":\"GHN888999\",\"status\":\"delivering\",\"log\":[{\"status\":\"picking\",\"updated_date\":\"2026-11-12T08:00:00Z\"},{\"status\":\"delivering\",\"updated_date\":\"2026-11-12T10:00:00Z\"}]}}",
                        MediaType.APPLICATION_JSON));

        CarrierTrackingResult result = adapter.trackShipment("GHN888999");

        assertThat(result.carrierCode()).isEqualTo("GHN");
        assertThat(result.trackingNumber()).isEqualTo("GHN888999");
        assertThat(result.carrierStatus()).isEqualTo("delivering");
        assertThat(result.mappedStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(result.events()).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("GHN Error Contract: mã lỗi nghiệp vụ address không hỗ trợ trả về SHIPMENT_ADDRESS_UNSUPPORTED")
    void quote_unsupportedAddress_mapsError() {
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/fee"))
                .andRespond(withSuccess(
                        "{\"code\":400,\"message\":\"Unsupported delivery address\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.quote(new CarrierQuoteRequest(
                UUID.randomUUID(), UUID.randomUUID(), "WH", "Remote", 9999, "00000",
                500, new BigDecimal("50"), "STANDARD")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_ADDRESS_UNSUPPORTED.name());
    }

    @Test
    @DisplayName("GHN Error Contract: HTTP 500 từ GHN trả về SHIPMENT_CARRIER_ERROR và timeout trả về SHIPMENT_CARRIER_TIMEOUT")
    void ghnServerErrorAndTimeout_mappedCorrectly() {
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/detail"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.trackShipment("GHN1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_ERROR.name());

        server.reset();
        server.expect(requestTo("https://ghn-sandbox.test/shiip/public-api/v2/shipping-order/detail"))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("timeout"));
                });

        assertThatThrownBy(() -> adapter.trackShipment("GHN1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_TIMEOUT.name());
    }
}
