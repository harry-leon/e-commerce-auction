-- Kich hoat extension pg_trgm cho tim kiem full-text / trigram fuzzy search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE categories (
  id uuid NOT NULL,
  parent_id uuid,
  name varchar(255) NOT NULL,
  level integer NOT NULL DEFAULT 1,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_category_level CHECK (level >= 1 AND level <= 3), -- BR-CFG-11: CATEGORY_MAX_LEVEL = 3
  CONSTRAINT chk_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE products (
  id uuid NOT NULL,
  seller_id uuid NOT NULL,
  category_id uuid NOT NULL,
  name varchar(255) NOT NULL,
  description text,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  auction_enabled boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT,
  CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')) -- BR-CFG-08
);

CREATE TABLE skus (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  sku_code varchar(100) NOT NULL UNIQUE,
  attributes jsonb,
  price numeric(15, 2) NOT NULL,
  original_price numeric(15, 2),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
  CONSTRAINT chk_sku_price CHECK (price >= 0),
  CONSTRAINT chk_sku_prices CHECK (original_price IS NULL OR original_price >= price),
  CONSTRAINT chk_sku_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE product_images (
  id uuid NOT NULL,
  product_id uuid NOT NULL,
  url varchar(500) NOT NULL,
  sort_order integer NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
  CONSTRAINT chk_image_sort_order CHECK (sort_order >= 0)
);

CREATE TABLE product_reviews (
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
  FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
  FOREIGN KEY (sku_id) REFERENCES skus(id) ON DELETE SET NULL,
  CONSTRAINT chk_product_review_score CHECK (score >= 1 AND score <= 5),
  CONSTRAINT uq_product_order_user UNIQUE (product_id, order_id, user_id)
);

CREATE INDEX idx_products_seller ON products(seller_id);
CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops); -- Toi uu tim kiem san pham theo tu khoa
CREATE INDEX idx_skus_product ON skus(product_id);
CREATE INDEX idx_skus_price ON skus(price); -- Toi uu loc theo khoang gia
CREATE INDEX idx_reviews_product ON product_reviews(product_id);
CREATE INDEX idx_reviews_user ON product_reviews(user_id);
