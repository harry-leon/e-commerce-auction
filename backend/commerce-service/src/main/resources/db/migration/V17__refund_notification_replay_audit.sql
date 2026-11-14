-- Audit replay thông báo đã dead letter; không thay snapshot/eventId hoặc trạng thái tài chính.
-- Bảng mới rỗng khi upgrade; key theo actor bảo vệ retry request sau mất ACK của API quản trị.
CREATE TABLE refund_notification_replays (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES refund_notification_outbox(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
