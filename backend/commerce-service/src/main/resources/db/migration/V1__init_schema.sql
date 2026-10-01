CREATE TABLE carts (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamp,
  PRIMARY KEY (id),
  CONSTRAINT chk_cart_status CHECK (status IN ('ACTIVE', 'MERGED', 'EXPIRED', 'CONVERTED'))
);

-- BR-CFG-14 & BR-COM-01: Mỗi người dùng chỉ có tối đa 1 giỏ hàng hoạt động (Active)
CREATE UNIQUE INDEX uq_user_active_cart ON carts (user_id) WHERE status = 'ACTIVE';

CREATE TABLE cart_items (
  id uuid NOT NULL,
  cart_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  quantity integer NOT NULL,
  unit_price numeric(15, 2),
  PRIMARY KEY (id),
  FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
  CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_cart_item_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
  CONSTRAINT uq_cart_sku UNIQUE (cart_id, sku_id) -- 1 giỏ hàng không bị trùng lặp dòng SKU
);

CREATE TABLE vouchers (
  id uuid NOT NULL,
  code varchar(50) NOT NULL UNIQUE,
  name varchar(255) NOT NULL,
  description text,
  discount_type varchar(20) NOT NULL,
  discount_value numeric(15, 2) NOT NULL,
  min_order_value numeric(15, 2) NOT NULL DEFAULT 0,
  max_discount_amount numeric(15, 2),
  usage_limit integer,
  used_count integer NOT NULL DEFAULT 0,
  start_date timestamp NOT NULL,
  end_date timestamp NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_voucher_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
  CONSTRAINT chk_voucher_value CHECK (discount_value > 0),
  CONSTRAINT chk_voucher_percentage CHECK (discount_type <> 'PERCENTAGE' OR discount_value <= 100),
  CONSTRAINT chk_voucher_min_order CHECK (min_order_value >= 0),
  CONSTRAINT chk_voucher_max_discount CHECK (max_discount_amount IS NULL OR max_discount_amount > 0),
  CONSTRAINT chk_voucher_duration CHECK (end_date > start_date),
  CONSTRAINT chk_voucher_usage CHECK (used_count >= 0 AND (usage_limit IS NULL OR used_count <= usage_limit))
);

CREATE TABLE orders (
  id uuid NOT NULL,
  order_no varchar(50) NOT NULL UNIQUE,
  buyer_id uuid NOT NULL,
  seller_id uuid NOT NULL,
  source_type varchar(20) NOT NULL,
  auction_id uuid,
  voucher_id uuid,
  status varchar(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
  subtotal numeric(15, 2) NOT NULL DEFAULT 0,
  shipping_fee numeric(15, 2) NOT NULL DEFAULT 0,
  discount numeric(15, 2) NOT NULL DEFAULT 0,
  total numeric(15, 2) NOT NULL,
  shipping_address varchar(500) NOT NULL,
  idempotency_key varchar(100) UNIQUE,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (voucher_id) REFERENCES vouchers(id) ON DELETE SET NULL,
  CONSTRAINT chk_order_source_type CHECK (source_type IN ('REGULAR', 'AUCTION')),
  CONSTRAINT chk_order_status CHECK (status IN (
    'PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'REFUNDED'
  )),
  CONSTRAINT chk_order_amounts CHECK (
    subtotal >= 0 AND 
    shipping_fee >= 0 AND 
    discount >= 0 AND 
    total >= 0
  ),
  CONSTRAINT chk_order_auction_source CHECK (source_type <> 'AUCTION' OR auction_id IS NOT NULL)
);

CREATE TABLE order_items (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  product_name varchar(255) NOT NULL,
  sku_code varchar(100) NOT NULL,
  unit_price numeric(15, 2) NOT NULL,
  quantity integer NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
  CONSTRAINT chk_order_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_order_item_price CHECK (unit_price >= 0),
  CONSTRAINT uq_order_sku UNIQUE (order_id, sku_id)
);

CREATE TABLE order_status_history (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  from_status varchar(30),
  to_status varchar(30) NOT NULL,
  reason varchar(500),
  changed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE TABLE payments (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  provider varchar(30) NOT NULL,
  provider_ref varchar(100),
  method varchar(30) NOT NULL,
  amount numeric(15, 2) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  retry_count integer NOT NULL DEFAULT 0,
  idempotency_key varchar(100) UNIQUE,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
  CONSTRAINT chk_payment_amount CHECK (amount > 0),
  CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELLED')),
  CONSTRAINT chk_payment_retry_count CHECK (retry_count >= 0)
);

CREATE TABLE refunds (
  id uuid NOT NULL,
  payment_id uuid NOT NULL,
  amount numeric(15, 2) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  reason varchar(500),
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
  CONSTRAINT chk_refund_amount CHECK (amount > 0),
  CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

CREATE TABLE invoices (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  invoice_no varchar(50) NOT NULL UNIQUE,
  file_url varchar(500),
  issued_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
);

CREATE TABLE voucher_usages (
  id uuid NOT NULL,
  voucher_id uuid NOT NULL,
  order_id uuid NOT NULL,
  user_id uuid NOT NULL,
  discount_amount numeric(15, 2) NOT NULL,
  used_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (voucher_id) REFERENCES vouchers(id) ON DELETE RESTRICT,
  FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
  CONSTRAINT uq_voucher_order UNIQUE (voucher_id, order_id),
  CONSTRAINT chk_voucher_used_discount CHECK (discount_amount > 0)
);

CREATE INDEX idx_vouchers_code ON vouchers(code);
CREATE INDEX idx_vouchers_active ON vouchers(is_active, start_date, end_date);
CREATE INDEX idx_voucher_usages_user ON voucher_usages(user_id);
CREATE INDEX idx_voucher_usages_voucher ON voucher_usages(voucher_id);
CREATE INDEX idx_orders_voucher ON orders(voucher_id);
CREATE INDEX idx_orders_buyer ON orders(buyer_id);
CREATE INDEX idx_orders_seller ON orders(seller_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_order_items_sku ON order_items(sku_id);
CREATE INDEX idx_payments_order ON payments(order_id);
