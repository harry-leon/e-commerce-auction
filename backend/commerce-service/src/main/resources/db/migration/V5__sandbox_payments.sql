-- Bổ sung snapshot URL/merchant và kết quả IPN; dữ liệu payment cũ giữ nguyên, cột mới nullable.
ALTER TABLE payments ADD COLUMN payment_url text;
ALTER TABLE payments ADD COLUMN merchant_code varchar(100);
ALTER TABLE payments ADD COLUMN expires_at timestamp;
ALTER TABLE payments ADD COLUMN response_code varchar(2);
ALTER TABLE payments ADD COLUMN transaction_status varchar(2);
ALTER TABLE payments ADD COLUMN completed_at timestamp;
-- Một attempt VNPAY cho mỗi order; callback/khởi tạo lặp không tạo giao dịch mới hoặc dùng lại mã giao dịch đã trả tiền.
CREATE UNIQUE INDEX uq_vnpay_order ON payments(order_id) WHERE provider = 'VNPAY';
CREATE UNIQUE INDEX uq_vnpay_success_ref ON payments(provider_ref) WHERE provider = 'VNPAY' AND status = 'SUCCESS';
-- Lưu reservation IDs từ checkout để worker xác nhận kho sau IPN mà không giữ token người mua trong DB.
CREATE TABLE order_reservations (
  order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  reservation_id uuid NOT NULL,
  PRIMARY KEY (order_id, reservation_id)
);
-- Outbox cùng transaction với kết quả payment; retry worker không làm mất tác vụ kho khi process chết.
CREATE TABLE payment_inventory_tasks (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  target_id uuid NOT NULL,
  action varchar(10) NOT NULL CHECK (action IN ('CONFIRM', 'RELEASE')),
  done boolean NOT NULL DEFAULT false,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (order_id, target_id, action)
);
