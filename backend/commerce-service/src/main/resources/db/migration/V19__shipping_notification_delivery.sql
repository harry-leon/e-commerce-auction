-- Theo dõi giao inbox/push của snapshot Shipping V18; backlog cũ chuyển PENDING nhưng worker mặc định tắt.
-- Không sinh sự kiện lịch sử mới, không thay payload hoặc trạng thái Order.
ALTER TABLE shipping_notification_outbox
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','DEAD_LETTER')),
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error_type varchar(100),
    ADD COLUMN delivered_at timestamptz;
CREATE INDEX idx_shipping_notification_pending ON shipping_notification_outbox(next_attempt_at,id) WHERE status='PENDING';
