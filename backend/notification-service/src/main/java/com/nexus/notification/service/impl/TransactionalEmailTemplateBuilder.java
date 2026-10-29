package com.nexus.notification.service.impl;

import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.dto.OrderItemEmailDto;
import com.nexus.notification.enums.OrderEmailType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Bộ tạo mẫu nội dung email giao dịch thương mại điện tử (SRS §3.2.3).
 *
 * Chức năng:
 * - Soạn tiêu đề và thân thư (HTML & Plain-Text) cho 3 sự kiện chính:
 *   1. OrderCreated: Đặt hàng thành công, hướng dẫn thanh toán.
 *   2. OrderPaid: Hóa đơn điện tử, thông báo xác nhận thanh toán và trừ kho chuẩn bị giao hàng.
 *   3. OrderPaymentFailed: Thông báo thanh toán thất bại kèm lý do và hạn chót giữ kho 30 phút.
 *
 * Quy tắc:
 * - Định dạng số tiền theo tiền tệ Việt Nam (VND).
 * - Sử dụng HTML responsive, tương thích với hầu hết các trình đọc email.
 */
@Component
public class TransactionalEmailTemplateBuilder {

    private final NumberFormat currencyFormatter;

    public TransactionalEmailTemplateBuilder() {
        this.currencyFormatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
    }

    /**
     * Tạo tiêu đề thư tương ứng với loại sự kiện.
     */
    public String buildSubject(OrderEmailRequest request) {
        String orderNo = request.orderNo();
        if (request.eventType() == OrderEmailType.ORDER_PAID) {
            return "[Nexus Shop] Hóa đơn điện tử & Xác nhận thanh toán thành công - Đơn hàng #" + orderNo;
        } else if (request.eventType() == OrderEmailType.ORDER_PAYMENT_FAILED) {
            return "[Nexus Shop] Thông báo: Thanh toán đơn hàng #" + orderNo + " không thành công";
        } else {
            return "[Nexus Shop] Xác nhận đặt hàng thành công - Đơn hàng #" + orderNo;
        }
    }

    /**
     * Tạo thân thư HTML chi tiết.
     */
    public String buildHtmlContent(OrderEmailRequest request) {
        String recipient = request.recipientName() != null ? request.recipientName() : "Quý khách";
        String formattedTotal = formatCurrency(request.totalAmount());
        String orderNo = request.orderNo();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        sb.append("<style>");
        sb.append("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; color: #333; line-height: 1.6; }");
        sb.append(".container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e0e0e0; border-radius: 8px; }");
        sb.append(".header { background-color: #1a73e8; color: #fff; padding: 15px 20px; border-radius: 6px 6px 0 0; text-align: center; }");
        sb.append(".header-danger { background-color: #d93025; }");
        sb.append(".header-success { background-color: #1e8e3e; }");
        sb.append(".content { padding: 20px; }");
        sb.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }");
        sb.append("th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }");
        sb.append("th { background-color: #f8f9fa; }");
        sb.append(".total-row { font-weight: bold; background-color: #f1f3f4; }");
        sb.append(".footer { margin-top: 20px; font-size: 12px; color: #777; text-align: center; }");
        sb.append("</style></head><body>");

        sb.append("<div class=\"container\">");

        // Tiêu đề khối header
        if (request.eventType() == OrderEmailType.ORDER_PAID) {
            sb.append("<div class=\"header header-success\"><h2>HÓA ĐƠN ĐIỆN TỬ & THANH TOÁN THÀNH CÔNG</h2></div>");
        } else if (request.eventType() == OrderEmailType.ORDER_PAYMENT_FAILED) {
            sb.append("<div class=\"header header-danger\"><h2>THANH TOÁN THẤT BẠI</h2></div>");
        } else {
            sb.append("<div class=\"header\"><h2>XÁC NHẬN ĐƠN HÀNG MỚI</h2></div>");
        }

        sb.append("<div class=\"content\">");
        sb.append("<p>Xin chào <strong>").append(escapeHtml(recipient)).append("</strong>,</p>");

        if (request.eventType() == OrderEmailType.ORDER_PAID) {
            sb.append("<p>Hệ thống Nexus Shop xin trân trọng thông báo đơn hàng <strong>#")
                    .append(escapeHtml(orderNo))
                    .append("</strong> của quý khách đã được thanh toán thành công qua cổng Sandbox (")
                    .append(escapeHtml(request.paymentMethod() != null ? request.paymentMethod() : "SANDBOX_GATEWAY"))
                    .append(").</p>");
            sb.append("<p>Hàng hóa đã được chính thức xuất kho (Commit) và chuyển giao cho bộ phận vận chuyển.</p>");
        } else if (request.eventType() == OrderEmailType.ORDER_PAYMENT_FAILED) {
            sb.append("<p>Rất tiếc, giao dịch thanh toán cho đơn hàng <strong>#")
                    .append(escapeHtml(orderNo))
                    .append("</strong> đã không thành công.</p>");
            if (request.failureReason() != null) {
                sb.append("<p style=\"color: #d93025;\"><strong>Lý do: </strong>")
                        .append(escapeHtml(request.failureReason()))
                        .append("</p>");
            }
            sb.append("<p><em>Lưu ý quan trọng:</em> Hàng trong kho chỉ được tạm giữ tối đa <strong>30 phút</strong> kể từ khi đặt. ")
                    .append("Vui lòng thực hiện thanh toán lại trước khi thời hạn giữ kho kết thúc.</p>");
        } else {
            sb.append("<p>Cảm ơn quý khách đã đặt hàng tại Nexus Shop! Đơn hàng <strong>#")
                    .append(escapeHtml(orderNo))
                    .append("</strong> đã được khởi tạo thành công và đang chờ quý khách hoàn tất thanh toán.</p>");
        }

        // Bảng mặt hàng
        if (request.items() != null && !request.items().isEmpty()) {
            sb.append("<h3>Chi tiết đơn hàng:</h3>");
            sb.append("<table>");
            sb.append("<thead><tr><th>Sản phẩm</th><th>Mã SKU</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>");
            sb.append("<tbody>");
            for (OrderItemEmailDto item : request.items()) {
                sb.append("<tr>")
                        .append("<td>").append(escapeHtml(item.productName())).append("</td>")
                        .append("<td>").append(escapeHtml(item.skuCode())).append("</td>")
                        .append("<td style=\"text-align:center;\">").append(item.quantity()).append("</td>")
                        .append("<td style=\"text-align:right;\">").append(formatCurrency(item.unitPrice())).append("</td>")
                        .append("<td style=\"text-align:right;\">").append(formatCurrency(item.calculateSubtotal())).append("</td>")
                        .append("</tr>");
            }
            sb.append("<tr class=\"total-row\"><td colspan=\"4\" style=\"text-align:right;\">Tổng cộng:</td>")
                    .append("<td style=\"text-align:right;\">").append(formattedTotal).append(" VND</td></tr>");
            sb.append("</tbody></table>");
        } else {
            sb.append("<p><strong>Tổng tiền: </strong>").append(formattedTotal).append(" VND</p>");
        }

        sb.append("<p>Nếu quý khách cần hỗ trợ thêm, xin vui lòng phản hồi email này hoặc liên hệ hotline chăm sóc khách hàng.</p>");
        sb.append("</div>");

        sb.append("<div class=\"footer\">");
        sb.append("<p>&copy; 2026 Nexus E-Commerce Platform. Thư được gửi tự động, vui lòng không trả lời thư này.</p>");
        sb.append("</div></div></body></html>");

        return sb.toString();
    }

    /**
     * Tạo thân thư Plain Text dự phòng cho các client không hỗ trợ HTML.
     */
    public String buildPlainTextContent(OrderEmailRequest request) {
        String recipient = request.recipientName() != null ? request.recipientName() : "Quý khách";
        String formattedTotal = formatCurrency(request.totalAmount());
        String orderNo = request.orderNo();

        StringBuilder sb = new StringBuilder();
        sb.append("Kính chào ").append(recipient).append(",\n\n");

        if (request.eventType() == OrderEmailType.ORDER_PAID) {
            sb.append("Nexus Shop xác nhận đơn hàng #").append(orderNo).append(" đã thanh toán thành công.\n");
            sb.append("Phương thức thanh toán: ").append(request.paymentMethod() != null ? request.paymentMethod() : "Sandbox").append("\n");
            sb.append("Tồn kho đã được xác nhận xuất kho và chuyển sang trạng thái chuẩn bị giao hàng.\n\n");
        } else if (request.eventType() == OrderEmailType.ORDER_PAYMENT_FAILED) {
            sb.append("Thông báo: Giao dịch thanh toán cho đơn hàng #").append(orderNo).append(" đã thất bại.\n");
            if (request.failureReason() != null) {
                sb.append("Lý do: ").append(request.failureReason()).append("\n");
            }
            sb.append("Vui lòng thanh toán lại trước khi hết thời hạn giữ kho 30 phút.\n\n");
        } else {
            sb.append("Nexus Shop xác nhận quý khách đã đặt hàng thành công đơn hàng #").append(orderNo).append(".\n\n");
        }

        sb.append("Tổng tiền: ").append(formattedTotal).append(" VND\n\n");
        sb.append("Trân trọng cảm ơn,\nNexus Platform Team\n");

        return sb.toString();
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return currencyFormatter.format(amount);
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
