package com.nexus.fulfillment.service;

import com.nexus.common.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu kiện do Admin khai báo, không gọi Commerce/carrier; khóa actor/key rồi Order giữa các instance.
 * Parcel và audit actor cùng một row/transaction; không sửa khai báo cũ hoặc coi đây là quyền dispatch.
 */
@Service
public class ShipmentParcelService {
    private final JdbcTemplate jdbc;
    public ShipmentParcelService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Cùng actor/key/nội dung chuẩn hóa trả row cũ; khác nội dung hoặc Order đã có kiện trả 409, không ghi đè audit. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Result register(UUID order, UUID actor, UUID key, ShipmentPreparationService.Parcel input) {
        var parcel = normalize(order, actor, key, input);
        lock("key:" + actor + ":" + key);
        var previous = jdbc.query("SELECT * FROM shipment_parcels WHERE registered_by=? AND idempotency_key=?", this::map, actor, key);
        if (!previous.isEmpty()) {
            var existing = previous.getFirst();
            if (!order.equals(existing.orderId()) || !parcel.equals(existing.parcel())) { throw conflict(); }
            return new Result(existing, false);
        }
        lock("order:" + order);
        // Chặn nếu đã thấy kiện/dispatch; không thay thế lifecycle lock hoặc guard trước mutation carrier hiện có.
        Boolean used = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM shipment_parcels WHERE order_id=?)
                  OR EXISTS (SELECT 1 FROM shipment_dispatch_attempts WHERE order_id=?)
                  OR EXISTS (SELECT 1 FROM shipments WHERE order_id=?)
                """, Boolean.class, order, order, order);
        if (!Boolean.FALSE.equals(used)) { throw conflict(); }
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM warehouses WHERE id=? AND is_active)
                  AND EXISTS (SELECT 1 FROM carriers WHERE code=? AND is_active)
                """, Boolean.class, parcel.warehouseId(), parcel.carrier());
        if (!Boolean.TRUE.equals(active)) {
            throw new ApiException("PARCEL_RESOURCE_UNAVAILABLE", HttpStatus.CONFLICT, "Kho hoặc carrier không hoạt động", null);
        }
        jdbc.update("INSERT INTO shipment_parcels(id,order_id,warehouse_id,carrier_code,service_type,weight_grams,"
                + "to_district_id,to_ward_code,registered_by,idempotency_key) VALUES (?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), order,
                parcel.warehouseId(), parcel.carrier(), parcel.serviceType(), parcel.weightGrams(),
                parcel.toDistrictId(), parcel.toWardCode(), actor, key);
        return new Result(get(order), true);
    }

    /** Chỉ Admin đọc kiện/audit; GET không ghép PII hoặc suy ra điều kiện tài chính từ Order UUID. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Registration get(UUID order) {
        var rows = jdbc.query("SELECT * FROM shipment_parcels WHERE order_id=?", this::map, order);
        if (rows.isEmpty()) { throw new ApiException("PARCEL_NOT_FOUND", HttpStatus.NOT_FOUND, "Chưa có kiện đã khai báo", null); }
        return rows.getFirst();
    }

    /** Giới hạn định dạng/độ dài; không tự thêm 500g/kho hoặc giả carrier hỗ trợ một service/tuyến. */
    private ShipmentPreparationService.Parcel normalize(UUID order, UUID actor, UUID key, ShipmentPreparationService.Parcel p) {
        if (order == null || actor == null || key == null || p == null || p.warehouseId() == null
                || p.weightGrams() == null || p.weightGrams() <= 0 || p.toDistrictId() == null || p.toDistrictId() <= 0
                || p.carrier() == null || !p.carrier().trim().toUpperCase(Locale.ROOT).matches("GHN|GHTK")
                || p.serviceType() == null || !p.serviceType().trim().toUpperCase(Locale.ROOT).matches("[A-Z][A-Z0-9_]{0,29}")
                || p.toWardCode() == null || p.toWardCode().isBlank() || p.toWardCode().trim().length() > 100) {
            throw new ApiException("INVALID_PARCEL", HttpStatus.BAD_REQUEST, "Kiện thiếu dữ liệu hoặc sai định dạng", null);
        }
        return new ShipmentPreparationService.Parcel(p.warehouseId(), p.carrier().trim().toUpperCase(Locale.ROOT),
                p.serviceType().trim().toUpperCase(Locale.ROOT), p.weightGrams(), p.toDistrictId(), p.toWardCode().trim());
    }

    /** Khóa PostgreSQL tự nhả khi commit/rollback; namespace riêng không dùng khóa RAM giữa các instance. */
    private void lock(String value) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "shipment-parcel:" + value);
    }

    /** Chỉ ánh xạ định tuyến/cân nặng và audit; không ghép địa chỉ kho hoặc PII từ Commerce vào response. */
    private Registration map(ResultSet rs, int index) throws SQLException {
        return new Registration(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                new ShipmentPreparationService.Parcel(rs.getObject("warehouse_id", UUID.class), rs.getString("carrier_code"),
                        rs.getString("service_type"), rs.getInt("weight_grams"), rs.getInt("to_district_id"), rs.getString("to_ward_code")),
                rs.getObject("registered_by", UUID.class), rs.getTimestamp("registered_at").toInstant());
    }

    private ApiException conflict() {
        return new ApiException("PARCEL_CONFLICT", HttpStatus.CONFLICT, "Key khác nội dung hoặc đơn đã có kiện/dispatch cần đối soát", null);
    }

    /** Khai báo kiện và audit, không có tracking hoặc status giả vận đơn đã tạo. */
    public record Registration(UUID id, UUID orderId, ShipmentPreparationService.Parcel parcel, UUID registeredBy, Instant registeredAt) { }
    /** created chỉ phân biệt HTTP 201/200, không phải carrier đã nhận hàng. */
    public record Result(Registration registration, boolean created) { }
}
