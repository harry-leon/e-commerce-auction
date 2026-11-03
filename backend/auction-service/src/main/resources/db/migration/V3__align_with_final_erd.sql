-- ERD auctions chỉ có start_time/end_time; bỏ mốc tạo bổ sung ở V2, không đổi lịch đấu giá hay phiên bản khóa.
-- Sao lưu created_at trước triển khai nếu dữ liệu cũ cần phục vụ tra soát.
ALTER TABLE auctions DROP COLUMN created_at;
