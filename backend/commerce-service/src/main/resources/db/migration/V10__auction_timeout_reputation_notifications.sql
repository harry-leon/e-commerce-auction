-- Mở rộng outbox thông báo cho vòng đời thắng đấu giá (AUCTION_WON, nhắc hạn 2h, quá hạn 24h)
-- và thêm outbox bất biến phục vụ trừ điểm uy tín / đồng bộ DEFAULTED khi quá hạn thanh toán 24h.
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT c.conname INTO constraint_name
    FROM pg_constraint c
    JOIN pg_class t ON c.conrelid = t.oid
    WHERE t.relname = 'commerce_notification_outbox'
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) LIKE '%event_type%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE commerce_notification_outbox DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE commerce_notification_outbox ADD CONSTRAINT chk_commerce_notification_event_type
    CHECK (event_type IN (
        'ORDER_CREATED',
        'PAYMENT_SUCCESS',
        'PAYMENT_FAILED',
        'AUCTION_WON',
        'AUCTION_PAYMENT_REMINDER',
        'AUCTION_PAYMENT_TIMEOUT'
    ));

-- Hàng đợi tác vụ hậu kiểm khi đơn đấu giá quá hạn thanh toán 24h:
-- lỗi mạng sang Reputation hoặc Auction được retry an toàn mà không làm rollback việc hủy đơn/hoàn kho.
CREATE TABLE auction_timeout_tasks (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    user_id uuid NOT NULL,
    auction_id uuid NOT NULL,
    task_type varchar(30) NOT NULL CHECK (task_type IN ('REPUTATION_PENALTY', 'AUCTION_DEFAULTED')),
    score_delta integer NOT NULL DEFAULT 10 CHECK (score_delta > 0),
    reason varchar(500) NOT NULL,
    done boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_id, task_type)
);

CREATE INDEX idx_auction_timeout_tasks_pending
    ON auction_timeout_tasks(next_attempt_at, id) WHERE NOT done;
