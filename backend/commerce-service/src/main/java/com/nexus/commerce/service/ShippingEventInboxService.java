package com.nexus.commerce.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inbox shipment xác thực từ Fulfillment; Order/history/notification snapshot commit nguyên tử, không gọi kho/provider. */
@Service
@RequiredArgsConstructor
public class ShippingEventInboxService {
    private final JdbcTemplate jdbc;

    /**
     * Khóa eventId rồi Order: cùng event/payload ACK lại, đổi payload conflict; không nhận buyer/giá từ Fulfillment.
     * Delivered đến trước handover trả 409 để transport retry sau, không bypass máy trạng thái hoặc chốt thanh toán.
     */
    @Transactional
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_EVENTS')")
    public void receive(Event event) {
        String payload;
        try { payload = new ObjectMapper().findAndRegisterModules().writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw conflict(); }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "shipping-event:" + event.eventId());
        var previous = jdbc.queryForList("SELECT payload=?::jsonb AS matches FROM shipping_event_inbox WHERE event_id=?", payload, event.eventId());
        if (!previous.isEmpty()) {
            if (!Boolean.TRUE.equals(previous.getFirst().get("matches"))) { throw conflict(); }
            return;
        }
        var rows = jdbc.queryForList("SELECT * FROM orders WHERE id=? FOR UPDATE", event.orderId());
        if (rows.isEmpty()) { throw new ApiException("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng", null); }
        var order = rows.getFirst();
        if (!validStatus(event)) { throw conflict(); }
        // Chỉ một shipment cho một order; không để callback shipment khác hoặc tracking đổi chiếm liên kết cũ.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "shipping-binding:" + event.shipmentId());
        var other = jdbc.queryForList("SELECT order_id FROM shipping_order_bindings WHERE shipment_id=?", event.shipmentId());
        if (!other.isEmpty() && !event.orderId().equals(other.getFirst().get("order_id"))) { throw conflict(); }
        var bindings = jdbc.queryForList("SELECT * FROM shipping_order_bindings WHERE order_id=?", event.orderId());
        if (bindings.isEmpty()) {
            jdbc.update("INSERT INTO shipping_order_bindings(order_id,shipment_id,carrier,tracking_number) VALUES (?,?,?,?)",
                    event.orderId(), event.shipmentId(), event.carrier(), event.trackingNumber());
        } else {
            var binding = bindings.getFirst();
            if (!event.shipmentId().equals(binding.get("shipment_id")) || !event.carrier().equals(binding.get("carrier"))
                    || !event.trackingNumber().equals(binding.get("tracking_number"))) { throw conflict(); }
        }
        String current = (String) order.get("status");
        String target = null;
        if (event.eventType() == Type.ShipmentPickedUp || event.eventType() == Type.ShipmentInTransit) {
            if ("PROCESSING".equals(current)) { target = "SHIPPED"; }
            else if (!java.util.Set.of("SHIPPED", "DELIVERED", "COMPLETED").contains(current)) { throw conflict(); }
        } else if (event.eventType() == Type.ShipmentDelivered) {
            if ("SHIPPED".equals(current)) { target = "DELIVERED"; }
            else if (!java.util.Set.of("DELIVERED", "COMPLETED").contains(current)) { throw conflict(); }
        } else if (event.eventType() == Type.ShipmentFailed || event.eventType() == Type.ShipmentReturned) {
            // Chỉ đánh dấu cần đối soát: giao thất bại/trả hàng không phải bằng chứng refund hoặc đã nhập lại kho.
            jdbc.update("UPDATE shipping_order_bindings SET review_event_type=?,updated_at=now() WHERE order_id=?",
                    event.eventType().name(), event.orderId());
        }
        if (target != null) {
            if (!Boolean.TRUE.equals(order.get("price_finalized")) || "REVIEW".equals(order.get("fulfillment_status"))) { throw conflict(); }
            jdbc.update("UPDATE orders SET status=? WHERE id=?", target, event.orderId());
            jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), event.orderId(), current, target, "[FULFILLMENT] " + event.eventType() + " event=" + event.eventId());
            enqueueNotification(event, order, "SHIPPED".equals(target) ? "ORDER_SHIPPED" : "ORDER_DELIVERED");
        }
        jdbc.update("INSERT INTO shipping_event_inbox(event_id,order_id,payload) VALUES (?,?,?::jsonb)", event.eventId(), event.orderId(), payload);
    }

    /** Thông báo không chứa tracking/PII; giữ eventId ổn định và buyer/tổng chính thức từ Order đã khóa. */
    private void enqueueNotification(Event event, Map<String, Object> order, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shipping_notification_outbox(id,order_id,event_type,payload) VALUES (?,?,?,"
                + "jsonb_build_object('id',?::text,'userId',?::text,'orderId',?::text,'orderNo',?::text,'type',?::text,'amount',?::numeric,'occurredAt',?::text)) "
                + "ON CONFLICT(order_id,event_type) DO NOTHING", id, event.orderId(), type, id, order.get("buyer_id"), event.orderId(),
                order.get("order_no"), type, order.get("total"), event.occurredAt().toString());
    }
    /** Đối chiếu cả loại event và status gốc, không nhận Delivered mang trạng thái chưa giao. */
    private boolean validStatus(Event event) {
        return switch (event.eventType()) {
            case ShipmentCreated -> java.util.Set.of("PENDING", "CREATED", "PICKING", "PACKED").contains(event.status());
            case ShipmentPickedUp -> java.util.Set.of("PICKED_UP", "SHIPPED").contains(event.status());
            case ShipmentInTransit -> "IN_TRANSIT".equals(event.status());
            case ShipmentDelivered -> "DELIVERED".equals(event.status());
            case ShipmentFailed -> "FAILED".equals(event.status());
            case ShipmentReturned -> "RETURNED".equals(event.status());
        };
    }
    private ApiException conflict() { return new ApiException("SHIPPING_EVENT_CONFLICT", HttpStatus.CONFLICT, "Sự kiện shipment cần đối chiếu trạng thái hoặc định danh", null); }
    /** Tên type giữ contract Fulfillment outbox hiện có; không nhận type tùy ý để điều khiển Order. */
    public enum Type { ShipmentCreated, ShipmentPickedUp, ShipmentInTransit, ShipmentDelivered, ShipmentFailed, ShipmentReturned }
    /** Bỏ qua metadata kho/phí/location chưa dùng; producer không được chỉ định buyer hoặc giá thông báo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(@NotNull UUID eventId, @NotNull Type eventType, @NotNull Instant occurredAt, @NotNull UUID shipmentId,
            @NotNull UUID orderId, @NotBlank @Size(max = 30) String carrier, @NotBlank @Size(max = 100) String trackingNumber,
            @NotBlank String status) { }
}
