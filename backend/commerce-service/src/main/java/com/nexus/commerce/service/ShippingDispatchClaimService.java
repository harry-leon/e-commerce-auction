package com.nexus.commerce.service;

import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấp một claim bền vững cho handoff/kiện, khóa key → parcel → Order → Payment; cùng khóa Order với tạo refund.
 * Không gọi carrier hoặc xác minh DB Fulfillment. Claim giữ chặn refund kể cả timeout/crash, chưa có API giải phóng.
 */
@Service
public class ShippingDispatchClaimService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;
    private final boolean enabled;

    public ShippingDispatchClaimService(JdbcTemplate jdbc, CheckoutLock locks,
            @Value("${app.shipping.dispatch-claim-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.locks = locks; this.enabled = enabled;
    }

    /** Cùng caller/key/body trả claim cũ, không cấp lần gửi mới; khác nội dung/parcel hoặc tài chính không sẵn sàng trả 409. */
    @Transactional
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')")
    public Result claim(UUID caller, UUID key, Command body) {
        if (caller == null || key == null || body == null || body.handoffId() == null || body.parcelId() == null) {
            throw new ApiException("INVALID_DISPATCH_CLAIM", HttpStatus.BAD_REQUEST, "Claim thiếu UUID hợp lệ", null);
        }
        if (!enabled) { throw new ApiException("SHIPPING_DISPATCH_DISABLED", HttpStatus.SERVICE_UNAVAILABLE, "Cấp quyền dispatch chưa được bật", null); }
        locks.acquire("shipping-claim-key:" + caller + ":" + key);
        var previous = jdbc.query("SELECT * FROM shipping_dispatch_claims WHERE requested_by=? AND idempotency_key=?", this::map, caller, key);
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            if (!body.handoffId().equals(old.handoffId()) || !body.parcelId().equals(old.parcelId())) { throw conflict(); }
            return new Result(old, false);
        }
        locks.acquire("shipping-claim-parcel:" + body.parcelId());
        var tasks = jdbc.queryForList("SELECT order_id,payment_id FROM shipping_handoff_tasks WHERE id=?", body.handoffId());
        if (tasks.isEmpty()) { throw new ApiException("HANDOFF_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu bàn giao", null); }
        UUID order = (UUID) tasks.getFirst().get("order_id");
        UUID payment = (UUID) tasks.getFirst().get("payment_id");
        // Đọc lại điều kiện sau khóa Order, không tin row WAITING_PARCEL được ghi trước khi có refund/hold.
        jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", order);
        jdbc.queryForList("SELECT id FROM payments WHERE id=? FOR UPDATE", payment);
        if (!ready(body.handoffId(), body.parcelId(), null)) { throw conflict(); }
        UUID id = UUID.randomUUID();
        var claim = jdbc.queryForObject("INSERT INTO shipping_dispatch_claims(id,order_id,handoff_id,parcel_id,requested_by,idempotency_key) "
                + "VALUES (?,?,?,?,?,?) RETURNING *", this::map, id, order, body.handoffId(), body.parcelId(), caller, key);
        jdbc.update("UPDATE shipping_handoff_tasks SET state='CLAIMED' WHERE id=?", body.handoffId());
        return new Result(claim, true);
    }

    /** Response chỉ định danh/thời điểm, không trả merchant, token, địa chỉ hoặc giả tracking carrier. */
    private Claim map(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Claim(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("handoff_id", UUID.class),
                rs.getObject("parcel_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    /** Dùng cùng điều kiện tiền/kho/refund cho cấp claim và preflight; khác state/claim đã tồn tại, không bỏ guard ở replay. */
    private boolean ready(UUID handoff, UUID parcel, UUID claim) {
        Boolean ready = jdbc.queryForObject("""
                SELECT o.status IN ('PAID','PROCESSING') AND o.price_finalized AND o.payment_method='VNPAY'
                  AND COALESCE(o.fulfillment_status,'')<>'REVIEW' AND p.provider='VNPAY' AND p.method='VNPAY'
                  AND p.status='SUCCESS' AND p.inventory_status='CONFIRMED' AND p.response_code='00' AND p.transaction_status='00'
                  AND p.completed_at IS NOT NULL AND p.provider_ref ~ '^[0-9]{1,100}$' AND p.provider_ref !~ '^0+$'
                  AND length(trim(p.merchant_code))>0 AND p.merchant_code<>'DEMO_ONLY'
                  AND length(trim(o.recipient_name))>0 AND length(trim(o.recipient_phone))>0 AND length(trim(o.shipping_address))>0
                  AND p.amount>0 AND p.amount=trunc(p.amount) AND p.amount=o.total AND p.amount=t.amount
                  AND CASE WHEN jsonb_typeof(p.price_snapshot->'total')='number'
                    THEN (p.price_snapshot->>'total')::numeric=p.amount ELSE false END
                  AND t.state=?
                  AND ((? AND NOT EXISTS (SELECT 1 FROM shipping_dispatch_claims c WHERE c.order_id=o.id OR c.parcel_id=?))
                    OR (? AND EXISTS (SELECT 1 FROM shipping_dispatch_claims c WHERE c.id=? AND c.handoff_id=t.id AND c.parcel_id=?)))
                  AND NOT EXISTS (SELECT 1 FROM refund_requests r WHERE r.order_id=o.id AND r.status<>'FAILED')
                  AND NOT EXISTS (SELECT 1 FROM refund_payment_holds h JOIN payments q ON q.id=h.payment_id WHERE q.order_id=o.id)
                  AND NOT EXISTS (SELECT 1 FROM payments q WHERE q.order_id=o.id AND (q.status='REVIEW' OR q.inventory_status='REVIEW'))
                  AND (SELECT count(*) FROM payments q WHERE q.order_id=o.id AND q.status='SUCCESS')=1
                  AND NOT EXISTS (SELECT 1 FROM shipping_order_bindings b WHERE b.order_id=o.id)
                FROM shipping_handoff_tasks t JOIN orders o ON o.id=t.order_id JOIN payments p ON p.id=t.payment_id
                WHERE t.id=? AND p.order_id=o.id
                """, Boolean.class, claim == null ? "WAITING_PARCEL" : "CLAIMED", claim == null, parcel,
                        claim != null, claim, parcel, handoff);
        return Boolean.TRUE.equals(ready);
    }

    private ApiException conflict() {
        return new ApiException("DISPATCH_CLAIM_CONFLICT", HttpStatus.CONFLICT, "Handoff/kiện/key hoặc điều kiện tài chính cần đối soát", null);
    }
    /** Parcel UUID do service Fulfillment đáng tin cậy khai báo; Commerce chưa tự kiểm chứng kiện hoặc route. */
    public record Command(UUID handoffId, UUID parcelId) { }
    /** Không có TTL tự giải phóng; replay là đọc kết quả cũ, không phải quyền retry mutation carrier. */
    public record Claim(UUID id, UUID orderId, UUID handoffId, UUID parcelId, Instant createdAt) { }
    public record Result(Claim claim, boolean created) { }

    /** Tra ý định bằng Order, không đọc DB chéo; không có handoff trả 404 để worker chờ tiền/kho, không tự tạo ý định. */
    @Transactional(readOnly = true)
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')")
    public Handoff handoff(UUID order) {
        enabled();
        var rows = jdbc.query("SELECT id,order_id,state FROM shipping_handoff_tasks WHERE order_id=?", (rs, i) ->
                new Handoff(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("state")), order);
        if (rows.isEmpty()) { throw new ApiException("HANDOFF_NOT_FOUND", HttpStatus.NOT_FOUND, "Chưa có yêu cầu bàn giao", null); }
        return rows.getFirst();
    }

    /**
     * Claim replay không tái cấp quyền: preflight đọc lại tiền/kho/hold và snapshot người nhận dưới khóa Order.
     * Chỉ caller đã claim với cả DISPATCH/READ được nhận PII. Không gọi carrier, không gia hạn/xóa claim.
     */
    @Transactional
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_DISPATCH')"
            + " and T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_READ')")
    public Authorization preflight(UUID caller, UUID id) {
        enabled();
        if (caller == null || id == null) { throw new ApiException("INVALID_DISPATCH_CLAIM", HttpStatus.BAD_REQUEST, "Preflight thiếu UUID", null); }
        var rows = jdbc.query("SELECT * FROM shipping_dispatch_claims WHERE id=? AND requested_by=?", this::map, id, caller);
        if (rows.isEmpty()) { throw new ApiException("CLAIM_NOT_FOUND", HttpStatus.NOT_FOUND, "Không có claim thuộc caller", null); }
        var claim = rows.getFirst();
        jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", claim.orderId());
        jdbc.queryForList("SELECT p.id FROM payments p JOIN shipping_handoff_tasks t ON t.payment_id=p.id WHERE t.id=? FOR UPDATE OF p", claim.handoffId());
        if (!ready(claim.handoffId(), claim.parcelId(), id)) { throw conflict(); }
        var snapshot = jdbc.queryForObject("SELECT * FROM orders WHERE id=?", (rs, i) -> new ShippingOrderSnapshotService.Snapshot(
                rs.getObject("id", UUID.class), rs.getString("order_no"), com.nexus.commerce.enums.OrderStatus.valueOf(rs.getString("status")),
                rs.getString("recipient_name"), rs.getString("recipient_phone"), rs.getString("shipping_address"), rs.getString("payment_method"),
                rs.getBoolean("price_finalized"), rs.getBigDecimal("total"), rs.getString("fulfillment_status")), claim.orderId());
        return new Authorization(claim, snapshot);
    }

    private void enabled() {
        if (!enabled) { throw new ApiException("SHIPPING_DISPATCH_DISABLED", HttpStatus.SERVICE_UNAVAILABLE, "Cấp quyền dispatch chưa được bật", null); }
    }
    /** Metadata để worker giữ đúng handoff qua retry, không phải quyền tạo vận đơn. */
    public record Handoff(UUID id, UUID orderId, String state) { }
    /** Snapshot tại thời điểm preflight, không hứa trạng thái không bao giờ thay đổi hoặc browser đã nhận tin. */
    public record Authorization(Claim claim, ShippingOrderSnapshotService.Snapshot snapshot) { }
}
