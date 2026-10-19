# Database ERD

Generated from service-owned Flyway CREATE TABLE statements by `node scripts/generate-erd.mjs`. Each diagram is a physical database boundary. IDs referring to another service are logical references, not cross-database foreign keys. Later ALTER migrations remain authoritative for added columns.

## user-service

```mermaid
erDiagram
  service_metadata {
    VARCHAR key
    VARCHAR value
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
    varchar password_hash
    varchar status
    boolean is_deleted
    timestamp created_at
    integer token_version
    timestamp locked_until
  }
  roles {
    uuid id
    varchar code
    varchar name
    varchar description
  }
  privileges {
    uuid id
    varchar code
    varchar description
  }
  user_roles {
    uuid user_id
    uuid role_id
  }
  role_privileges {
    uuid role_id
    uuid privilege_id
  }
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
  penalties {
    uuid id
    uuid user_id
    varchar reason
    integer score_delta
    varchar ref_type
    uuid ref_id
    timestamp created_at
  }
  ratings {
    uuid id
    uuid rater_id
    uuid ratee_id
    uuid order_id
    integer score
    varchar comment
    boolean is_disputed
    timestamp created_at
  }
  password_reset_tokens {
    uuid id
    uuid user_id
    varchar token_hash
    timestamp expires_at
    boolean used
    timestamp created_at
  }
  audit_logs {
    uuid id
    uuid user_id
    varchar action
    text description
    varchar ip_address
    varchar user_agent
    varchar status
    timestamp created_at
  }
  users ||--o{ user_roles : user_id
  roles ||--o{ user_roles : role_id
  roles ||--o{ role_privileges : role_id
  privileges ||--o{ role_privileges : privilege_id
  users ||--o{ reputations : user_id
  users ||--o{ penalties : user_id
  users ||--o{ ratings : rater_id
  users ||--o{ ratings : ratee_id
  users ||--o{ password_reset_tokens : user_id
```

## catalog-service

```mermaid
erDiagram
  service_metadata {
    VARCHAR key
    VARCHAR value
  }
  categories {
    uuid id
    uuid parent_id
    varchar name
    integer level
    varchar status
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
    varchar sku_code
    jsonb attributes
    numeric price
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
    VARCHAR key
    VARCHAR value
  }
  carts {
    uuid id
    uuid user_id
    varchar status
    timestamp created_at
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
  }
  order_items {
    uuid id
    uuid order_id
    uuid sku_id
    varchar product_name
    varchar sku_code
    numeric unit_price
    integer quantity
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
  }
  refunds {
    uuid id
    uuid payment_id
    numeric amount
    varchar status
    varchar reason
    timestamp created_at
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
  carts ||--o{ cart_items : cart_id
  vouchers ||--o{ orders : voucher_id
  orders ||--o{ order_items : order_id
  orders ||--o{ order_status_history : order_id
  orders ||--o{ payments : order_id
  payments ||--o{ refunds : payment_id
  orders ||--o{ invoices : order_id
  vouchers ||--o{ voucher_usages : voucher_id
  orders ||--o{ voucher_usages : order_id
```

## auction-service

```mermaid
erDiagram
  service_metadata {
    VARCHAR key
    VARCHAR value
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
    timestamp created_at
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
    VARCHAR key
    VARCHAR value
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
  }
  inventory_reservations {
    uuid id
    uuid inventory_id
    integer quantity
    varchar ref_type
    uuid ref_id
    varchar status
    timestamp created_at
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
  }
  shipment_status_history {
    uuid id
    uuid shipment_id
    varchar carrier_status
    varchar internal_status
    timestamp occurred_at
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
    VARCHAR key
    VARCHAR value
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
