# Implementation Plan: Dynamic Pricing & Deferred Payment Settlement (HG-S2-5)

## Technical Context
- **Backend**: Java 21, Spring Boot 3.x, Spring Data JPA, Flyway, PostgreSQL (Supabase), Spring Cloud Gateway.
- **Frontend**: React, TypeScript (strict), Vite, TailwindCSS, Vitest.
- **Quality Gates (`.ci/jobs.yml`)**:
  - Backend: `mvn checkstyle:check`, `mvn verify` (JaCoCo >= 70%).
  - Frontend: `npm run lint`, `npm run typecheck`, `npm run format:check`, `npm run coverage`.

## Phase 1: Catalog Service (`backend/catalog-service`)
1. **Flyway Migration `V6__dynamic_pricing_and_sku_original_price.sql`**:
   - Backfill `skus.original_price = price` where null; enforce `NOT NULL` and `>= 0`.
   - Drop constraints/index on `skus.price` and drop column `price`.
   - Create `pricing_rules` table + indexes + default active seed rules.
2. **Entities, DTOs & Repository**:
   - Update `Sku.java` (remove `price`, `originalPrice` `@NotNull`).
   - Add `PricingRule.java` + `PricingRuleRepository.java`.
   - Update `SkuRequest.java` (remove `price`, require `originalPrice`), `SkuResponse.java`, `ProductResponse.java` (add `minPrice`, `maxPrice`).
   - Add `PricingRuleRequest.java`, `PricingRuleResponse.java`, `PricePreviewResponse.java`.
3. **Services & Controllers**:
   - Implement `DynamicPricingService` (rule selection by category priority -> global fallback; clamping total multiplier >= 1.0).
   - Implement `PricingRuleService` & `PricingRuleController` (`/api/v1/pricing-rules`).
   - Update `ProductServiceImpl`, `ProductMapper`, `ProductSearchService`, `ProductSpecification`, `SkuLookupService`.
4. **Tests**:
   - Unit tests for `DynamicPricingServiceTest`, `PricingRuleControllerTest`, updated `ProductServiceTest`, `ProductSearchServiceTest`, `SkuLookupServiceTest`.

## Phase 2: API Gateway (`backend/api-gateway`)
1. Add `/api/v1/pricing-rules/**` to `catalog-service` route in `application.yml`.
2. Permit `GET /api/v1/pricing-rules/**` in `GatewaySecurityConfig.java` and `catalog-service/SecurityConfig.java`; require auth (`ADMIN`) for write operations.

## Phase 3: Commerce Service (`backend/commerce-service`)
1. Integrate verified Payment Attempt Pricing foundation from `origin/feature/tt-price-payment-attempts`:
   - `V10__payment_attempt_price_snapshots.sql` (nullable `unit_price`/`total`, `estimated_*`, `price_finalized`, `price_snapshot`, `inventory_ref_id`, `uq_vnpay_active_order`).
   - `PaymentPricingService`, `PaymentPriceSnapshot`, `PaymentService` (multi-attempt + retry key + inventory ref per attempt + IPN snapshot finalization + late-funds `REVIEW`).
2. Align COD behavior per specification:
   - In `CheckoutService.buildOrder()`: For `COD`, finalize `OrderItem.unitPrice`, `Order.subtotal/total`, `priceFinalized = true`, and set `status = PROCESSING` (after inventory reserve succeeds).
   - For `VNPAY`, keep `PENDING_PAYMENT`, `priceFinalized = false`, `unitPrice = null`, `total = null`, reserve deferred until `POST /api/v1/payments`.
3. Update & run all tests (`CheckoutServiceTest`, `PaymentPricingServiceTest`, `PaymentServiceTest`, `OrderServiceTest`, `BaselineMigrationPostgresTest`, `PaymentPostgresTest`).

## Phase 4: Supabase Migration
1. Execute Flyway migrations on Supabase for:
   - `nexus_catalog`: `V6__dynamic_pricing_and_sku_original_price.sql`.
   - `nexus_commerce`: `V8`, `V9`, `V10`.
2. Verify schema, constraints, and existing data integrity on Supabase.

## Phase 5: Frontend Web (`frontend-web`)
1. Update `src/api/client.ts` (`ApiProduct` price range), create `src/api/pricing.ts`.
2. Update `src/api/orders.ts`, `src/api/orderData.ts`, `src/api/payments.ts`.
3. Update `src/features/catalog/catalog.ts`, `ProductDetailPage.tsx`, `SearchPage.tsx` for dynamic price ranges.
4. Update `src/features/seller/SellerPages.tsx` to accept `originalPrice` (minPrice) and preview computed selling price.
5. Add Pricing Rules management UI in `src/features/admin/AdminPages.tsx`.
6. Update `src/features/buyer/BuyerPages.tsx` & `VnpayPaymentResult.tsx` for deferred-price checkout, `startPayment` price snapshot confirmation, and Retry Payment action.
7. Add/update Vitest unit tests (`pricing`, `orders`, `catalog`, `features`).

## Phase 6: Verification, Browser E2E, Swagger Screenshots & Docs
1. Run full CI verification commands for `catalog-service`, `commerce-service`, `api-gateway`, `frontend-web`.
2. Boot local services pointing to Supabase DB and start Frontend Web.
3. Use Playwright / Browser subagent to test Swagger APIs and live Browser E2E flows, saving screenshots to `docs/screenshots/HG-S2-5/`.
4. Write runbook & feature documentation in `docs/HG-S2-5_DYNAMIC_PRICING_PAYMENT_ATTEMPTS.md` and update DTO contract docs.
5. Commit, push branch `feature/HG-S2-5-dynamic-pricing-checkout-flow`, and open Merge Request into `dev`.
