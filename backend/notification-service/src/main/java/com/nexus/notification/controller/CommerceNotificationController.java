package com.nexus.notification.controller;

import com.nexus.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nhận sự kiện từ service account Commerce; tạo nội dung IN_APP tại server và lưu một lần theo eventId. */
@RestController
public class CommerceNotificationController {
    private final JdbcTemplate jdbc;

    public CommerceNotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Snapshot không chứa địa chỉ, điện thoại, JWT; buyer lấy từ đơn bởi Commerce, không lấy từ yêu cầu FE. */
    public record Event(@NotNull UUID id, @NotNull UUID userId, @NotNull UUID orderId,
            @NotBlank @Size(max = 100) String orderNo, @NotNull Type type,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull Instant occurredAt) { }

    /** Chỉ ba tình huống Sprint 1; không nhận title/content tùy ý từ caller. */
    public enum Type { ORDER_CREATED, PAYMENT_SUCCESS, PAYMENT_FAILED }

    /** Ghi thông báo và attempt cùng transaction; retry cùng payload trả 204, đổi payload cùng UUID trả 409. */
    @PostMapping("/internal/commerce-notifications")
    @PreAuthorize("hasRole('NOTIFICATION_WRITER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Nhận sự kiện đơn/thanh toán idempotent từ Commerce")
    public void receive(@Valid @RequestBody Event event) {
        String title = switch (event.type()) {
            case ORDER_CREATED -> "Đã tạo đơn hàng";
            case PAYMENT_SUCCESS -> "Thanh toán thành công";
            case PAYMENT_FAILED -> "Thanh toán thất bại, đơn đã hủy";
        };
        String content = "Đơn " + event.orderNo() + " • " + event.amount().setScale(2).toPlainString() + " VND";
        int inserted = jdbc.update("""
                INSERT INTO notifications(id,user_id,event_id,channel,title,content,status,sent_at,created_at,order_id,event_type)
                VALUES (?,?,?,'IN_APP',?,?,'SENT',CURRENT_TIMESTAMP,?,?,?)
                ON CONFLICT (event_id) WHERE event_type IS NOT NULL DO NOTHING
                """, event.id(), event.userId(), event.id().toString(), title, content,
                java.time.LocalDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC), event.orderId(), event.type().name());
        if (inserted == 1) {
            jdbc.update("INSERT INTO notification_attempts(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), event.id());
        } else {
            // Không cho replay UUID đổi người nhận/nội dung; READ vẫn được giữ nguyên khi worker retry sau mất ACK.
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM notifications WHERE event_id=? AND user_id=? AND order_id=?
                    AND event_type=? AND title=? AND content=? AND created_at=?
                    """, Integer.class, event.id().toString(), event.userId(), event.orderId(), event.type().name(),
                    title, content, java.time.LocalDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC));
            if (matches == null || matches != 1) {
                throw new ApiException("NOTIFICATION_EVENT_CONFLICT", HttpStatus.CONFLICT, "UUID sự kiện đã có nội dung khác", null);
            }
        }
    }
}
