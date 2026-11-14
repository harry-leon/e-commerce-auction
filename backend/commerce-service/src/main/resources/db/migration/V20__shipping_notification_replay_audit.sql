-- Audit Admin replay dead letter Shipping, không sửa snapshot/id hoặc phát lại lệnh shipment.
-- Nâng schema chỉ tạo bảng rỗng; không tự reset backlog.
CREATE TABLE shipping_notification_replays (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES shipping_notification_outbox(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
