# Feature Specification: Dynamic Pricing Engine & Deferred Order Price Settlement (HG-S2-5)

**Feature Branch**: `feature/HG-S2-5-dynamic-pricing-checkout-flow`  
**Created**: 2026-11-11  
**Status**: Approved for Implementation  

## 1. User Scenarios & Testing

### User Story 1 - SKU Min Price & Dynamic Pricing Engine (Catalog)
As a Platform Admin / Seller, I want each SKU to store only its minimum import/base cost (`originalPrice` / `minPrice`) without a hardcoded `price` column, while the platform dynamically computes selling prices using configurable `pricing_rules` (margin, seasonal factor, market adjustment), so that selling prices never fall below the minimum price and automatically adapt to market conditions.

**Acceptance Scenarios**:
1. **Given** a Seller creates or updates a product with SKUs specifying `originalPrice >= 0`, **When** saved to `catalog-service`, **Then** only `original_price` is persisted in `skus`, and the API computes dynamic `price` (>= `originalPrice`) for each SKU and dynamic `[minPrice, maxPrice]` range on `ProductResponse`.
2. **Given** an Admin creates an active `PricingRule` (category-specific or global) with `marginRate`, `seasonFactor`, and `marketAdjustmentRate`, **When** products or SKUs are queried (`GET /api/v1/products`, `GET /api/v1/products/skus/{id}`), **Then** the returned selling `price` reflects the active rule immediately.
3. **Given** a `PricingRule` has negative total adjustment rate (`margin + season + market < 0`), **When** dynamic price is calculated, **Then** the engine clamps the total multiplier at `1.0` so selling `price` is never less than `originalPrice` (`minPrice`).

### User Story 2 - Dynamic Product Price Range & FE Cart Calculation
As a Buyer, I want to see the dynamic price range `[min - max]` on product cards and exact dynamic prices per SKU variant on the product detail page, while the Frontend calculates estimated cart subtotals locally without being trusted by the Backend for final billing.

**Acceptance Scenarios**:
1. **Given** a Product has multiple ACTIVE SKUs with different dynamic prices, **When** rendered in Search / Product Detail, **Then** FE displays the price range (e.g., `115.000 ₫ - 230.000 ₫`).
2. **Given** a Buyer places an order, **When** FE sends `CreateOrderRequest`, **Then** no prices or totals are sent in the request payload; BE retrieves live prices directly from `catalog-service`.

### User Story 3 - Deferred Order Price Finalization & Payment Attempts (Commerce)
As a Buyer paying online via VNPAY, I want my initial order to save only item selections and estimated amounts (`OrderItem.unitPrice = NULL`, `priceFinalized = false`), lock prices and inventory only when initiating a Payment Attempt, finalize official `OrderItem.unitPrice` and `Order.total` only upon verified Payment Success, and allow retrying with fresh dynamic prices if a Payment Attempt fails.

**Acceptance Scenarios**:
1. **Given** a Buyer creates a VNPAY order (`POST /api/v1/orders`), **When** order is persisted, **Then** status is `PENDING_PAYMENT`, `priceFinalized = false`, official `unitPrice`/`subtotal`/`total` are `NULL`, and `estimated_*` fields hold preliminary values.
2. **Given** a Buyer creates a COD order (`POST /api/v1/orders`), **When** order is persisted, **Then** official `unitPrice` and `total` are finalized immediately (`priceFinalized = true`), inventory is reserved, and status transitions to `PROCESSING`.
3. **Given** a Buyer starts a Payment Attempt (`POST /api/v1/payments`), **When** BE processes the request, **Then** BE queries live dynamic price from Catalog, reserves inventory using a unique `inventoryRefId` (`payment.id`), saves `priceSnapshot` and `amount`, and returns `paymentUrl`.
4. **Given** catalog pricing rules change while a `PENDING` Payment Attempt is in progress, **When** VNPAY IPN arrives for that attempt, **Then** the in-flight attempt keeps its original `payment.amount` and `priceSnapshot` without mutating mid-transaction.
5. **Given** VNPAY IPN confirms Payment Success (`00`/`00`), **When** verified idempotently inside `@Transactional`, **Then** `OrderItem.unitPrice` and `Order.total` are written from `priceSnapshot`, `priceFinalized = true`, inventory is confirmed, and status becomes `PAID`.
6. **Given** VNPAY IPN confirms Payment Failure, **When** processed, **Then** official prices remain `NULL` (`priceFinalized = false`), `Payment` becomes `FAILED`, inventory for that attempt is released immediately, and `Order` remains `PENDING_PAYMENT` so the Buyer can click **Retry Payment** (`POST /api/v1/payments` with a new UUID `Idempotency-Key`).

---

## 2. Functional Requirements

- **FR-001**: `catalog-service` `skus` table MUST drop `price` column and enforce `original_price NUMERIC(15,2) NOT NULL CHECK (original_price >= 0)`.
- **FR-002**: `catalog-service` MUST store dynamic pricing rules in `pricing_rules` table (`category_id`, `margin_rate`, `season_factor`, `market_adjustment_rate`, `priority`, `start_date`, `end_date`, `is_active`).
- **FR-003**: `DynamicPricingService` MUST compute SKU selling price dynamically from `originalPrice` and active rules, enforcing `sellingPrice >= originalPrice`.
- **FR-004**: `ProductResponse` MUST include dynamically computed `minPrice` and `maxPrice` from `ACTIVE` SKUs without adding persistent price columns to `products`.
- **FR-005**: `commerce-service` MUST keep `order_items.unit_price` and `orders.total` nullable until payment confirmation for online orders, while finalizing COD orders upon creation.
- **FR-006**: `commerce-service` MUST record `Payment` attempts with `price_snapshot` (JSONB) and `inventory_ref_id` (UUID), release inventory immediately upon `FAILED` IPN, and support retries with new `Idempotency-Key`.
- **FR-007**: `PaymentService.ipn` MUST remain strictly idempotent using distributed locks, signature/amount verification, and transactional updates.
- **FR-008**: `frontend-web` MUST render dynamic price ranges, support Admin Pricing Rules management, accept Seller `originalPrice` input with live dynamic price preview, and implement Buyer deferred-price checkout and Retry Payment UI.
