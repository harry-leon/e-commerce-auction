package com.nexus.notification.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.notification.config.NotificationProperties;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhận sự kiện từ service account Commerce; tạo nội dung IN_APP + WebSocket PUSH (khi bật PUSH_NOTIFICATION_ENABLED)
 * tại server và lưu idempotent theo eventId.
 */
@RestController
public class CommerceNotificationController {

    private static final Logger log = LoggerFactory.getLogger(CommerceNotificationController.class);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm '(GMT+7)'");
    private static final int DEFAULT_PENALTY_SCORE = 10;

    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final SimpMessagingTemplate messagingTemplate;

    public CommerceNotificationController(JdbcTemplate jdbc) {
        this(jdbc, null, null);
    }

    @Autowired
    public CommerceNotificationController(
            JdbcTemplate jdbc,
            @Autowired(required = false) NotificationProperties properties,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Snapshot không chứa địa chỉ, điện thoại, JWT; buyer lấy từ đơn bởi Commerce.
     * Bổ sung auctionId, paymentDeadline, paymentLink và penaltyScore (nullable) cho vòng đời đấu giá.
     */
    public record Event(
            @NotNull UUID id,
            @NotNull UUID userId,
            @NotNull UUID orderId,
            @NotBlank @Size(max = 100) String orderNo,
            @NotNull Type type,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull Instant occurredAt,
            UUID auctionId,
            Instant paymentDeadline,
            @Size(max = 500) String paymentLink,
            Integer penaltyScore
    ) {
        /** Constructor tương thích ngược với 7 tham số Sprint 1. */
        public Event(UUID id, UUID userId, UUID orderId, String orderNo,
                     Type type, BigDecimal amount, Instant occurredAt) {
            this(id, userId, orderId, orderNo, type, amount, occurredAt, null, null, null, null);
        }
    }

    /** Các tình huống thông báoCommerce: đơn thường Sprint 1 và vòng đời winner đấu giá Sprint 2. */
    public enum Type {
        ORDER_CREATED,
        PAYMENT_SUCCESS,
        PAYMENT_FAILED,
        AUCTION_WON,
        AUCTION_PAYMENT_REMINDER,
        AUCTION_PAYMENT_TIMEOUT
    }

    /** Ghi thông báo IN_APP/PUSH và attempt cùng transaction; retry cùng payload trả 204, đổi payload cùng UUID trả 409. */
    @PostMapping("/internal/commerce-notifications")
    @PreAuthorize("hasRole('NOTIFICATION_WRITER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Nhận sự kiện đơn/thanh toán/đấu giá idempotent từ Commerce")
    public void receive(@Valid @RequestBody Event event) {
        String title = buildTitle(event.type());
        String resolvedLink = resolvePaymentLink(event);
        String content = buildContent(event, resolvedLink);
        LocalDateTime createdAtUtc = LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC);
        Timestamp deadlineTs = event.paymentDeadline() != null
                ? Timestamp.from(event.paymentDeadline())
                : null;

        int inserted = jdbc.update("""
                INSERT INTO notifications(
                    id, user_id, event_id, channel, title, content, status, sent_at, created_at,
                    order_id, event_type, auction_id, payment_link, payment_deadline)
                VALUES (?, ?, ?, 'IN_APP', ?, ?, 'SENT', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) WHERE event_type IS NOT NULL DO NOTHING
                """,
                event.id(), event.userId(), event.id().toString(), title, content, createdAtUtc,
                event.orderId(), event.type().name(), event.auctionId(), resolvedLink, deadlineTs);

        if (inserted == 1) {
            jdbc.update("INSERT INTO notification_attempts(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), event.id());
            if (isPushEnabled()) {
                recordAndDispatchPush(event, title, content, resolvedLink, createdAtUtc, deadlineTs);
            }
        } else {
            // Không cho replay UUID đổi người nhận/nội dung; READ vẫn được giữ nguyên khi worker retry sau mất ACK.
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM notifications WHERE event_id=? AND user_id=? AND order_id=?
                    AND event_type=? AND title=? AND content=? AND created_at=?
                    """, Integer.class, event.id().toString(), event.userId(), event.orderId(), event.type().name(),
                    title, content, createdAtUtc);
            if (matches == null || matches != 1) {
                throw new ApiException("NOTIFICATION_EVENT_CONFLICT", HttpStatus.CONFLICT,
                        "UUID sự kiện đã có nội dung khác", null);
            }
        }
    }

    private void recordAndDispatchPush(
            Event event,
            String title,
            String content,
            String resolvedLink,
            LocalDateTime createdAtUtc,
            Timestamp deadlineTs) {
        UUID pushId = UUID.randomUUID();
        String pushEventId = "PUSH-" + event.id();
        int pushInserted = jdbc.update("""
                INSERT INTO notifications(
                    id, user_id, event_id, channel, title, content, status, sent_at, created_at,
                    order_id, event_type, auction_id, payment_link, payment_deadline)
                VALUES (?, ?, ?, 'PUSH', ?, ?, 'SENT', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) WHERE event_type IS NOT NULL DO NOTHING
                """,
                pushId, event.userId(), pushEventId, title, content, createdAtUtc,
                event.orderId(), event.type().name(), event.auctionId(), resolvedLink, deadlineTs);
        if (pushInserted == 1) {
            jdbc.update("INSERT INTO notification_attempts(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), pushId);
        }
        if (messagingTemplate != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", pushId);
            payload.put("userId", event.userId());
            payload.put("orderId", event.orderId());
            payload.put("orderNo", event.orderNo());
            payload.put("auctionId", event.auctionId());
            payload.put("type", event.type().name());
            payload.put("channel", "PUSH");
            payload.put("title", title);
            payload.put("content", content);
            payload.put("paymentLink", resolvedLink);
            payload.put("paymentDeadline", event.paymentDeadline() != null ? event.paymentDeadline().toString() : null);
            payload.put("penaltyScore", event.type() == Type.AUCTION_PAYMENT_TIMEOUT ? resolvePenalty(event) : null);
            payload.put("occurredAt", event.occurredAt().toString());
            try {
                messagingTemplate.convertAndSend("/topic/users/" + event.userId() + "/notifications", payload);
            } catch (RuntimeException ex) {
                // Không làm rollback thông báo DB nếu kết nối WebSocket tức thời chưa có subscriber hoặc lỗi broker.
                log.warn("Không đẩy được WebSocket push cho user [{}]: {}", event.userId(), ex.getMessage());
            }
        }
    }

    private String buildTitle(Type type) {
        return switch (type) {
            case ORDER_CREATED -> "Đã tạo đơn hàng";
            case PAYMENT_SUCCESS -> "Thanh toán thành công";
            case PAYMENT_FAILED -> "Thanh toán thất bại, đơn đã hủy";
            case AUCTION_WON -> "Chúc mừng thắng đấu giá";
            case AUCTION_PAYMENT_REMINDER -> "Sắp hết hạn thanh toán đơn đấu giá";
            case AUCTION_PAYMENT_TIMEOUT -> "Quá hạn thanh toán 24h, đơn đã hủy";
        };
    }

    private String buildContent(Event event, String resolvedLink) {
        String amountText = event.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String base = "Đơn " + event.orderNo() + " • " + amountText + " VND";
        return switch (event.type()) {
            case ORDER_CREATED, PAYMENT_SUCCESS, PAYMENT_FAILED -> base;
            case AUCTION_WON -> appendPaymentDetails("Chúc mừng thắng đấu giá " + base, event.paymentDeadline(), resolvedLink);
            case AUCTION_PAYMENT_REMINDER -> appendPaymentDetails(
                    "Nhắc nhở sắp hết hạn thanh toán " + base + " • Nguy cơ trừ " + resolvePenalty(event) + " điểm uy tín",
                    event.paymentDeadline(), resolvedLink);
            case AUCTION_PAYMENT_TIMEOUT -> base + " • Quá hạn thanh toán 24h • Đã hoàn kho & trừ "
                    + resolvePenalty(event) + " điểm uy tín";
        };
    }

    private String appendPaymentDetails(String prefix, Instant deadline, String resolvedLink) {
        StringBuilder sb = new StringBuilder(prefix);
        if (deadline != null) {
            sb.append(" • Hạn thanh toán: ").append(DEADLINE_FMT.format(deadline.atZone(DISPLAY_ZONE)));
        }
        if (resolvedLink != null && !resolvedLink.isBlank()) {
            sb.append(" • Thanh toán: ").append(resolvedLink);
        }
        return sb.toString();
    }

    private String resolvePaymentLink(Event event) {
        if (event.type() != Type.AUCTION_WON && event.type() != Type.AUCTION_PAYMENT_REMINDER) {
            return null;
        }
        String baseUrl = (properties != null && properties.getFrontendUrl() != null && !properties.getFrontendUrl().isBlank())
                ? properties.getFrontendUrl().replaceAll("/+$", "")
                : "http://localhost:3000";
        String candidate = event.paymentLink();
        if (candidate != null && !candidate.isBlank()) {
            if (candidate.startsWith("/")) {
                return baseUrl + candidate;
            }
            if (candidate.startsWith(baseUrl + "/")) {
                return candidate;
            }
        }
        return baseUrl + "/order-detail?id=" + event.orderId();
    }

    private boolean isPushEnabled() {
        return properties == null || properties.isPushEnabled();
    }

    private int resolvePenalty(Event event) {
        return event.penaltyScore() != null && event.penaltyScore() > 0
                ? event.penaltyScore()
                : DEFAULT_PENALTY_SCORE;
    }
}
