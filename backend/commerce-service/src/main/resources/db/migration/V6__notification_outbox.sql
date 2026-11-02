-- Snapshot thông báo được commit cùng đơn/thanh toán; worker gửi sau, không gọi HTTP trong checkout/IPN.
-- Đơn cũ không được backfill để tránh phát thông báo lịch sử khi deploy.
CREATE TABLE commerce_notification_outbox (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    user_id uuid NOT NULL,
    event_type varchar(40) NOT NULL CHECK (event_type IN ('ORDER_CREATED','PAYMENT_SUCCESS','PAYMENT_FAILED')),
    order_no varchar(100) NOT NULL,
    amount numeric(15,2) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    done boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(order_id,event_type)
);
CREATE INDEX idx_commerce_notification_pending ON commerce_notification_outbox(next_attempt_at,id) WHERE NOT done;
