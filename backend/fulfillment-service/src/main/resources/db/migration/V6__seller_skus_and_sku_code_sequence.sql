-- Migration V6: Tạo seller_skus và sequence cấp skuCode duy nhất trong Fulfillment Service.
-- Bổ sung inventories.seller_sku_id (FK -> seller_skus.id) và backfill dữ liệu hiện có,
-- đồng thời giữ nguyên inventories.sku_id trong giai đoạn chuyển tiếp để không làm gián đoạn consumer hiện tại.

CREATE SEQUENCE IF NOT EXISTS fulfillment_sku_code_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS seller_skus (
    id          uuid PRIMARY KEY,
    seller_id   uuid NOT NULL,
    sku_id      uuid NOT NULL,
    sku_code    varchar(100) NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamp NOT NULL DEFAULT now(),
    updated_at  timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uq_seller_skus_sku_id UNIQUE (sku_id),
    CONSTRAINT uq_seller_skus_seller_code UNIQUE (seller_id, sku_code),
    CONSTRAINT chk_seller_skus_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_seller_skus_seller_id ON seller_skus(seller_id);
CREATE INDEX IF NOT EXISTS idx_seller_skus_sku_code ON seller_skus(sku_code);

ALTER TABLE inventories ADD COLUMN IF NOT EXISTS seller_sku_id uuid;
ALTER TABLE inventories ADD CONSTRAINT fk_inventories_seller_sku FOREIGN KEY (seller_sku_id) REFERENCES seller_skus(id);
ALTER TABLE inventories ADD CONSTRAINT uq_inventories_seller_sku_warehouse UNIQUE (seller_sku_id, warehouse_id);

CREATE TABLE IF NOT EXISTS fulfillment_event_outbox (
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

CREATE INDEX IF NOT EXISTS idx_fulfillment_outbox_pending
    ON fulfillment_event_outbox(done, next_attempt_at, created_at);

-- Backfill dữ liệu tồn kho cũ sang bảng seller_skus theo từng sku_id duy nhất trước khi bật NOT NULL cho seller_sku_id.
DO $$
DECLARE
    inv_rec RECORD;
    resolved_seller_id uuid;
    existing_ss_id uuid;
    seq_val bigint;
    generated_code varchar(100);
    has_catalog boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'nexus_catalog'
          AND table_name = 'skus'
    ) INTO has_catalog;

    FOR inv_rec IN
        SELECT DISTINCT sku_id
        FROM inventories
        WHERE seller_sku_id IS NULL
        ORDER BY sku_id
    LOOP
        SELECT id INTO existing_ss_id
        FROM seller_skus
        WHERE sku_id = inv_rec.sku_id;

        IF existing_ss_id IS NULL THEN
            resolved_seller_id := '00000000-0000-0000-0000-000000000000'::uuid;
            IF has_catalog THEN
                EXECUTE '
                    SELECT p.seller_id
                    FROM nexus_catalog.skus s
                    JOIN nexus_catalog.products p ON p.id = s.product_id
                    WHERE s.id = $1
                    LIMIT 1'
                INTO resolved_seller_id
                USING inv_rec.sku_id;
                resolved_seller_id := COALESCE(resolved_seller_id, '00000000-0000-0000-0000-000000000000'::uuid);
            END IF;

            SELECT nextval('fulfillment_sku_code_seq') INTO seq_val;
            generated_code := 'FSKU-' || LPAD(seq_val::text, 8, '0');
            existing_ss_id := gen_random_uuid();

            INSERT INTO seller_skus (id, seller_id, sku_id, sku_code, status, created_at, updated_at)
            VALUES (existing_ss_id, resolved_seller_id, inv_rec.sku_id, generated_code, 'ACTIVE', now(), now())
            ON CONFLICT (sku_id) DO UPDATE SET updated_at = seller_skus.updated_at
            RETURNING id INTO existing_ss_id;
        END IF;

        UPDATE inventories
        SET seller_sku_id = existing_ss_id
        WHERE sku_id = inv_rec.sku_id
          AND seller_sku_id IS NULL;
    END LOOP;
END $$;

ALTER TABLE inventories ALTER COLUMN seller_sku_id SET NOT NULL;
