package com.nexus.fulfillment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fulfillment.entity.FulfillmentOutboxEvent;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.repository.FulfillmentOutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch vụ ghi sự kiện Transactional Outbox khi Fulfillment hoàn tất nhập kho (InventoryInboundCompleted).
 * Đảm bảo sự kiện chỉ được cam kết khi bản ghi seller_skus, inventories và inventory_ledger cùng thành công.
 */
@Service
public class FulfillmentEventOutboxService {

    public static final String EVENT_INVENTORY_INBOUND_COMPLETED = "InventoryInboundCompleted";

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

        FulfillmentOutboxEvent entry = new FulfillmentOutboxEvent();
        entry.setId(eventId);
        entry.setAggregateType("SELLER_SKU");
        entry.setAggregateId(sellerSku.getId());
        entry.setEventType(EVENT_INVENTORY_INBOUND_COMPLETED);
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
            throw new IllegalStateException("Không thể tuần tự hóa payload InventoryInboundCompleted", ex);
        }
    }
}
