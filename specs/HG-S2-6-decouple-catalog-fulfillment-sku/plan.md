# Kế hoạch kỹ thuật HG-S2-6: Tách Catalog SKU ID và Fulfillment Seller SKU Code

## 1. Kiến trúc tổng thể & Giao tiếp liên dịch vụ

```
[Seller / Admin UI]
   │
   ├── (1) POST/PUT /api/v1/products ──► [API Gateway] ──► [Catalog Service]
   │                                                          ├─ nexus_catalog.skus (id, product_id, original_price, attributes, status)
   │                                                          ├─ pricing_rules (DynamicPricingService)
   │                                                          └─ catalog_event_outbox (ProductSkuCreated)
   │
   └── (2) POST /api/v1/inventory/inbound ──► [API Gateway] ──► [Fulfillment Service]
                                                                    │
                                   HTTP GET /api/v1/skus/{skuId} ◄──┘ (xác thực tồn tại + sellerId)
                                                                    │
                                                                    ├─ nexus_fulfillment.seller_skus (FSKU-XXXXXXXX từ seq)
                                                                    ├─ inventories (seller_sku_id NOT NULL + giữ sku_id đồng bộ)
                                                                    ├─ inventory_ledger (INBOUND)
                                                                    └─ fulfillment_event_outbox (InventoryInboundCompleted)
```

---

## 2. Thiết kế chi tiết theo tầng

### 2.1. Catalog Service (`backend/catalog-service`)
1. **Đồng bộ V6 Supabase + thêm V7:**
   - Giữ nguyên checksum file `V6__dynamic_pricing_and_sku_original_price.sql` đã chạy trên Supabase cùng entity/service `PricingRule`, `DynamicPricingService`, `PricingRuleService`, `PricingRuleController`, `ProductResponse(minPrice, maxPrice)`.
   - Tạo `V7__remove_sku_code_from_skus.sql`: xóa cột `sku_code` khỏi `skus`; tạo bảng `catalog_event_outbox (id uuid PK, aggregate_type varchar(50), aggregate_id uuid, event_type varchar(100), payload text/jsonb, done boolean, attempts integer, next_attempt_at timestamp, published_at timestamp, created_at timestamp, last_error text)` theo đúng cú pháp được `scripts/generate-erd.mjs` hỗ trợ.
2. **Xử lý bảo toàn `skuId` và Outbox:**
   - Gán sẵn `UUID` trước khi persist `Sku` mới để tạo payload `ProductSkuCreated` đồng bộ trong cùng transaction `ProductServiceImpl`.
   - Trong `ProductServiceImpl.update(UUID id, ProductRequest)`:
     - Các `SkuRequest` có `id`: tìm trong danh sách SKU hiện hữu của product, cập nhật `attributes`, `originalPrice`, `status` tại chỗ để giữ nguyên `skuId`; nếu gửi `id` không thuộc product này → ném `BusinessException` (`Invalid SKU id for product`).
     - Các `SkuRequest` không có `id`:
       - Nếu payload có thêm SKU mới hoàn toàn → tạo SKU mới (gán UUID mới) và phát `ProductSkuCreated`.
       - Các SKU cũ không nằm trong request `skus` bị loại bỏ an toàn như trước (orphanRemoval).
3. **Endpoint & Gateway:**
   - `SkuLookupController`: `@GetMapping({"/api/v1/skus/{id}", "/api/v1/products/skus/{id}"})`.
   - `SecurityConfig`: permit `GET /api/v1/skus/**`.
   - `backend/api-gateway/src/main/resources/application.yml`: thêm `/api/v1/skus/**` vào `catalog-service` route.

### 2.2. Fulfillment Service (`backend/fulfillment-service`)
1. **Flyway Migration V6 (`V6__seller_skus_and_sku_code_sequence.sql`):**
   - Dùng version `V6` vì `V5__warehouse_inventory_enhancements.sql` đã chạy trên Supabase (`nexus_fulfillment`). Cấu hình `spring.flyway.out-of-order: true` và `ignore-migration-patterns: "*:missing,*:future"` để vừa chạy sạch trên CI/Docker DB trống (`V1..V4,V6`), vừa nối tiếp `V1..V5` trên Supabase.
   - Các câu lệnh DDL chính tuân thủ parser `scripts/generate-erd.mjs` (mỗi `ALTER TABLE` chỉ một hành động `ADD COLUMN` / `ADD CONSTRAINT` / `ALTER COLUMN`; phần backfill phức tạp đặt trong khối `DO $$ ... END $$;`).
   - Sequence `fulfillment_sku_code_seq` bắt đầu từ `1`, tăng `1`, format `'FSKU-' || LPAD(nextval('fulfillment_sku_code_seq')::text, 8, '0')`.
2. **Client xác thực Catalog (`CatalogSkuClient` / `HttpCatalogSkuClient`):**
   - Dùng `RestClient` với timeout ngắn (`connectTimeout`, `readTimeout`) gọi `${app.catalog.base-url}/api/v1/skus/{skuId}`.
   - Parse envelope `{ data: { skuId, productId, sellerId, productName, price, originalPrice, isActive } }`.
   - Phân biệt rạch ròi: `404` → `ResourceNotFoundException`, lỗi `5xx`/I-O/timeout/JSON hỏng → `ApiException(HttpStatus.BAD_GATEWAY, "CATALOG_UNAVAILABLE")`.
   - Trong unit test thuần service (`InventoryServiceTest`), inject mock `CatalogSkuClient`. Trong `@WebMvcTest`, `@MockitoBean InventoryService` giữ cô lập web layer.
3. **Concurrency-safe `seller_skus` Find-or-Create:**
   - Trong `InventoryServiceImpl`: kiểm tra `sellerSkuRepository.findBySkuId(skuId)`.
   - Nếu chưa tồn tại: gọi helper trong bean/transaction hoặc `saveAndFlush` với `REQUIRES_NEW`? Lưu ý nếu chạy cùng transaction JDBC mà constraint violation xảy ra thì Postgres đánh dấu transaction bị `aborted` nếu dùng lại entity manager; do đó đóng gói bước **insert `seller_skus` qua một service helper `SellerSkuRegistrar`** (`@Transactional(propagation = Propagation.REQUIRES_NEW)`) catch `DataIntegrityViolationException`, sau đó `findBySkuId(skuId)` ở outer transaction -> đảm bảo 100% an toàn race condition trên PostgreSQL thật mà không làm hỏng transaction ngoài!
   - Sinh mã kế tiếp qua `SellerSkuRepository`: truy vấn native Postgres `nextval('fulfillment_sku_code_seq')` (có fallback atomic counter nếu chạy H2 mà sequence chưa có hoặc dùng chuẩn SQL `NEXT VALUE FOR fulfillment_sku_code_seq` — thậm chí H2 `MODE=PostgreSQL` hỗ trợ `SELECT nextval('fulfillment_sku_code_seq')` khi tạo sequence trong H2!). Ta có thể test trực tiếp bằng Hibernate/H2 hoặc viết query tương thích cả hai!
4. **Endpoints & Security:**
   - `POST /api/v1/inventory/inbound`: trả `InventoryStockResponse` đã mở rộng với `UUID sellerSkuId` và `String skuCode` (giữ nguyên các field cũ ở cuối hoặc đầu chuẩn JSON tương thích test hiện có).
   - `GET /api/v1/inventory/skus` & `GET /api/v1/inventory/skus/{id}`: bảo vệ bằng `@PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")`; với `ROLE_SELLER`, bắt buộc `UUID.fromString(authentication.getName())` làm `sellerId`.

### 2.3. Commerce Service (`backend/commerce-service`)
- `SkuInfo` đặt `skuCode` tùy chọn (nullable).
- `CheckoutService.loadCatalogSkus` / preview: bỏ điều kiện bắt buộc `sku.skuCode() == null`; tạo helper `fallbackSkuCode(UUID skuId, String skuCode)` trả `"SKU-" + skuId.toString().substring(0, 8).toUpperCase(Locale.ROOT)` khi Catalog không còn trường `skuCode` (đồng nhất pattern đã có trong `OrderServiceImpl`).
- `CartMapper`: dùng `skuInfo != null && skuInfo.skuCode() != null ? skuInfo.skuCode() : fallbackSkuCode(item.getSkuId())`.

### 2.4. Frontend (`frontend-web`)
- `src/api/client.ts` & `src/api/pricing.ts`: cập nhật `ApiProduct` / `ApiSku` để `originalPrice` là đầu vào chuẩn, `skuCode?: string` tùy chọn.
- `src/api/inventory.ts`: client gọn cho Seller/Admin inventory (`/inventory/skus`, `/inventory/skus/{id}`, `/inventory/inbound`, `/inventory/adjust`, `/inventory/ledger`).
- `src/features/seller/SellerPages.tsx`:
  - `CreateProductPage`: hỗ trợ tạo mới & chỉnh sửa sản phẩm hiện có (`pageParams.id`), bỏ field nhập `skuCode`, chỉ nhập `originalPrice` (hiển thị giá ước tính theo Pricing Rules nếu có), giữ `sku.id` khi `PUT /products/{id}`. Hiển thị banner giải thích mã SKU kho được cấp tự động khi nhập kho.
  - `SellerInventoryPage`: thay mock `PRODUCTS` bằng dữ liệu thật từ `/inventory/skus` + `/products/mine` + `/inventory/ledger`, thêm modal **Nhập kho (Inbound Stock)** chọn biến thể SKU sản phẩm, nhập số lượng, gọi `POST /inventory/inbound`, hiển thị ngay `FSKU-XXXXXXXX` được cấp (và giữ nguyên khi nhập thêm).
- `ProductDetailPage.tsx`, `BuyerPages.tsx`, `AdminPages.tsx`, `Cards.tsx`, `guestCart.ts`: hiển thị `sku.skuCode || fallback` an toàn (không vỡ UI khi Catalog trả về không có `skuCode`).

---

## 3. Kế hoạch kiểm thử & Xác minh thực tế
1. **Unit & Slice Tests:** Backend Maven `mvn checkstyle:check verify` cho `catalog-service`, `fulfillment-service`, `commerce-service`, `api-gateway` (JaCoCo >= 70%).
2. **PostgreSQL Real Concurrency Test:** Kiểm tra concurrent first-inbound cho cùng 1 `skuId` trên PostgreSQL / Supabase không tạo trùng `seller_skus`, rollback sạch nếu lỗi.
3. **ERD Sync Check:** `node --test scripts/generate-erd.test.mjs && node scripts/generate-erd.mjs`.
4. **Frontend Quality Gates:** `npm run lint`, `npm run typecheck`, `npx prettier --check`, `npm run coverage`.
5. **Live Stack + Supabase + Swagger + Browser Screenshots:** Khởi chạy Gateway + User + Catalog + Fulfillment (+ Commerce nếu cần) kết nối Supabase, chạy Swagger thử từng case success/403/404/400/502, thao tác Browser UI luồng Seller tạo sản phẩm -> nhập kho lần 1 (sinh `FSKU-...`) -> nhập kho lần 2 (giữ nguyên mã), chụp ảnh lưu tại `docs/screenshots/HG-S2-6/`, dọn dẹp dữ liệu test tạm trước khi commit & mở MR.
