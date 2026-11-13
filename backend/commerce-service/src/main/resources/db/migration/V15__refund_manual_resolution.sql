-- Ghi quyết định đối soát Admin độc lập với query PAY, giữ bằng chứng và snapshot khi replay.
-- Không tự chốt hoặc giải phóng hạn mức cho dữ liệu UNKNOWN/REVIEW hiện có.
CREATE TABLE refund_manual_resolutions (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES refund_requests(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    previous_status varchar(30) NOT NULL,
    decision varchar(20) NOT NULL CHECK (decision IN ('SUCCEEDED','FAILED')),
    amount numeric(15,2) NOT NULL CHECK (amount > 0 AND amount=trunc(amount)),
    currency varchar(3) NOT NULL CHECK (currency='VND'),
    provider_payment_ref varchar(100) NOT NULL,
    provider_ref varchar(100),
    evidence_reference varchar(200) NOT NULL CHECK (length(trim(evidence_reference)) > 0),
    note varchar(500) NOT NULL CHECK (length(trim(note)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
CREATE INDEX idx_refund_manual_history ON refund_manual_resolutions(refund_id,created_at,id);
-- Registry dùng chung kết quả worker và Admin, không cho một receipt provider xác nhận hai refund khác nhau.
CREATE TABLE refund_provider_receipts (
    refund_id uuid PRIMARY KEY REFERENCES refund_requests(id),
    provider varchar(30) NOT NULL,
    merchant_code varchar(100) NOT NULL,
    provider_ref varchar(100) NOT NULL,
    amount numeric(15,2) NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency='VND'),
    source varchar(10) NOT NULL CHECK (source IN ('PROVIDER','MANUAL')),
    source_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider,merchant_code,provider_ref)
);
-- Backfill receipt đã được worker xác nhận ở V14, không phát event hoặc thay Order/Payment.
INSERT INTO refund_provider_receipts(refund_id,provider,merchant_code,provider_ref,amount,currency,source,source_id)
SELECT r.id,r.provider,r.merchant_code,a.provider_ref,r.amount,r.currency,'PROVIDER',a.id
FROM refund_requests r JOIN refund_attempts a ON a.refund_id=r.id
WHERE r.status='SUCCEEDED' AND a.status='SUCCEEDED' AND a.provider_ref IS NOT NULL;
-- Kết quả muộn mâu thuẫn cần dừng lệnh mới theo payment; không tự xóa hold sau quyết định khác.
CREATE TABLE refund_payment_holds (
    payment_id uuid PRIMARY KEY REFERENCES payments(id),
    reason varchar(50) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Giữ kết quả network muộn trái với quyết định đã chốt; không sửa audit cũ hoặc hạ SUCCEEDED xuống FAILED.
CREATE TABLE refund_result_conflicts (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES refund_requests(id),
    attempt_id uuid NOT NULL REFERENCES refund_attempts(id),
    response_code varchar(2),
    transaction_status varchar(2),
    transaction_type varchar(2),
    provider_ref varchar(100),
    reason varchar(50) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(attempt_id,reason)
);
