CREATE TABLE warehouses (
  id uuid NOT NULL,
  code varchar(30) NOT NULL UNIQUE,
  name varchar(255) NOT NULL,
  address varchar(500) NOT NULL,
  type varchar(20) NOT NULL DEFAULT 'STANDARD',
  is_active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id),
  CONSTRAINT chk_warehouse_type CHECK (type IN ('STANDARD', 'BONDED', 'RETURN', 'CROSS_DOCK'))
);

CREATE TABLE inventories (
  id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  total_qty integer NOT NULL DEFAULT 0,
  reserved_qty integer NOT NULL DEFAULT 0,
  unavailable_qty integer NOT NULL DEFAULT 0,
  version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
  CONSTRAINT uq_inventory_sku_warehouse UNIQUE (sku_id, warehouse_id), -- 1 SKU tại 1 kho chỉ có đúng 1 bản ghi tồn kho
  CONSTRAINT chk_inventory_qty_positive CHECK (
    total_qty >= 0 AND 
    reserved_qty >= 0 AND 
    unavailable_qty >= 0
  ),
  -- BR-CFG-32 & BR-INV-03: Tuyệt đối không cho phép bán vượt tồn kho (ALLOW_OVERSELL = false)
  CONSTRAINT chk_inventory_no_oversell CHECK (total_qty >= (reserved_qty + unavailable_qty)),
  CONSTRAINT chk_inventory_version CHECK (version >= 1)
);

CREATE TABLE inventory_reservations (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  quantity integer NOT NULL,
  ref_type varchar(20) NOT NULL,
  ref_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamp NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (inventory_id) REFERENCES inventories(id) ON DELETE CASCADE,
  CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
  CONSTRAINT chk_reservation_ref_type CHECK (ref_type IN ('ORDER', 'AUCTION')),
  CONSTRAINT chk_reservation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
  CONSTRAINT chk_reservation_expiry CHECK (expires_at >= created_at)
);

CREATE TABLE inventory_ledger (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  movement_type varchar(20) NOT NULL,
  quantity integer NOT NULL,
  ref_type varchar(20) NOT NULL,
  ref_id uuid,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (inventory_id) REFERENCES inventories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_ledger_movement_type CHECK (movement_type IN ('INBOUND', 'OUTBOUND', 'RESERVE', 'RELEASE', 'ADJUSTMENT')),
  CONSTRAINT chk_ledger_quantity CHECK (quantity <> 0) -- Biến động kho bắt buộc phải khác 0
);

CREATE TABLE carriers (
  id uuid NOT NULL,
  code varchar(30) NOT NULL UNIQUE,
  name varchar(100) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id)
);

CREATE TABLE shipments (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  carrier_id uuid NOT NULL,
  service_type varchar(50),
  shipping_fee numeric(15, 2) NOT NULL DEFAULT 0,
  tracking_no varchar(100),
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
  FOREIGN KEY (carrier_id) REFERENCES carriers(id) ON DELETE RESTRICT,
  CONSTRAINT chk_shipment_shipping_fee CHECK (shipping_fee >= 0),
  CONSTRAINT chk_shipment_status CHECK (status IN (
    'PENDING', 'PICKING', 'PACKED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED', 'CANCELLED'
  ))
);

CREATE TABLE shipment_status_history (
  id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  carrier_status varchar(50),
  internal_status varchar(30) NOT NULL,
  occurred_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (shipment_id) REFERENCES shipments(id) ON DELETE CASCADE
);

CREATE INDEX idx_inventories_sku ON inventories(sku_id);
CREATE INDEX idx_reservations_ref ON inventory_reservations(ref_type, ref_id);
CREATE INDEX idx_shipments_order ON shipments(order_id);
CREATE INDEX idx_shipments_tracking ON shipments(tracking_no);
