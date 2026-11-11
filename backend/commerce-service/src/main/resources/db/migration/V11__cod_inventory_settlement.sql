-- Lưu kết quả kho cho COD độc lập với việc đã xác nhận thu tiền; không sửa V10 đã được chạy/test.
-- Giá chính thức vẫn giữ khi kho lỗi để không xóa dấu vết tiền thực tế đã thu.
ALTER TABLE orders ADD COLUMN fulfillment_status varchar(20)
    CHECK (fulfillment_status IN ('PENDING','CONFIRMED','REVIEW'));
ALTER TABLE payment_inventory_tasks DROP CONSTRAINT payment_inventory_tasks_action_check;
ALTER TABLE payment_inventory_tasks ADD CONSTRAINT payment_inventory_tasks_action_check
    CHECK (action IN ('CONFIRM','RELEASE','COMMIT','COD_COMMIT'));

-- Thu hồi trạng thái sẵn sàng giao của COD V10 còn task CONFIRM chưa xong, giữ nguyên lịch sử và giá.
-- Thay từng CONFIRM bằng một COD_COMMIT nguyên tử; Fulfillment chấp nhận cả phiếu đã confirm và phiếu pending.
UPDATE orders o SET fulfillment_status='PENDING'
WHERE o.payment_method='COD' AND o.price_finalized AND EXISTS (
    SELECT 1 FROM payment_inventory_tasks t WHERE t.order_id=o.id AND t.action='CONFIRM' AND NOT t.done);
INSERT INTO order_status_history(id,order_id,from_status,to_status,reason)
SELECT gen_random_uuid(), id, 'PAID', 'PENDING_PAYMENT', 'COD_INVENTORY_PENDING_V11'
FROM orders WHERE fulfillment_status='PENDING' AND status='PAID';
UPDATE orders SET status='PENDING_PAYMENT' WHERE fulfillment_status='PENDING' AND status='PAID';
INSERT INTO payment_inventory_tasks(id,order_id,target_id,action)
SELECT gen_random_uuid(), id, id, 'COD_COMMIT' FROM orders WHERE fulfillment_status='PENDING'
ON CONFLICT DO NOTHING;
UPDATE payment_inventory_tasks t SET done=true
FROM orders o WHERE t.order_id=o.id AND o.fulfillment_status='PENDING' AND t.action='CONFIRM' AND NOT t.done;
