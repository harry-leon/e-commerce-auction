-- ERD reservation chỉ lưu expires_at. Bỏ kiểm tra phụ thuộc created_at trước khi bỏ cột này.
-- Giữ số lượng, tham chiếu, trạng thái và hạn giữ hàng; không thay bằng CHECK so với now() vì dữ liệu có thể hết hạn hợp lệ.
ALTER TABLE inventory_reservations DROP CONSTRAINT chk_reservation_expiry;
ALTER TABLE inventory_reservations DROP COLUMN created_at;
