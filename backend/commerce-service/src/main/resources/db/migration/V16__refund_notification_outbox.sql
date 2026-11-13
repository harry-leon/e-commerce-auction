-- Snapshot sự kiện refund trong cùng transaction trạng thái, không backfill lịch sử hoặc gọi Notification từ DB.
ALTER TABLE refund_requests ADD COLUMN notification_version bigint NOT NULL DEFAULT 0;
CREATE TABLE refund_notification_outbox (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES refund_requests(id),
    source_version bigint NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','DEAD_LETTER')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_type varchar(100),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at timestamptz,
    UNIQUE(refund_id,source_version)
);
CREATE INDEX idx_refund_notification_pending ON refund_notification_outbox(next_attempt_at,id) WHERE status='PENDING';

-- Revision chỉ tăng khi trạng thái thực sự đổi: replay UPDATE cùng trạng thái không sinh thêm sự kiện.
CREATE FUNCTION refund_notification_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN NEW.notification_version := 0;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN NEW.notification_version := OLD.notification_version+1;
    ELSE NEW.notification_version := OLD.notification_version;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER refund_notification_revision BEFORE INSERT OR UPDATE OF status ON refund_requests
FOR EACH ROW EXECUTE FUNCTION refund_notification_revision();

-- Nội dung chỉ lấy buyer/order/amount từ Commerce. Không đưa note, evidence, provider ref, secret vào payload.
-- Dùng TG_TABLE_SCHEMA cho mọi bảng truy cập để không đọc/ghi nhầm schema khi test/nâng nhiều schema cùng DB.
CREATE FUNCTION enqueue_refund_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text; event_id uuid; snapshot record;
BEGIN
    IF TG_OP='UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
    event_type := CASE NEW.status
        WHEN 'REQUESTED' THEN 'REFUND_REQUESTED' WHEN 'SUCCEEDED' THEN 'REFUND_SUCCEEDED'
        WHEN 'FAILED' THEN 'REFUND_FAILED' WHEN 'UNKNOWN' THEN 'REFUND_REQUIRES_REVIEW'
        WHEN 'REQUIRES_REVIEW' THEN 'REFUND_REQUIRES_REVIEW' ELSE NULL END;
    IF event_type IS NULL THEN RETURN NEW; END IF;
    EXECUTE format('SELECT buyer_id,order_no FROM %I.orders WHERE id=$1',TG_TABLE_SCHEMA) INTO snapshot USING NEW.order_id;
    IF snapshot.buyer_id IS NULL OR snapshot.order_no IS NULL THEN RAISE EXCEPTION 'Refund notification snapshot missing'; END IF;
    event_id := gen_random_uuid();
    EXECUTE format('INSERT INTO %I.refund_notification_outbox(id,refund_id,source_version,payload) VALUES ($1,$2,$3,$4)
        ON CONFLICT(refund_id,source_version) DO NOTHING',TG_TABLE_SCHEMA)
        USING event_id,NEW.id,NEW.notification_version,jsonb_build_object(
            'id',event_id,'refundId',NEW.id,'userId',snapshot.buyer_id,'orderId',NEW.order_id,'orderNo',snapshot.order_no,
            'type',event_type,'amount',NEW.amount,
            'occurredAt',to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
    RETURN NEW;
END $$;
CREATE TRIGGER enqueue_refund_notification AFTER INSERT OR UPDATE OF status ON refund_requests
FOR EACH ROW EXECUTE FUNCTION enqueue_refund_notification();

-- Xung đột kết quả muộn vẫn cần cảnh báo dù refund đã terminal; giữ nguyên quyết định tài chính và tăng revision.
CREATE FUNCTION enqueue_refund_conflict_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE snapshot record; buyer uuid; number text; event_id uuid := gen_random_uuid();
BEGIN
    EXECUTE format('UPDATE %I.refund_requests SET notification_version=notification_version+1 WHERE id=$1 RETURNING *',TG_TABLE_SCHEMA)
        INTO snapshot USING NEW.refund_id;
    EXECUTE format('SELECT buyer_id,order_no FROM %I.orders WHERE id=$1',TG_TABLE_SCHEMA) INTO buyer,number USING snapshot.order_id;
    EXECUTE format('INSERT INTO %I.refund_notification_outbox(id,refund_id,source_version,payload) VALUES ($1,$2,$3,$4)',TG_TABLE_SCHEMA)
        USING event_id,snapshot.id,snapshot.notification_version,jsonb_build_object(
            'id',event_id,'refundId',snapshot.id,'userId',buyer,'orderId',snapshot.order_id,'orderNo',number,
            'type','REFUND_REQUIRES_REVIEW','amount',snapshot.amount,
            'occurredAt',to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
    RETURN NEW;
END $$;
CREATE TRIGGER enqueue_refund_conflict_notification AFTER INSERT ON refund_result_conflicts
FOR EACH ROW EXECUTE FUNCTION enqueue_refund_conflict_notification();
