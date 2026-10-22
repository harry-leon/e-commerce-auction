-- V2__init_schema.sql
-- Chức năng: Khởi tạo lược đồ cơ sở dữ liệu cho reputation-service gồm 3 bảng cốt lõi:
--   1. reputations: Điểm uy tín, huy hiệu (trust level), tổng hợp số giao dịch và số sao trung bình.
--   2. ratings: Lịch sử đánh giá ngang hàng giữa người dùng (Peer-to-Peer Rating theo đơn hàng), kèm cờ is_aggregated xử lý non-realtime.
--   3. penalties: Biên bản xử phạt vi phạm (bỏ cọc, bom hàng, vi phạm quy định) và lịch sử trừ điểm uy tín.
-- Đầu vào và kết quả: Tạo mới các bảng, ràng buộc khóa chính, khóa ngoại nội bộ, chỉ mục tìm kiếm và check constraint.
-- Quy tắc và lý do:
--   - ratee_id trong ratings và user_id trong penalties liên kết nội bộ tới reputations(user_id).
--   - Cột is_aggregated phục vụ batch job định kỳ tính điểm, tránh tính realtime gây nghẽn database.
--   - Không tạo FK vật lý sang user-service hay commerce-service để đảm bảo Database-per-Service.

-- 1. Bảng tổng hợp điểm uy tín (Read Model / Aggregated View)
CREATE TABLE reputations (
  user_id uuid NOT NULL,
  score integer NOT NULL DEFAULT 100,
  trust_level varchar(20) NOT NULL DEFAULT 'STANDARD',
  total_tx integer NOT NULL DEFAULT 0,
  success_tx integer NOT NULL DEFAULT 0,
  failed_tx integer NOT NULL DEFAULT 0,
  auction_won integer NOT NULL DEFAULT 0,
  auction_failed integer NOT NULL DEFAULT 0,
  avg_rating numeric(3, 2) NOT NULL DEFAULT 5.00,
  total_ratings integer NOT NULL DEFAULT 0,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT chk_reputation_score CHECK (score >= 0),
  CONSTRAINT chk_reputation_trust_level CHECK (trust_level IN ('LOW', 'STANDARD', 'HIGH', 'VIP')),
  CONSTRAINT chk_reputation_counts CHECK (
    total_tx >= 0 AND 
    success_tx >= 0 AND 
    failed_tx >= 0 AND 
    auction_won >= 0 AND 
    auction_failed >= 0 AND
    total_ratings >= 0
  ),
  CONSTRAINT chk_reputation_tx_consistency CHECK (total_tx >= (success_tx + failed_tx))
);

-- 2. Bảng lịch sử đánh giá ngang hàng (Peer-to-Peer Rating)
CREATE TABLE ratings (
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
  FOREIGN KEY (ratee_id) REFERENCES reputations(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_no_self_rating CHECK (rater_id <> ratee_id),
  CONSTRAINT chk_rating_score_range CHECK (score >= 1 AND score <= 5),
  CONSTRAINT uq_order_rater UNIQUE (order_id, rater_id)
);

-- 3. Bảng biên bản xử phạt vi phạm
CREATE TABLE penalties (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  score_delta integer NOT NULL,
  ref_type varchar(30) NOT NULL,
  ref_id uuid,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES reputations(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_penalties_ref_type CHECK (ref_type IN ('AUCTION', 'ORDER', 'RATING', 'MANUAL')),
  CONSTRAINT uq_user_penalty_ref UNIQUE (user_id, ref_type, ref_id)
);

-- Chỉ mục tối ưu hóa truy vấn
CREATE INDEX idx_ratings_ratee_id ON ratings(ratee_id);
CREATE INDEX idx_ratings_order_id ON ratings(order_id);
CREATE INDEX idx_ratings_unaggregated ON ratings(ratee_id) WHERE is_aggregated = false;
CREATE INDEX idx_penalties_user ON penalties(user_id);
