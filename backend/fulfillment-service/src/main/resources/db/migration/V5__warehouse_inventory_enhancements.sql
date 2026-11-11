-- Mở rộng bảng sổ cái bất biến với khóa idempotency, lý do và tác nhân thực hiện;
-- đồng thời seed thêm dữ liệu kho mẫu phục vụ tra cứu và thao tác đa kho cho Seller/Admin.
-- Toàn bộ cột mới đều nullable nên tương thích với dữ liệu và script seed hiện có.
ALTER TABLE inventory_ledger
  ADD COLUMN IF NOT EXISTS idempotency_key varchar(128),
  ADD COLUMN IF NOT EXISTS reason varchar(500),
  ADD COLUMN IF NOT EXISTS actor_id uuid;

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_ledger_idempotency_key
  ON inventory_ledger(idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inventory_ledger_inventory_created
  ON inventory_ledger(inventory_id, created_at DESC);

-- Bổ sung kho mẫu khu vực Miền Bắc, Miền Trung và kho Hàng Hoàn Trả bên cạnh kho mặc định WH_001.
INSERT INTO warehouses (id, code, name, address, type, is_active)
VALUES
  (
    'a0000000-0000-0000-0000-000000000002'::uuid,
    'WH_002',
    'Kho Miền Bắc Nexus (Hanoi Hub)',
    'Khu Công nghiệp Bắc Thăng Long, Đông Anh, Hà Nội',
    'STANDARD',
    true
  ),
  (
    'a0000000-0000-0000-0000-000000000003'::uuid,
    'WH_003',
    'Kho Miền Trung Nexus (Da Nang Hub)',
    'Khu Công nghiệp Hòa Khánh, Liên Chiểu, TP. Đà Nẵng',
    'STANDARD',
    true
  ),
  (
    'a0000000-0000-0000-0000-000000000004'::uuid,
    'WH_RETURN',
    'Kho Hàng Hoàn Trả Nexus (Return Center)',
    'Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh',
    'RETURN',
    true
  )
ON CONFLICT (code) DO NOTHING;
