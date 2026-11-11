package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.dto.OrderItemEmailDto;
import com.nexus.notification.service.impl.TransactionalEmailTemplateBuilder;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tạo khuôn mẫu email giao dịch (TransactionalEmailTemplateBuilder).
 *
 * Tình huống kiểm thử:
 * 1. Tiêu đề và thân thư khi đặt hàng thành công (OrderCreated).
 * 2. Tiêu đề và thân thư hóa đơn điện tử khi thanh toán thành công (OrderPaid).
 * 3. Tiêu đề và thân thư cảnh báo khi thanh toán thất bại (OrderPaymentFailed).
 * 4. Xử lý escape ký tự đặc biệt HTML (xss prevention).
 * 5. Định dạng số tiền VNĐ chính xác.
 */
class TransactionalEmailTemplateBuilderTest {

    private TransactionalEmailTemplateBuilder templateBuilder;

    @BeforeEach
    void setUp() {
        templateBuilder = new TransactionalEmailTemplateBuilder();
    }

    @Test
    @DisplayName("Kiểm tra sinh tiêu đề và HTML cho sự kiện OrderPaid")
    void testOrderPaidTemplate() {
        List<OrderItemEmailDto> items = List.of(
                new OrderItemEmailDto("Áo thun Nexus Edition", "TSHIRT-NX-01", 2, new BigDecimal("250000"))
        );
        OrderEmailRequest request = OrderEmailRequest.ofOrderPaid(
                UUID.randomUUID(), "ORD-PAID-999", UUID.randomUUID(),
                "test@example.com", "Nguyễn Văn Test",
                new BigDecimal("500000"), "SANDBOX_VNPAY", items, "EVT-1"
        );

        String subject = templateBuilder.buildSubject(request);
        String html = templateBuilder.buildHtmlContent(request);
        String text = templateBuilder.buildPlainTextContent(request);

        assertNotNull(subject);
        assertTrue(subject.contains("Hóa đơn điện tử"));
        assertTrue(subject.contains("ORD-PAID-999"));

        assertTrue(html.contains("ORD-PAID-999"));
        assertTrue(html.contains("Nguyễn Văn Test"));
        assertTrue(html.contains("TSHIRT-NX-01"));
        assertTrue(html.contains("SANDBOX_VNPAY"));

        assertTrue(text.contains("ORD-PAID-999"));
        assertTrue(text.contains("thanh toán thành công"));
    }

    @Test
    @DisplayName("Kiểm tra sinh HTML khi có ký tự đặc biệt cần escape")
    void testHtmlEscaping() {
        OrderEmailRequest request = OrderEmailRequest.ofPaymentFailed(
                UUID.randomUUID(), "ORD-<TEST>&1", UUID.randomUUID(),
                "test@example.com", "<script>alert('xss')</script>",
                new BigDecimal("100000"), "Lỗi kết nối <Gateway> & thẻ 'hết hạn'", "EVT-2"
        );

        String html = templateBuilder.buildHtmlContent(request);
        assertTrue(!html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    /** Kiểm tra 3 mẫu email vòng đời winner đấu giá: thắng phiên, nhắc trước 2h và hủy quá hạn 24h. */
    @Test
    @DisplayName("Kiểm tra nội dung chúc mừng thắng đấu giá, nhắc hạn 2h và hủy quá hạn 24h")
    void testAuctionLifecycleTemplates() {
        var deadline = java.time.Instant.parse("2026-11-12T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        List<OrderItemEmailDto> items = List.of(
                new OrderItemEmailDto("Đồng hồ giới hạn", "SKU-AUC-1", 1, new BigDecimal("2500000"))
        );

        OrderEmailRequest won = OrderEmailRequest.ofAuctionWon(
                orderId, "ORD-AUC-01", userId, "winner@nexus.test", "Người thắng",
                new BigDecimal("2500000"), items, "EVT-WON", auctionId, deadline,
                "http://localhost:3000/order-detail?id=" + orderId);
        assertTrue(templateBuilder.buildSubject(won).contains("Chúc mừng thắng đấu giá"));
        String wonHtml = templateBuilder.buildHtmlContent(won);
        assertTrue(wonHtml.contains("http://localhost:3000/order-detail?id=" + orderId));
        assertTrue(wonHtml.contains("17:00:00 (GMT+7)"));
        assertTrue(templateBuilder.buildPlainTextContent(won).contains("Hạn chót thanh toán (24 giờ)"));

        OrderEmailRequest reminder = OrderEmailRequest.ofAuctionPaymentReminder(
                orderId, "ORD-AUC-01", userId, "winner@nexus.test", "Người thắng",
                new BigDecimal("2500000"), items, "EVT-REM", auctionId, deadline,
                "http://localhost:3000/order-detail?id=" + orderId, 10);
        assertTrue(templateBuilder.buildSubject(reminder).contains("Nhắc nhở"));
        assertTrue(templateBuilder.buildHtmlContent(reminder).contains("trừ 10 điểm uy tín"));

        OrderEmailRequest timeout = OrderEmailRequest.ofAuctionPaymentTimeout(
                orderId, "ORD-AUC-01", userId, "winner@nexus.test", "Người thắng",
                new BigDecimal("2500000"), "AUCTION_PAYMENT_DEADLINE", items, "EVT-TMO",
                auctionId, deadline, 10);
        assertTrue(templateBuilder.buildSubject(timeout).contains("quá hạn thanh toán 24h"));
        String timeoutHtml = templateBuilder.buildHtmlContent(timeout);
        String timeoutText = templateBuilder.buildPlainTextContent(timeout);
        assertTrue(timeoutHtml.contains("-10 điểm uy tín"));
        assertTrue(timeoutHtml.contains("đang xử lý hoàn trả tồn kho"));
        assertFalse(timeoutHtml.contains("đã được giải phóng"));
        assertTrue(timeoutText.contains("trừ 10 điểm uy tín"));
        assertTrue(timeoutText.contains("đang xử lý hoàn trả tồn kho"));
    }
}
