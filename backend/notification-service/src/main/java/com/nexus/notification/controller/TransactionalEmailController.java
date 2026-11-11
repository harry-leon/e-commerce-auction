package com.nexus.notification.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.notification.dto.NotificationResponse;
import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.entity.NotificationAttempt;
import com.nexus.notification.repository.NotificationAttemptRepository;
import com.nexus.notification.repository.NotificationRepository;
import com.nexus.notification.service.TransactionalEmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nexus.common.exception.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tiếp nhận yêu cầu gửi email giao dịch bắt buộc (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Cung cấp API cho Commerce Service và Fulfillment Service kích hoạt gửi email giao dịch:
 *   + Đặt hàng thành công (OrderCreated).
 *   + Thanh toán thành công & Xuất hóa đơn (OrderPaid).
 *   + Thanh toán thất bại & Cảnh báo giữ kho (OrderPaymentFailed).
 * - Cung cấp API tra cứu lịch sử và trạng thái thông báo theo notificationId.
 *
 * Quy tắc:
 * - Chuẩn hóa envelope ApiResponse<T> theo quy chuẩn Project Nexus.
 * - Cô lập lỗi: Trả về kết quả xử lý an toàn không làm gián đoạn luồng đặt hàng.
 */
/** POST dành cho service account; GET giới hạn chủ thông báo/admin, không công khai nội dung email. */
@RestController
@PreAuthorize("hasRole('NOTIFICATION_WRITER')")
@RequestMapping("/api/v1/notifications")
@Tag(name = "Transactional Email", description = "Dịch vụ gửi email giao dịch thương mại điện tử (SRS §3.2.3)")
public class TransactionalEmailController {

    private final TransactionalEmailService emailService;
    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;

    public TransactionalEmailController(TransactionalEmailService emailService,
                                        NotificationRepository notificationRepository,
                                        NotificationAttemptRepository attemptRepository) {
        this.emailService = emailService;
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
    }

    @Operation(summary = "Gửi email giao dịch tổng quát", description = "Tiếp nhận sự kiện đơn hàng và tiến hành gửi email xác nhận")
    @PostMapping("/transactional-email")
    public ResponseEntity<ApiResponse<NotificationResponse>> sendTransactionalEmail(
            @Valid @RequestBody OrderEmailRequest request) {
        NotificationResponse response = emailService.processOrderTransactionalEmail(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Gửi email giao dịch bất đồng bộ", description = "Tiếp nhận sự kiện và xử lý bất đồng bộ, trả về ngay HTTP 202 Accepted")
    @PostMapping("/transactional-email/async")
    public ResponseEntity<ApiResponse<String>> sendTransactionalEmailAsync(
            @Valid @RequestBody OrderEmailRequest request) {
        emailService.sendOrderTransactionalEmail(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("ACCEPTED"));
    }

    @Operation(summary = "Sự kiện Đặt hàng thành công", description = "Gửi email xác nhận đặt hàng thành công cho người mua")
    @PostMapping("/events/order-created")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleOrderCreatedEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        NotificationResponse response = emailService.processOrderTransactionalEmail(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Sự kiện Thanh toán thành công (OrderPaid)", description = "Gửi hóa đơn điện tử và xác nhận xuất kho")
    @PostMapping("/events/order-paid")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleOrderPaidEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        NotificationResponse response = emailService.processOrderTransactionalEmail(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Sự kiện Thanh toán thất bại", description = "Gửi cảnh báo thanh toán thất bại kèm hướng dẫn thanh toán lại")
    @PostMapping("/events/order-payment-failed")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleOrderPaymentFailedEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        NotificationResponse response = emailService.processOrderTransactionalEmail(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Sự kiện Thắng đấu giá (AuctionWon)", description = "Gửi email chúc mừng người thắng kèm link thanh toán và mốc hạn 24 giờ")
    @PostMapping("/events/auction-won")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleAuctionWonEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(emailService.processOrderTransactionalEmail(request)));
    }

    @Operation(summary = "Sự kiện Nhắc hạn thanh toán đấu giá", description = "Gửi cảnh báo nhắc nhở trước khi hết hạn thanh toán 24h")
    @PostMapping("/events/auction-payment-reminder")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleAuctionPaymentReminderEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(emailService.processOrderTransactionalEmail(request)));
    }

    @Operation(summary = "Sự kiện Quá hạn thanh toán đấu giá 24h", description = "Thông báo hủy đơn, giải phóng kho và trừ điểm uy tín")
    @PostMapping("/events/auction-payment-timeout")
    public ResponseEntity<ApiResponse<NotificationResponse>> handleAuctionPaymentTimeoutEvent(
            @Valid @RequestBody OrderEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(emailService.processOrderTransactionalEmail(request)));
    }

    @Operation(summary = "Tra cứu thông báo theo ID", description = "Lấy chi tiết thông báo cùng lịch sử các lần thử gửi (attempts)")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNotificationById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return notificationRepository.findById(id)
                .map(n -> {
                    // Kiểm tra subject trước khi tải attempts để không lộ nội dung/lỗi SMTP của người khác.
                    var authorities = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities();
                    boolean admin = authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                    if (!admin && !n.getUserId().toString().equals(jwt.getSubject())) {
                        throw new ApiException("NOTIFICATION_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy thông báo", null);
                    }
                    List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(n.getId());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("notification", n);
                    data.put("attempts", attempts);
                    return ResponseEntity.ok(ApiResponse.ok(data));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("NOT_FOUND", "Không tìm thấy thông báo với mã " + id)));
    }
}
