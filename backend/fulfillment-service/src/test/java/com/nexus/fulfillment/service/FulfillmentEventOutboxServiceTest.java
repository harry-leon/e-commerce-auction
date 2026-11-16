package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fulfillment.entity.FulfillmentOutboxEvent;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.repository.FulfillmentOutboxEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Bảo vệ envelope sự kiện shipment ở phía producer: eventId/type/version/aggregate/correlation phải có đủ
 * để consumer Commerce kiểm tra chéo và từ chối đúng poison event. Đây là test unit, không cần PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class FulfillmentEventOutboxServiceTest {
    @Mock private FulfillmentOutboxEventRepository repository;

    /** Sự kiện shipment phát ra phải mang envelope đầy đủ và aggregate trỏ đúng shipment. */
    @Test
    void shipmentPayloadCarriesCompleteEnvelope() throws Exception {
        var service = new FulfillmentEventOutboxService(repository, new ObjectMapper());
        Shipment shipment = draftShipment();
        service.enqueueShipmentPickedUp(shipment);

        var captor = ArgumentCaptor.forClass(FulfillmentOutboxEvent.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        var payload = new ObjectMapper().readTree(saved.getPayload());

        assertEquals("SHIPMENT", saved.getAggregateType());
        assertEquals(shipment.getId(), saved.getAggregateId());
        assertEquals("ShipmentPickedUp", saved.getEventType());
        assertEquals("ShipmentPickedUp", payload.get("eventType").asText());
        assertEquals(1, payload.get("schemaVersion").asInt());
        assertEquals("SHIPMENT", payload.get("aggregateType").asText());
        assertEquals(shipment.getId().toString(), payload.get("aggregateId").asText());
        assertEquals(saved.getId().toString(), payload.get("correlationId").asText());
        assertEquals(shipment.getId().toString(), payload.get("shipmentId").asText());
        assertEquals(shipment.getOrderId().toString(), payload.get("orderId").asText());
        assertNotNull(payload.get("occurredAt"));
    }

    /** Trạng thái null không được làm hỏng payload; consumer dùng status để đối chiếu loại event. */
    @Test
    void shipmentWithoutStatusStillProducesValidEnvelope() throws Exception {
        var service = new FulfillmentEventOutboxService(repository, new ObjectMapper());
        Shipment shipment = draftShipment();
        shipment.setStatus(null);
        service.enqueueShipmentDelivered(shipment);

        var captor = ArgumentCaptor.forClass(FulfillmentOutboxEvent.class);
        verify(repository).save(captor.capture());
        var payload = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("ShipmentDelivered", payload.get("eventType").asText());
        assertEquals(1, payload.get("schemaVersion").asInt());
        assertEquals(true, payload.has("status"));
    }

    /** Shipment fixture tối thiểu; không cần warehouse vì payload chấp nhận null. */
    private Shipment draftShipment() {
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(UUID.randomUUID());
        shipment.setCarrierCode("GHN");
        shipment.setTrackingNo("TRACK-ENVELOPE");
        return shipment;
    }
}
