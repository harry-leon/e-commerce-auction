CREATE TABLE users (
  id uuid NOT NULL,
  email varchar(255) NOT NULL UNIQUE,
  phone varchar(20),
  full_name varchar(255) NOT NULL,
  identify_number varchar(30),
  gender varchar(10),
  date_of_birth date,
  address varchar(500),
  password_hash varchar(255) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  is_deleted boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  token_version integer NOT NULL DEFAULT 1,
  locked_until timestamp,
  PRIMARY KEY (id),
  CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'SUSPENDED')),
  CONSTRAINT chk_users_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
  CONSTRAINT chk_users_token_version CHECK (token_version >= 1)
);

CREATE TABLE roles (
  id uuid NOT NULL,
  code varchar(50) NOT NULL UNIQUE,
  name varchar(100) NOT NULL,
  description varchar(500),
  PRIMARY KEY (id)
);

CREATE TABLE privileges (
  id uuid NOT NULL,
  code varchar(100) NOT NULL UNIQUE,
  description varchar(500),
  PRIMARY KEY (id)
);

CREATE TABLE user_roles (
  user_id uuid NOT NULL,
  role_id uuid NOT NULL,
  PRIMARY KEY (user_id, role_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE role_privileges (
  role_id uuid NOT NULL,
  privilege_id uuid NOT NULL,
  PRIMARY KEY (role_id, privilege_id),
  FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
  FOREIGN KEY (privilege_id) REFERENCES privileges(id) ON DELETE CASCADE
);

CREATE TABLE reputations (
  user_id uuid NOT NULL,
  score integer NOT NULL DEFAULT 100,
  trust_level varchar(20) NOT NULL DEFAULT 'STANDARD',
  total_tx integer NOT NULL DEFAULT 0,
  success_tx integer NOT NULL DEFAULT 0,
  failed_tx integer NOT NULL DEFAULT 0,
  auction_won integer NOT NULL DEFAULT 0,
  auction_failed integer NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
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

CREATE TABLE penalties (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  score_delta integer NOT NULL,
  ref_type varchar(30) NOT NULL,
  ref_id uuid,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT chk_penalties_ref_type CHECK (ref_type IN ('AUCTION', 'ORDER', 'RATING', 'MANUAL')),
  CONSTRAINT uq_user_penalty_ref UNIQUE (user_id, ref_type, ref_id)
);

CREATE TABLE ratings (
  id uuid NOT NULL,
  rater_id uuid NOT NULL,
  ratee_id uuid NOT NULL,
  order_id uuid NOT NULL,
  score integer NOT NULL,
  comment varchar(1000),
  is_disputed boolean NOT NULL DEFAULT false,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (rater_id) REFERENCES users(id),
  FOREIGN KEY (ratee_id) REFERENCES users(id),
  CONSTRAINT chk_no_self_rating CHECK (rater_id <> ratee_id),
  CONSTRAINT chk_rating_score_range CHECK (score >= 1 AND score <= 5),
  CONSTRAINT uq_order_rater UNIQUE (order_id, rater_id)
);

CREATE INDEX idx_penalties_user ON penalties(user_id);
CREATE INDEX idx_ratings_ratee_id ON ratings(ratee_id);
CREATE INDEX idx_ratings_order_id ON ratings(order_id);
