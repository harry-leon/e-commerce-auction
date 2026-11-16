# Database ERD

Generated from service-owned Flyway migrations by `node scripts/generate-erd.mjs`, including column ADD/DROP/TYPE changes. Each diagram is a physical database boundary. IDs referring to another service are logical references, not cross-database foreign keys. The approved Nerum V2 ERD defines the business tables; these migrations implement it. This diagram does not describe constraints or data backfills.

## user-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  users {
    uuid id
    varchar email
    varchar phone
    varchar full_name
    varchar identify_number
    varchar gender
    date date_of_birth
    varchar address
    varchar status
    boolean is_deleted
    timestamp created_at
    uuid keycloak_id
    timestamp updated_at
  }

```

## catalog-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  categories {
    uuid id
    uuid parent_id
    varchar name
    integer level
    varchar status
    varchar name_vi
  }
  products {
    uuid id
    uuid seller_id
    uuid category_id
    varchar name
    text description
    varchar status
    boolean auction_enabled
    timestamp created_at
  }
  skus {
    uuid id
    uuid product_id
    jsonb attributes
    numeric original_price
    varchar status
  }
  product_images {
    uuid id
    uuid product_id
    varchar url
    integer sort_order
  }
  product_reviews {
    uuid id
    uuid product_id
    uuid sku_id
    uuid user_id
    uuid order_id
    integer score
    text comment
    jsonb media_urls
    text seller_reply
    timestamp seller_replied_at
    boolean is_hidden
    timestamp created_at
  }
  pricing_rules {
    uuid id
    varchar name
    uuid category_id
    numeric margin_rate
    numeric season_factor
    numeric market_adjustment_rate
    integer priority
    timestamp start_date
    timestamp end_date
    boolean is_active
    timestamp created_at
    timestamp updated_at
  }
  catalog_event_outbox {
    uuid id
    varchar aggregate_type
    uuid aggregate_id
    varchar event_type
    jsonb payload
    boolean done
    integer attempts
    timestamp next_attempt_at
    timestamp published_at
    timestamp created_at
    text last_error
  }
  categories ||--o{ categories : parent_id
  categories ||--o{ products : category_id
  products ||--o{ skus : product_id
  products ||--o{ product_images : product_id
  products ||--o{ product_reviews : product_id
  skus ||--o{ product_reviews : sku_id
```

## commerce-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  carts {
    uuid id
    uuid user_id
    varchar status
    timestamp expires_at
  }
  cart_items {
    uuid id
    uuid cart_id
    uuid sku_id
    integer quantity
    numeric unit_price
  }
  vouchers {
    uuid id
    varchar code
    varchar name
    text description
    varchar discount_type
    numeric discount_value
    numeric min_order_value
    numeric max_discount_amount
    integer usage_limit
    integer used_count
    timestamp start_date
    timestamp end_date
    boolean is_active
    timestamp created_at
  }
  orders {
    uuid id
    varchar order_no
    uuid buyer_id
    uuid seller_id
    varchar source_type
    uuid auction_id
    uuid voucher_id
    varchar status
    numeric subtotal
    numeric shipping_fee
    numeric discount
    numeric total
    varchar shipping_address
    varchar idempotency_key
    timestamp created_at
    varchar recipient_name
    varchar recipient_phone
    varchar payment_method
    varchar request_hash
    varchar buyer_email
    timestamp payment_deadline
    numeric estimated_subtotal
    numeric estimated_shipping_fee
    numeric estimated_discount
    numeric estimated_total
    boolean price_finalized
    varchar fulfillment_status
  }
  order_items {
    uuid id
    uuid order_id
    uuid sku_id
    varchar product_name
    varchar sku_code
    numeric unit_price
    integer quantity
    numeric estimated_unit_price
  }
  order_status_history {
    uuid id
    uuid order_id
    varchar from_status
    varchar to_status
    varchar reason
    timestamp changed_at
  }
  payments {
    uuid id
    uuid order_id
    varchar provider
    varchar provider_ref
    varchar method
    numeric amount
    varchar status
    integer retry_count
    varchar idempotency_key
    timestamp created_at
    text payment_url
    varchar merchant_code
    timestamp expires_at
    varchar response_code
    varchar transaction_status
    timestamp completed_at
    varchar inventory_status
    jsonb price_snapshot
    uuid inventory_ref_id
  }
  invoices {
    uuid id
    uuid order_id
    varchar invoice_no
    varchar file_url
    timestamp issued_at
  }
  voucher_usages {
    uuid id
    uuid voucher_id
    uuid order_id
    uuid user_id
    numeric discount_amount
    timestamp used_at
  }
  order_reservations {
    uuid order_id
    uuid reservation_id
  }
  payment_inventory_tasks {
    uuid id
    uuid order_id
    uuid target_id
    varchar action
    boolean done
    integer attempts
    timestamp next_attempt_at
  }
  commerce_notification_outbox {
    uuid id
    uuid order_id
    uuid user_id
    varchar event_type
    varchar order_no
    numeric amount
    boolean done
    integer attempts
    jsonb email_payload
  }
  auction_timeout_tasks {
    uuid id
    uuid order_id
    uuid user_id
    uuid auction_id
    varchar task_type
    integer score_delta
    varchar reason
    boolean done
    integer attempts
  }
  refund_requests {
    uuid id
    uuid payment_id
    uuid order_id
    uuid requested_by
    uuid idempotency_key
    varchar reason
    numeric amount
    varchar currency
    varchar status
    varchar provider
    varchar merchant_code
    varchar provider_payment_ref
    uuid dispatch_approved_by
    timestamptz dispatch_approved_at
    varchar dispatch_note
    varchar review_reason
    timestamptz reconcile_after
    integer reconcile_count
    bigint notification_version
  }
  refund_attempts {
    uuid id
    uuid refund_id
    varchar request_id
    jsonb request_payload
    varchar status
    varchar response_code
    varchar transaction_status
    varchar provider_ref
    varchar error_type
  }
  refund_reconciliation_checks {
    uuid id
    uuid refund_id
    varchar request_id
    varchar response_code
    varchar transaction_status
    varchar transaction_type
    varchar error_type
  }
  refund_manual_resolutions {
    uuid id
    uuid refund_id
    uuid actor_id
    uuid idempotency_key
    varchar previous_status
    varchar decision
    numeric amount
    varchar currency
    varchar provider_payment_ref
    varchar provider_ref
    varchar evidence_reference
    varchar note
  }
  refund_provider_receipts {
    uuid refund_id
    varchar provider
    varchar merchant_code
    varchar provider_ref
    numeric amount
    varchar currency
    varchar source
    uuid source_id
  }
  refund_payment_holds {
    uuid payment_id
    varchar reason
  }
  refund_result_conflicts {
    uuid id
    uuid refund_id
    uuid attempt_id
    varchar response_code
    varchar transaction_status
    varchar transaction_type
    varchar provider_ref
    varchar reason
  }
  refund_notification_outbox {
    uuid id
    uuid refund_id
    bigint source_version
    jsonb payload
    varchar status
    integer attempts
    varchar last_error_type
  }
  refund_notification_replays {
    uuid id
    uuid event_id
    uuid actor_id
    uuid idempotency_key
    varchar reason
  }
  shipping_order_bindings {
    uuid order_id
    uuid shipment_id
    varchar carrier
    varchar tracking_number
    varchar review_event_type
  }
  shipping_event_inbox {
    uuid event_id
    uuid order_id
    jsonb payload
  }
  shipping_notification_outbox {
    uuid id
    uuid order_id
    varchar event_type
    jsonb payload
    varchar status
  }
  shipping_notification_replays {
    uuid id
    uuid event_id
    uuid actor_id
    uuid idempotency_key
    varchar reason
  }
  shipping_handoff_tasks {
    uuid id
    uuid order_id
    uuid payment_id
    numeric amount
    varchar currency
    varchar state
  }
  shipping_dispatch_claims {
    uuid id
    uuid order_id
    uuid handoff_id
    uuid parcel_id
    uuid requested_by
    uuid idempotency_key
  }
  refunded_order_finalizations {
    uuid id
    uuid order_id
    uuid refund_id
    uuid actor_id
    uuid idempotency_key
    varchar previous_status
    varchar note
  }
  refund_credit_notes {
    uuid id
    uuid refund_id
    uuid payment_id
    uuid order_id
    varchar document_no
    varchar original_receipt_id
    varchar refund_receipt_id
    numeric amount
    varchar currency
    varchar evidence_source
    uuid issued_by
    uuid idempotency_key
    varchar note
  }
  carts ||--o{ cart_items : cart_id
  vouchers ||--o{ orders : voucher_id
  orders ||--o{ order_items : order_id
  orders ||--o{ order_status_history : order_id
  orders ||--o{ payments : order_id
  orders ||--o{ invoices : order_id
  vouchers ||--o{ voucher_usages : voucher_id
  orders ||--o{ voucher_usages : order_id
```

## auction-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  auctions {
    uuid id
    uuid product_id
    uuid sku_id
    uuid seller_id
    numeric start_price
    numeric bid_increment
    numeric current_price
    uuid highest_bidder_id
    varchar visibility
    varchar status
    timestamp start_time
    timestamp end_time
    integer extension_count
    integer version
  }
  bids {
    uuid id
    uuid auction_id
    uuid bidder_id
    numeric amount
    varchar idempotency_key
    timestamp placed_at
  }
  auction_settlements {
    uuid id
    uuid auction_id
    uuid winner_id
    numeric final_price
    varchar result
    uuid order_id
    timestamp payment_deadline
    timestamp settled_at
  }
  auctions ||--o{ bids : auction_id
  auctions ||--o{ auction_settlements : auction_id
```

## fulfillment-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  warehouses {
    uuid id
    varchar code
    varchar name
    varchar address
    varchar type
    boolean is_active
  }
  inventories {
    uuid id
    uuid sku_id
    uuid warehouse_id
    integer total_qty
    integer reserved_qty
    integer unavailable_qty
    integer version
    uuid seller_sku_id
  }
  inventory_reservations {
    uuid id
    uuid inventory_id
    integer quantity
    varchar ref_type
    uuid ref_id
    varchar status
    timestamp expires_at
  }
  inventory_ledger {
    uuid id
    uuid inventory_id
    varchar movement_type
    integer quantity
    varchar ref_type
    uuid ref_id
    timestamp created_at
    varchar idempotency_key
  }
  carriers {
    uuid id
    varchar code
    varchar name
    boolean is_active
  }
  shipments {
    uuid id
    uuid order_id
    uuid warehouse_id
    uuid carrier_id
    varchar service_type
    numeric shipping_fee
    varchar tracking_no
    varchar status
    timestamp created_at
    varchar carrier_code
  }
  shipment_status_history {
    uuid id
    uuid shipment_id
    varchar carrier_status
    varchar internal_status
    timestamp occurred_at
    varchar event_id
  }
  seller_skus {
    uuid id
    uuid seller_id
    uuid sku_id
    varchar sku_code
    varchar status
    timestamp created_at
    timestamp updated_at
  }
  fulfillment_event_outbox {
    uuid id
    varchar aggregate_type
    uuid aggregate_id
    varchar event_type
    jsonb payload
    boolean done
    integer attempts
    timestamp next_attempt_at
    timestamp published_at
    timestamp created_at
    text last_error
  }
  shipment_dispatch_attempts {
    uuid id
    uuid order_id
    varchar carrier_code
    text request_payload
    varchar status
    varchar tracking_number
    varchar last_error_type
    timestamp created_at
    timestamp updated_at
  }
  shipment_dispatch_locks {
    uuid order_id
  }
  shipment_dispatch_resolutions {
    uuid id
    uuid attempt_id
    uuid shipment_id
    uuid actor_id
    uuid idempotency_key
    varchar tracking_number
    numeric shipping_fee
    varchar evidence_reference
    varchar note
    timestamp created_at
  }
  shipping_event_replays {
    uuid id
    uuid event_id
    uuid actor_id
    uuid idempotency_key
    varchar reason
    integer previous_attempts
  }
  shipment_parcels {
    uuid id
    uuid order_id
    uuid warehouse_id
    varchar carrier_code
    varchar service_type
    integer weight_grams
    integer to_district_id
    varchar to_ward_code
    uuid registered_by
    uuid idempotency_key
  }
  shipment_dispatch_jobs {
    uuid id
    uuid parcel_id
    uuid order_id
    uuid claim_id
    varchar state
    integer attempts
    uuid lease_token
    uuid shipment_id
    varchar last_error_type
  }
  shipment_dispatch_job_reconciliations {
    uuid id
    uuid job_id
    uuid attempt_id
    uuid shipment_id
    uuid actor_id
    uuid idempotency_key
    varchar evidence_reference
    varchar note
  }
  warehouses ||--o{ inventories : warehouse_id
  inventories ||--o{ inventory_reservations : inventory_id
  inventories ||--o{ inventory_ledger : inventory_id
  warehouses ||--o{ shipments : warehouse_id
  carriers ||--o{ shipments : carrier_id
  shipments ||--o{ shipment_status_history : shipment_id
```

## notification-service

```mermaid
erDiagram
  service_metadata {
    varchar key
    varchar value
  }
  notifications {
    uuid id
    uuid user_id
    varchar event_id
    varchar channel
    varchar title
    text content
    varchar status
    timestamp sent_at
    timestamp created_at
    uuid order_id
    varchar event_type
    uuid auction_id
    varchar payment_link
    timestamp payment_deadline
    uuid refund_id
  }
  notification_attempts {
    uuid id
    uuid notification_id
    integer attempt_no
    varchar result
    text error
    timestamp attempted_at
  }
  notification_preferences {
    uuid id
    uuid user_id
    varchar notification_type
    varchar channel
    boolean enabled
  }
  event_logs {
    uuid id
    varchar event_id
    varchar source_service
    jsonb payload
    varchar operator_id
    timestamp created_at
  }
  notifications ||--o{ notification_attempts : notification_id
```

## reputation-service

```mermaid
erDiagram
  reputations {
    uuid user_id
    integer score
    varchar trust_level
    integer total_tx
    integer success_tx
    integer failed_tx
    integer auction_won
    integer auction_failed
  }
  ratings {
    uuid id
    uuid rater_id
    uuid ratee_id
    uuid order_id
    integer score
    varchar comment
    boolean is_aggregated
    boolean is_disputed
    timestamp created_at
  }
  penalties {
    uuid id
    uuid user_id
    varchar reason
    integer score_delta
    varchar ref_type
    uuid ref_id
    timestamp created_at
  }
  reputations ||--o{ ratings : ratee_id
  reputations ||--o{ penalties : user_id
```
