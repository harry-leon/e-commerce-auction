-- Ghi ý định bàn giao sau xác nhận tiền/kho, cùng transaction PAID; không backfill hoặc gọi carrier.
-- WAITING_PARCEL chưa phải quyền dispatch: consumer sau này phải có kiện thật và kiểm tra lại refund/hold/state.
-- Không lưu PII, credential hoặc payload carrier; unique theo Order/Payment chặn IPN/worker lặp tạo thêm việc.
CREATE TABLE shipping_handoff_tasks (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    payment_id uuid NOT NULL UNIQUE REFERENCES payments(id),
    amount numeric(15,2) NOT NULL CHECK (amount > 0 AND amount=trunc(amount)),
    currency varchar(3) NOT NULL DEFAULT 'VND' CHECK (currency='VND'),
    state varchar(30) NOT NULL DEFAULT 'WAITING_PARCEL' CHECK (state='WAITING_PARCEL'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
