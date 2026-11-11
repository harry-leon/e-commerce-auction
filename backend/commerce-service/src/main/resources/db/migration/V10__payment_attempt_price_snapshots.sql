-- Tách giá ước tính khỏi giá chính thức để Create Order chưa ghi nhận doanh thu/giá đã thanh toán.
-- Sao lưu giá cũ sang estimated_* trước khi bỏ NOT NULL; đơn lịch sử đã hoàn tất giữ nguyên giá.
ALTER TABLE orders ADD COLUMN estimated_subtotal numeric(15,2);
ALTER TABLE orders ADD COLUMN estimated_shipping_fee numeric(15,2);
ALTER TABLE orders ADD COLUMN estimated_discount numeric(15,2);
ALTER TABLE orders ADD COLUMN estimated_total numeric(15,2);
ALTER TABLE orders ADD COLUMN price_finalized boolean NOT NULL DEFAULT true;
UPDATE orders SET estimated_subtotal=subtotal, estimated_shipping_fee=shipping_fee,
    estimated_discount=discount, estimated_total=total;
ALTER TABLE order_items ADD COLUMN estimated_unit_price numeric(15,2);
UPDATE order_items SET estimated_unit_price=unit_price;

-- Snapshot attempt cũ được lấy từ chính giá đã gửi cổng, không lấy Catalog mới khi nâng cấp.
-- Dừng nâng cấp nếu attempt đang chờ có dữ liệu giá lệch, thay vì tạo snapshot không thể đối soát.
-- Flyway/PostgreSQL rollback toàn migration; vận hành cần đối soát các đơn lỗi trước khi chạy lại.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payments p JOIN orders o ON o.id=p.order_id
        WHERE p.status='PENDING' AND (
            p.amount IS DISTINCT FROM o.total
            OR o.total IS DISTINCT FROM (o.subtotal-o.discount+o.shipping_fee)
            OR o.subtotal IS DISTINCT FROM (
                SELECT COALESCE(sum(i.unit_price*i.quantity),0) FROM order_items i WHERE i.order_id=o.id)
            OR NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id=o.id))
    ) THEN
        RAISE EXCEPTION 'Pending payment prices require reconciliation before V10';
    END IF;
END $$;
ALTER TABLE payments ADD COLUMN price_snapshot jsonb;
ALTER TABLE payments ADD COLUMN inventory_ref_id uuid;
UPDATE payments p SET inventory_ref_id=p.order_id, price_snapshot=jsonb_build_object(
    'subtotal', o.subtotal, 'shippingFee', o.shipping_fee, 'discount', o.discount, 'total', p.amount,
    'items', COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'orderItemId', i.id, 'skuId', i.sku_id, 'quantity', i.quantity,
        'unitPrice', i.unit_price, 'itemTotal', i.unit_price*i.quantity) ORDER BY i.id)
        FROM order_items i WHERE i.order_id=o.id), '[]'::jsonb))
FROM orders o WHERE o.id=p.order_id;
ALTER TABLE payments ALTER COLUMN price_snapshot SET NOT NULL;
ALTER TABLE payments ALTER COLUMN inventory_ref_id SET NOT NULL;
ALTER TABLE payments ADD CONSTRAINT chk_payment_snapshot_amount
    CHECK (price_snapshot ? 'total' AND (price_snapshot->>'total') IS NOT NULL
        AND amount=(price_snapshot->>'total')::numeric);

ALTER TABLE orders ALTER COLUMN subtotal DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN shipping_fee DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN discount DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN total DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN unit_price DROP NOT NULL;
-- Không xóa giá của giao dịch đã SUCCESS nhưng đang chờ kho; đơn chưa thu tiền chuyển sang giá chưa chốt.
UPDATE orders o SET price_finalized=false, subtotal=NULL, shipping_fee=NULL, discount=NULL, total=NULL
WHERE o.status='PENDING_PAYMENT' AND NOT EXISTS (
    SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='SUCCESS');
UPDATE order_items i SET unit_price=NULL FROM orders o WHERE i.order_id=o.id AND NOT o.price_finalized;
ALTER TABLE orders ADD CONSTRAINT chk_finalized_order_prices CHECK (
    NOT price_finalized OR (subtotal IS NOT NULL AND shipping_fee IS NOT NULL AND discount IS NOT NULL AND total IS NOT NULL));

-- Nhiều attempt được lưu lịch sử, nhưng chỉ một giao dịch PENDING/SUCCESS cho mỗi đơn.
-- URL hết hạn vẫn PENDING tới khi đối soát; không mở attempt mới chỉ vì đồng hồ client hết giờ.
DROP INDEX uq_vnpay_order;
CREATE UNIQUE INDEX uq_vnpay_active_order ON payments(order_id)
    WHERE provider='VNPAY' AND status IN ('PENDING','SUCCESS');
CREATE INDEX idx_payment_order_attempts ON payments(order_id, created_at DESC, id DESC);
-- REVIEW lưu tiền thu muộn của attempt đã FAILED mà không đụng unique của attempt đang PENDING.
ALTER TABLE payments DROP CONSTRAINT chk_payment_status;
ALTER TABLE payments ADD CONSTRAINT chk_payment_status CHECK (status IN ('PENDING','SUCCESS','FAILED','CANCELLED','REVIEW'));
DROP INDEX uq_vnpay_success_ref;
CREATE UNIQUE INDEX uq_vnpay_success_ref ON payments(provider_ref)
    WHERE provider='VNPAY' AND status IN ('SUCCESS','REVIEW');

-- Notification đơn chưa thu tiền dùng tổng ước tính; payload email có thể chứa unitPrice null.
-- Dữ liệu outbox cũ giữ nguyên; không phát lại thông báo lịch sử trong migration.
