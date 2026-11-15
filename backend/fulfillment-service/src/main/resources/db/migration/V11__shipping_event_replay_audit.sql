-- Audit replay HTTP shipment outbox; không reset backlog hoặc sửa snapshot khi nâng schema.
CREATE TABLE shipping_event_replays (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES fulfillment_event_outbox(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    previous_attempts integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
