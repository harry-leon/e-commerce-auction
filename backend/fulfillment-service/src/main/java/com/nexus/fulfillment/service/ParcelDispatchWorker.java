package com.nexus.fulfillment.service;

import com.nexus.fulfillment.client.DispatchCommerceClient;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiện đã ghi → job bền vững → handoff/claim/preflight → dấu DISPATCHING commit → ShippingService có guard.
 * Không giữ transaction DB trong HTTP. Lease/fencing chặn worker cũ; DISPATCHING không tự retry mutation.
 * Mặc định tắt; COMMITTED chỉ khi Shipment đúng Order/key/tracking đã lưu, không phải đã giao hàng.
 */
@Component
public class ParcelDispatchWorker {
    private final JdbcTemplate jdbc;
    private final DispatchCommerceClient commerce;
    private final ShippingService shipping;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ParcelDispatchWorker(JdbcTemplate jdbc, DispatchCommerceClient commerce, ShippingService shipping,
            PlatformTransactionManager manager, @Value("${app.shipping.dispatch-worker-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.commerce = commerce; this.shipping = shipping;
        tx = new TransactionTemplate(manager); this.enabled = enabled;
    }

    /** Mỗi tick tối đa 10 job; cổng tắt/thiếu OAuth không chạm DB, không tự bật transport/provider. */
    @Scheduled(fixedDelayString = "${app.shipping.dispatch-worker-delay-ms:5000}", scheduler = "shipmentDispatchScheduler")
    public void run() {
        if (!enabled || !commerce.configured()) { return; }
        // Claim lease hết hạn chỉ cho retry API idempotent. Dấu có thể đã gọi carrier phải giữ REVIEW, không gửi lại.
        jdbc.update("UPDATE shipment_dispatch_jobs SET state=CASE WHEN attempts>=8 THEN 'REQUIRES_REVIEW' ELSE 'WAITING' END,lease_token=NULL,lease_expires_at=NULL,"
                + "last_error_type='CLAIM_LEASE_EXPIRED',next_attempt_at=now(),updated_at=now() WHERE state='CLAIMING' AND lease_expires_at<now()");
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='REQUIRES_REVIEW',last_error_type='DISPATCH_RESULT_UNKNOWN',updated_at=now() "
                + "WHERE state='DISPATCHING' AND lease_expires_at<now()");
        jdbc.update("""
                INSERT INTO shipment_dispatch_jobs(id,parcel_id,order_id)
                SELECT gen_random_uuid(),p.id,p.order_id FROM shipment_parcels p
                WHERE NOT EXISTS (SELECT 1 FROM shipment_dispatch_jobs j WHERE j.parcel_id=p.id)
                ORDER BY p.registered_at,p.id LIMIT 10 ON CONFLICT DO NOTHING
                """);
        for (int i = 0; i < 10; i++) {
            Work work = tx.execute(status -> take());
            if (work == null) { break; }
            process(work);
        }
    }

    /** SKIP LOCKED chia job giữa instance; token lease mới là hàng rào trước mọi cập nhật/call carrier. */
    private Work take() {
        var rows = jdbc.queryForList("""
                SELECT j.*,p.warehouse_id,p.carrier_code,p.service_type,p.weight_grams,p.to_district_id,p.to_ward_code
                FROM shipment_dispatch_jobs j JOIN shipment_parcels p ON p.id=j.parcel_id
                WHERE j.state='WAITING' AND j.next_attempt_at<=now()
                ORDER BY j.next_attempt_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """);
        if (rows.isEmpty()) { return null; }
        var row = rows.getFirst(); UUID id = (UUID) row.get("id"); UUID token = UUID.randomUUID();
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='CLAIMING',attempts=attempts+1,lease_token=?,"
                + "lease_expires_at=now()+interval '120 seconds',updated_at=now() WHERE id=?", token, id);
        return new Work(id, (UUID) row.get("order_id"), (UUID) row.get("parcel_id"), token, ((Number) row.get("attempts")).intValue() + 1,
                new ShipmentPreparationService.Parcel((UUID) row.get("warehouse_id"), (String) row.get("carrier_code"),
                        (String) row.get("service_type"), ((Number) row.get("weight_grams")).intValue(),
                        ((Number) row.get("to_district_id")).intValue(), (String) row.get("to_ward_code")));
    }

    /** Chỉ retry claim cùng job UUID; sau dấu DISPATCHING mọi exception/crash phải chờ đối soát thay vì gọi carrier mới. */
    private void process(Work work) {
        boolean dispatchStarted = false;
        try {
            Boolean usable = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM warehouses WHERE id=? AND is_active)
                      AND EXISTS (SELECT 1 FROM carriers WHERE code=? AND is_active)
                      AND NOT EXISTS (SELECT 1 FROM shipments WHERE order_id=?)
                      AND NOT EXISTS (SELECT 1 FROM shipment_dispatch_attempts WHERE order_id=?)
                    """, Boolean.class, work.parcel().warehouseId(), work.parcel().carrier(), work.order(), work.order());
            if (!Boolean.TRUE.equals(usable)) { fail(work, "PARCEL_OR_DISPATCH_REQUIRES_REVIEW", true); return; }
            DispatchCommerceClient.Handoff handoff;
            try { handoff = commerce.handoff(work.order()); }
            catch (DispatchCommerceClient.Fault error) {
                if (error.status() != 404) { throw error; }
                // Chưa thu tiền/kho: chỉ chờ GET, không tiêu hao ngân sách claim hoặc đánh dấu đã gửi hàng.
                jdbc.update("UPDATE shipment_dispatch_jobs SET state='WAITING',attempts=greatest(attempts-1,0),"
                        + "next_attempt_at=now()+interval '60 seconds',lease_token=NULL,lease_expires_at=NULL,last_error_type='WAITING_HANDOFF',updated_at=now() "
                        + "WHERE id=? AND state='CLAIMING' AND lease_token=?", work.id(), work.token());
                return;
            }
            var claim = commerce.claim(work.order(), handoff.id(), work.parcelId(), work.id());
            int retained = jdbc.update("UPDATE shipment_dispatch_jobs SET claim_id=?,updated_at=now() WHERE id=? AND state='CLAIMING' "
                    + "AND lease_token=? AND lease_expires_at>now() AND (claim_id IS NULL OR claim_id=?)",
                    claim.id(), work.id(), work.token(), claim.id());
            if (retained != 1) { fail(work, "CLAIM_FENCE_OR_ID_MISMATCH", true); return; }
            var authorization = commerce.preflight(claim);
            // Ghi dấu trước call ShippingService; chỉ CAS đã commit với lease còn hạn mới được tới carrier guard.
            Boolean marked = tx.execute(status -> jdbc.update("""
                    UPDATE shipment_dispatch_jobs j SET state='DISPATCHING',lease_expires_at=now()+interval '120 seconds',updated_at=now()
                    WHERE j.id=? AND j.state='CLAIMING' AND j.lease_token=? AND j.lease_expires_at>now()
                      AND EXISTS (SELECT 1 FROM shipment_parcels p JOIN warehouses w ON w.id=p.warehouse_id
                        JOIN carriers c ON c.code=p.carrier_code WHERE p.id=j.parcel_id AND w.is_active AND c.is_active)
                    """, work.id(), work.token()) == 1);
            if (!Boolean.TRUE.equals(marked)) { fail(work, "DISPATCH_FENCE_LOST", true); return; }
            dispatchStarted = true;
            var order = authorization.snapshot(); var parcel = work.parcel();
            var request = new CreateShipmentRequest(work.order(), parcel.warehouseId(), parcel.carrier(), parcel.serviceType(),
                    order.recipientName(), order.recipientPhone(), order.shippingAddress(), parcel.toDistrictId(), parcel.toWardCode(),
                    parcel.weightGrams(), order.total(), BigDecimal.ZERO, "Claimed parcel dispatch");
            String key = "dispatch-job:" + work.id();
            var shipment = shipping.createShipment(request, key);
            if (shipment == null || shipment.id() == null || !work.order().equals(shipment.orderId())
                    || shipment.trackingNumber() == null || shipment.trackingNumber().isBlank()) { throw new IllegalStateException(); }
            int committed = jdbc.update("""
                    UPDATE shipment_dispatch_jobs j SET state='COMMITTED',shipment_id=?,last_error_type=NULL,updated_at=now()
                    WHERE j.id=? AND j.state='DISPATCHING' AND j.lease_token=?
                      AND EXISTS (SELECT 1 FROM shipments s WHERE s.id=? AND s.order_id=j.order_id AND s.idempotency_key=? AND s.tracking_no=?)
                    """, shipment.id(), work.id(), work.token(), shipment.id(), key, shipment.trackingNumber());
            if (committed != 1) { throw new IllegalStateException(); }
        } catch (RuntimeException error) {
            if (dispatchStarted) {
                jdbc.update("UPDATE shipment_dispatch_jobs SET state='REQUIRES_REVIEW',last_error_type=?,updated_at=now() "
                        + "WHERE id=? AND state='DISPATCHING' AND lease_token=?", error.getClass().getSimpleName(), work.id(), work.token());
            } else {
                boolean permanent = error instanceof DispatchCommerceClient.Fault fault
                        && fault.status() >= 400 && fault.status() < 500 && fault.status() != 401 && fault.status() != 429;
                fail(work, error.getClass().getSimpleName(), permanent);
            }
        }
    }

    /** Không bao giờ reset DISPATCHING ở nhánh retry; tối đa 8 lỗi claim, chỉ lưu type/code không chứa payload/PII. */
    private void fail(Work work, String type, boolean permanent) {
        jdbc.update("UPDATE shipment_dispatch_jobs SET state=?,last_error_type=?,next_attempt_at=now()+(? * interval '1 second'),"
                + "lease_token=NULL,lease_expires_at=NULL,updated_at=now() WHERE id=? AND state='CLAIMING' AND lease_token=?",
                permanent || work.attempts() >= 8 ? "REQUIRES_REVIEW" : "WAITING", type, Math.min(300, work.attempts() * 15), work.id(), work.token());
    }

    /** Metadata/kiện nội bộ, không lưu người nhận/địa chỉ/phone vào job hoặc log. */
    private record Work(UUID id, UUID order, UUID parcelId, UUID token, int attempts, ShipmentPreparationService.Parcel parcel) { }
}
