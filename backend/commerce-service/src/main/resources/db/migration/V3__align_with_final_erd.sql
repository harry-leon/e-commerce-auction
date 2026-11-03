-- Đồng bộ giỏ hàng/voucher với ERD: giỏ chỉ lưu hạn dùng, tên voucher tối đa 30 ký tự.
-- Không cắt chuỗi âm thầm: nếu dữ liệu cũ dài quá 30, migration rollback để quản trị sửa tên rồi chạy lại.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM vouchers WHERE length(name) > 30) THEN
        RAISE EXCEPTION 'Ten voucher vuot 30 ky tu; can sua du lieu truoc khi chay migration';
    END IF;
END $$;
ALTER TABLE vouchers ALTER COLUMN name TYPE varchar(30);
-- expires_at được giữ nguyên; sao lưu created_at trước triển khai nếu cần lịch sử ngoài ERD.
ALTER TABLE carts DROP COLUMN created_at;
