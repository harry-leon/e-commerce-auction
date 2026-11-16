-- Bổ sung index cho các cột khóa ngoại chưa có index để tăng tốc tra cứu tồn kho/vận đơn
-- và tránh quét toàn bảng con khi DELETE/UPDATE bản ghi cha (PostgreSQL không tự tạo index cho FK).
-- Chỉ thêm index, không đổi dữ liệu hay hành vi; IF NOT EXISTS để chạy an toàn trên môi trường đã tạo tay.
-- Không dùng CONCURRENTLY vì Flyway chạy trong transaction; bảng hiện nhỏ nên khóa ghi chỉ trong thời gian ngắn.

-- Tồn kho: unique (sku_id, warehouse_id) không phủ truy vấn chỉ theo warehouse_id.
CREATE INDEX IF NOT EXISTS idx_inventories_warehouse_id ON inventories (warehouse_id);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_inventory_id ON inventory_reservations (inventory_id);

-- Vận đơn và kiện hàng theo kho/hãng vận chuyển.
CREATE INDEX IF NOT EXISTS idx_shipments_warehouse_id ON shipments (warehouse_id);
CREATE INDEX IF NOT EXISTS idx_shipments_carrier_id ON shipments (carrier_id);
CREATE INDEX IF NOT EXISTS idx_shipment_parcels_warehouse_id ON shipment_parcels (warehouse_id);
CREATE INDEX IF NOT EXISTS idx_shipment_parcels_carrier_code ON shipment_parcels (carrier_code);

-- Dispatch: audit xử lý thủ công, job và đối soát job tra theo attempt/shipment.
CREATE INDEX IF NOT EXISTS idx_shipment_dispatch_resolutions_attempt_id ON shipment_dispatch_resolutions (attempt_id);
CREATE INDEX IF NOT EXISTS idx_shipment_dispatch_resolutions_shipment_id ON shipment_dispatch_resolutions (shipment_id);
CREATE INDEX IF NOT EXISTS idx_shipment_dispatch_jobs_shipment_id ON shipment_dispatch_jobs (shipment_id);
CREATE INDEX IF NOT EXISTS idx_shipment_dispatch_job_reconciliations_attempt_id ON shipment_dispatch_job_reconciliations (attempt_id);
CREATE INDEX IF NOT EXISTS idx_shipment_dispatch_job_reconciliations_shipment_id ON shipment_dispatch_job_reconciliations (shipment_id);

-- Audit gửi lại sự kiện shipping theo event.
CREATE INDEX IF NOT EXISTS idx_shipping_event_replays_event_id ON shipping_event_replays (event_id);
