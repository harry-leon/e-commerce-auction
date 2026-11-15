-- Audit thao tác Admin chốt Order sau refund toàn phần đã xác minh và trước dispatch.
-- Không backfill Order/biên nhận cũ, không hoàn kho; unique giữ một quyết định cho mỗi Order/refund và actor/key.
CREATE TABLE refunded_order_finalizations (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    refund_id uuid NOT NULL UNIQUE REFERENCES refund_requests(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    previous_status varchar(30) NOT NULL CHECK (previous_status IN ('PAID','PROCESSING')),
    note varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
