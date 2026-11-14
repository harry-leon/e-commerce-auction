package com.nexus.fulfillment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentResult;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commit dấu gửi và snapshot trước HTTP; UNKNOWN không được gửi lại, kể cả restart hoặc shipment transaction rollback. */
@Component
public class ShipmentDispatchGuard {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShipmentDispatchGuard(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Yêu cầu transaction shipment thật để xác nhận COMMITTED bằng afterCompletion, không suy ra từ HTTP thành công.
     * Unique order bảo vệ mọi key/instance; không giữ khóa attempt trong HTTP. Snapshot chứa PII chỉ lưu DB, không log.
     */
    public CarrierCreateShipmentResult send(String carrier, CarrierCreateShipmentRequest request,
            Supplier<CarrierCreateShipmentResult> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Dispatch phải nằm trong transaction shipment");
        }
        String payload;
        try { payload = mapper.writeValueAsString(request); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Không lưu được snapshot dispatch");
        }
        UUID attempt = UUID.randomUUID();
        var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            independent.executeWithoutResult(tx -> {
                jdbc.update("INSERT INTO shipment_dispatch_attempts"
                    + "(id,order_id,carrier_code,request_payload,status) VALUES (?,?,?,?,'UNKNOWN')",
                    attempt, request.orderId(), carrier, payload);
                jdbc.update("INSERT INTO shipment_dispatch_locks(order_id) VALUES (?)", request.orderId());
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException("SHIPMENT_DISPATCH_UNRESOLVED", HttpStatus.CONFLICT,
                    "Đơn đã có lần gửi carrier; cần đối soát trước khi gửi lại", null);
        }
        // Giữ khóa lifecycle trong transaction shipment để Admin chờ request đang gửi/lưu kết thúc.
        // Row attempt vẫn tự do cho các ghi nhận độc lập; tránh tự deadlock với REQUIRES_NEW của chính guard.
        jdbc.queryForList("SELECT order_id FROM shipment_dispatch_locks WHERE order_id=? FOR UPDATE", request.orderId());
        // Admin có thể lấy lifecycle lock ngay sau claim commit nhưng trước bước này: đọc lại để không gửi sau khi đã recovery.
        if (!"UNKNOWN".equals(jdbc.queryForObject("SELECT status FROM shipment_dispatch_attempts WHERE id=?", String.class, attempt))) {
            throw new ApiException("SHIPMENT_DISPATCH_RESOLVED", HttpStatus.CONFLICT, "Dispatch đã được đối soát", null);
        }
        // Callback chạy cả khi lưu shipment/history/outbox lỗi. Nếu callback DB lỗi, UNKNOWN vẫn chặn gửi lặp an toàn.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED && acknowledged.get()) {
                    independent.executeWithoutResult(tx -> jdbc.update("UPDATE shipment_dispatch_attempts "
                            + "SET status='COMMITTED',updated_at=CURRENT_TIMESTAMP WHERE id=?", attempt));
                }
            }
        });
        try {
            var result = action.get();
            if (result == null || result.trackingNumber() == null || result.trackingNumber().isBlank()) {
                throw new IllegalStateException("Carrier thiếu tracking number");
            }
            // Giữ tracking độc lập để operator đối soát kể cả transaction shipment sau đó rollback.
            independent.executeWithoutResult(tx -> jdbc.update("UPDATE shipment_dispatch_attempts "
                    + "SET tracking_number=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", result.trackingNumber(), attempt));
            acknowledged.set(true);
            return result;
        } catch (RuntimeException error) {
            independent.executeWithoutResult(tx -> jdbc.update("UPDATE shipment_dispatch_attempts "
                    + "SET last_error_type=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", error.getClass().getSimpleName(), attempt));
            throw error;
        }
    }
}
