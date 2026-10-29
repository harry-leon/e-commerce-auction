package com.nexus.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.notification.config.SecurityConfig;
import com.nexus.notification.dto.NotificationResponse;
import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.dto.OrderItemEmailDto;
import com.nexus.notification.entity.Notification;
import com.nexus.notification.entity.NotificationAttempt;
import com.nexus.notification.enums.AttemptResult;
import com.nexus.notification.enums.NotificationChannel;
import com.nexus.notification.enums.NotificationStatus;
import com.nexus.notification.enums.OrderEmailType;
import com.nexus.notification.repository.NotificationAttemptRepository;
import com.nexus.notification.repository.NotificationRepository;
import com.nexus.notification.service.TransactionalEmailService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử tầng Web REST Controller cho dịch vụ gửi email giao dịch (TransactionalEmailController).
 *
 * Tình huống kiểm thử và lỗi bảo vệ:
 * 1. POST /api/v1/notifications/transactional-email: Tiếp nhận hợp lệ, trả về HTTP 200 OK với ApiResponse chuẩn.
 * 2. POST /api/v1/notifications/transactional-email/async: Tiếp nhận hợp lệ bất đồng bộ, trả về HTTP 202 Accepted.
 * 3. POST /api/v1/notifications/events/order-paid: Gửi hóa đơn thanh toán thành công.
 * 4. POST /api/v1/notifications/events/order-created: Gửi email xác nhận đơn hàng mới.
 * 5. POST /api/v1/notifications/events/order-payment-failed: Gửi cảnh báo thanh toán thất bại.
 * 6. GET /api/v1/notifications/{id}: Tra cứu thông báo thành công kèm danh sách attempts.
 * 7. GET /api/v1/notifications/{id}: Trả về HTTP 404 NOT_FOUND khi mã thông báo không tồn tại.
 * 8. Validation DTO: Chặn gửi email khi thiếu trường bắt buộc (email không hợp lệ, thiếu totalAmount, ...).
 */
@WebMvcTest(TransactionalEmailController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class TransactionalEmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionalEmailService emailService;

    @MockitoBean
    private NotificationRepository notificationRepository;

    @MockitoBean
    private NotificationAttemptRepository attemptRepository;

    @Test
    @DisplayName("POST /api/v1/notifications/transactional-email - Xử lý thành công trả về HTTP 200 OK")
    void testSendTransactionalEmail_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();

        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                orderId, "ORD-12345", userId, "buyer@nexus.com", "Nguyễn Văn A",
                new BigDecimal("500000"), "SANDBOX_VNPAY", null, "EVT-PAID-001"
        );

        NotificationResponse response = new NotificationResponse(
                notificationId, userId, NotificationChannel.EMAIL,
                NotificationStatus.SENT, 1, "Gửi email thành công", Instant.now()
        );

        when(emailService.processOrderTransactionalEmail(any(OrderEmailRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/notifications/transactional-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.attempts").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/transactional-email/async - Tiếp nhận bất đồng bộ trả HTTP 202 Accepted")
    void testSendTransactionalEmailAsync_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        OrderEmailRequest request = OrderEmailRequest.ofOrderCreated(
                orderId, "ORD-99999", userId, "buyer@nexus.com", "Lý Vĩnh Khang",
                new BigDecimal("200000"), null, "EVT-ASYNC-001"
        );

        mockMvc.perform(post("/api/v1/notifications/transactional-email/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value("ACCEPTED"));

        verify(emailService).sendOrderTransactionalEmail(any(OrderEmailRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/events/order-paid - Gửi hóa đơn thanh toán thành công")
    void testHandleOrderPaidEvent_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();

        List<OrderItemEmailDto> items = List.of(
                new OrderItemEmailDto("Chuột Logitech G Pro", "LOGI-GPRO", 1, new BigDecimal("1800000"))
        );
        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                orderId, "ORD-PAID-01", userId, "khang@example.com", "Lý Vĩnh Khang",
                new BigDecimal("1800000"), "SANDBOX_MOCK", items, "EVT-PAID-ORDER"
        );

        NotificationResponse response = new NotificationResponse(
                notifId, userId, NotificationChannel.EMAIL,
                NotificationStatus.SENT, 1, "Đã gửi hóa đơn", Instant.now()
        );
        when(emailService.processOrderTransactionalEmail(any(OrderEmailRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/notifications/events/order-paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/events/order-payment-failed - Gửi cảnh báo thanh toán thất bại")
    void testHandleOrderPaymentFailedEvent_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();

        OrderEmailRequest request = OrderEmailRequest.ofPaymentFailed(
                orderId, "ORD-FAIL-01", userId, "khang@example.com", "Lý Vĩnh Khang",
                new BigDecimal("1800000"), "Thẻ hết hạn", "EVT-FAIL-ORDER"
        );

        NotificationResponse response = new NotificationResponse(
                notifId, userId, NotificationChannel.EMAIL,
                NotificationStatus.SENT, 1, "Đã gửi cảnh báo", Instant.now()
        );
        when(emailService.processOrderTransactionalEmail(any(OrderEmailRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/notifications/events/order-payment-failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} - Tra cứu thông báo thành công cùng lịch sử attempts")
    void testGetNotificationById_Success() throws Exception {
        UUID notifId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Notification notif = new Notification(
                notifId, userId, "EVT-001", NotificationChannel.EMAIL,
                "Hóa đơn đơn hàng #123", "Nội dung", NotificationStatus.SENT, Instant.now(), Instant.now()
        );
        NotificationAttempt attempt = new NotificationAttempt(
                UUID.randomUUID(), notif, 1, AttemptResult.SUCCESS, null, Instant.now()
        );

        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));
        when(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(notifId)).thenReturn(List.of(attempt));

        mockMvc.perform(get("/api/v1/notifications/" + notifId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notification.id").value(notifId.toString()))
                .andExpect(jsonPath("$.data.attempts[0].result").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} - Trả về HTTP 404 NOT_FOUND khi mã thông báo không tồn tại")
    void testGetNotificationById_NotFound() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notificationRepository.findById(notifId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notifications/" + notifId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Validation: Từ chối request khi email người nhận không hợp lệ, trả HTTP 400")
    void testValidation_InvalidEmail() throws Exception {
        String invalidJson = """
                {
                    "eventType": "ORDER_CREATED",
                    "orderId": "11111111-1111-1111-1111-111111111111",
                    "orderNo": "ORD-001",
                    "userId": "22222222-2222-2222-2222-222222222222",
                    "recipientEmail": "not-an-email",
                    "totalAmount": 150000
                }
                """;

        mockMvc.perform(post("/api/v1/notifications/transactional-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
