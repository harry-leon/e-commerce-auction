package com.nexus.notification.service.impl;

import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.dto.OrderItemEmailDto;
import com.nexus.notification.enums.OrderEmailType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Bộ tạo mẫu nội dung email giao dịch và vòng đời đơn thắng đấu giá (SRS §3.2.3 & KH-S2-3).
 * Chức năng:
 * - Soạn tiêu đề, HTML responsive (tương thích client email, escape XSS) và Plain-Text dự phòng.
 * - Hỗ trợ: ORDER_CREATED, ORDER_PAID, ORDER_PAYMENT_FAILED, AUCTION_WON,
 *   AUCTION_PAYMENT_REMINDER (nhắc trước hạn 2h), AUCTION_PAYMENT_TIMEOUT (hủy quá hạn 24h & trừ điểm uy tín).
 */
@Component
public class TransactionalEmailTemplateBuilder {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DEADLINE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss '(GMT+7)'");
    private static final int DEFAULT_PENALTY_SCORE = 10;

    private final NumberFormat currencyFormatter;

    public TransactionalEmailTemplateBuilder() {
        this.currencyFormatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
    }

    /** Tạo tiêu đề thư tương ứng với loại sự kiện đơn hàng / đấu giá. */
    public String buildSubject(OrderEmailRequest request) {
        String orderNo = request.orderNo();
        return switch (request.eventType()) {
            case ORDER_PAID -> "[Nexus Shop] Hóa đơn điện tử & Xác nhận thanh toán thành công - Đơn hàng #" + orderNo;
            case ORDER_PAYMENT_FAILED -> "[Nexus Shop] Thông báo: Thanh toán đơn hàng #" + orderNo + " không thành công";
            case AUCTION_WON -> "[Nexus Shop] Chúc mừng thắng đấu giá! Hoàn tất thanh toán đơn #" + orderNo + " trong 24h";
            case AUCTION_PAYMENT_REMINDER -> "[Nexus Shop] Nhắc nhở: Đơn đấu giá #" + orderNo + " sắp hết hạn thanh toán";
            case AUCTION_PAYMENT_TIMEOUT -> "[Nexus Shop] Đơn đấu giá #" + orderNo + " đã bị hủy do quá hạn thanh toán 24h";
            case ORDER_CREATED -> "[Nexus Shop] Xác nhận đặt hàng thành công - Đơn hàng #" + orderNo;
        };
    }

    /** Tạo thân thư HTML chi tiết kèm liên kết thanh toán an toàn và hạn 24h cho đơn thắng đấu giá. */
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
        sb.append(".header-warning { background-color: #e37400; }");
        sb.append(".header-success { background-color: #1e8e3e; }");
        sb.append(".content { padding: 20px; }");
        sb.append(".cta-btn { display: inline-block; background-color: #1a73e8; color: #fff !important; padding: 12px 22px; text-decoration: none; border-radius: 6px; font-weight: bold; margin: 12px 0; }");
        sb.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }");
        sb.append("th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }");
        sb.append("th { background-color: #f8f9fa; }");
        sb.append(".total-row { font-weight: bold; background-color: #f1f3f4; }");
        sb.append(".footer { margin-top: 20px; font-size: 12px; color: #777; text-align: center; }");
        sb.append("</style></head><body>");

        sb.append("<div class=\"container\">");
        sb.append(headerHtml(request.eventType()));
        sb.append("<div class=\"content\">");
        sb.append("<p>Xin chào <strong>").append(escapeHtml(recipient)).append("</strong>,</p>");
        appendBodyHtml(sb, request);

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

    /** Tạo thân thư Plain Text dự phòng cho các trình đọc email không hỗ trợ HTML. */
    public String buildPlainTextContent(OrderEmailRequest request) {
        String recipient = request.recipientName() != null ? request.recipientName() : "Quý khách";
        String formattedTotal = formatCurrency(request.totalAmount());
        String orderNo = request.orderNo();
        String deadlineText = formatDeadline(request.paymentDeadline());
        int penalty = resolvePenalty(request.penaltyScore());

        StringBuilder sb = new StringBuilder();
        sb.append("Kính chào ").append(recipient).append(",\n\n");

        switch (request.eventType()) {
            case ORDER_PAID -> {
                sb.append("Nexus Shop xác nhận đơn hàng #").append(orderNo).append(" đã thanh toán thành công.\n");
                sb.append("Phương thức thanh toán: ")
                        .append(request.paymentMethod() != null ? request.paymentMethod() : "Sandbox").append("\n");
                sb.append("Theo dõi tiến độ chuẩn bị và giao hàng trong lịch sử đơn hàng.\n\n");
            }
            case ORDER_PAYMENT_FAILED -> {
                sb.append("Thông báo: Giao dịch thanh toán cho đơn hàng #").append(orderNo).append(" đã thất bại.\n");
                if (request.failureReason() != null) {
                    sb.append("Lý do: ").append(request.failureReason()).append("\n");
                }
                sb.append("Đơn thanh toán thất bại đã được hủy; vui lòng tạo đơn mới nếu muốn mua lại.\n\n");
            }
            case AUCTION_WON -> {
                sb.append("Chúc mừng quý khách đã chiến thắng phiên đấu giá! Đơn hàng #")
                        .append(orderNo).append(" đã được khởi tạo.\n");
                if (deadlineText != null) {
                    sb.append("Hạn chót thanh toán (24 giờ): ").append(deadlineText).append("\n");
                }
                if (request.paymentLink() != null && !request.paymentLink().isBlank()) {
                    sb.append("Liên kết thanh toán ngay: ").append(request.paymentLink()).append("\n");
                }
                sb.append("\n");
            }
            case AUCTION_PAYMENT_REMINDER -> {
                sb.append("Nhắc nhở: Đơn thắng đấu giá #").append(orderNo)
                        .append(" sắp hết hạn thanh toán trong khoảng 2 giờ tới.\n");
                if (deadlineText != null) {
                    sb.append("Thời hạn thanh toán: ").append(deadlineText).append("\n");
                }
                if (request.paymentLink() != null && !request.paymentLink().isBlank()) {
                    sb.append("Thanh toán ngay tại: ").append(request.paymentLink()).append("\n");
                }
                sb.append("Lưu ý: Nếu quá hạn 24 giờ, đơn hàng sẽ bị hủy và tài khoản bị trừ ")
                        .append(penalty).append(" điểm uy tín.\n\n");
            }
            case AUCTION_PAYMENT_TIMEOUT -> {
                // Tác vụ giải phóng kho và trừ uy tín chạy bất đồng bộ ở outbox nên thông báo ghi nhận trạng thái đang xử lý.
                sb.append("Đơn thắng đấu giá #").append(orderNo)
                        .append(" đã quá hạn thanh toán 24 giờ nên đã được tự động hủy.\n");
                sb.append("Hệ thống đang xử lý hoàn trả tồn kho cho người bán và áp dụng trừ ").append(penalty)
                        .append(" điểm uy tín theo quy định quá hạn thanh toán đấu giá.\n\n");
            }
            case ORDER_CREATED -> sb.append("Nexus Shop xác nhận quý khách đã đặt hàng thành công đơn hàng #")
                    .append(orderNo).append(".\n\n");
        }

        sb.append("Tổng tiền: ").append(formattedTotal).append(" VND\n\n");
        sb.append("Trân trọng cảm ơn,\nNexus Platform Team\n");
        return sb.toString();
    }

    private String headerHtml(OrderEmailType type) {
        return switch (type) {
            case ORDER_PAID -> "<div class=\"header header-success\"><h2>HÓA ĐƠN ĐIỆN TỬ & THANH TOÁN THÀNH CÔNG</h2></div>";
            case ORDER_PAYMENT_FAILED -> "<div class=\"header header-danger\"><h2>THANH TOÁN THẤT BẠI</h2></div>";
            case AUCTION_WON -> "<div class=\"header header-success\"><h2>CHÚC MỪNG THẮNG ĐẤU GIÁ</h2></div>";
            case AUCTION_PAYMENT_REMINDER -> "<div class=\"header header-warning\"><h2>NHẮC NHỞ SẮP HẾT HẠN THANH TOÁN</h2></div>";
            case AUCTION_PAYMENT_TIMEOUT -> "<div class=\"header header-danger\"><h2>ĐƠN ĐẤU GIÁ QUÁ HẠN THANH TOÁN 24H</h2></div>";
            case ORDER_CREATED -> "<div class=\"header\"><h2>XÁC NHẬN ĐƠN HÀNG MỚI</h2></div>";
        };
    }

    private void appendBodyHtml(StringBuilder sb, OrderEmailRequest request) {
        String orderNo = escapeHtml(request.orderNo());
        String deadlineText = formatDeadline(request.paymentDeadline());
        int penalty = resolvePenalty(request.penaltyScore());
        switch (request.eventType()) {
            case ORDER_PAID -> {
                sb.append("<p>Hệ thống Nexus Shop xin trân trọng thông báo đơn hàng <strong>#").append(orderNo)
                        .append("</strong> của quý khách đã được thanh toán thành công qua cổng Sandbox (")
                        .append(escapeHtml(request.paymentMethod() != null ? request.paymentMethod() : "SANDBOX_GATEWAY"))
                        .append(").</p>");
                sb.append("<p>Nexus đã xác nhận thanh toán; trạng thái chuẩn bị và giao hàng được cập nhật trong lịch sử đơn hàng.</p>");
            }
            case ORDER_PAYMENT_FAILED -> {
                sb.append("<p>Rất tiếc, giao dịch thanh toán cho đơn hàng <strong>#").append(orderNo)
                        .append("</strong> đã không thành công.</p>");
                if (request.failureReason() != null) {
                    sb.append("<p style=\"color: #d93025;\"><strong>Lý do: </strong>")
                            .append(escapeHtml(request.failureReason())).append("</p>");
                }
                sb.append("<p><em>Lưu ý quan trọng:</em> Đơn thanh toán thất bại đã được hủy và yêu cầu giải phóng tồn kho. ")
                        .append("Vui lòng tạo đơn mới nếu muốn mua lại.</p>");
            }
            case AUCTION_WON -> {
                sb.append("<p>Chúc mừng quý khách đã chiến thắng phiên đấu giá! Đơn hàng <strong>#").append(orderNo)
                        .append("</strong> đã được khởi tạo và giữ tồn kho riêng cho quý khách.</p>");
                if (deadlineText != null) {
                    sb.append("<p><strong>Thời hạn thanh toán (24 giờ): </strong>").append(escapeHtml(deadlineText)).append("</p>");
                }
                appendPaymentButtonHtml(sb, request.paymentLink());
                sb.append("<p>Vui lòng hoàn tất thanh toán đúng hạn để tránh hủy đơn và trừ ")
                        .append(penalty).append(" điểm uy tín.</p>");
            }
            case AUCTION_PAYMENT_REMINDER -> {
                sb.append("<p style=\"color: #e37400;\"><strong>Cảnh báo:</strong> Đơn hàng thắng đấu giá <strong>#")
                        .append(orderNo).append("</strong> sắp hết hạn thanh toán (còn dưới 2 giờ).</p>");
                if (deadlineText != null) {
                    sb.append("<p><strong>Hạn thanh toán: </strong>").append(escapeHtml(deadlineText)).append("</p>");
                }
                appendPaymentButtonHtml(sb, request.paymentLink());
                sb.append("<p>Nếu không thanh toán trước hạn, đơn hàng sẽ tự động bị hủy để hoàn kho cho người bán và tài khoản bị trừ ")
                        .append(penalty).append(" điểm uy tín.</p>");
            }
            case AUCTION_PAYMENT_TIMEOUT -> {
                // Tác vụ hoàn kho và phạt uy tín chạy bất đồng bộ nên email không khẳng định đã hoàn tất trước khi thực thi.
                sb.append("<p>Đơn hàng thắng đấu giá <strong>#").append(orderNo)
                        .append("</strong> đã quá thời hạn thanh toán 24 giờ quy định và đã được tự động hủy.</p>");
                sb.append("<p>Hệ thống đang xử lý hoàn trả tồn kho về cho người bán và áp dụng mức phạt <strong>-")
                        .append(penalty).append(" điểm uy tín</strong> đối với tài khoản của quý khách.</p>");
            }
            case ORDER_CREATED -> sb.append("<p>Cảm ơn quý khách đã đặt hàng tại Nexus Shop! Đơn hàng <strong>#")
                    .append(orderNo).append("</strong> đã được khởi tạo thành công và đang chờ quý khách hoàn tất thanh toán.</p>");
        }
    }

    private void appendPaymentButtonHtml(StringBuilder sb, String paymentLink) {
        if (paymentLink == null || paymentLink.isBlank()) {
            return;
        }
        String safeUrl = escapeHtml(paymentLink);
        sb.append("<p><a class=\"cta-btn\" href=\"").append(safeUrl).append("\">Thanh toán đơn đấu giá ngay</a></p>");
        sb.append("<p style=\"font-size: 13px; color: #555;\">Hoặc mở liên kết: ")
                .append(safeUrl).append("</p>");
    }

    private int resolvePenalty(Integer penaltyScore) {
        return penaltyScore != null && penaltyScore > 0 ? penaltyScore : DEFAULT_PENALTY_SCORE;
    }

    private String formatDeadline(Instant deadline) {
        if (deadline == null) {
            return null;
        }
        return DEADLINE_FORMATTER.format(deadline.atZone(DISPLAY_ZONE));
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
