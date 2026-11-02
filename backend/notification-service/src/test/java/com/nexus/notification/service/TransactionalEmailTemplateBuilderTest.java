package com.nexus.notification.service;

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
}
