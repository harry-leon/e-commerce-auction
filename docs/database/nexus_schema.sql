-- Project Nexus: schema nghiệp vụ theo ERD Nerum V2 (1).json đã chốt (PostgreSQL)
-- Bản tổng hợp để đọc/đối chiếu: mỗi schema biểu diễn database của một service.
-- Khi chạy ứng dụng, deploy/postgres/init-databases.sh tạo database riêng;
-- Flyway trong từng service quản lý bảng ở schema mặc định. Không chạy file này thay Flyway.
-- Audit hồ sơ thuộc notification_svc.event_logs; danh mục chỉ có một name. Xem erd-alignment.md trước khi nâng cấp DB cũ.
-- Không có FK giữa các service; các bảng hạ tầng service_metadata/Flyway không đưa vào bản tổng hợp.

CREATE SCHEMA IF NOT EXISTS user_svc;
CREATE SCHEMA IF NOT EXISTS catalog_svc;
CREATE SCHEMA IF NOT EXISTS commerce_svc;
CREATE SCHEMA IF NOT EXISTS auction_svc;
CREATE SCHEMA IF NOT EXISTS fulfillment_svc;
CREATE SCHEMA IF NOT EXISTS notification_svc;
CREATE SCHEMA IF NOT EXISTS reputation_svc;

-- Kich hoat extension pg_trgm cho tim kiem full-text / trigram fuzzy search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================================
-- ===== 1. user_svc =====
-- Lưu trữ hồ sơ cá nhân (Profile) người dùng.
-- Hệ thống xác thực & phân quyền (Auth/IAM) do Keycloak quản lý tập trung.
-- Điểm uy tín, điểm phạt và đánh giá được tách sang reputation_svc.
-- ============================================================================

CREATE TABLE user_svc.users (
  id uuid NOT NULL,
  -- Theo migration user V5: định danh Keycloak là UUID, credential do Keycloak quản lý.
  keycloak_id uuid UNIQUE,
  email varchar(255) NOT NULL UNIQUE,
  phone varchar(20),
  full_name varchar(255) NOT NULL,
  identify_number varchar(30),
  gender varchar(10),
  date_of_birth date,
  address varchar(500),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  is_deleted boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'SUSPENDED')),
  CONSTRAINT chk_users_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER'))
);

CREATE INDEX idx_users_keycloak_id ON user_svc.users(keycloak_id);
CREATE INDEX idx_users_email ON user_svc.users(email);

-- ============================================================================
-- ===== 2. catalog_svc =====
-- ============================================================================

CREATE TABLE catalog_svc.categories (
  id uuid NOT NULL,
  parent_id uuid,
  name varchar(255) NOT NULL,
  level integer NOT NULL DEFAULT 1,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  FOREIGN KEY (parent_id) REFERENCES catalog_svc.categories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_category_level CHECK (level >= 1 AND level <= 3), -- BR-CFG-11: CATEGORY_MAX_LEVEL = 3
  CONSTRAINT chk_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE catalog_svc.products (
  id uuid NOT NULL,
  seller_id uuid NOT NULL,
  category_id uuid NOT NULL,
  name varchar(255) NOT NULL,
  description text,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  auction_enabled boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (category_id) REFERENCES catalog_svc.categories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')) -- BR-CFG-08
);

CREATE TABLE catalog_svc.skus (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  sku_code varchar(100) NOT NULL UNIQUE,
  attributes jsonb,
  price numeric(15, 2) NOT NULL,
  original_price numeric(15, 2),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  FOREIGN KEY (product_id) REFERENCES catalog_svc.products(id) ON DELETE CASCADE,
  CONSTRAINT chk_sku_price CHECK (price >= 0),
  CONSTRAINT chk_sku_prices CHECK (original_price IS NULL OR original_price >= price),
  CONSTRAINT chk_sku_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE catalog_svc.product_images (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  url varchar(500) NOT NULL,
  sort_order integer NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  FOREIGN KEY (product_id) REFERENCES catalog_svc.products(id) ON DELETE CASCADE,
  CONSTRAINT chk_image_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE catalog_svc.product_reviews (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  sku_id uuid,
  user_id uuid NOT NULL,
  order_id uuid NOT NULL,
  score integer NOT NULL,
  comment text,
  media_urls jsonb,
  seller_reply text,
  seller_replied_at timestamp,
  is_hidden boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (product_id) REFERENCES catalog_svc.products(id) ON DELETE CASCADE,
  FOREIGN KEY (sku_id) REFERENCES catalog_svc.skus(id) ON DELETE SET NULL,
  CONSTRAINT chk_product_review_score CHECK (score >= 1 AND score <= 5),
  CONSTRAINT uq_product_order_user UNIQUE (product_id, order_id, user_id)
);

CREATE INDEX idx_products_seller ON catalog_svc.products(seller_id);
CREATE INDEX idx_products_category ON catalog_svc.products(category_id);
CREATE INDEX idx_products_status ON catalog_svc.products(status);
CREATE INDEX idx_products_name_trgm ON catalog_svc.products USING gin (name gin_trgm_ops); -- Toi uu tim kiem san pham theo tu khoa
CREATE INDEX idx_skus_product ON catalog_svc.skus(product_id);
CREATE INDEX idx_skus_price ON catalog_svc.skus(price); -- Toi uu loc theo khoang gia
CREATE INDEX idx_reviews_product ON catalog_svc.product_reviews(product_id);
CREATE INDEX idx_reviews_user ON catalog_svc.product_reviews(user_id);

-- ============================================================================
-- ===== 3. commerce_svc =====
-- ============================================================================

CREATE TABLE commerce_svc.carts (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  expires_at timestamp,
  PRIMARY KEY (id),
  CONSTRAINT chk_cart_status CHECK (status IN ('ACTIVE', 'MERGED', 'EXPIRED', 'CONVERTED'))
);

-- BR-CFG-14 & BR-COM-01: Mỗi người dùng chỉ có tối đa 1 giỏ hàng hoạt động (Active)
CREATE UNIQUE INDEX uq_user_active_cart ON commerce_svc.carts (user_id) WHERE status = 'ACTIVE';

CREATE TABLE commerce_svc.cart_items (
  id uuid NOT NULL,
  cart_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  quantity integer NOT NULL,
  unit_price numeric(15, 2),
  PRIMARY KEY (id),
  FOREIGN KEY (cart_id) REFERENCES commerce_svc.carts(id) ON DELETE CASCADE,
  CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_cart_item_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
  CONSTRAINT uq_cart_sku UNIQUE (cart_id, sku_id) -- 1 giỏ hàng không bị trùng lặp dòng SKU
);

CREATE TABLE commerce_svc.vouchers (
  id uuid NOT NULL,
  code varchar(50) NOT NULL UNIQUE,
  name varchar(30) NOT NULL,
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

CREATE TABLE commerce_svc.orders (
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
  FOREIGN KEY (voucher_id) REFERENCES commerce_svc.vouchers(id) ON DELETE SET NULL,
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

CREATE TABLE commerce_svc.order_items (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  product_name varchar(255) NOT NULL,
  sku_code varchar(100) NOT NULL,
  unit_price numeric(15, 2) NOT NULL,
  quantity integer NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES commerce_svc.orders(id) ON DELETE CASCADE,
  CONSTRAINT chk_order_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_order_item_price CHECK (unit_price >= 0),
  CONSTRAINT uq_order_sku UNIQUE (order_id, sku_id)
);

CREATE TABLE commerce_svc.order_status_history (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  from_status varchar(30),
  to_status varchar(30) NOT NULL,
  reason varchar(500),
  changed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES commerce_svc.orders(id) ON DELETE CASCADE
);

CREATE TABLE commerce_svc.payments (
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
  FOREIGN KEY (order_id) REFERENCES commerce_svc.orders(id) ON DELETE RESTRICT,
  CONSTRAINT chk_payment_amount CHECK (amount > 0),
  CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELLED')),
  CONSTRAINT chk_payment_retry_count CHECK (retry_count >= 0)
);

CREATE TABLE commerce_svc.refunds (
  id uuid NOT NULL,
  payment_id uuid NOT NULL,
  amount numeric(15, 2) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  reason varchar(500),
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (payment_id) REFERENCES commerce_svc.payments(id) ON DELETE RESTRICT,
  CONSTRAINT chk_refund_amount CHECK (amount > 0),
  CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

CREATE TABLE commerce_svc.invoices (
  id uuid NOT NULL,
  order_id uuid NOT NULL,
  invoice_no varchar(50) NOT NULL UNIQUE,
  file_url varchar(500),
  issued_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (order_id) REFERENCES commerce_svc.orders(id) ON DELETE RESTRICT
);

CREATE TABLE commerce_svc.voucher_usages (
  id uuid NOT NULL,
  voucher_id uuid NOT NULL,
  order_id uuid NOT NULL,
  user_id uuid NOT NULL,
  discount_amount numeric(15, 2) NOT NULL,
  used_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (voucher_id) REFERENCES commerce_svc.vouchers(id) ON DELETE RESTRICT,
  FOREIGN KEY (order_id) REFERENCES commerce_svc.orders(id) ON DELETE CASCADE,
  CONSTRAINT uq_voucher_order UNIQUE (voucher_id, order_id),
  CONSTRAINT chk_voucher_used_discount CHECK (discount_amount > 0)
);

CREATE INDEX idx_vouchers_code ON commerce_svc.vouchers(code);
CREATE INDEX idx_vouchers_active ON commerce_svc.vouchers(is_active, start_date, end_date);
CREATE INDEX idx_voucher_usages_user ON commerce_svc.voucher_usages(user_id);
CREATE INDEX idx_voucher_usages_voucher ON commerce_svc.voucher_usages(voucher_id);
CREATE INDEX idx_orders_voucher ON commerce_svc.orders(voucher_id);
CREATE INDEX idx_orders_buyer ON commerce_svc.orders(buyer_id);
CREATE INDEX idx_orders_seller ON commerce_svc.orders(seller_id);
CREATE INDEX idx_orders_status ON commerce_svc.orders(status);
CREATE INDEX idx_order_items_sku ON commerce_svc.order_items(sku_id);
CREATE INDEX idx_payments_order ON commerce_svc.payments(order_id);

-- ============================================================================
-- ===== 4. auction_svc =====
-- ============================================================================

CREATE TABLE auction_svc.auctions (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  sku_id uuid,
  seller_id uuid NOT NULL,
  start_price numeric(15, 2) NOT NULL,
  bid_increment numeric(15, 2) NOT NULL,
  current_price numeric(15, 2),
  highest_bidder_id uuid,
  visibility varchar(20) NOT NULL DEFAULT 'PUBLIC',
  status varchar(20) NOT NULL DEFAULT 'SCHEDULED',
  start_time timestamp NOT NULL,
  end_time timestamp NOT NULL,
  extension_count integer NOT NULL DEFAULT 0,
  version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  CONSTRAINT chk_auction_start_price CHECK (start_price > 0),
  CONSTRAINT chk_auction_bid_increment CHECK (bid_increment > 0), -- BR-CFG-24: Bước giá phải dương
  CONSTRAINT chk_auction_current_price CHECK (current_price IS NULL OR current_price >= start_price),
  CONSTRAINT chk_auction_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'ENDED', 'SETTLED', 'CANCELLED')),
  CONSTRAINT chk_auction_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
  CONSTRAINT chk_auction_duration CHECK (end_time > start_time), -- Thời gian kết thúc phải sau bắt đầu
  CONSTRAINT chk_auction_extension_count CHECK (extension_count >= 0),
  CONSTRAINT chk_auction_version CHECK (version >= 1),
  CONSTRAINT chk_seller_not_bidder CHECK (highest_bidder_id IS NULL OR highest_bidder_id <> seller_id) -- Người bán không được bid giá phiên của mình
);

CREATE TABLE auction_svc.bids (
  id uuid NOT NULL,
  auction_id uuid NOT NULL,
  bidder_id uuid NOT NULL,
  amount numeric(15, 2) NOT NULL,
  idempotency_key varchar(100) UNIQUE,
  placed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (auction_id) REFERENCES auction_svc.auctions(id) ON DELETE CASCADE,
  CONSTRAINT chk_bid_amount CHECK (amount > 0)
);

CREATE TABLE auction_svc.auction_settlements (
  id uuid NOT NULL,
  auction_id uuid NOT NULL,
  winner_id uuid,
  final_price numeric(15, 2),
  result varchar(20) NOT NULL,
  order_id uuid,
  payment_deadline timestamp,
  settled_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (auction_id) REFERENCES auction_svc.auctions(id) ON DELETE RESTRICT,
  CONSTRAINT uq_settlement_auction UNIQUE (auction_id), -- 1 phiên đấu giá chỉ có tối đa 1 bản ghi quyết toán
  CONSTRAINT chk_settlement_result CHECK (result IN ('SUCCESS', 'FAILED', 'CANCELLED', 'DEFAULTED')),
  CONSTRAINT chk_settlement_final_price CHECK (final_price IS NULL OR final_price >= 0),
  CONSTRAINT chk_settlement_deadline CHECK (payment_deadline IS NULL OR payment_deadline >= settled_at)
);

CREATE INDEX idx_auctions_seller ON auction_svc.auctions(seller_id);
CREATE INDEX idx_auctions_status ON auction_svc.auctions(status);
CREATE INDEX idx_auctions_product ON auction_svc.auctions(product_id);
CREATE INDEX idx_bids_auction ON auction_svc.bids(auction_id);
CREATE INDEX idx_bids_bidder ON auction_svc.bids(bidder_id);

-- ============================================================================
-- ===== 5. fulfillment_svc =====
-- ============================================================================

CREATE TABLE fulfillment_svc.warehouses (
  id uuid NOT NULL,
  code varchar(30) NOT NULL UNIQUE,
  name varchar(255) NOT NULL,
  address varchar(500) NOT NULL,
  type varchar(20) NOT NULL DEFAULT 'STANDARD',
  is_active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id),
  CONSTRAINT chk_warehouse_type CHECK (type IN ('STANDARD', 'BONDED', 'RETURN', 'CROSS_DOCK'))
);

CREATE TABLE fulfillment_svc.inventories (
  id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  total_qty integer NOT NULL DEFAULT 0,
  reserved_qty integer NOT NULL DEFAULT 0,
  unavailable_qty integer NOT NULL DEFAULT 0,
  version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  FOREIGN KEY (warehouse_id) REFERENCES fulfillment_svc.warehouses(id) ON DELETE RESTRICT,
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

CREATE TABLE fulfillment_svc.inventory_reservations (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  quantity integer NOT NULL,
  ref_type varchar(20) NOT NULL,
  ref_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  expires_at timestamp NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (inventory_id) REFERENCES fulfillment_svc.inventories(id) ON DELETE CASCADE,
  CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
  CONSTRAINT chk_reservation_ref_type CHECK (ref_type IN ('ORDER', 'AUCTION')),
  CONSTRAINT chk_reservation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED'))
);

CREATE TABLE fulfillment_svc.inventory_ledger (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  movement_type varchar(20) NOT NULL,
  quantity integer NOT NULL,
  ref_type varchar(20) NOT NULL,
  ref_id uuid,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (inventory_id) REFERENCES fulfillment_svc.inventories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_ledger_movement_type CHECK (movement_type IN ('INBOUND', 'OUTBOUND', 'RESERVE', 'RELEASE', 'ADJUSTMENT')),
  CONSTRAINT chk_ledger_quantity CHECK (quantity <> 0) -- Biến động kho bắt buộc phải khác 0
);

CREATE TABLE fulfillment_svc.carriers (
  id uuid NOT NULL,
  code varchar(30) NOT NULL UNIQUE,
  name varchar(100) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id)
);

CREATE TABLE fulfillment_svc.shipments (
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
  FOREIGN KEY (warehouse_id) REFERENCES fulfillment_svc.warehouses(id) ON DELETE RESTRICT,
  FOREIGN KEY (carrier_id) REFERENCES fulfillment_svc.carriers(id) ON DELETE RESTRICT,
  CONSTRAINT chk_shipment_shipping_fee CHECK (shipping_fee >= 0),
  CONSTRAINT chk_shipment_status CHECK (status IN (
    'PENDING', 'PICKING', 'PACKED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED', 'CANCELLED'
  ))
);

CREATE TABLE fulfillment_svc.shipment_status_history (
  id uuid NOT NULL,
  shipment_id uuid NOT NULL,
  carrier_status varchar(50),
  internal_status varchar(30) NOT NULL,
  occurred_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (shipment_id) REFERENCES fulfillment_svc.shipments(id) ON DELETE CASCADE
);

CREATE INDEX idx_inventories_sku ON fulfillment_svc.inventories(sku_id);
CREATE INDEX idx_reservations_ref ON fulfillment_svc.inventory_reservations(ref_type, ref_id);
CREATE INDEX idx_shipments_order ON fulfillment_svc.shipments(order_id);
CREATE INDEX idx_shipments_tracking ON fulfillment_svc.shipments(tracking_no);

-- ============================================================================
-- ===== 6. notification_svc =====
-- ============================================================================

CREATE TABLE notification_svc.notifications (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  event_id varchar(50),
  channel varchar(20) NOT NULL,
  title varchar(255) NOT NULL,
  content text NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  sent_at timestamp,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_notification_channel CHECK (channel IN ('EMAIL', 'PUSH', 'IN_APP', 'SMS')),
  CONSTRAINT chk_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ'))
);

CREATE TABLE notification_svc.notification_attempts (
  id uuid NOT NULL,
  notification_id uuid NOT NULL,
  attempt_no integer NOT NULL,
  result varchar(20) NOT NULL,
  error text,
  attempted_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (notification_id) REFERENCES notification_svc.notifications(id) ON DELETE CASCADE,
  CONSTRAINT chk_attempt_no CHECK (attempt_no >= 1),
  CONSTRAINT chk_attempt_result CHECK (result IN ('SUCCESS', 'FAILURE', 'RETRY'))
);

CREATE TABLE notification_svc.notification_preferences (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  notification_type varchar(50) NOT NULL,
  channel varchar(20) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id),
  CONSTRAINT uq_user_notification_pref UNIQUE (user_id, notification_type, channel), -- 1 user không bị cấu hình lặp kênh + loại tin
  CONSTRAINT chk_pref_channel CHECK (channel IN ('EMAIL', 'PUSH', 'IN_APP', 'SMS'))
);

CREATE TABLE notification_svc.event_logs (
  id uuid NOT NULL,
  event_id varchar(50) NOT NULL,
  source_service varchar(50) NOT NULL,
  payload jsonb NOT NULL,
  operator_id varchar(100),
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE INDEX idx_notifications_user ON notification_svc.notifications(user_id);
CREATE INDEX idx_notifications_status ON notification_svc.notifications(status);
CREATE INDEX idx_event_logs_event ON notification_svc.event_logs(event_id);
CREATE INDEX idx_event_logs_source ON notification_svc.event_logs(source_service);

-- ============================================================================
-- ===== 7. reputation_svc =====
-- Quản lý đánh giá ngang hàng giữa người dùng (Peer-to-Peer), điểm uy tín,
-- huy hiệu tin cậy và lịch sử các biên bản xử phạt vi phạm.
-- Tách biệt hoàn toàn khỏi user-service và xử lý đánh giá dạng non-realtime.
-- ============================================================================

CREATE TABLE reputation_svc.reputations (
  user_id uuid NOT NULL,
  score integer NOT NULL DEFAULT 100,
  trust_level varchar(20) NOT NULL DEFAULT 'STANDARD',
  total_tx integer NOT NULL DEFAULT 0,
  success_tx integer NOT NULL DEFAULT 0,
  failed_tx integer NOT NULL DEFAULT 0,
  auction_won integer NOT NULL DEFAULT 0,
  auction_failed integer NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id),
  CONSTRAINT chk_reputation_score CHECK (score >= 0),
  CONSTRAINT chk_reputation_trust_level CHECK (trust_level IN ('LOW', 'STANDARD', 'HIGH', 'VIP')),
  CONSTRAINT chk_reputation_counts CHECK (
    total_tx >= 0 AND 
    success_tx >= 0 AND 
    failed_tx >= 0 AND 
    auction_won >= 0 AND 
    auction_failed >= 0
  ),
  CONSTRAINT chk_reputation_tx_consistency CHECK (total_tx >= (success_tx + failed_tx))
);

CREATE TABLE reputation_svc.ratings (
  id uuid NOT NULL,
  rater_id uuid NOT NULL,
  ratee_id uuid NOT NULL,
  order_id uuid NOT NULL,
  score integer NOT NULL,
  comment varchar(1000),
  is_aggregated boolean NOT NULL DEFAULT false,
  is_disputed boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (ratee_id) REFERENCES reputation_svc.reputations(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_no_self_rating CHECK (rater_id <> ratee_id),
  CONSTRAINT chk_rating_score_range CHECK (score >= 1 AND score <= 5),
  CONSTRAINT uq_order_rater UNIQUE (order_id, rater_id)
);

CREATE TABLE reputation_svc.penalties (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  score_delta integer NOT NULL,
  ref_type varchar(30) NOT NULL,
  ref_id uuid,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES reputation_svc.reputations(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_penalties_ref_type CHECK (ref_type IN ('AUCTION', 'ORDER', 'RATING', 'MANUAL')),
  CONSTRAINT uq_user_penalty_ref UNIQUE (user_id, ref_type, ref_id)
);

CREATE INDEX idx_ratings_ratee_id ON reputation_svc.ratings(ratee_id);
CREATE INDEX idx_ratings_order_id ON reputation_svc.ratings(order_id);
CREATE INDEX idx_ratings_unaggregated ON reputation_svc.ratings(ratee_id) WHERE is_aggregated = false;
CREATE INDEX idx_penalties_user ON reputation_svc.penalties(user_id);
