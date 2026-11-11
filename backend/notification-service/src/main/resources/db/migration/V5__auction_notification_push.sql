-- Bổ sung ngữ cảnh đấu giá cho thông báo thắng phiên, nhắc hạn thanh toán 2h và quá hạn 24h (KH-S2-3).
-- Các cột đều nullable để tương thích ngược toàn bộ thông báo ORDER_* và lịch sử Sprint 1.
ALTER TABLE notifications ADD COLUMN auction_id uuid;
ALTER TABLE notifications ADD COLUMN payment_link varchar(500);
ALTER TABLE notifications ADD COLUMN payment_deadline timestamp;
CREATE INDEX idx_notifications_auction_id ON notifications(auction_id) WHERE auction_id IS NOT NULL;
