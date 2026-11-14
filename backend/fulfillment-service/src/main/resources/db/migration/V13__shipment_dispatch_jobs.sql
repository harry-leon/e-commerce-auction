-- Job bền vững từ kiện Admin; không backfill/gửi carrier trong migration.
-- CLAIMING có lease/fencing để retry claim idempotent; DISPATCHING không bao giờ tự reset để gửi carrier lần nữa.
CREATE TABLE shipment_dispatch_jobs (
    id uuid PRIMARY KEY,
    parcel_id uuid NOT NULL UNIQUE REFERENCES shipment_parcels(id),
    order_id uuid NOT NULL UNIQUE,
    claim_id uuid,
    state varchar(30) NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING','CLAIMING','DISPATCHING','COMMITTED','REQUIRES_REVIEW')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
    lease_token uuid,
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    shipment_id uuid REFERENCES shipments(id),
    last_error_type varchar(100),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (state<>'COMMITTED' OR shipment_id IS NOT NULL)
);
CREATE INDEX idx_shipment_dispatch_jobs_due ON shipment_dispatch_jobs(next_attempt_at,id) WHERE state='WAITING';
