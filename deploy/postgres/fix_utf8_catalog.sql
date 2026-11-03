
-- Cập nhật danh mục với tiếng Việt chuẩn UTF-8
UPDATE categories SET name_vi = 'Điện tử' WHERE id = '11111111-1111-1111-1111-111111111111';
UPDATE categories SET name_vi = 'Thời trang' WHERE id = '22222222-2222-2222-2222-222222222222';
UPDATE categories SET name_vi = 'Nhà cửa & Đời sống' WHERE id = '33333333-3333-3333-3333-333333333333';
UPDATE categories SET name_vi = 'Thể thao' WHERE id = '44444444-4444-4444-4444-444444444444';
UPDATE categories SET name_vi = 'Sách' WHERE id = '55555555-5555-5555-5555-555555555555';
UPDATE categories SET name_vi = 'Đồ sưu tầm' WHERE id = '66666666-6666-6666-6666-666666666666';

-- Cập nhật mô tả sản phẩm với tiếng Việt chuẩn UTF-8
UPDATE products SET description = 'Tai nghe chống ồn cao cấp Sony WH-1000XM5 với âm thanh đỉnh cao và pin 30 giờ.' WHERE id = 'a0000001-0000-0000-0000-000000000001';
UPDATE products SET description = 'Laptop mỏng nhẹ hiệu năng mạnh mẽ từ chip Apple M3.' WHERE id = 'a0000002-0000-0000-0000-000000000002';
UPDATE products SET description = 'Giày thể thao êm ái phong cách trẻ trung.' WHERE id = 'a0000003-0000-0000-0000-000000000003';
UPDATE products SET description = 'Đồng hồ cơ cổ điển tinh xảo Thụy Sĩ.' WHERE id = 'a0000004-0000-0000-0000-000000000004';
UPDATE products SET description = 'Ghế công thái học bảo vệ cột sống, thoáng khí.' WHERE id = 'a0000005-0000-0000-0000-000000000005';
UPDATE products SET description = 'Bộ truyện tranh siêu anh hùng hiếm xuất bản thập niên 70.' WHERE id = 'a0000006-0000-0000-0000-000000000006';
