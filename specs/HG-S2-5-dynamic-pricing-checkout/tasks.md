# Implementation Tasks: HG-S2-5 Dynamic Pricing & Deferred Payment Settlement

- [ ] T001 [Catalog] Create Flyway `V6__dynamic_pricing_and_sku_original_price.sql`
- [ ] T002 [Catalog] Update `Sku.java`, add `PricingRule.java` & `PricingRuleRepository.java`
- [ ] T003 [Catalog] Implement `DynamicPricingService`, `PricingRuleService`, `PricingRuleController`
- [ ] T004 [Catalog] Update `ProductMapper`, `ProductResponse`, `SkuRequest`, `SkuResponse`, `ProductServiceImpl`, `ProductSearchService`, `SkuLookupService`, `ProductSpecification`
- [ ] T005 [Catalog] Update/Add unit tests & verify `mvn checkstyle:check verify` (>= 70% coverage)
- [ ] T006 [Gateway] Add `/api/v1/pricing-rules/**` routing and security permissions + test
- [ ] T007 [Commerce] Integrate `V10__payment_attempt_price_snapshots.sql`, `PaymentPricingService`, `PaymentService`, `CheckoutService` (COD immediate finalize + `PROCESSING`, VNPAY deferred `unitPrice=null`)
- [ ] T008 [Commerce] Update/Add unit & postgres tests & verify `mvn checkstyle:check verify` (>= 70% coverage)
- [ ] T009 [Supabase] Apply & verify Flyway migrations on Supabase (`nexus_catalog` V6, `nexus_commerce` V8-V10)
- [ ] T010 [Frontend] Update `client.ts`, `orders.ts`, `orderData.ts`, `payments.ts`, add `pricing.ts`
- [ ] T011 [Frontend] Update `catalog.ts`, `ProductDetailPage.tsx`, `SearchPage.tsx`, `SellerPages.tsx`, `AdminPages.tsx`, `BuyerPages.tsx`, `VnpayPaymentResult.tsx`
- [ ] T012 [Frontend] Run `npm run lint`, `npm run typecheck`, `npm run format:check`, `npm run coverage`
- [ ] T013 [E2E & Docs] Boot live services + FE, verify Swagger & Browser E2E via Playwright, capture screenshots to `docs/screenshots/HG-S2-5/`, update `docs/`
- [ ] T014 [Git] Commit, push to origin, create Merge Request into `dev`
