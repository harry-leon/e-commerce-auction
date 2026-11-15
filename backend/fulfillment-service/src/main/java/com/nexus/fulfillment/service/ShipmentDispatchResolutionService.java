package com.nexus.fulfillment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.repository.CarrierRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin gắn vận đơn đã đối soát vào snapshot gốc; không gọi carrier hoặc cho phép gửi lại mutation. */
@Service
public class ShipmentDispatchResolutionService {
    private final JdbcTemplate jdbc;
    private final ShipmentRepository shipments;
    private final WarehouseRepository warehouses;
    private final CarrierRepository carriers;
    private final ShipmentStatusHistoryRepository history;
    private final FulfillmentEventOutboxService outbox;

    public ShipmentDispatchResolutionService(JdbcTemplate jdbc, ShipmentRepository shipments, WarehouseRepository warehouses,
            CarrierRepository carriers, ShipmentStatusHistoryRepository history, FulfillmentEventOutboxService outbox) {
        this.jdbc = jdbc; this.shipments = shipments; this.warehouses = warehouses;
        this.carriers = carriers; this.history = history; this.outbox = outbox;
    }

    /**
     * Khóa key → lifecycle order → attempt → tracking; chờ dispatch đang chạy commit/rollback trước khi quyết định.
     * Audit, shipment/history/outbox và COMMITTED cùng transaction. Replay trả audit cũ, khác nội dung trả 409.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Result resolve(UUID attemptId, UUID actor, UUID key, Command command) {
        if (actor == null || key == null || command == null || invalid(command.trackingNumber(), 100)
                || invalid(command.evidenceReference(), 200) || invalid(command.note(), 500) || command.shippingFee() == null
                || command.shippingFee().signum() < 0 || command.shippingFee().scale() > 2
                || command.shippingFee().precision() - command.shippingFee().scale() > 13) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_DISPATCH_RESOLUTION");
        }
        String tracking = command.trackingNumber().trim();
        lock("dispatch-resolution:" + actor + ":" + key);
        var previous = jdbc.queryForList("SELECT * FROM shipment_dispatch_resolutions WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!attemptId.equals(row.get("attempt_id")) || !tracking.equals(row.get("tracking_number"))
                    || command.shippingFee().compareTo((BigDecimal) row.get("shipping_fee")) != 0
                    || !command.evidenceReference().trim().equals(row.get("evidence_reference")) || !command.note().trim().equals(row.get("note"))) {
                throw error(HttpStatus.CONFLICT, "DISPATCH_RESOLUTION_CONFLICT");
            }
            return new Result((UUID) row.get("id"), (UUID) row.get("shipment_id"));
        }
        var refs = jdbc.queryForList("SELECT order_id FROM shipment_dispatch_attempts WHERE id=?", attemptId);
        if (refs.isEmpty()) { throw error(HttpStatus.NOT_FOUND, "DISPATCH_NOT_FOUND"); }
        UUID orderId = (UUID) refs.getFirst().get("order_id");
        if (jdbc.queryForList("SELECT order_id FROM shipment_dispatch_locks WHERE order_id=? FOR UPDATE", orderId).isEmpty()) {
            throw error(HttpStatus.CONFLICT, "DISPATCH_LIFECYCLE_MISSING");
        }
        var attempt = jdbc.queryForMap("SELECT * FROM shipment_dispatch_attempts WHERE id=? FOR UPDATE", attemptId);
        if (!"UNKNOWN".equals(attempt.get("status")) || (attempt.get("tracking_number") != null
                && !tracking.equals(attempt.get("tracking_number")))) { throw error(HttpStatus.CONFLICT, "DISPATCH_RESOLUTION_CONFLICT"); }
        lock("dispatch-tracking:" + tracking);
        CarrierCreateShipmentRequest snapshot;
        try { snapshot = new ObjectMapper().readValue((String) attempt.get("request_payload"), CarrierCreateShipmentRequest.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw error(HttpStatus.CONFLICT, "DISPATCH_SNAPSHOT_INVALID"); }
        if (!orderId.equals(snapshot.orderId())) { throw error(HttpStatus.CONFLICT, "DISPATCH_SNAPSHOT_INVALID"); }
        var existing = shipments.findByOrderId(orderId);
        Shipment shipment;
        if (existing.isPresent()) {
            shipment = existing.get();
            // Callback guard có thể thất bại sau commit shipment: chỉ liên kết lại bản ghi khớp, không hạ trạng thái hiện tại.
            if (!tracking.equals(shipment.getTrackingNo()) || !attempt.get("carrier_code").equals(shipment.getCarrierCode())
                    || command.shippingFee().compareTo(shipment.getShippingFee()) != 0) { throw error(HttpStatus.CONFLICT, "DISPATCH_RESOLUTION_CONFLICT"); }
        } else {
            if (shipments.findByTrackingNo(tracking).isPresent()) { throw error(HttpStatus.CONFLICT, "TRACKING_ALREADY_ASSIGNED"); }
            shipment = new Shipment();
            shipment.setId(UUID.randomUUID()); shipment.setOrderId(orderId);
            shipment.setWarehouse(warehouses.findById(snapshot.warehouseId()).orElseThrow(() -> error(HttpStatus.CONFLICT, "WAREHOUSE_NOT_FOUND")));
            shipment.setCarrier(carriers.findByCodeIgnoreCase((String) attempt.get("carrier_code"))
                    .orElseThrow(() -> error(HttpStatus.CONFLICT, "CARRIER_NOT_FOUND")));
            shipment.setCarrierCode((String) attempt.get("carrier_code")); shipment.setTrackingNo(tracking);
            shipment.setServiceType(snapshot.serviceType()); shipment.setShippingFee(command.shippingFee());
            shipment.setRecipientName(snapshot.recipientName()); shipment.setRecipientPhone(snapshot.recipientPhone());
            shipment.setShippingAddress(snapshot.shippingAddress()); shipment.setWeightGrams(snapshot.weightGrams());
            shipment.setOrderTotal(snapshot.insuranceValue()); shipment.setStatus(ShipmentStatus.PENDING);
            shipment.setIdempotencyKey("dispatch-resolution:" + attemptId);
            shipment = shipments.saveAndFlush(shipment);
            history.save(new ShipmentStatusHistory(shipment, "MANUAL_CONFIRMED", ShipmentStatus.PENDING, Instant.now()));
            outbox.enqueueShipmentCreated(shipment);
        }
        UUID audit = UUID.randomUUID();
        jdbc.update("INSERT INTO shipment_dispatch_resolutions(id,attempt_id,shipment_id,actor_id,idempotency_key,tracking_number,"
                + "shipping_fee,evidence_reference,note) VALUES (?,?,?,?,?,?,?,?,?)", audit, attemptId, shipment.getId(), actor, key,
                tracking, command.shippingFee(), command.evidenceReference().trim(), command.note().trim());
        jdbc.update("UPDATE shipment_dispatch_attempts SET status='COMMITTED',tracking_number=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", tracking, attemptId);
        return new Result(audit, shipment.getId());
    }

    /** Advisory lock PostgreSQL transaction tự nhả; key namespace riêng không đụng khóa kho hoặc Commerce. */
    private void lock(String key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key); }
    private boolean invalid(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
    /** Không đưa note, snapshot PII hoặc response carrier vào lỗi công khai. */
    private ApiException error(HttpStatus status, String code) { return new ApiException(code, status, "Không thể chốt đối soát dispatch với dữ liệu hiện tại", null); }
    /** Tracking/fee là xác nhận thủ công từ bằng chứng carrier, không phải kết quả query tự động. */
    public record Command(String trackingNumber, BigDecimal shippingFee, String evidenceReference, String note) { }
    /** Chỉ trả định danh audit/shipment, không trả snapshot PII hoặc xác nhận đã giao hàng. */
    public record Result(UUID auditId, UUID shipmentId) { }
}
