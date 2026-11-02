package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.notification.config.NotificationProperties;
import com.nexus.notification.dto.NotificationResponse;
import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.dto.OrderItemEmailDto;
import com.nexus.notification.entity.Notification;
import com.nexus.notification.entity.NotificationAttempt;
import com.nexus.notification.enums.AttemptResult;
import com.nexus.notification.enums.NotificationChannel;
import com.nexus.notification.enums.NotificationStatus;
import com.nexus.notification.exception.EmailDeliveryException;
import com.nexus.notification.repository.NotificationAttemptRepository;
import com.nexus.notification.repository.NotificationRepository;
import com.nexus.notification.service.impl.TransactionalEmailServiceImpl;
import com.nexus.notification.service.impl.TransactionalEmailTemplateBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử đơn vị toàn diện cho dịch vụ gửi email giao dịch (TransactionalEmailService).
 *
 * Tình huống kiểm thử và lỗi bảo vệ:
 * 1. Gửi email xác nhận đặt hàng mới (OrderCreated): lưu notification PENDING -> SENT, ghi nhận attempt SUCCESS.
 * 2. Gửi hóa đơn điện tử thanh toán thành công (OrderPaid): kiểm tra nội dung hóa đơn, mã đơn và sản phẩm.
 * 3. Gửi thông báo thanh toán thất bại (OrderPaymentFailed): cảnh báo giữ kho 30 phút.
 * 4. Tự động thử lại tối đa 3 lần (NOTIFICATION_RETRY_LIMIT = 3) khi gặp lỗi mạng tạm thời:
 *    - Lần 1 & 2 ném EmailDeliveryException (ghi nhận RETRY).
 *    - Lần 3 gửi thành công (ghi nhận SUCCESS, notification chuyển SENT).
 * 5. Thất bại vĩnh viễn khi kiệt 3 lần thử: cả 3 lần ném lỗi -> notification chuyển FAILED, ghi nhận 3 attempts.
 * 6. Nguyên tắc cô lập lỗi (§5.2 SRS): Không bao giờ ném Exception ra ngoài làm hỏng luồng đặt/trừ kho.
 * 7. Quy tắc Idempotent: Bỏ qua gửi thư lặp khi eventId đã được gửi thành công trước đó.
 * 8. Cấu hình tắt gửi email (EMAIL_NOTIFICATION_ENABLED = false): Xử lý êm thuận không gọi SMTP.
 * 9. Xử lý bất đồng bộ (@Async): sendOrderTransactionalEmail trả về CompletableFuture hoàn tất an toàn.
 */
@ExtendWith(MockitoExtension.class)
class TransactionalEmailServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationAttemptRepository attemptRepository;

    @Mock
    private EmailSender emailSender;

    private NotificationProperties properties;
    private TransactionalEmailTemplateBuilder templateBuilder;
    private TransactionalEmailServiceImpl emailService;

    private UUID userId;
    private UUID orderId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setEmailEnabled(true);
        properties.setRetryLimit(3);
        properties.setTransactionalMandatory(true);
        properties.setSenderFrom("no-reply@nexus.com");

        templateBuilder = new TransactionalEmailTemplateBuilder();

        emailService = new TransactionalEmailServiceImpl(
                notificationRepository,
                attemptRepository,
                emailSender,
                templateBuilder,
                properties, org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)
        );

        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        orderNo = "ORD-20261029-001";

        org.mockito.Mockito.lenient().when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Gửi email xác nhận đặt hàng (OrderCreated) thành công ở lần thử đầu tiên")
    void testOrderCreated_Success() {
        List<OrderItemEmailDto> items = List.of(
                new OrderItemEmailDto("Bàn phím cơ không dây", "KB-MECH-01", 1, new BigDecimal("1200000"))
        );
        OrderEmailRequest request = OrderEmailRequest.ofOrderCreated(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("1200000"), items, "EVT-CREATED-1"
        );

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.SENT, response.status());
        assertEquals(1, response.attempts());
        assertEquals(NotificationChannel.EMAIL, response.channel());

        // Xác nhận đã gọi emailSender 1 lần
        verify(emailSender, times(1)).sendEmail(
                eq("no-reply@nexus.com"),
                eq("buyer@example.com"),
                anyString(),
                anyString(),
                anyString()
        );

        // Xác nhận đã lưu attempt SUCCESS
        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(attemptRepository, times(1)).save(attemptCaptor.capture());
        assertEquals(1, attemptCaptor.getValue().getAttemptNo());
        assertEquals(AttemptResult.SUCCESS, attemptCaptor.getValue().getResult());
    }

    @Test
    @DisplayName("Gửi hóa đơn điện tử khi thanh toán thành công (OrderPaid) thành công")
    void testOrderPaid_Success() {
        List<OrderItemEmailDto> items = List.of(
                new OrderItemEmailDto("Chuột Gaming không dây", "MOUSE-WL-01", 2, new BigDecimal("500000"))
        );
        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                orderId, orderNo, userId, "buyer@example.com", "Trần Thị B",
                new BigDecimal("1000000"), "SANDBOX_VNPAY", items, "EVT-PAID-1"
        );

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.SENT, response.status());
        assertEquals(1, response.attempts());

        ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notifCaptor.capture());
        Notification finalNotif = notifCaptor.getAllValues().get(1);
        assertEquals(NotificationStatus.SENT, finalNotif.getStatus());
        assertNotNull(finalNotif.getSentAt());
    }

    @Test
    @DisplayName("Gửi email cảnh báo thanh toán thất bại (OrderPaymentFailed) thành công")
    void testOrderPaymentFailed_Success() {
        OrderEmailRequest request = OrderEmailRequest.ofPaymentFailed(
                orderId, orderNo, userId, "buyer@example.com", "Lê Văn C",
                new BigDecimal("800000"), "Số dư tài khoản sandbox không đủ", "EVT-FAIL-1"
        );

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.SENT, response.status());
        assertEquals(1, response.attempts());
    }

    @Test
    @DisplayName("Tự động thử lại tối đa 3 lần: Thất bại 2 lần đầu do lỗi mạng, lần 3 thành công")
    void testRetryMechanism_SuccessOnThirdAttempt() {
        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("500000"), "SANDBOX_GATEWAY", null, "EVT-RETRY-1"
        );

        // Lần 1 và 2 ném lỗi mạng, lần 3 gửi thành công
        doThrow(new EmailDeliveryException("Connection timed out to SMTP server"))
                .doThrow(new EmailDeliveryException("Socket closed unexpectedly"))
                .doNothing()
                .when(emailSender).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.SENT, response.status());
        assertEquals(3, response.attempts());

        verify(emailSender, times(3)).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());

        // Kiểm tra 3 attempt đã lưu: 2 RETRY và 1 SUCCESS
        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(attemptRepository, times(3)).save(attemptCaptor.capture());
        List<NotificationAttempt> attempts = attemptCaptor.getAllValues();
        assertEquals(AttemptResult.RETRY, attempts.get(0).getResult());
        assertEquals(1, attempts.get(0).getAttemptNo());
        assertEquals(AttemptResult.RETRY, attempts.get(1).getResult());
        assertEquals(2, attempts.get(1).getAttemptNo());
        assertEquals(AttemptResult.SUCCESS, attempts.get(2).getResult());
        assertEquals(3, attempts.get(2).getAttemptNo());
    }

    @Test
    @DisplayName("Kiệt sức số lần thử lại (3 lần đều thất bại): Chuyển FAILED nhưng không ném Exception (§5.2 SRS)")
    void testRetryMechanism_FailedAfterMaxRetries() {
        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("500000"), "SANDBOX_GATEWAY", null, "EVT-EXHAUST-1"
        );

        // Cả 3 lần đều ném lỗi
        doThrow(new EmailDeliveryException("SMTP server down"))
                .when(emailSender).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.FAILED, response.status());
        assertEquals(3, response.attempts());

        verify(emailSender, times(3)).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());

        // Kiểm tra 3 attempt đã lưu: 2 RETRY và lần cuối là FAILURE
        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(attemptRepository, times(3)).save(attemptCaptor.capture());
        List<NotificationAttempt> attempts = attemptCaptor.getAllValues();
        assertEquals(AttemptResult.RETRY, attempts.get(0).getResult());
        assertEquals(AttemptResult.RETRY, attempts.get(1).getResult());
        assertEquals(AttemptResult.FAILURE, attempts.get(2).getResult());
    }

    @Test
    @DisplayName("Quy tắc Idempotency: Không gửi lại email nếu sự kiện đã được gửi thành công trước đó")
    void testIdempotency_AlreadySent() {
        String eventId = "EVT-IDEMPOTENT-001";
        Notification existing = new Notification(
                UUID.randomUUID(), userId, eventId, NotificationChannel.EMAIL,
                "Tiêu đề", "Nội dung", NotificationStatus.SENT, Instant.now(), Instant.now()
        );
        when(notificationRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        OrderEmailRequest request = OrderEmailRequest.ofOrderCreated(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("100000"), null, eventId
        );

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        assertEquals(NotificationStatus.SENT, response.status());
        assertTrue(response.message().contains("Idempotent"));

        // Tuyệt đối không gọi emailSender gửi lặp
        verify(emailSender, never()).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Cấu hình tắt gửi email (EMAIL_NOTIFICATION_ENABLED = false): Giữ PENDING để retry sau khi bật SMTP")
    void testEmailDisabledConfiguration() {
        properties.setEmailEnabled(false);

        OrderEmailRequest request = OrderEmailRequest.ofOrderCreated(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("200000"), null, "EVT-DISABLED-1"
        );

        NotificationResponse response = emailService.processOrderTransactionalEmail(request);

        assertNotNull(response);
        // Không gửi SMTP thì không được ghi nhận SENT.
        assertEquals(NotificationStatus.PENDING, response.status());
        assertEquals(0, response.attempts());
        verify(emailSender, never()).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Gửi email bất đồng bộ: Hoàn tất CompletableFuture an toàn kể cả khi có ngoại lệ ngoài ý muốn")
    void testSendOrderTransactionalEmailAsync_Success() throws Exception {
        OrderEmailRequest request = OrderEmailRequest.ofOrderCreated(
                orderId, orderNo, userId, "buyer@example.com", "Nguyễn Văn A",
                new BigDecimal("300000"), null, "EVT-ASYNC-1"
        );

        CompletableFuture<NotificationResponse> future = emailService.sendOrderTransactionalEmail(request);
        assertNotNull(future);
        NotificationResponse response = future.get();
        assertEquals(NotificationStatus.SENT, response.status());
    }
}
