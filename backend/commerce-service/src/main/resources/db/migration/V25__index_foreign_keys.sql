-- Bổ sung index cho các cột khóa ngoại chưa có index để tăng tốc tra cứu theo đơn/refund
-- và tránh quét toàn bảng con khi DELETE/UPDATE bản ghi cha (PostgreSQL không tự tạo index cho FK).
-- Chỉ thêm index, không đổi dữ liệu hay hành vi; IF NOT EXISTS để chạy an toàn trên môi trường đã tạo tay.
-- Không dùng CONCURRENTLY vì Flyway chạy trong transaction; bảng hiện nhỏ nên khóa ghi chỉ trong thời gian ngắn.
-- Bảng refunds cũ không thêm index vì bị xóa ở V26.

-- Order nạp lịch sử bằng @OrderBy("changedAt ASC") nên index ghép phục vụ cả lọc lẫn sắp xếp.
CREATE INDEX IF NOT EXISTS idx_order_status_history_order_changed ON order_status_history (order_id, changed_at);
CREATE INDEX IF NOT EXISTS idx_invoices_order_id ON invoices (order_id);
-- Unique (voucher_id, order_id) bắt đầu bằng voucher_id nên không phủ truy vấn theo order_id.
CREATE INDEX IF NOT EXISTS idx_voucher_usages_order_id ON voucher_usages (order_id);

-- Luồng refund: tra cứu yêu cầu/đối soát/xung đột theo đơn hoặc theo refund.
CREATE INDEX IF NOT EXISTS idx_refund_requests_order_id ON refund_requests (order_id);
CREATE INDEX IF NOT EXISTS idx_refund_reconciliation_checks_refund_id ON refund_reconciliation_checks (refund_id);
CREATE INDEX IF NOT EXISTS idx_refund_result_conflicts_refund_id ON refund_result_conflicts (refund_id);
CREATE INDEX IF NOT EXISTS idx_refund_notification_replays_event_id ON refund_notification_replays (event_id);
CREATE INDEX IF NOT EXISTS idx_refund_credit_notes_order_id ON refund_credit_notes (order_id);

-- Luồng shipping: sự kiện inbox và audit gửi lại thông báo theo đơn/sự kiện.
CREATE INDEX IF NOT EXISTS idx_shipping_event_inbox_order_id ON shipping_event_inbox (order_id);
CREATE INDEX IF NOT EXISTS idx_shipping_notification_replays_event_id ON shipping_notification_replays (event_id);
