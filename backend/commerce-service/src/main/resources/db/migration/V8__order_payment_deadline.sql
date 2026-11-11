-- Bổ sung cột payment_deadline lưu thời hạn thanh toán đơn hàng (SRS §3.6.1 & §3.5.3).
-- Phục vụ cho đơn hàng đấu giá với thời hạn thanh toán quy định là 24 giờ kể từ thời điểm chốt phiên.
-- Cột nullable để tương thích ngược với các đơn hàng cũ đã tồn tại trong cơ sở dữ liệu.
-- Bổ sung index idx_orders_auction_id tối ưu hóa tra cứu lũy nghiệm (idempotency check) khi bàn giao phiên đấu giá.
ALTER TABLE orders ADD COLUMN payment_deadline timestamp;
CREATE INDEX idx_orders_auction_id ON orders (auction_id);
