-- Dấu lần gửi carrier tồn tại độc lập transaction shipment; dữ liệu cũ không bị backfill hoặc gửi lại.
-- UNKNOWN cũng bao gồm request đang gửi: tuyệt đối không giải phóng tự động để tránh tạo hai vận đơn khi mất ACK.
CREATE TABLE shipment_dispatch_attempts (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    carrier_code varchar(30) NOT NULL,
    request_payload text NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('UNKNOWN','COMMITTED')),
    tracking_number varchar(255),
    last_error_type varchar(100),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);
