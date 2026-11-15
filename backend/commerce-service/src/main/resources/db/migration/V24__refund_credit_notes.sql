-- Chứng từ điều chỉnh (credit note) cho refund đã xác minh; liên kết biên nhận thu gốc, không sửa chứng từ gốc.
-- Mỗi refund chỉ có tối đa một credit note nên UNIQUE(refund_id) chặn phát hành trùng khi Admin retry.
-- Bảng mới rỗng khi nâng cấp; không backfill refund lịch sử vì thiếu bằng chứng đối soát tại thời điểm nâng.
CREATE TABLE refund_credit_notes (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE REFERENCES refund_requests(id),
    payment_id uuid NOT NULL REFERENCES payments(id),
    order_id uuid NOT NULL REFERENCES orders(id),
    document_no varchar(50) NOT NULL UNIQUE,
    original_receipt_id varchar(80) NOT NULL,
    refund_receipt_id varchar(80) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0 AND amount = trunc(amount)),
    currency varchar(3) NOT NULL CHECK (currency = 'VND'),
    evidence_source varchar(10) NOT NULL CHECK (evidence_source IN ('PROVIDER','MANUAL')),
    issued_by uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    -- Ghi chú nội bộ phục vụ audit/đối soát; không trả trong response công khai.
    note varchar(500) NOT NULL CHECK (length(trim(note)) > 0),
    issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (issued_by, idempotency_key)
);
-- Tra cứu credit note theo payment khi đối soát chứng từ của một giao dịch thu.
CREATE INDEX idx_refund_credit_notes_payment ON refund_credit_notes(payment_id, issued_at);
