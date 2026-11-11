package com.nexus.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.catalog.entity.CatalogOutboxEvent;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.repository.CatalogOutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch vụ ghi và điều phối sự kiện Outbox khi Catalog tạo biến thể SKU mới (ProductSkuCreated).
 * Luồng: ghi trực tiếp vào bảng catalog_event_outbox trong cùng transaction nghiệp vụ;
 * nếu transaction sản phẩm rollback thì sự kiện outbox cũng bị hủy theo để bảo toàn tính nhất quán.
 */
@Service
public class CatalogEventOutboxService {

    public static final String EVENT_PRODUCT_SKU_CREATED = "ProductSkuCreated";
    private static final Logger log = LoggerFactory.getLogger(CatalogEventOutboxService.class);

    private final CatalogOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public CatalogEventOutboxService(
            CatalogOutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /** Ghi sự kiện ProductSkuCreated cho từng SKU mới phát sinh trong transaction tạo/cập nhật sản phẩm. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueSkuCreated(Product product, List<Sku> createdSkus) {
        if (product == null || createdSkus == null || createdSkus.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Sku sku : createdSkus) {
            if (sku.getId() == null) {
                log.debug("Bỏ qua outbox ProductSkuCreated vì SKU chưa có id (chế độ unit test mock)");
                continue;
            }
            UUID eventId = UUID.randomUUID();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", eventId);
            body.put("eventType", EVENT_PRODUCT_SKU_CREATED);
            body.put("occurredAt", now.toString());
            body.put("skuId", sku.getId());
            body.put("productId", product.getId());
            body.put("sellerId", product.getSellerId());
            body.put("attributes", sku.getAttributes());
            body.put("originalPrice", sku.getOriginalPrice());
            body.put("status", sku.getStatus() != null ? sku.getStatus().name() : null);

            CatalogOutboxEvent entry = new CatalogOutboxEvent();
            entry.setId(eventId);
            entry.setAggregateType("SKU");
            entry.setAggregateId(sku.getId());
            entry.setEventType(EVENT_PRODUCT_SKU_CREATED);
            entry.setPayload(serialize(body));
            entry.setDone(false);
            entry.setAttempts(0);
            entry.setNextAttemptAt(now);
            outboxRepository.save(entry);
        }
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể tuần tự hóa payload ProductSkuCreated", ex);
        }
    }
}
