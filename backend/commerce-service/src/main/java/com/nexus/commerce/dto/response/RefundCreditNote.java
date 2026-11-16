package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Chứng từ điều chỉnh nội bộ cho một khoản hoàn tiền đã xác minh.
 *
 * <p>Chức năng: biểu diễn credit note liên kết biên nhận thu gốc (PAY-{paymentId}) và biên nhận hoàn tiền
 * (REFUND-{refundId}) mà không sửa nội dung hai chứng từ đó. Dùng cho đối soát kế toán và tra cứu của Buyer/Admin.
 *
 * <p>Quy tắc: {@code documentNo} ổn định theo refundId nên gọi lại không sinh số chứng từ mới.
 * {@code originalReceiptId} là tham chiếu chứ không phải bản sao số tiền thu, vì credit note chỉ điều chỉnh
 * phần đã hoàn. Không chứa merchant reference, provider payload, ghi chú đối soát hay thông tin định danh người mua.
 *
 * <p>Giới hạn: đây là chứng từ nội bộ, không phải hóa đơn điện tử có ký số và không thay thế chứng từ điều chỉnh
 * do nhà cung cấp hóa đơn phát hành.
 */
public record RefundCreditNote(int version, String documentType, String documentNo, UUID creditNoteId,
                               UUID refundId, UUID paymentId, UUID orderId, String originalReceiptId,
                               String refundReceiptId, BigDecimal amount, String currency,
                               String evidenceSource, Instant issuedAt) { }
