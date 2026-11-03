-- Seed sample categories for catalog
INSERT INTO categories (id, name, name_vi, level, status) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Electronics', 'Điện tử', 1, 'ACTIVE'),
  ('22222222-2222-2222-2222-222222222222', 'Fashion', 'Thời trang', 1, 'ACTIVE'),
  ('33333333-3333-3333-3333-333333333333', 'Home & Living', 'Nhà cửa & Đời sống', 1, 'ACTIVE'),
  ('44444444-4444-4444-4444-444444444444', 'Sports', 'Thể thao', 1, 'ACTIVE'),
  ('55555555-5555-5555-5555-555555555555', 'Books', 'Sách', 1, 'ACTIVE'),
  ('66666666-6666-6666-6666-666666666666', 'Collectibles', 'Đồ sưu tầm', 1, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Seed sample products
INSERT INTO products (id, seller_id, category_id, name, description, status, auction_enabled) VALUES
  ('a0000001-0000-0000-0000-000000000001', '0442e77a-c2c0-448e-baea-9e3584d78eec', '11111111-1111-1111-1111-111111111111', 'Sony WH-1000XM5 Wireless Headphones', 'Tai nghe chống ồn cao cấp Sony WH-1000XM5 với âm thanh đỉnh cao và pin 30 giờ.', 'ACTIVE', true),
  ('a0000002-0000-0000-0000-000000000002', '0442e77a-c2c0-448e-baea-9e3584d78eec', '11111111-1111-1111-1111-111111111111', 'Apple MacBook Air M3 13"', 'Laptop mỏng nhẹ hiệu năng mạnh mẽ từ chip Apple M3.', 'ACTIVE', false),
  ('a0000003-0000-0000-0000-000000000003', '0442e77a-c2c0-448e-baea-9e3584d78eec', '44444444-4444-4444-4444-444444444444', 'Nike Air Max 270', 'Giày thể thao êm ái phong cách trẻ trung.', 'ACTIVE', false),
  ('a0000004-0000-0000-0000-000000000004', '0442e77a-c2c0-448e-baea-9e3584d78eec', '66666666-6666-6666-6666-666666666666', 'Vintage Mechanical Watch', 'Đồng hồ cơ cổ điển tinh xảo Thụy Sĩ.', 'ACTIVE', true),
  ('a0000005-0000-0000-0000-000000000005', '0442e77a-c2c0-448e-baea-9e3584d78eec', '33333333-3333-3333-3333-333333333333', 'Ergonomic Office Chair', 'Ghế công thái học bảo vệ cột sống, thoáng khí.', 'ACTIVE', false),
  ('a0000006-0000-0000-0000-000000000006', '0442e77a-c2c0-448e-baea-9e3584d78eec', '66666666-6666-6666-6666-666666666666', 'Rare Comic Book Collection', 'Bộ truyện tranh siêu anh hùng hiếm xuất bản thập niên 70.', 'ACTIVE', true)
ON CONFLICT (id) DO NOTHING;

-- Seed SKUs
INSERT INTO skus (id, product_id, sku_code, price, original_price, status) VALUES
  ('b0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000001', 'SNY-WH1000XM5-BK', 279.99, 349.99, 'ACTIVE'),
  ('b0000002-0000-0000-0000-000000000002', 'a0000002-0000-0000-0000-000000000002', 'APL-MBA-M3-13-SLV', 1099.00, 1199.00, 'ACTIVE'),
  ('b0000003-0000-0000-0000-000000000003', 'a0000003-0000-0000-0000-000000000003', 'NKE-AM270-WHT-42', 149.99, 180.00, 'ACTIVE'),
  ('b0000004-0000-0000-0000-000000000004', 'a0000004-0000-0000-0000-000000000004', 'VTG-MECH-001', 899.00, 999.00, 'ACTIVE'),
  ('b0000005-0000-0000-0000-000000000005', 'a0000005-0000-0000-0000-000000000005', 'ERGO-CHAIR-BLK', 349.00, 420.00, 'ACTIVE'),
  ('b0000006-0000-0000-0000-000000000006', 'a0000006-0000-0000-0000-000000000006', 'COMIC-MRVL-70S', 2500.00, 3000.00, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Seed Images
INSERT INTO product_images (id, product_id, url, sort_order) VALUES
  ('c0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600&h=600&fit=crop&auto=format', 0),
  ('c0000002-0000-0000-0000-000000000002', 'a0000002-0000-0000-0000-000000000002', 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=600&h=600&fit=crop&auto=format', 0),
  ('c0000003-0000-0000-0000-000000000003', 'a0000003-0000-0000-0000-000000000003', 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=600&h=600&fit=crop&auto=format', 0),
  ('c0000004-0000-0000-0000-000000000004', 'a0000004-0000-0000-0000-000000000004', 'https://images.unsplash.com/photo-1524592094714-0f0654e20314?w=600&h=600&fit=crop&auto=format', 0),
  ('c0000005-0000-0000-0000-000000000005', 'a0000005-0000-0000-0000-000000000005', 'https://images.unsplash.com/photo-1592078615290-033ee584e267?w=600&h=600&fit=crop&auto=format', 0),
  ('c0000006-0000-0000-0000-000000000006', 'a0000006-0000-0000-0000-000000000006', 'https://images.unsplash.com/photo-1531956531700-dc0ee0f1f9a5?w=600&h=600&fit=crop&auto=format', 0)
ON CONFLICT (id) DO NOTHING;
