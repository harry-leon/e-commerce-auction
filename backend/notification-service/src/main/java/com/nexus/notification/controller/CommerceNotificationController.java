package com.nexus.notification.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.notification.config.NotificationProperties;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
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
 * Nhận sự kiện từ service account Commerce hoặc Auction; tạo nội dung IN_APP + WebSocket PUSH (khi bật PUSH_NOTIFICATION_ENABLED)
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
* Bổ sung auctionId, paymentDeadline, paymentLink, penaltyScore, auctionTitle và refundId phục vụ cả đấu giá và hoàn tiền.
     */
    public record Event(
            @NotNull UUID id,
            @NotNull UUID userId,
            UUID orderId,
            @Size(max = 100) String orderNo,
            @NotNull Type type,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull Instant occurredAt,
            UUID auctionId,
            Instant paymentDeadline,
            @Size(max = 500) String paymentLink,
            Integer penaltyScore,
            UUID refundId,
            String auctionTitle
    ) {
        /**
         * Giữ constructor Sprint 2 (11 tham số) cho caller Java cũ không khai báo refundId và auctionTitle.
         * Đầu vào: các trường thông tin sự kiện giao thương/đấu giá cơ bản.
         * Kết quả: Khởi tạo Event với refundId=null và auctionTitle=null.
         */
        public Event(UUID id, UUID userId, UUID orderId, String orderNo, Type type, BigDecimal amount,
                Instant occurredAt, UUID auctionId, Instant paymentDeadline, String paymentLink, Integer penaltyScore) {
            this(id, userId, orderId, orderNo, type, amount, occurredAt, auctionId, paymentDeadline, paymentLink, penaltyScore, null, null);
        }

        /**
         * Giữ constructor 12 tham số có refundId cho các luồng xử lý khiếu nại/hoàn tiền.
         * Đầu vào: thông tin sự kiện kèm refundId.
         * Kết quả: Khởi tạo Event với auctionTitle=null.
         */
        public Event(UUID id, UUID userId, UUID orderId, String orderNo, Type type, BigDecimal amount,
                Instant occurredAt, UUID auctionId, Instant paymentDeadline, String paymentLink, Integer penaltyScore, UUID refundId) {
            this(id, userId, orderId, orderNo, type, amount, occurredAt, auctionId, paymentDeadline, paymentLink, penaltyScore, refundId, null);
        }

        /**
         * Constructor tương thích ngược với 7 tham số Sprint 1.
         * Đầu vào: id, userId, orderId, orderNo, type, amount, occurredAt.
         * Kết quả: Khởi tạo Event với các trường mở rộng mang giá trị null.
         */
        public Event(UUID id, UUID userId, UUID orderId, String orderNo,
                     Type type, BigDecimal amount, Instant occurredAt) {
            this(id, userId, orderId, orderNo, type, amount, occurredAt, null, null, null, null, null, null);
        }

        /** Refund bắt buộc có UUID riêng và số tiền VND nguyên dương; không gắn refundId vào sự kiện khác. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "Sự kiện refund cần refundId và số tiền VND nguyên dương")
        public boolean isRefundContextValid() {
            if (type == null) { return true; }
            if (!type.name().startsWith("REFUND_")) { return refundId == null; }
            return refundId != null && amount != null && amount.signum() > 0 && amount.stripTrailingZeros().scale() <= 0;
        }
    }

    /** Các loại sự kiện thông báo: đơn hàng, giao hàng, thanh toán, đấu giá, outbid và hoàn tiền (refund). ORDER_DELIVERED không có nghĩa hoàn tất thanh toán COD. */
    public enum Type {
        ORDER_CREATED,
        ORDER_SHIPPED,
        ORDER_DELIVERED,
        PAYMENT_SUCCESS,
        PAYMENT_FAILED,
        AUCTION_WON,
        AUCTION_PAYMENT_REMINDER,
        AUCTION_PAYMENT_TIMEOUT,
        REFUND_REQUESTED,
        REFUND_SUCCEEDED,
        REFUND_FAILED,
        REFUND_REQUIRES_REVIEW,
        AUCTION_OUTBID
    }

    /** Ghi thông báo IN_APP/PUSH và attempt cùng transaction; retry cùng payload trả 204, đổi payload cùng UUID trả 409. */
    @PostMapping("/internal/commerce-notifications")
    @PreAuthorize("hasRole('NOTIFICATION_WRITER') or hasAuthority('COMMERCE.AUCTION')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Nhận sự kiện đơn/thanh toán/đấu giá idempotent từ Commerce hoặc Auction")
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
                    order_id, event_type, auction_id, payment_link, payment_deadline, refund_id)
                VALUES (?, ?, ?, 'IN_APP', ?, ?, 'SENT', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) WHERE event_type IS NOT NULL DO NOTHING
                """,
                event.id(), event.userId(), event.id().toString(), title, content, createdAtUtc,
                event.orderId(), event.type().name(), event.auctionId(), resolvedLink, deadlineTs, event.refundId());

        if (inserted == 1) {
            jdbc.update("INSERT INTO notification_attempts(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), event.id());
            if (isPushEnabled() && pushAllowed(event.userId(), event.type())) {
                recordAndDispatchPush(event, title, content, resolvedLink, createdAtUtc, deadlineTs);
            }
        } else {
            // Không cho replay UUID đổi người nhận/nội dung; READ vẫn được giữ nguyên khi worker retry sau mất ACK.
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM notifications WHERE event_id=? AND user_id=?
                    AND (order_id=? OR (order_id IS NULL AND ?::uuid IS NULL))
                    AND event_type=? AND title=? AND content=? AND created_at=?
                    AND refund_id IS NOT DISTINCT FROM ?
                    AND auction_id IS NOT DISTINCT FROM ? AND payment_link IS NOT DISTINCT FROM ?
                    AND payment_deadline IS NOT DISTINCT FROM ?
                    """, Integer.class, event.id().toString(), event.userId(), event.orderId(), event.orderId(),
                    event.type().name(), title, content, createdAtUtc, event.refundId(), event.auctionId(), resolvedLink, deadlineTs);
            if (matches == null || matches != 1) {
                throw new ApiException("NOTIFICATION_EVENT_CONFLICT", HttpStatus.CONFLICT,
                        "UUID sự kiện đã có nội dung khác", null);
            }
        }
    }

    /**
     * Chỉ chặn kênh PUSH khi người dùng đã tắt rõ ràng trong notification_preferences.
     *
     * <p>Đầu vào: userId và loại sự kiện của snapshot Commerce. Kết quả: true nếu không có cấu hình
     * (mặc định bật) hoặc cấu hình đang bật, false khi có dòng enabled=false.
     *
     * <p>Lý do: IN_APP vẫn được ghi để giữ nguồn resync; quyết định này chỉ ảnh hưởng bản ghi PUSH và
     * WebSocket, không thay đổi nội dung inbox hay trạng thái tài chính. Lỗi đọc cấu hình không làm mất
     * thông báo: trả true để push tiếp tục như hành vi trước khi có preferences.
     */
    private boolean pushAllowed(UUID userId, Type type) {
        try {
            Boolean enabled = jdbc.queryForObject(
                    "SELECT enabled FROM notification_preferences WHERE user_id=? AND notification_type=? AND channel='PUSH'",
                    Boolean.class, userId, type.name());
            return enabled == null || enabled;
        } catch (RuntimeException error) {
            return true;
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
                    order_id, event_type, auction_id, payment_link, payment_deadline, refund_id)
                VALUES (?, ?, ?, 'PUSH', ?, ?, 'SENT', CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) WHERE event_type IS NOT NULL DO NOTHING
                """,
                pushId, event.userId(), pushEventId, title, content, createdAtUtc,
                event.orderId(), event.type().name(), event.auctionId(), resolvedLink, deadlineTs, event.refundId());
        if (pushInserted == 1) {
            jdbc.update("INSERT INTO notification_attempts(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), pushId);
        }
        if (messagingTemplate != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", pushId);
            payload.put("userId", event.userId());
            payload.put("orderId", event.orderId());
            payload.put("refundId", event.refundId());
            payload.put("orderNo", event.orderNo());
            payload.put("auctionId", event.auctionId());
            payload.put("auctionTitle", event.auctionTitle());
            payload.put("type", event.type().name());
            payload.put("channel", "PUSH");
            payload.put("title", title);
            payload.put("content", content);
            payload.put("paymentLink", resolvedLink);
            payload.put("paymentDeadline", event.paymentDeadline() != null ? event.paymentDeadline().toString() : null);
            payload.put("penaltyScore", event.type() == Type.AUCTION_PAYMENT_TIMEOUT ? resolvePenalty(event) : null);
            payload.put("occurredAt", event.occurredAt().toString());
            // Commit DB trước push để HTTP refresh từ browser luôn đọc được tin mới và không nhận sự kiện rollback.
            Runnable dispatch = () -> {
                try {
                    messagingTemplate.convertAndSendToUser(event.userId().toString(), "/queue/notifications", payload);
                    messagingTemplate.convertAndSend("/topic/users/" + event.userId() + "/notifications", payload);
                } catch (RuntimeException ex) {
                    // Push best-effort; inbox REST còn nguyên và được resync khi reconnect, không ghi nhận đã tới browser.
                    // Exception broker có thể chứa payload; chỉ ghi ID sự kiện/loại lỗi, không message/cause.
                    log.warn("Không đẩy được WebSocket push: event={}, errorType={}", event.id(), ex.getClass().getSimpleName());
                }
            };
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() { dispatch.run(); }
                        });
            } else {
                dispatch.run();
            }
        }
    }

    private String buildTitle(Type type) {
        return switch (type) {
            case ORDER_CREATED -> "Đã tạo đơn hàng";
            case ORDER_SHIPPED -> "Đơn hàng đã bàn giao vận chuyển";
            case ORDER_DELIVERED -> "Đơn hàng đã giao thành công";
            case PAYMENT_SUCCESS -> "Thanh toán thành công";
            case PAYMENT_FAILED -> "Thanh toán thất bại, đơn đã hủy";
            case AUCTION_WON -> "Chúc mừng thắng đấu giá";
            case AUCTION_PAYMENT_REMINDER -> "Sắp hết hạn thanh toán đơn đấu giá";
            case AUCTION_PAYMENT_TIMEOUT -> "Quá hạn thanh toán 24h, đơn đã hủy";
            case REFUND_REQUESTED -> "Đã tiếp nhận yêu cầu hoàn tiền";
            case REFUND_SUCCEEDED -> "Đã xác nhận hoàn tiền";
            case REFUND_FAILED -> "Yêu cầu hoàn tiền không thành công";
            case REFUND_REQUIRES_REVIEW -> "Yêu cầu hoàn tiền cần đối soát";
            case AUCTION_OUTBID -> "Bạn đã bị trả giá cao hơn!";
        };
    }

    private String buildContent(Event event, String resolvedLink) {
        String amountText = event.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String base = (event.orderNo() != null ? ("Đơn " + event.orderNo() + " • ") : "") + amountText + " VND";
        return switch (event.type()) {
            case ORDER_CREATED, PAYMENT_SUCCESS, PAYMENT_FAILED -> base;
            // Chỉ phản ánh trạng thái Order đã được Commerce xác nhận, không hướng dẫn thanh toán hoặc xác nhận refund.
            case ORDER_SHIPPED -> base + " • Đã bàn giao vận chuyển";
            case ORDER_DELIVERED -> base + " • Đã giao thành công";
            // Chỉ SUCCEEDED xác nhận kết quả tài chính; REQUESTED/REVIEW không hướng dẫn người dùng trả tiền lại.
            case REFUND_REQUESTED -> base + " • Yêu cầu " + event.refundId() + " • Chưa xác nhận đã hoàn tiền";
            case REFUND_SUCCEEDED -> base + " • Yêu cầu " + event.refundId() + " • Backend đã xác nhận hoàn tiền";
            case REFUND_FAILED -> base + " • Yêu cầu " + event.refundId() + " • Chưa có hoàn tiền thành công";
            case REFUND_REQUIRES_REVIEW -> base + " • Yêu cầu " + event.refundId() + " • Đang đối soát, không thanh toán lại";
            case AUCTION_WON -> appendPaymentDetails("Chúc mừng thắng đấu giá " + base, event.paymentDeadline(), resolvedLink);
            case AUCTION_PAYMENT_REMINDER -> appendPaymentDetails(
                    "Nhắc nhở sắp hết hạn thanh toán " + base + " • Nguy cơ trừ " + resolvePenalty(event) + " điểm uy tín",
                    event.paymentDeadline(), resolvedLink);
            // Hoàn kho và trừ điểm uy tín chạy qua outbox bất đồng bộ khi quá hạn, nên chỉ báo trạng thái đang xử lý.
            case AUCTION_PAYMENT_TIMEOUT -> base + " • Quá hạn thanh toán 24h • Đang xử lý hoàn kho và áp dụng mức trừ "
                    + resolvePenalty(event) + " điểm uy tín";
            case AUCTION_OUTBID -> {
                String title = event.auctionTitle() != null && !event.auctionTitle().isBlank()
                        ? event.auctionTitle()
                        : "phiên đấu giá";
                yield "Phiên \"" + title + "\" đã có người đặt mức giá cao hơn: " + amountText
                        + " VND. Hãy đặt lại giá để giữ vị trí dẫn đầu!";
            }
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
        if (event.type() == Type.AUCTION_OUTBID) {
            if (event.paymentLink() != null && !event.paymentLink().isBlank()) {
                return event.paymentLink();
            }
            return event.auctionId() != null ? "/auction-detail?id=" + event.auctionId() : null;
        }
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
