-- Kiện thực do Admin khai báo, chưa phải quyền dispatch; không backfill hoặc gọi carrier khi nâng schema.
-- order_id thuộc Commerce, không FK xuyên service. Không lưu PII người nhận; unique Order và actor/key bảo vệ replay.
CREATE TABLE shipment_parcels (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    carrier_code varchar(30) NOT NULL REFERENCES carriers(code),
    service_type varchar(30) NOT NULL,
    weight_grams integer NOT NULL CHECK (weight_grams>0),
    to_district_id integer NOT NULL CHECK (to_district_id>0),
    to_ward_code varchar(100) NOT NULL CHECK (length(trim(to_ward_code))>0),
    registered_by uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(registered_by,idempotency_key)
);
