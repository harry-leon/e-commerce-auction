-- EMAIL có key riêng với IN_APP; advisory lock tuần tự hóa gửi, unique index ngăn tạo bản ghi trùng khi retry.
-- Dọn bản ghi EMAIL trùng key cũ bằng cách giữ notification đã SENT trước, rồi bản ghi sớm nhất.
-- Không xóa lịch sử: chỉ bỏ event_id trên bản ghi trùng; attempts và nội dung cũ vẫn được giữ.
WITH duplicates AS (
  SELECT id, row_number() OVER (
    PARTITION BY event_id ORDER BY (status = 'SENT') DESC, created_at, id
  ) AS position
  FROM notifications WHERE channel = 'EMAIL' AND event_id IS NOT NULL
)
UPDATE notifications SET event_id = NULL
WHERE id IN (SELECT id FROM duplicates WHERE position > 1);
CREATE UNIQUE INDEX uq_email_notification_event ON notifications(event_id)
WHERE channel = 'EMAIL' AND event_id IS NOT NULL;
