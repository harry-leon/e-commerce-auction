CREATE TABLE auctions (
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
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
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

CREATE TABLE bids (
  id uuid NOT NULL,
  auction_id uuid NOT NULL,
  bidder_id uuid NOT NULL,
  amount numeric(15, 2) NOT NULL,
  idempotency_key varchar(100) UNIQUE,
  placed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,
  CONSTRAINT chk_bid_amount CHECK (amount > 0)
);

CREATE TABLE auction_settlements (
  id uuid NOT NULL,
  auction_id uuid NOT NULL,
  winner_id uuid,
  final_price numeric(15, 2),
  result varchar(20) NOT NULL,
  order_id uuid,
  payment_deadline timestamp,
  settled_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE RESTRICT,
  CONSTRAINT uq_settlement_auction UNIQUE (auction_id), -- 1 phiên đấu giá chỉ có tối đa 1 bản ghi quyết toán
  CONSTRAINT chk_settlement_result CHECK (result IN ('SUCCESS', 'FAILED', 'CANCELLED', 'DEFAULTED')),
  CONSTRAINT chk_settlement_final_price CHECK (final_price IS NULL OR final_price >= 0),
  CONSTRAINT chk_settlement_deadline CHECK (payment_deadline IS NULL OR payment_deadline >= settled_at)
);

CREATE INDEX idx_auctions_seller ON auctions(seller_id);
CREATE INDEX idx_auctions_status ON auctions(status);
CREATE INDEX idx_auctions_product ON auctions(product_id);
CREATE INDEX idx_bids_auction ON bids(auction_id);
CREATE INDEX idx_bids_bidder ON bids(bidder_id);
