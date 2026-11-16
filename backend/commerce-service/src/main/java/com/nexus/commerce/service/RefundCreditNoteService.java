package com.nexus.commerce.service;

import com.nexus.commerce.dto.response.RefundCreditNote;
import com.nexus.commerce.dto.response.RefundReceipt;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phát hành chứng từ điều chỉnh (credit note) cho refund đã có biên nhận hợp lệ.
 *
 * <p>Chức năng: tạo một credit note bất biến liên kết biên nhận thu gốc và biên nhận hoàn tiền, phục vụ đối soát
 * kế toán. Không sửa Payment, không sửa biên nhận gốc, không gọi provider và không thay đổi hạn mức refund.
 *
 * <p>Đầu vào: refundId, Idempotency-Key của Admin, ghi chú nội bộ; actor lấy từ JWT subject.
 * Kết quả: bản ghi credit note mới (201) hoặc bản ghi cũ khi replay cùng key/nội dung (200).
 *
 * <p>Luồng: khóa key → replay audit → đọc chứng từ gốc qua {@link RefundReceiptService} (đã kiểm tra bằng chứng,
 * hold và quyền) → chèn credit note → commit cùng transaction. Vì {@link RefundReceiptService#download} đã từ chối
 * refund thiếu bằng chứng, credit note không thể phát hành sớm hơn kết quả tài chính.
 *
 * <p>Lỗi: 400 khi đầu vào sai, 404 khi refund không tồn tại hoặc Buyer không sở hữu, 409 khi chứng từ đã tồn tại
 * với nội dung khác hoặc khi refund chưa đủ điều kiện xuất chứng từ.
 */
@Service
@RequiredArgsConstructor
public class RefundCreditNoteService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;
    private final RefundReceiptService refundReceipts;

    /**
     * Phát hành hoặc replay credit note của một refund.
     *
     * <p>Quy tắc: số chứng từ dạng {@code CN-{refundId}} cố định theo refund nên tra cứu lại luôn trả cùng số.
     * Key idempotency gắn actor: hai Admin khác nhau có thể cùng thấy một credit note nhưng không tạo bản ghi thứ hai
     * vì UNIQUE(refund_id) chặn ở tầng DB. Ghi chú chỉ lưu nội bộ, không trả trong response.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN')")
    public Result issue(UUID refundId, UUID actor, UUID key, String note, Authentication authentication) {
        if (refundId == null || actor == null || key == null || note == null || note.isBlank()
                || note.trim().length() > 500 || note.chars().anyMatch(Character::isISOControl)) {
            throw error("CREDIT_NOTE_INVALID", HttpStatus.BAD_REQUEST, "Yêu cầu phát hành chứng từ không hợp lệ");
        }
        String normalized = note.trim();
        locks.acquire("refund-credit-note:" + actor + ":" + key);
        var replay = jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE issued_by=? AND idempotency_key=?", actor, key);
        if (!replay.isEmpty()) {
            var row = replay.getFirst();
            if (!refundId.equals(row.get("refund_id")) || !normalized.equals(row.get("note"))) { throw conflict(); }
            return new Result(map(row), false);
        }
        // RefundReceiptService đã xác minh refund SUCCEEDED, registry bằng chứng, hold/conflict và quyền chủ đơn.
        // Dùng lại để credit note không thể xuất sớm hơn kết quả tài chính hoặc trên chứng từ mâu thuẫn.
        RefundReceipt receipt;
        try {
            receipt = refundReceipts.download(refundId, authentication);
        } catch (ApiException error) {
            if (error.getHttpStatus() == HttpStatus.NOT_FOUND) { throw error; }
            throw conflict();
        }
        var existing = jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refundId);
        if (!existing.isEmpty()) { throw conflict(); }
        UUID creditNoteId = UUID.randomUUID();
        String documentNo = "CN-" + refundId;
        jdbc.update("""
                INSERT INTO refund_credit_notes(id,refund_id,payment_id,order_id,document_no,original_receipt_id,
                    refund_receipt_id,amount,currency,evidence_source,issued_by,idempotency_key,note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, creditNoteId, refundId, receipt.paymentId(), receipt.orderId(), documentNo, receipt.originalReceiptId(),
                receipt.receiptId(), receipt.amount(), receipt.currency(), receipt.evidenceSource(), actor, key, normalized);
        var saved = jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE id=?", creditNoteId).getFirst();
        return new Result(map(saved), true);
    }

    /**
     * Buyer chủ đơn hoặc Admin đọc credit note đã phát hành của một refund.
     *
     * <p>Quyền được kiểm tra lại qua biên nhận refund: Buyer khác nhận 404 thay vì 403 để không dò được refund.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    public RefundCreditNote get(UUID refundId, Authentication authentication) {
        // Gọi dịch vụ biên nhận trước để áp dụng đúng quy tắc ownership hiện có thay vì tự suy đoán từ DB.
        refundReceipts.download(refundId, authentication);
        var rows = jdbc.queryForList("SELECT * FROM refund_credit_notes WHERE refund_id=?", refundId);
        if (rows.isEmpty()) {
            throw new ApiException("CREDIT_NOTE_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "Refund chưa có chứng từ điều chỉnh", null);
        }
        return map(rows.getFirst());
    }

    /** Ánh xạ cột DB sang DTO công khai; không đưa note, idempotency key hoặc actor ra ngoài. */
    private RefundCreditNote map(java.util.Map<String, Object> row) {
        return new RefundCreditNote(1, "INTERNAL_REFUND_CREDIT_NOTE", (String) row.get("document_no"),
                (UUID) row.get("id"), (UUID) row.get("refund_id"), (UUID) row.get("payment_id"),
                (UUID) row.get("order_id"), (String) row.get("original_receipt_id"), (String) row.get("refund_receipt_id"),
                (java.math.BigDecimal) row.get("amount"), (String) row.get("currency"),
                (String) row.get("evidence_source"),
                ((java.sql.Timestamp) row.get("issued_at")).toInstant());
    }

    private ApiException conflict() {
        return new ApiException("CREDIT_NOTE_CONFLICT", HttpStatus.CONFLICT,
                "Chứng từ đã tồn tại với nội dung khác hoặc refund chưa đủ điều kiện phát hành", null);
    }

    private ApiException error(String code, HttpStatus status, String message) {
        return new ApiException(code, status, message, null);
    }

    /** created quyết định 201/200; không phải xác nhận hoàn tiền lần nữa. */
    public record Result(RefundCreditNote note, boolean created) { }
}
