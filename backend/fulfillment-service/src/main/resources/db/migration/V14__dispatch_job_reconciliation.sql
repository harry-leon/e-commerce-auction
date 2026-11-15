-- Audit Admin đóng job REVIEW khi shipment/guard đã được xác nhận; không requeue, gọi carrier hoặc giải phóng claim.
-- Không backfill/quét tự đóng job cũ. Unique job và actor/key giữ quyết định đầu tiên bất biến qua replay.
CREATE TABLE shipment_dispatch_job_reconciliations (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE REFERENCES shipment_dispatch_jobs(id),
    attempt_id uuid NOT NULL REFERENCES shipment_dispatch_attempts(id),
    shipment_id uuid NOT NULL REFERENCES shipments(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    evidence_reference varchar(200) NOT NULL CHECK (length(trim(evidence_reference))>0),
    note varchar(500) NOT NULL CHECK (length(trim(note))>0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
