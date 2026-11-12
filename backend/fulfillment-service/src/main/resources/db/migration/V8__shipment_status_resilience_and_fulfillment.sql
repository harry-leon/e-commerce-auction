-- Migration V8: Mở rộng ràng buộc trạng thái vận đơn, bổ sung tracking event ID và ghi chú cho lịch sử chuyển trạng thái (KH-S3-2, SRS §3.6.1, §3.6.2).
-- Chức năng:
-- - Cập nhật CHECK constraint chk_shipment_status trên bảng shipments để hỗ trợ chuẩn hóa trạng thái nội bộ:
--   CREATED, PENDING, PICKING, PACKED, PICKED_UP, SHIPPED, IN_TRANSIT, DELIVERED, RETURNED, FAILED, CANCELLED.
-- - Thêm cột event_id và notes vào bảng shipment_status_history để phục vụ xử lý idempotent webhook/callback và polling.
-- - Tạo UNIQUE INDEX trên (event_id) để ngăn chặn ghi trùng lặp sự kiện cùng transaction/event ID từ carrier.
-- Ảnh hưởng dữ liệu: các cột bổ sung đều nullable, constraint mới là tập cha chứa tất cả trạng thái cũ nên hoàn toàn an toàn với dữ liệu hiện có.

ALTER TABLE shipments DROP CONSTRAINT IF EXISTS chk_shipment_status;
ALTER TABLE shipments ADD CONSTRAINT chk_shipment_status CHECK (status IN (
    'CREATED', 'PENDING', 'PICKING', 'PACKED', 'PICKED_UP', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED', 'CANCELLED'
));

ALTER TABLE shipment_status_history
    ADD COLUMN IF NOT EXISTS event_id varchar(128),
    ADD COLUMN IF NOT EXISTS notes varchar(500);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shipment_status_history_event
    ON shipment_status_history(event_id)
    WHERE event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_shipments_active_status
    ON shipments(status)
    WHERE status IN ('CREATED', 'PENDING', 'PICKING', 'PACKED', 'PICKED_UP', 'SHIPPED', 'IN_TRANSIT');
