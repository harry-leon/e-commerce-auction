package com.nexus.notification.service.impl;

import com.nexus.notification.config.NotificationProperties;
import com.nexus.notification.dto.NotificationResponse;
import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.entity.Notification;
import com.nexus.notification.entity.NotificationAttempt;
import com.nexus.notification.enums.AttemptResult;
import com.nexus.notification.enums.NotificationChannel;
import com.nexus.notification.enums.NotificationStatus;
import com.nexus.notification.exception.EmailDeliveryException;
import com.nexus.notification.repository.NotificationAttemptRepository;
import com.nexus.notification.repository.NotificationRepository;
import com.nexus.notification.service.EmailSender;
import com.nexus.notification.service.TransactionalEmailService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ gửi email giao dịch với cơ chế thử lại và cô lập lỗi (SRS §2.6, §3.2.3, §5.2).
 *
 * Chức năng:
 * 1. Tiếp nhận sự kiện OrderCreated, OrderPaid, OrderPaymentFailed.
 * 2. Soạn email HTML/Text chuẩn hóa qua TransactionalEmailTemplateBuilder.
 * 3. Ghi nhận bản ghi thông báo trong bảng notifications (status = PENDING).
 * 4. Gửi email qua EmailSender với cơ chế thử lại tối đa 3 lần (NOTIFICATION_RETRY_LIMIT = 3) khi gặp lỗi mạng.
 * 5. Lưu vết từng lần thử vào bảng notification_attempts với trạng thái (SUCCESS, RETRY, FAILURE).
 * 6. SMTP lỗi trả FAILED; lỗi DB rollback notification và báo lỗi để Commerce outbox retry. Thanh toán đã commit độc lập.
 */
@Service
public class TransactionalEmailServiceImpl implements TransactionalEmailService {

    private static final Logger log = LoggerFactory.getLogger(TransactionalEmailServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final EmailSender emailSender;
    private final TransactionalEmailTemplateBuilder templateBuilder;
    private final NotificationProperties properties;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TransactionalEmailServiceImpl(NotificationRepository notificationRepository,
                                         NotificationAttemptRepository attemptRepository,
                                         EmailSender emailSender,
                                         TransactionalEmailTemplateBuilder templateBuilder,
                                         NotificationProperties properties, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
        this.emailSender = emailSender;
        this.templateBuilder = templateBuilder;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * Gửi email giao dịch bất đồng bộ (Asynchronous) qua thread pool của Spring (@Async).
     *
     * Luồng xử lý:
     * - Chạy trên thread riêng và mở transaction cho cả khóa event/lưu DB; worker bền vững dùng endpoint đồng bộ.
     * - Bắt mọi lỗi để tuân thủ nguyên tắc cô lập lỗi (§5.2 SRS).
     */
    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<NotificationResponse> sendOrderTransactionalEmail(OrderEmailRequest request) {
        log.info("Bắt đầu xử lý gửi email giao dịch bất đồng bộ cho đơn hàng #{} ({})",
                request.orderNo(), request.eventType());
        try {
            NotificationResponse response = processOrderTransactionalEmail(request);
            return CompletableFuture.completedFuture(response);
        } catch (Exception ex) {
            log.error("Lỗi ngoài dự kiến trong tiến trình gửi email bất đồng bộ đơn hàng #{}: {}",
                    request.orderNo(), ex.getMessage(), ex);
            return CompletableFuture.completedFuture(new NotificationResponse(
                    null,
                    request.userId(),
                    NotificationChannel.EMAIL,
                    NotificationStatus.FAILED,
                    0,
                    "Lỗi hệ thống ngoài dự kiến: " + ex.getMessage(),
                    null
            ));
        }
    }

    /**
     * Thực hiện gửi email giao dịch với đầy đủ logic kiểm tra Idempotent, Retry và lưu vết.
     *
     * Luồng xử lý:
     * 1. Kiểm tra tính Idempotent: Nếu eventId đã có thông báo SENT, trả về ngay kết quả trước đó.
     * 2. Kiểm tra cờ cấu hình EMAIL_NOTIFICATION_ENABLED: Nếu false, ghi log và bỏ qua gửi SMTP.
     * 3. Khởi tạo Notification (status = PENDING).
     * 4. Vòng lặp thử lại tối đa retryLimit lần (mặc định 3 lần):
     *    - Gọi emailSender.sendEmail.
     *    - Thành công: Ghi attempt SUCCESS, cập nhật notification SENT, thoát vòng lặp.
     *    - Lỗi mạng tạm thời (EmailDeliveryException): Ghi attempt RETRY/FAILURE, tiếp tục thử lại nếu chưa hết số lần.
     * 5. Nếu kiệt số lần thử mà vẫn lỗi: Cập nhật notification FAILED.
     * 6. SMTP lỗi trả FAILED; lỗi DB truyền lên để rollback và giữ outbox nguồn.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationResponse processOrderTransactionalEmail(OrderEmailRequest request) {
        // Khóa event bằng PostgreSQL để nhiều worker không cùng gửi email.
        // Process chết sau SMTP trước commit vẫn có thể gửi lại; SMTP không có exactly-once.
        Notification previous = null;
        // 1. Kiểm tra tính Idempotent khi có eventId
        if (request.eventId() != null && !request.eventId().isBlank()) {
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, "email:" + request.eventId());
                    statement.execute();
                }
                return null;
            });
            Optional<Notification> existingOpt = notificationRepository.findByEventId(request.eventId());
            if (existingOpt.isPresent()) {
                Notification existing = existingOpt.get();
                previous = existing;
                if (existing.getStatus() == NotificationStatus.SENT) {
                    log.info("Sự kiện {} đã được gửi email thành công trước đó (Idempotent), bỏ qua gửi lặp",
                            request.eventId());
                    return new NotificationResponse(
                            existing.getId(),
                            existing.getUserId(),
                            existing.getChannel(),
                            existing.getStatus(),
                            1,
                            "Email đã được gửi thành công trước đó (Idempotent)",
                            existing.getSentAt()
                    );
                }
            }
        }

        // 2. Soạn nội dung email
        String subject = templateBuilder.buildSubject(request);
        String bodyHtml = templateBuilder.buildHtmlContent(request);
        String bodyText = templateBuilder.buildPlainTextContent(request);

        // 3. Khởi tạo và lưu Notification entity ở trạng thái PENDING
        UUID notificationId = previous == null ? UUID.randomUUID() : previous.getId();
        Notification notification = previous != null ? previous : new Notification(
                notificationId,
                request.userId(),
                request.eventId(),
                NotificationChannel.EMAIL,
                subject,
                bodyText,
                NotificationStatus.PENDING,
                null,
                Instant.now()
        );
        // Retry FAILED/PENDING tái dùng bản ghi/eventId, không tạo thêm notification.
        notification = notificationRepository.save(notification);

        // 4. Kiểm tra cấu hình bật/tắt gửi email (SRS §2.6 EMAIL_NOTIFICATION_ENABLED)
        if (!properties.isEmailEnabled()) {
            // Tắt SMTP giữ PENDING để outbox retry sau khi bật lại, không ghi SENT giả.
            notification.setStatus(NotificationStatus.PENDING);
            notificationRepository.save(notification);
            return new NotificationResponse(
                    notification.getId(),
                    notification.getUserId(),
                    notification.getChannel(),
                    NotificationStatus.PENDING,
                    0,
                    "Email notification đã được tắt theo cấu hình hệ thống",
                    notification.getSentAt()
            );
        }

        // 5. Vòng lặp gửi email và tự động thử lại tối đa NOTIFICATION_RETRY_LIMIT lần
        int maxRetries = Math.max(1, properties.getRetryLimit());
        int offset = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(notification.getId()).stream()
                .mapToInt(NotificationAttempt::getAttemptNo).max().orElse(0);
        boolean isSentSuccess = false;
        String lastErrorMessage = null;
        int totalAttemptsMade = 0;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            totalAttemptsMade = attempt;
            log.info("Thực hiện gửi email đơn hàng #{} (Lần thử {}/{})", request.orderNo(), attempt, maxRetries);

            try {
                emailSender.sendEmail(
                        properties.getSenderFrom(),
                        request.recipientEmail(),
                        subject,
                        bodyHtml,
                        bodyText
                );

                // Gửi thành công
                isSentSuccess = true;
                NotificationAttempt attemptRecord = new NotificationAttempt(
                        UUID.randomUUID(),
                        notification,
                        offset + attempt,
                        AttemptResult.SUCCESS,
                        null,
                        Instant.now()
                );
                attemptRepository.save(attemptRecord);
                log.info("Gửi email thành công cho đơn hàng #{} ở lần thử {}", request.orderNo(), attempt);
                break;

            } catch (EmailDeliveryException ex) {
                lastErrorMessage = ex.getMessage();
                boolean hasMoreRetries = attempt < maxRetries;
                AttemptResult attemptResult = hasMoreRetries ? AttemptResult.RETRY : AttemptResult.FAILURE;

                log.warn("Lỗi gửi email cho đơn hàng #{} ở lần thử {}: {} (kết quả: {})",
                        request.orderNo(), attempt, ex.getMessage(), attemptResult);

                NotificationAttempt attemptRecord = new NotificationAttempt(
                        UUID.randomUUID(),
                        notification,
                        offset + attempt,
                        attemptResult,
                        ex.getMessage(),
                        Instant.now()
                );
                attemptRepository.save(attemptRecord);

                if (hasMoreRetries) {
                    try {
                        // Tạm dừng 100ms trước khi thử lại để tránh dồn dập
                        Thread.sleep(100L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Tiến trình retry bị ngắt quãng: {}", ie.getMessage());
                        break;
                    }
                }
            } catch (Exception ex) {
                // Lỗi nghiêm trọng khác
                lastErrorMessage = ex.getMessage();
                log.error("Lỗi không mong muốn khi gửi email cho đơn hàng #{}: {}",
                        request.orderNo(), ex.getMessage(), ex);

                NotificationAttempt attemptRecord = new NotificationAttempt(
                        UUID.randomUUID(),
                        notification,
                        offset + attempt,
                        AttemptResult.FAILURE,
                        ex.getMessage(),
                        Instant.now()
                );
                attemptRepository.save(attemptRecord);
                break;
            }
        }

        // 6. Cập nhật trạng thái cuối cùng của Notification
        if (isSentSuccess) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
            notificationRepository.save(notification);

            return new NotificationResponse(
                    notification.getId(),
                    notification.getUserId(),
                    notification.getChannel(),
                    NotificationStatus.SENT,
                    totalAttemptsMade,
                    "Gửi email giao dịch thành công",
                    notification.getSentAt()
            );
        } else {
            notification.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(notification);

            log.error("Gửi email giao dịch đơn hàng #{} thất bại sau {} lần thử: {}",
                    request.orderNo(), totalAttemptsMade, lastErrorMessage);

            // Cô lập lỗi (§5.2 SRS): Không ném lỗi ra ngoài, trả về phản hồi FAILED an toàn
            return new NotificationResponse(
                    notification.getId(),
                    notification.getUserId(),
                    notification.getChannel(),
                    NotificationStatus.FAILED,
                    totalAttemptsMade,
                    "Không thể gửi email giao dịch sau " + totalAttemptsMade + " lần thử: " + lastErrorMessage,
                    null
            );
        }
    }
}
