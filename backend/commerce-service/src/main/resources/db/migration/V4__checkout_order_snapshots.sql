-- Checkout lưu thông tin người nhận/phương thức và hash payload để phát hiện key dùng lại sai nội dung.
-- Các cột mới nullable cho đơn cũ; không thay đổi giá, trạng thái hay idempotency_key đã lưu.
ALTER TABLE orders ADD COLUMN recipient_name varchar(255);
ALTER TABLE orders ADD COLUMN recipient_phone varchar(30);
ALTER TABLE orders ADD COLUMN payment_method varchar(20);
ALTER TABLE orders ADD COLUMN request_hash varchar(64);
CREATE INDEX idx_orders_buyer_created ON orders (buyer_id, created_at);
