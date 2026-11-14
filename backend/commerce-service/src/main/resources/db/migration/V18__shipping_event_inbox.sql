-- Inbox Fulfillment và ràng buộc shipment/order cùng transaction với trạng thái Order.
-- Không backfill/suy diễn shipment hoặc phát thông báo cho đơn lịch sử.
CREATE TABLE shipping_order_bindings (
    order_id uuid PRIMARY KEY REFERENCES orders(id),
    shipment_id uuid NOT NULL UNIQUE,
    carrier varchar(30) NOT NULL,
    tracking_number varchar(100) NOT NULL,
    review_event_type varchar(50),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shipping_event_inbox (
    event_id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Snapshot thông báo lấy buyer/giá từ Commerce; worker giao Notification sẽ triển khai ở phần transport kế tiếp.
CREATE TABLE shipping_notification_outbox (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    event_type varchar(30) NOT NULL CHECK (event_type IN ('ORDER_SHIPPED','ORDER_DELIVERED')),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(order_id,event_type)
);
