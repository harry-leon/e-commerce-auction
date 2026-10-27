-- ERD không có user_svc.audit_logs; audit mới được gửi tới notification_svc.event_logs qua API nội bộ.
-- Chặn xóa lịch sử đang có: xuất/chuyển dữ liệu theo docs/database/erd-alignment.md rồi mới xóa dữ liệu nguồn.
-- Migration không tự nối DB service khác và không âm thầm bỏ nhật ký người dùng.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM audit_logs) THEN
        RAISE EXCEPTION 'audit_logs con du lieu; chuyen sang notification.event_logs theo docs/database/erd-alignment.md truoc';
    END IF;
END $$;
DROP TABLE audit_logs;
