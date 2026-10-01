-- Baseline. Mỗi service sở hữu DB riêng (SRS 4.1), không dùng chung schema.
-- Tên file: V<số>__<mô_tả>.sql. KHÔNG sửa file đã merge, chỉ thêm file mới.
CREATE TABLE IF NOT EXISTS service_metadata (
    key        VARCHAR(100) PRIMARY KEY,
    value      VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
