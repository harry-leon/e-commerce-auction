package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.CarrierInfoResponse;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteOptionResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.service.ShippingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử tầng Web cho ShippingController và ShipmentController (KH-S3-1, SRS §2.7 & §3.6.2).
 *
 * Bảo vệ:
 * - Quyền SHIPPING.QUOTE, SHIPPING.CREATE_SHIPMENT, SHIPPING.VIEW_SHIPMENT được kiểm tra đúng (401/403/200/201).
 * - Cấu trúc envelope ApiResponse chuẩn cho các API quote, create, tracking và carriers.
 */
@WebMvcTest({ShippingController.class, ShipmentController.class})
// Policy thật mặc định tắt giữ hành vi legacy; ca worker bật được bảo vệ ở ClaimedDispatchIngressControllerTest.
@Import({SecurityConfig.class, GlobalExceptionHandler.class, com.nexus.fulfillment.service.ClaimedDispatchIngressPolicy.class})
@ActiveProfiles("test")
class ShippingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShippingService shippingService;

    @MockitoBean
    private com.nexus.fulfillment.shipping.adapter.resilience.CarrierResilienceManager resilienceManager;

    @MockitoBean
    private com.nexus.fulfillment.service.WarehouseFulfillmentService warehouseFulfillmentService;

    @Test
    @DisplayName("POST /api/v1/shipping/quotes: yêu cầu SHIPPING.QUOTE, trả 401 khi thiếu token và 200 khi hợp lệ")
    void quoteShipping_securityAndEnvelope() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        ShippingQuoteRequest request = new ShippingQuoteRequest(
                orderId, warehouseId, null, "STANDARD", "Q1, TP.HCM", 1454, "21211", 500, new BigDecimal("120"));

        mockMvc.perform(post("/api/v1/shipping/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        ShippingQuoteResponse response = new ShippingQuoteResponse(
                orderId,
                warehouseId,
                "GHN",
                new BigDecimal("100"),
                true,
                List.of(new ShippingQuoteOptionResponse(
                        "GHN", "STANDARD", BigDecimal.ZERO, new BigDecimal("22.00"),
                        true, 2, Instant.now()))
        );
        when(shippingService.quoteShipping(any(ShippingQuoteRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/shipping/quotes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SHIPPING.QUOTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.freeShippingApplied").value(true))
                .andExpect(jsonPath("$.data.options[0].carrier").value("GHN"));
    }

    @Test
    @DisplayName("POST /api/v1/shipments: 403 khi thiếu quyền SHIPPING.CREATE_SHIPMENT, 201 khi có quyền")
    void createShipment_privilegeEnforcement() throws Exception {
        UUID orderId = UUID.randomUUID();
        CreateShipmentRequest request = new CreateShipmentRequest(
                orderId, null, "GHN", "STANDARD", "Nguyen Van A", "0901234567",
                "123 Le Loi", 1454, "21211", 500, new BigDecimal("80"), BigDecimal.ZERO, null);

        mockMvc.perform(post("/api/v1/shipments")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SHIPPING.VIEW_SHIPMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        ShipmentResponse created = new ShipmentResponse(
                UUID.randomUUID(), orderId, UUID.randomUUID(), "WH_001", "GHN", "STANDARD",
                new BigDecimal("22.00"), false, "GHN-12345678", ShipmentStatus.PENDING,
                2, Instant.now(), "Nguyen Van A", "0901234567", "123 Le Loi",
                Instant.now(), Instant.now());
        when(shippingService.createShipment(any(CreateShipmentRequest.class), eq("idem-key-1"))).thenReturn(created);

        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "idem-key-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SHIPPING.CREATE_SHIPMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trackingNumber").value("GHN-12345678"))
                .andExpect(jsonPath("$.data.carrier").value("GHN"));
    }

    @Test
    @DisplayName("GET /api/v1/shipments/{id}/tracking & /api/v1/shipping/carriers: kiểm tra quyền SHIPPING.VIEW_SHIPMENT")
    void trackingAndCarriers_success() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ShipmentTrackingResponse tracking = new ShipmentTrackingResponse(
                shipmentId, orderId, "GHN", "GHN-12345678", ShipmentStatus.IN_TRANSIT,
                "delivering", "Kho Q1", Instant.now(),
                List.of(new ShipmentTrackingResponse.TrackingHistoryItem(
                        "delivering", ShipmentStatus.IN_TRANSIT, Instant.now()))
        );
        when(shippingService.trackShipment(shipmentId)).thenReturn(tracking);
        when(shippingService.listCarriers()).thenReturn(List.of(
                new CarrierInfoResponse("GHN", "Giao Hang Nhanh", true, true),
                new CarrierInfoResponse("GHTK", "Giao Hang Tiet Kiem", true, false)
        ));

        mockMvc.perform(get("/api/v1/shipments/{id}/tracking", shipmentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SHIPPING.VIEW_SHIPMENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.history[0].internalStatus").value("IN_TRANSIT"));

        mockMvc.perform(get("/api/v1/shipping/carriers").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("GHN"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/shipments/callbacks/{carrierCode}: tiếp nhận webhook công khai và trả về vận đơn")
    void callback_publicEndpointSuccess() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        com.nexus.fulfillment.dto.request.CarrierWebhookRequest request =
                new com.nexus.fulfillment.dto.request.CarrierWebhookRequest(
                        "evt-12345", "GHN-12345678", "delivering", "GHN", "Ha Noi", "Dang trung chuyen", Instant.now()
                );

        ShipmentResponse mockResponse = new ShipmentResponse(
                shipmentId, orderId, warehouseId, "WH_001", "GHN", "STANDARD",
                new BigDecimal("22.00"), false, "GHN-12345678", ShipmentStatus.IN_TRANSIT,
                3, Instant.now().plusSeconds(86400), "Nguyen Van A", "0901234567",
                "123 Le Loi", Instant.now(), Instant.now()
        );

        when(shippingService.processCarrierWebhook(eq("GHN"), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/shipments/callbacks/GHN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.trackingNumber").value("GHN-12345678"));
    }

    @Test
    @DisplayName("Pick, Pack, Ship APIs: yêu cầu quyền FULFILLMENT tương ứng và cập nhật trạng thái kho")
    void pickPackShip_success() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        ShipmentResponse pickingResponse = new ShipmentResponse(
                shipmentId, orderId, warehouseId, "WH_001", "GHN", "STANDARD",
                new BigDecimal("22.00"), false, "GHN-12345678", ShipmentStatus.PICKING,
                3, null, "Nguyen Van A", "0901234567", "123 Le Loi", Instant.now(), Instant.now()
        );
        ShipmentResponse packedResponse = new ShipmentResponse(
                shipmentId, orderId, warehouseId, "WH_001", "GHN", "STANDARD",
                new BigDecimal("22.00"), false, "GHN-12345678", ShipmentStatus.PACKED,
                3, null, "Nguyen Van A", "0901234567", "123 Le Loi", Instant.now(), Instant.now()
        );
        ShipmentResponse shippedResponse = new ShipmentResponse(
                shipmentId, orderId, warehouseId, "WH_001", "GHN", "STANDARD",
                new BigDecimal("22.00"), false, "GHN-12345678", ShipmentStatus.SHIPPED,
                3, null, "Nguyen Van A", "0901234567", "123 Le Loi", Instant.now(), Instant.now()
        );

        when(warehouseFulfillmentService.pick(shipmentId)).thenReturn(pickingResponse);
        when(warehouseFulfillmentService.pack(shipmentId)).thenReturn(packedResponse);
        when(warehouseFulfillmentService.ship(shipmentId)).thenReturn(shippedResponse);

        mockMvc.perform(post("/api/v1/shipments/{id}/pick", shipmentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("FULFILLMENT.PICK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKING"));

        mockMvc.perform(post("/api/v1/shipments/{id}/pack", shipmentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("FULFILLMENT.PACK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PACKED"));

        mockMvc.perform(post("/api/v1/shipments/{id}/ship", shipmentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("FULFILLMENT.SHIP"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("GET /api/v1/shipping/resilience: trả về thông tin trạng thái circuit breaker của các carrier")
    void resilience_status() throws Exception {
        when(resilienceManager.getResilienceStatuses()).thenReturn(List.of(
                new com.nexus.fulfillment.dto.response.CarrierResilienceStatus(
                        "GHN",
                        com.nexus.fulfillment.shipping.adapter.resilience.CarrierCircuitBreakerState.CLOSED,
                        0, 10, 10, 3, 3, 3000L, Instant.now()
                )
        ));

        mockMvc.perform(get("/api/v1/shipping/resilience")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SHIPPING.RESILIENCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].carrierCode").value("GHN"))
                .andExpect(jsonPath("$.data[0].state").value("CLOSED"))
                .andExpect(jsonPath("$.data[0].timeoutMs").value(3000));
    }
}
