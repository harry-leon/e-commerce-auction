-- Migration V3: Cập nhật schema đơn hàng (orders) và lịch sử trạng thái (order_status_history)
-- 1. Chức năng: Lưu trữ snapshot người nhận (tên, SĐT) và audit trail người thay đổi trạng thái (changed_by).
-- 2. Quy tắc và lý do: Thỏa mãn SRS §3.4.3, BR-COM-02 và API_SPEC_CART_ORDER.md.

ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS recipient_name varchar(255),
  ADD COLUMN IF NOT EXISTS recipient_phone varchar(20);

ALTER TABLE order_status_history
  ADD COLUMN IF NOT EXISTS changed_by uuid;

CREATE INDEX IF NOT EXISTS idx_orders_buyer_id ON orders(buyer_id);
CREATE INDEX IF NOT EXISTS idx_orders_seller_id ON orders(seller_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_order_status_history_order_id ON order_status_history(order_id);
