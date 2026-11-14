package com.nexus.fulfillment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin liên kết job REVIEW với shipment đã xác nhận, không gọi mạng hoặc giải phóng claim/refund.
 * Khóa key → job → lifecycle → guard/ship; audit và COMMITTED cùng transaction, không tự reset job để gửi lại.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class DispatchJobReconciliationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public DispatchJobReconciliationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Metadata Admin không chứa lease token, recipient/address/phone hoặc payload carrier. */
    @Transactional(readOnly = true)
    public Job get(UUID id) {
        var rows = jdbc.query("SELECT id,order_id,parcel_id,claim_id,state,shipment_id,attempts,last_error_type,updated_at "
                + "FROM shipment_dispatch_jobs WHERE id=?", (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("parcel_id", UUID.class), rs.getObject("claim_id", UUID.class), rs.getString("state"),
                rs.getObject("shipment_id", UUID.class), rs.getInt("attempts"), rs.getString("last_error_type"),
                rs.getTimestamp("updated_at").toInstant()), id);
        if (rows.isEmpty()) { throw error("DISPATCH_JOB_NOT_FOUND", HttpStatus.NOT_FOUND); }
        return rows.getFirst();
    }

    /** Cùng actor/key/body trả audit cũ; chỉ đóng REVIEW có claim, guard COMMITTED và shipment khớp kiện/snapshot/provenance. */
    @Transactional
    public Result reconcile(UUID job, UUID actor, UUID key, Command body) {
        if (job == null || actor == null || key == null || body == null || blank(body.evidenceReference())
                || body.evidenceReference().trim().length() > 200 || blank(body.note()) || body.note().trim().length() > 500) {
            throw error("INVALID_JOB_RECONCILIATION", HttpStatus.BAD_REQUEST);
        }
        String evidence = body.evidenceReference().trim(); String note = body.note().trim();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "dispatch-job-reconcile:" + actor + ":" + key);
        var previous = jdbc.queryForList("SELECT * FROM shipment_dispatch_job_reconciliations WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!job.equals(row.get("job_id")) || !evidence.equals(row.get("evidence_reference")) || !note.equals(row.get("note"))) { throw conflict(); }
            return new Result(resolution(row), false);
        }
        var jobs = jdbc.queryForList("SELECT * FROM shipment_dispatch_jobs WHERE id=? FOR UPDATE", job);
        if (jobs.isEmpty()) { throw error("DISPATCH_JOB_NOT_FOUND", HttpStatus.NOT_FOUND); }
        var row = jobs.getFirst(); UUID order = (UUID) row.get("order_id");
        if (!"REQUIRES_REVIEW".equals(row.get("state")) || row.get("claim_id") == null) { throw conflict(); }
        // Chờ ShippingService/manual recovery đang giữ lifecycle kết thúc; timeout tuổi job không phải bằng chứng đã ngừng gửi.
        if (jdbc.queryForList("SELECT order_id FROM shipment_dispatch_locks WHERE order_id=? FOR UPDATE", order).isEmpty()) { throw conflict(); }
        var attempts = jdbc.queryForList("SELECT * FROM shipment_dispatch_attempts WHERE order_id=? FOR UPDATE", order);
        var ships = jdbc.queryForList("SELECT * FROM shipments WHERE order_id=? FOR SHARE", order);
        if (attempts.isEmpty() || ships.isEmpty()) { throw conflict(); }
        var attempt = attempts.getFirst(); var ship = ships.getFirst();
        var parcel = jdbc.queryForMap("SELECT * FROM shipment_parcels WHERE id=?", row.get("parcel_id"));
        CarrierCreateShipmentRequest snapshot;
        try { snapshot = mapper.readValue((String) attempt.get("request_payload"), CarrierCreateShipmentRequest.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw conflict(); }
        if (!"COMMITTED".equals(attempt.get("status")) || blank((String) attempt.get("tracking_number"))
                || !Objects.equals(attempt.get("tracking_number"), ship.get("tracking_no")) || snapshot == null
                || !order.equals(snapshot.orderId()) || !Objects.equals(parcel.get("order_id"), order)
                || !Objects.equals(parcel.get("warehouse_id"), snapshot.warehouseId()) || !Objects.equals(ship.get("warehouse_id"), snapshot.warehouseId())
                || !Objects.equals(parcel.get("carrier_code"), attempt.get("carrier_code")) || !Objects.equals(ship.get("carrier_code"), attempt.get("carrier_code"))
                || !Objects.equals(parcel.get("service_type"), snapshot.serviceType()) || !Objects.equals(ship.get("service_type"), snapshot.serviceType())
                || !Objects.equals(parcel.get("weight_grams"), snapshot.weightGrams()) || !Objects.equals(ship.get("weight_grams"), snapshot.weightGrams())
                || !Objects.equals(parcel.get("to_district_id"), snapshot.toDistrictId()) || !Objects.equals(parcel.get("to_ward_code"), snapshot.toWardCode())
                || blank(snapshot.recipientName()) || blank(snapshot.recipientPhone()) || blank(snapshot.shippingAddress())
                || !Objects.equals(ship.get("recipient_name"), snapshot.recipientName()) || !Objects.equals(ship.get("recipient_phone"), snapshot.recipientPhone())
                || !Objects.equals(ship.get("shipping_address"), snapshot.shippingAddress()) || !moneyEquals(snapshot.codAmount(), BigDecimal.ZERO)
                || snapshot.insuranceValue() == null || snapshot.insuranceValue().signum() <= 0
                || !moneyEquals((BigDecimal) ship.get("order_total"), snapshot.insuranceValue())) { throw conflict(); }
        UUID attemptId = (UUID) attempt.get("id"); UUID shipmentId = (UUID) ship.get("id");
        // ACK local phải thuộc job key; shipment khôi phục/key khác cần audit manual-resolution đúng attempt/ship/tracking.
        boolean ownKey = ("dispatch-job:" + job).equals(ship.get("idempotency_key"));
        if (!ownKey && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shipment_dispatch_resolutions "
                + "WHERE attempt_id=? AND shipment_id=? AND tracking_number=?)", Boolean.class, attemptId, shipmentId, ship.get("tracking_no")))) { throw conflict(); }
        UUID audit = UUID.randomUUID();
        var saved = jdbc.queryForMap("INSERT INTO shipment_dispatch_job_reconciliations(id,job_id,attempt_id,shipment_id,actor_id,"
                + "idempotency_key,evidence_reference,note) VALUES (?,?,?,?,?,?,?,?) RETURNING *",
                audit, job, attemptId, shipmentId, actor, key, evidence, note);
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='COMMITTED',shipment_id=?,last_error_type=NULL,updated_at=now() WHERE id=?", shipmentId, job);
        return new Result(resolution(saved), true);
    }

    /** So giá trị tiền không làm tròn hoặc phụ thuộc scale JSON/DB khác nhau. */
    private boolean moneyEquals(BigDecimal first, BigDecimal second) { return first != null && second != null && first.compareTo(second) == 0; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    /** Chỉ trả định danh/thời điểm audit, không xuất note/evidence hoặc payload chứa PII. */
    private Resolution resolution(java.util.Map<String, Object> row) {
        return new Resolution((UUID) row.get("id"), (UUID) row.get("job_id"), (UUID) row.get("attempt_id"),
                (UUID) row.get("shipment_id"), ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }
    private ApiException conflict() { return error("DISPATCH_JOB_RECONCILIATION_CONFLICT", HttpStatus.CONFLICT); }
    private ApiException error(String code, HttpStatus status) {
        return new ApiException(code, status, "Job/claim/shipment/guard cần đối soát hoặc dữ liệu yêu cầu không hợp lệ", null);
    }
    /** Bằng chứng/ghi chú chỉ lưu audit nội bộ, không nhập token, secret hoặc thông tin người nhận. */
    public record Command(String evidenceReference, String note) { }
    /** Kết quả audit ổn định qua replay, không mang note/evidence hoặc dữ liệu người nhận. */
    public record Resolution(UUID auditId, UUID jobId, UUID attemptId, UUID shipmentId, Instant createdAt) { }
    /** created chỉ phân biệt HTTP 201/200, không báo tạo shipment mới. */
    public record Result(Resolution resolution, boolean created) { }
    /** Metadata job đã lọc lease token và snapshot PII trước trả cho Admin. */
    public record Job(UUID id, UUID orderId, UUID parcelId, UUID claimId, String state, UUID shipmentId,
                      int attempts, String lastErrorType, Instant updatedAt) { }
}
