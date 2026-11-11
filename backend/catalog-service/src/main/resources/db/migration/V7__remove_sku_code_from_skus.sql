-- Migration V7: Tách ranh giới Catalog vs Fulfillment — xóa cột sku_code khỏi catalog.skus
-- và tạo bảng catalog_event_outbox để phát sự kiện ProductSkuCreated theo Transactional Outbox Pattern.

ALTER TABLE skus DROP COLUMN IF EXISTS sku_code;

CREATE TABLE IF NOT EXISTS catalog_event_outbox (
    id               uuid PRIMARY KEY,
    aggregate_type   varchar(50) NOT NULL,
    aggregate_id     uuid NOT NULL,
    event_type       varchar(100) NOT NULL,
    payload          jsonb NOT NULL,
    done             boolean NOT NULL DEFAULT false,
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamp NOT NULL DEFAULT now(),
    published_at     timestamp,
    created_at       timestamp NOT NULL DEFAULT now(),
    last_error       text
);

CREATE INDEX IF NOT EXISTS idx_catalog_event_outbox_pending
    ON catalog_event_outbox(done, next_attempt_at, created_at);
