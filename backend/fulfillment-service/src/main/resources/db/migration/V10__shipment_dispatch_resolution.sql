-- Khóa lifecycle riêng, không khóa row attempt mà guard cần cập nhật bằng REQUIRES_NEW trong HTTP.
-- Backfill chỉ thêm row khóa cho attempt cũ, không gửi lại hoặc chốt kết quả carrier.
CREATE TABLE shipment_dispatch_locks (order_id uuid PRIMARY KEY);
INSERT INTO shipment_dispatch_locks(order_id) SELECT order_id FROM shipment_dispatch_attempts;
-- Quyết định Admin gắn shipment đã tồn tại tại carrier, không cấp lại quyền gọi createShipment.
CREATE TABLE shipment_dispatch_resolutions (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES shipment_dispatch_attempts(id),
    shipment_id uuid NOT NULL REFERENCES shipments(id),
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    tracking_number varchar(100) NOT NULL,
    shipping_fee numeric(15,2) NOT NULL CHECK (shipping_fee >= 0),
    evidence_reference varchar(200) NOT NULL,
    note varchar(500) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id,idempotency_key)
);
