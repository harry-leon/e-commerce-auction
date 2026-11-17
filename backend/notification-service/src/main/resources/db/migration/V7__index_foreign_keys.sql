-- Bổ sung index cho notification_attempts.notification_id (FK chưa có index) để tra lịch sử gửi
-- theo thông báo nhanh và tránh quét toàn bảng khi xóa thông báo. Chỉ thêm index, không đổi dữ liệu.
-- Index ghép kèm attempt_no để lấy các lần gửi theo đúng thứ tự.
CREATE INDEX IF NOT EXISTS idx_notification_attempts_notification_attempt
    ON notification_attempts (notification_id, attempt_no);
