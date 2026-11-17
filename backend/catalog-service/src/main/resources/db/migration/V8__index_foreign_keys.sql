-- Bổ sung index cho các cột khóa ngoại chưa có index để tăng tốc join/lọc theo cha
-- và tránh quét toàn bảng con khi DELETE/UPDATE bản ghi cha (PostgreSQL không tự tạo index cho FK).
-- Chỉ thêm index, không đổi dữ liệu hay hành vi; IF NOT EXISTS để chạy an toàn trên môi trường đã tạo tay.
-- Không dùng CONCURRENTLY vì Flyway chạy trong transaction; bảng hiện nhỏ nên khóa ghi chỉ trong thời gian ngắn.

-- Duyệt cây danh mục: tìm danh mục con theo parent_id.
CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories (parent_id);

-- Product nạp ảnh bằng @OrderBy("sortOrder ASC") nên index ghép phục vụ cả lọc lẫn sắp xếp.
CREATE INDEX IF NOT EXISTS idx_product_images_product_sort ON product_images (product_id, sort_order);

-- Unique (product_id, order_id, user_id) không phủ được truy vấn theo sku_id.
CREATE INDEX IF NOT EXISTS idx_product_reviews_sku_id ON product_reviews (sku_id);

-- Tìm quy tắc giá áp dụng cho một danh mục.
CREATE INDEX IF NOT EXISTS idx_pricing_rules_category_id ON pricing_rules (category_id);
