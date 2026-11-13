-- Lưu yêu cầu refund độc lập với Payment/Order; không backfill hoặc sửa giao dịch lịch sử.
-- REQUESTED chỉ là tiếp nhận và giữ hạn mức, chưa phải hoàn tiền hay tác vụ gửi provider.
CREATE TABLE refund_requests (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payments(id),
    order_id uuid NOT NULL REFERENCES orders(id),
    requested_by uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    reason varchar(500) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0 AND amount = trunc(amount)),
    currency varchar(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    status varchar(30) NOT NULL CHECK (status IN
        ('REQUESTED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','REQUIRES_REVIEW')),
    provider varchar(30) NOT NULL,
    merchant_code varchar(100) NOT NULL,
    provider_payment_ref varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (requested_by, idempotency_key)
);
-- Tổng tiền refund được đọc dưới khóa payment; index giảm thời gian giữ khóa khi kiểm tra hạn mức.
CREATE INDEX idx_refund_requests_payment ON refund_requests(payment_id, status);
