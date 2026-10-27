-- ERD đã chốt chỉ có categories.name; bỏ cột bản dịch do V3 thêm, giữ nguyên name và quan hệ cây.
-- Cần sao lưu name_vi trước khi triển khai nếu có bản dịch riêng; không tự ghi đè tên chính bằng bản dịch.
ALTER TABLE categories DROP COLUMN name_vi;
