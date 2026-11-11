-- Bổ sung cột price và original_price vào bảng skus trên môi trường Supabase.
-- Supabase đã được khởi tạo thủ công trước khi migration V2 được áp dụng,
-- nên các cột này tồn tại trên local PostgreSQL nhưng thiếu trên remote Supabase.
-- Dùng ADD COLUMN IF NOT EXISTS để idempotent — không lỗi khi chạy lại.

ALTER TABLE skus ADD COLUMN IF NOT EXISTS price numeric(15, 2) NOT NULL DEFAULT 0;
ALTER TABLE skus ADD COLUMN IF NOT EXISTS original_price numeric(15, 2);

-- Thêm CHECK constraint nếu chưa tồn tại (Supabase cũ có thể thiếu)
DO $$
BEGIN
    -- Ràng buộc: giá bán phải >= 0
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_sku_price' AND conrelid = 'skus'::regclass
    ) THEN
        ALTER TABLE skus ADD CONSTRAINT chk_sku_price CHECK (price >= 0);
    END IF;

    -- Ràng buộc: giá gốc (giá vốn) phải >= giá bán nếu có; đây là quy tắc nghiệp vụ hiện tại
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_sku_prices' AND conrelid = 'skus'::regclass
    ) THEN
        ALTER TABLE skus ADD CONSTRAINT chk_sku_prices CHECK (original_price IS NULL OR original_price >= price);
    END IF;

    -- Ràng buộc trạng thái SKU
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_sku_status' AND conrelid = 'skus'::regclass
    ) THEN
        ALTER TABLE skus ADD CONSTRAINT chk_sku_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
    END IF;
END $$;

-- Index hỗ trợ lọc sản phẩm theo khoảng giá
CREATE INDEX IF NOT EXISTS idx_skus_price ON skus(price);
