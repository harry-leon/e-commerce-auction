package com.nexus.fulfillment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fulfillment.entity.FulfillmentOutboxEvent;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.repository.FulfillmentOutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch vụ ghi sự kiện Transactional Outbox khi Fulfillment hoàn tất các thao tác nghiệp vụ kho và giao vận (SRS §2.8, KH-S3-2).
 *
 * Chức năng:
 * - Đảm bảo tính nhất quán (Atomicity): các sự kiện miền ShipmentCreated, ShipmentPickedUp, ShipmentInTransit,
 *   ShipmentDelivered, ShipmentFailed, ShipmentReturned và InventoryInboundCompleted được ghi vào bảng outbox
 *   trong cùng transaction với thay đổi dữ liệu chính.
 * - Worker Shipping giao event shipment qua HTTP tới Commerce; các loại event khác không do worker đó xử lý.
 * - Bản ghi outbox không chứng minh đã giao thành công hoặc đã có Kafka/Debezium; transport phải ACK/retry riêng.
 */
@Service
public class FulfillmentEventOutboxService {

    public static final String EVENT_INVENTORY_INBOUND_COMPLETED = "InventoryInboundCompleted";
    public static final String EVENT_SHIPMENT_CREATED = "ShipmentCreated";
    public static final String EVENT_SHIPMENT_PICKED_UP = "ShipmentPickedUp";
    public static final String EVENT_SHIPMENT_IN_TRANSIT = "ShipmentInTransit";
    public static final String EVENT_SHIPMENT_DELIVERED = "ShipmentDelivered";
    public static final String EVENT_SHIPMENT_FAILED = "ShipmentFailed";
    public static final String EVENT_SHIPMENT_RETURNED = "ShipmentReturned";

    private final FulfillmentOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public FulfillmentEventOutboxService(
            FulfillmentOutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /** Ghi sự kiện nhập kho hoàn tất (kèm sellerSkuId và skuCode vừa cấp/tái sử dụng) trong cùng transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInboundCompleted(
            SellerSku sellerSku,
            Inventory inventory,
            int quantityAdded,
            boolean firstInbound) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("eventType", EVENT_INVENTORY_INBOUND_COMPLETED);
        body.put("occurredAt", now.toString());
        body.put("sellerSkuId", sellerSku.getId());
        body.put("sellerId", sellerSku.getSellerId());
        body.put("skuId", sellerSku.getSkuId());
        body.put("skuCode", sellerSku.getSkuCode());
        body.put("firstInbound", firstInbound);
        body.put("warehouseId", inventory.getWarehouse().getId());
        body.put("warehouseCode", inventory.getWarehouse().getCode());
        body.put("quantityAdded", quantityAdded);
        body.put("totalQty", inventory.getTotalQty());
        body.put("availableQty", inventory.getAvailableQty());

        saveOutboxEvent(eventId, "SELLER_SKU", sellerSku.getId(), EVENT_INVENTORY_INBOUND_COMPLETED, body, now);
    }

    /** Ghi sự kiện ShipmentCreated (SRS §2.8) vào bảng outbox trong cùng transaction tạo vận đơn. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentCreated(Shipment shipment) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_CREATED, shipment, now);
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_CREATED, body, now);
    }

    /** Ghi sự kiện ShipmentPickedUp khi kiện hàng được lấy từ kho bàn giao carrier (SRS §2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentPickedUp(Shipment shipment) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_PICKED_UP, shipment, now);
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_PICKED_UP, body, now);
    }

    /** Ghi sự kiện ShipmentInTransit khi kiện hàng đang trung chuyển (SRS §2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentInTransit(Shipment shipment, String location) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_IN_TRANSIT, shipment, now);
        if (location != null) {
            body.put("currentLocation", location);
        }
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_IN_TRANSIT, body, now);
    }

    /** Ghi sự kiện ShipmentDelivered khi đơn hàng giao thành công (SRS §2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentDelivered(Shipment shipment) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_DELIVERED, shipment, now);
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_DELIVERED, body, now);
    }

    /** Ghi sự kiện ShipmentFailed khi giao hàng thất bại hoặc gặp sự cố (SRS §2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentFailed(Shipment shipment, String reason) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_FAILED, shipment, now);
        body.put("reason", reason != null ? reason : "Unknown failure");
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_FAILED, body, now);
    }

    /** Ghi sự kiện ShipmentReturned khi kiện hàng bị chuyển trả về kho xuất (SRS §2.8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueShipmentReturned(Shipment shipment, String reason) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = buildShipmentBasePayload(eventId, EVENT_SHIPMENT_RETURNED, shipment, now);
        body.put("reason", reason != null ? reason : "Shipment returned");
        saveOutboxEvent(eventId, "SHIPMENT", shipment.getId(), EVENT_SHIPMENT_RETURNED, body, now);
    }

    /**
     * Dựng envelope chung cho sự kiện shipment: định danh, phiên bản schema, aggregate và correlation.
     *
     * <p>Quy tắc: {@code aggregateId} bằng shipmentId để consumer kiểm tra chéo với cột aggregate_id của outbox.
     * {@code correlationId} giữ chuỗi nhân quả; sự kiện phát sinh trực tiếp dùng chính eventId làm gốc.
     * {@code schemaVersion} cố định 1 cho contract hiện tại; consumer từ chối phiên bản lạ thay vì đoán trường thiếu.
     */
    private Map<String, Object> buildShipmentBasePayload(UUID eventId, String eventType, Shipment shipment, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("eventType", eventType);
        body.put("schemaVersion", 1);
        body.put("aggregateType", "SHIPMENT");
        body.put("aggregateId", shipment.getId());
        body.put("correlationId", eventId);
        body.put("occurredAt", now.toString());
        body.put("shipmentId", shipment.getId());
        body.put("orderId", shipment.getOrderId());
        body.put("warehouseId", shipment.getWarehouse() != null ? shipment.getWarehouse().getId() : null);
        body.put("carrier", shipment.getCarrierCode());
        body.put("serviceType", shipment.getServiceType());
        body.put("shippingFee", shipment.getShippingFee());
        body.put("trackingNumber", shipment.getTrackingNo());
        body.put("status", shipment.getStatus() != null ? shipment.getStatus().name() : null);
        return body;
    }

    private void saveOutboxEvent(UUID eventId, String aggregateType, UUID aggregateId, String eventType, Map<String, Object> body, Instant now) {
        FulfillmentOutboxEvent entry = new FulfillmentOutboxEvent();
        entry.setId(eventId);
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setEventType(eventType);
        entry.setPayload(serialize(body));
        entry.setDone(false);
        entry.setAttempts(0);
        entry.setNextAttemptAt(now);
        outboxRepository.save(entry);
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể tuần tự hóa payload outbox", ex);
        }
    }
}
