-- Giữ quyền dispatch tuần tự với tạo refund dưới khóa Order; không cấp quyền hoặc backfill cho task cũ.
-- Parcel UUID thuộc Fulfillment, không FK xuyên service. Claim không tự hết hạn/xóa sau timeout vì carrier có thể đã nhận.
ALTER TABLE shipping_handoff_tasks DROP CONSTRAINT shipping_handoff_tasks_state_check;
ALTER TABLE shipping_handoff_tasks ADD CONSTRAINT shipping_handoff_tasks_state_check CHECK (state IN ('WAITING_PARCEL','CLAIMED'));
CREATE TABLE shipping_dispatch_claims (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    handoff_id uuid NOT NULL UNIQUE REFERENCES shipping_handoff_tasks(id),
    parcel_id uuid NOT NULL UNIQUE,
    requested_by uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(requested_by,idempotency_key)
);
