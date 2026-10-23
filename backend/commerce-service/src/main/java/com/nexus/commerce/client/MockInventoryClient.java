package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.ReservationItem;
import com.nexus.commerce.client.dto.ReservationResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Triển khai mặc định / Stub cho InventoryClient.
 *
 * <p>Chức năng: Cung cấp hành vi giữ kho giả lập an toàn phục vụ môi trường Dev và Test
 * trong khi fulfillment-service đang hoàn thiện API.
 * Sẽ tự động được thay thế bởi Feign Client thật khi có bean "inventoryFeignClient".
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "inventoryFeignClient")
public class MockInventoryClient implements InventoryClient {

    @Override
    public ReservationResponse reserveStock(UUID orderId, List<ReservationItem> items) {
        log.info("[MockInventoryClient] Giữ kho thành công cho orderId={}, số mặt hàng={}",
                orderId, items != null ? items.size() : 0);
        return new ReservationResponse(
                UUID.randomUUID(),
                orderId,
                "PENDING",
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
    }

    @Override
    public void releaseReservation(UUID orderId) {
        log.info("[MockInventoryClient] Đã giải phóng giữ tồn kho cho orderId={}", orderId);
    }
}
