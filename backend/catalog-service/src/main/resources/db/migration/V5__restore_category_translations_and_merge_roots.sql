-- Khôi phục tên tiếng Việt vì giao diện cần chuyển EN/VI từ dữ liệu thật, không dùng mock hoặc suy đoán ở client.
ALTER TABLE categories ADD COLUMN IF NOT EXISTS name_vi varchar(255);

-- Sáu category gốc chuẩn giữ UUID cũ để không làm mất liên kết sản phẩm, URL hoặc dữ liệu audit.
UPDATE categories SET name = 'Electronics', name_vi = 'Điện tử' WHERE id = '00000000-0000-0000-0000-000000000001';
UPDATE categories SET name = 'Fashion', name_vi = 'Thời trang' WHERE id = '22222222-2222-2222-2222-222222222222';
UPDATE categories SET name = 'Home & Living', name_vi = 'Nhà cửa & Đời sống' WHERE id = '33333333-3333-3333-3333-333333333333';
UPDATE categories SET name = 'Sports', name_vi = 'Thể thao' WHERE id = '44444444-4444-4444-4444-444444444444';
UPDATE categories SET name = 'Books', name_vi = 'Sách' WHERE id = '55555555-5555-5555-5555-555555555555';
UPDATE categories SET name = 'Collectibles', name_vi = 'Đồ sưu tầm' WHERE id = '66666666-6666-6666-6666-666666666666';

-- Chuẩn hóa tên nhánh có sản phẩm để tìm kiếm/hiển thị theo hai ngôn ngữ nhất quán.
UPDATE categories SET name = 'Personal Devices', name_vi = 'Thiết bị cá nhân' WHERE id = '4a4e84d6-8611-5dd6-9834-8978b2e135b0';
UPDATE categories SET name = 'Casual Wear', name_vi = 'Trang phục thường ngày' WHERE id = '93cf1ed3-7627-5864-be75-e6d752bbfff3';
UPDATE categories SET name = 'Home Essentials', name_vi = 'Đồ dùng gia đình' WHERE id = 'af853218-bcb1-5286-b84e-2c0b4d9d60e1';
UPDATE categories SET name = 'Fitness Gear', name_vi = 'Dụng cụ vận động' WHERE id = 'fd7578b7-34e2-5079-97cb-bf7329635b4c';
UPDATE categories SET name = 'Study Corner', name_vi = 'Góc học tập' WHERE id = 'a0c89102-80a8-5031-a579-fbbbdbdf7bf4';
UPDATE categories SET name = 'Creative Gifts', name_vi = 'Quà tặng sáng tạo' WHERE id = '11329bc4-76b0-557f-b57b-11d0f0d7b24b';

-- Gộp sáu root trùng nghĩa: chỉ đổi parent của nhánh, không đổi category_id của bất kỳ sản phẩm nào.
UPDATE categories SET parent_id = '00000000-0000-0000-0000-000000000001' WHERE parent_id = 'e0a0a4f3-19bf-5c06-a272-27cd4b34975a';
UPDATE categories SET parent_id = '22222222-2222-2222-2222-222222222222' WHERE parent_id = '78f86e83-e899-5797-978a-d95a3c8e1fc4';
UPDATE categories SET parent_id = '33333333-3333-3333-3333-333333333333' WHERE parent_id = '67b91378-2d25-5aeb-aaeb-a8deedc19b02';
UPDATE categories SET parent_id = '44444444-4444-4444-4444-444444444444' WHERE parent_id = 'ad9aed5d-f2f2-5e7a-b0ac-b16cf6b0a9a8';
UPDATE categories SET parent_id = '55555555-5555-5555-5555-555555555555' WHERE parent_id = 'f3938a4f-9e79-5d03-9ff9-bdf09839fd84';
UPDATE categories SET parent_id = '66666666-6666-6666-6666-666666666666' WHERE parent_id = 'd8ef1390-15fd-5fc8-908a-adab65cf4962';

-- Root trùng chỉ bị xóa sau khi nhánh đã chuyển đi và không còn product tham chiếu, tránh mất dữ liệu nếu DB khác có phát sinh mới.
DELETE FROM categories duplicate_root
WHERE duplicate_root.id IN (
    'e0a0a4f3-19bf-5c06-a272-27cd4b34975a', '78f86e83-e899-5797-978a-d95a3c8e1fc4',
    '67b91378-2d25-5aeb-aaeb-a8deedc19b02', 'ad9aed5d-f2f2-5e7a-b0ac-b16cf6b0a9a8',
    'f3938a4f-9e79-5d03-9ff9-bdf09839fd84', 'd8ef1390-15fd-5fc8-908a-adab65cf4962'
)
AND NOT EXISTS (SELECT 1 FROM categories child WHERE child.parent_id = duplicate_root.id)
AND NOT EXISTS (SELECT 1 FROM products product WHERE product.category_id = duplicate_root.id);

-- Các category khác từ dữ liệu người dùng vẫn hiển thị được nếu chưa có bản dịch riêng.
UPDATE categories SET name_vi = name WHERE name_vi IS NULL OR btrim(name_vi) = '';
ALTER TABLE categories ALTER COLUMN name_vi SET NOT NULL;
