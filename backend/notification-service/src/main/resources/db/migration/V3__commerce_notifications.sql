-- Thông báo IN_APP của Commerce: khóa sự kiện chống gửi lặp, thêm snapshot liên kết đơn.
-- Không sửa dữ liệu audit cũ; index partial chỉ ràng buộc sự kiện Commerce mới.
ALTER TABLE notifications ADD COLUMN order_id uuid;
ALTER TABLE notifications ADD COLUMN event_type varchar(40);
CREATE UNIQUE INDEX uq_commerce_notification_event ON notifications(event_id)
    WHERE event_type IS NOT NULL;
CREATE INDEX idx_notification_inbox ON notifications(user_id, created_at DESC, id DESC);
