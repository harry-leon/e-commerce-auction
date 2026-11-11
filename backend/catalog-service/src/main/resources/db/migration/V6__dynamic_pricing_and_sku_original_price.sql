-- Migration V6: Bỏ cột giá bán cố định (skus.price), chuẩn hóa skus.original_price làm minPrice
-- và tạo bảng pricing_rules phục vụ công cụ tính giá bán động trên backend.

-- 1. Backfill original_price cho các SKU cũ còn thiếu trước khi ràng buộc NOT NULL.
UPDATE skus
SET original_price = price
WHERE original_price IS NULL;

-- 2. Xóa các ràng buộc và chỉ mục phụ thuộc skus.price rồi bỏ cột price khỏi bảng skus.
ALTER TABLE skus DROP CONSTRAINT IF EXISTS chk_sku_price;
ALTER TABLE skus DROP CONSTRAINT IF EXISTS chk_sku_prices;
DROP INDEX IF EXISTS idx_skus_price;
ALTER TABLE skus DROP COLUMN IF EXISTS price;

-- 3. Ràng buộc original_price bắt buộc >= 0 và lập index phục vụ tra cứu/lọc sơ bộ.
ALTER TABLE skus ALTER COLUMN original_price SET NOT NULL;
ALTER TABLE skus DROP CONSTRAINT IF EXISTS chk_sku_original_price;
ALTER TABLE skus ADD CONSTRAINT chk_sku_original_price CHECK (original_price >= 0);
CREATE INDEX IF NOT EXISTS idx_skus_original_price ON skus(original_price);

-- 4. Bảng cấu hình quy tắc giá động theo danh mục, biên lợi nhuận, mùa vụ và biến động thị trường.
CREATE TABLE IF NOT EXISTS pricing_rules (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    varchar(150) NOT NULL,
    category_id             uuid REFERENCES categories(id) ON DELETE CASCADE,
    margin_rate             numeric(7,4) NOT NULL DEFAULT 0.0000,
    season_factor           numeric(7,4) NOT NULL DEFAULT 0.0000,
    market_adjustment_rate  numeric(7,4) NOT NULL DEFAULT 0.0000,
    priority                integer NOT NULL DEFAULT 0,
    start_date              timestamp,
    end_date                timestamp,
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamp NOT NULL DEFAULT now(),
    updated_at              timestamp NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_rule_margin CHECK (margin_rate >= 0),
    CONSTRAINT chk_pricing_rule_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_pricing_rules_active_lookup
    ON pricing_rules(is_active, category_id, priority DESC);

-- 5. Khởi tạo quy tắc mặc định toàn hệ thống (+15% biên lợi nhuận) nếu bảng đang trống.
INSERT INTO pricing_rules (id, name, category_id, margin_rate, season_factor, market_adjustment_rate, priority, is_active)
SELECT gen_random_uuid(), 'Default Platform Margin (+15%)', NULL, 0.1500, 0.0000, 0.0000, 0, true
WHERE NOT EXISTS (SELECT 1 FROM pricing_rules);
