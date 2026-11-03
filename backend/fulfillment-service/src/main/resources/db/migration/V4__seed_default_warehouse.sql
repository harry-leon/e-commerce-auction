-- Seed kho mặc định WH_001 phục vụ khởi tạo hệ thống và gán tồn kho theo SRS §2.6 (DEFAULT_WAREHOUSE_ID = WH_001).
-- Luồng: chèn bản ghi kho trung tâm nếu chưa tồn tại mã WH_001.
-- Quy tắc: id cố định UUID chuẩn để các service phụ thuộc hoặc script kiểm thử có thể tham chiếu nhất quán.
INSERT INTO warehouses (id, code, name, address, type, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'WH_001',
    'Kho trung tâm Nexus (Central Warehouse)',
    'Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh',
    'STANDARD',
    true
)
ON CONFLICT (code) DO NOTHING;
