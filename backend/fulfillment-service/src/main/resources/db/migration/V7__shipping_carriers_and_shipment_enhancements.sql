-- Migration V7: Khởi tạo dữ liệu đơn vị vận chuyển mặc định (GHN, GHTK) và mở rộng bảng shipments (KH-S3-1, SRS §3.6.2).
-- Ảnh hưởng dữ liệu: chèn 2 carrier GHN/GHTK nếu chưa có (ON CONFLICT DO NOTHING);
-- các cột bổ sung trên bảng shipments đều nullable hoặc có giá trị mặc định nên an toàn với dữ liệu hiện có.

INSERT INTO carriers (id, code, name, is_active)
VALUES
    ('b0000000-0000-0000-0000-000000000001'::uuid, 'GHN', 'Giao Hàng Nhanh (GHN)', true),
    ('b0000000-0000-0000-0000-000000000002'::uuid, 'GHTK', 'Giao Hàng Tiết Kiệm (GHTK)', true)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS carrier_code varchar(30),
    ADD COLUMN IF NOT EXISTS estimated_delivery_days integer,
    ADD COLUMN IF NOT EXISTS estimated_delivery_at timestamp,
    ADD COLUMN IF NOT EXISTS recipient_name varchar(255),
    ADD COLUMN IF NOT EXISTS recipient_phone varchar(30),
    ADD COLUMN IF NOT EXISTS shipping_address varchar(500),
    ADD COLUMN IF NOT EXISTS weight_grams integer,
    ADD COLUMN IF NOT EXISTS order_total numeric(15, 2),
    ADD COLUMN IF NOT EXISTS free_shipping_applied boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(128),
    ADD COLUMN IF NOT EXISTS updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE shipments s
SET carrier_code = c.code
FROM carriers c
WHERE s.carrier_id = c.id
  AND s.carrier_code IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_shipments_idempotency_key
    ON shipments(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_shipments_status
    ON shipments(status);

CREATE INDEX IF NOT EXISTS idx_shipment_status_history_shipment
    ON shipment_status_history(shipment_id, occurred_at DESC);
