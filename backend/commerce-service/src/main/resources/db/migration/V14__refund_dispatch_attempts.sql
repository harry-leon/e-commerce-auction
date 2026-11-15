-- Tách duyệt dispatch khỏi tiếp nhận REQUESTED: dữ liệu V13 cũ không tự được gửi khi bật worker.
ALTER TABLE refund_requests ADD COLUMN dispatch_approved_by uuid;
ALTER TABLE refund_requests ADD COLUMN dispatch_approved_at timestamptz;
ALTER TABLE refund_requests ADD COLUMN dispatch_note varchar(500);
ALTER TABLE refund_requests ADD COLUMN review_reason varchar(50);
-- Mỗi refund chỉ có một lần gửi; timeout/restart không được tạo attempt thứ hai để gửi lại mù.
CREATE TABLE refund_attempts (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE REFERENCES refund_requests(id),
    request_id varchar(32) NOT NULL UNIQUE,
    request_payload jsonb NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('PROCESSING','UNKNOWN','SUCCEEDED','REQUIRES_REVIEW')),
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    response_code varchar(2),
    transaction_status varchar(2),
    provider_ref varchar(100),
    error_type varchar(50)
);
-- Payload lưu snapshot không có SecureHash/secret; response chỉ giữ mã và reference đã xác minh, không raw JSON.
CREATE UNIQUE INDEX uq_refund_provider_result ON refund_attempts(provider_ref) WHERE status='SUCCEEDED';
CREATE INDEX idx_refund_attempt_lease ON refund_attempts(lease_expires_at) WHERE status='PROCESSING';
-- QueryDr chỉ là bằng chứng giao dịch PAY gốc, không chuyển trạng thái refund thành SUCCEEDED.
CREATE TABLE refund_reconciliation_checks (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES refund_requests(id),
    request_id varchar(32) NOT NULL UNIQUE,
    response_code varchar(2),
    transaction_status varchar(2),
    transaction_type varchar(2),
    error_type varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE refund_requests ADD COLUMN reconcile_after timestamptz;
ALTER TABLE refund_requests ADD COLUMN reconcile_count integer NOT NULL DEFAULT 0 CHECK (reconcile_count >= 0);
