-- Snapshot người nhận từ JWT và nội dung hóa đơn từ đơn đã lưu, để worker retry không gửi nhầm người/giá.
-- Đơn và outbox cũ giữ email NULL/payload rỗng: tiếp tục IN_APP, không tự suy đoán địa chỉ email.
ALTER TABLE orders ADD COLUMN buyer_email varchar(254);
ALTER TABLE commerce_notification_outbox ADD COLUMN email_payload jsonb NOT NULL DEFAULT '{}'::jsonb;
