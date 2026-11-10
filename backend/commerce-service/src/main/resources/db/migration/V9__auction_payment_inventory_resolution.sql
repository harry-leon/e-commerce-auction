-- Tách kết quả thu tiền khỏi kết quả kho: đơn winner chỉ PAID sau commit kho;
-- dữ liệu payment cũ giữ NULL và tiếp tục dùng luồng thanh toán cũ, không backfill tác vụ lịch sử.
ALTER TABLE payments ADD COLUMN inventory_status varchar(20)
    CHECK (inventory_status IN ('PENDING', 'CONFIRMED', 'REVIEW'));
-- COMMIT xác nhận toàn bộ phiếu của đơn trong một transaction Fulfillment, cạnh tranh an toàn với sweeper.
ALTER TABLE payment_inventory_tasks DROP CONSTRAINT payment_inventory_tasks_action_check;
ALTER TABLE payment_inventory_tasks ADD CONSTRAINT payment_inventory_tasks_action_check
    CHECK (action IN ('CONFIRM', 'RELEASE', 'COMMIT'));
