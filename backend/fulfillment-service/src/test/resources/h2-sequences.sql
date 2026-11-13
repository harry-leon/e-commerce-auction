CREATE SEQUENCE IF NOT EXISTS fulfillment_sku_code_seq START WITH 1 INCREMENT BY 1;
-- Fixture H2 cho test orchestration; correctness transaction/unique của guard được kiểm chứng thêm bằng PostgreSQL riêng.
CREATE TABLE IF NOT EXISTS shipment_dispatch_attempts (
    id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE, carrier_code varchar(30) NOT NULL,
    request_payload text NOT NULL, status varchar(20) NOT NULL, tracking_number varchar(255),
    last_error_type varchar(100), created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
-- Khóa lifecycle cùng transaction shipment; không thay thế test PostgreSQL concurrency thực.
CREATE TABLE IF NOT EXISTS shipment_dispatch_locks (order_id uuid PRIMARY KEY);
