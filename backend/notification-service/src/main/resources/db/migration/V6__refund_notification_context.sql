-- Liên kết tin refund với đúng yêu cầu, không FK sang DB Commerce và không sửa lịch sử thông báo cũ.
ALTER TABLE notifications ADD COLUMN refund_id uuid;
CREATE INDEX idx_notifications_refund ON notifications(refund_id) WHERE refund_id IS NOT NULL;
