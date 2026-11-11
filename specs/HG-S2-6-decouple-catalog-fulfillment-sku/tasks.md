# Danh sách công việc (Tasks) HG-S2-6

## Giai đoạn 1: Spec-Kit & Phân tích đồng quy
- [x] T01: Viết `spec.md`, `plan.md`, `tasks.md` cho `HG-S2-6-decouple-catalog-fulfillment-sku` và rà chéo tính nhất quán (/speckit-analyze).

## Giai đoạn 2: Catalog Service & API Gateway
- [ ] T02: Đồng bộ thay đổi Dynamic Pricing V6 từ `HG-S2-5` vào `catalog-service` (khớp checksum V6 trên Supabase) và chạy compile/test baseline.
- [ ] T03: Tạo `V7__remove_sku_code_from_skus.sql` (drop `sku_code`, tạo bảng `catalog_event_outbox`).
- [ ] T04: Xóa `skuCode` khỏi `Sku`, `SkuRequest`, `SkuResponse`, `SkuRepository`, `ProductMapper`, `SkuLookupService`; bổ sung `UUID id` vào `SkuRequest` và sửa `ProductServiceImpl.update()` giữ nguyên `skuId` khi cập nhật.
- [ ] T05: Triển khai `CatalogEventOutbox` + `CatalogOutboxPublisher` bắn `ProductSkuCreated` trong cùng transaction tạo SKU.
- [ ] T06: Mở endpoint `GET /api/v1/skus/{id}` tại `SkuLookupController` + `SecurityConfig` và thêm route `/api/v1/skus/**` tại `api-gateway`.
- [ ] T07: Cập nhật & viết mới unit/controller tests cho `catalog-service` và `api-gateway`; xác nhận `mvn checkstyle:check verify` đạt chuẩn.

## Giai đoạn 3: Fulfillment Service
- [ ] T08: Tạo `V6__seller_skus_and_sku_code_sequence.sql` (`fulfillment_sku_code_seq`, `seller_skus`, `inventories.seller_sku_id`, backfill dữ liệu cũ giữ `inventories.sku_id`, `fulfillment_event_outbox`) và cấu hình Flyway out-of-order.
- [ ] T09: Tạo entity `SellerSku`, enum `SellerSkuStatus`, repository `SellerSkuRepository`, `FulfillmentEventOutbox` / `SellerSkuRegistrar` an toàn race condition.
- [ ] T10: Triển khai `CatalogSkuClient` & `HttpCatalogSkuClient` gọi `GET /api/v1/skus/{skuId}` (xử lý 404/403/502 rõ ràng).
- [ ] T11: Cập nhật `Inventory`, `InventoryRepository`, `StockInboundRequest`, `InventoryStockResponse`, `SellerSkuResponse`, `SellerSkuDetailResponse`.
- [ ] T12: Cập nhật `InventoryServiceImpl.inboundStock()` (xác thực quyền + Catalog + find-or-create `seller_skus` + `inventories` + `ledger` + outbox `InventoryInboundCompleted`) và bổ sung `getSellerSkus()`, `getSellerSkuDetail()`.
- [ ] T13: Mở endpoint `GET /api/v1/inventory/skus` và `GET /api/v1/inventory/skus/{id}` trong `InventoryController` + kiểm tra `SecurityConfig`.
- [ ] T14: Viết đầy đủ unit tests, controller tests (`@WebMvcTest`), HttpCatalogSkuClient tests (`MockRestServiceServer`) và concurrency/Postgres tests; chạy `mvn checkstyle:check verify`.

## Giai đoạn 4: Commerce Service & Cross-Service Compatibility
- [ ] T15: Cập nhật `CheckoutService`, `CartMapper`, `OrderServiceImpl`, `SkuInfo` và tests của `commerce-service` để không còn lỗi khi Catalog bỏ `skuCode`; chạy `mvn checkstyle:check verify`.

## Giai đoạn 5: Frontend (`frontend-web`)
- [ ] T16: Cập nhật types, `client.ts`, `pricing.ts`, tạo `src/api/inventory.ts`.
- [ ] T17: Cập nhật `CreateProductPage` & `SellerProductsPage` (bỏ nhập/gửi `skuCode`, giữ `sku.id` khi edit, hiển thị `FSKU` từ Fulfillment nếu đã nhập kho).
- [ ] T18: Triển khai đầy đủ `SellerInventoryPage` kết nối API thật (chọn SKU nhập kho, sinh & hiển thị `skuCode` `FSKU-...`, điều chỉnh tồn, xem sổ cái).
- [ ] T19: Cập nhật hiển thị `skuCode` an toàn ở `ProductDetailPage`, `BuyerPages`, `AdminPages`, `Cards`, `guestCart` và cập nhật/bổ sung Vitest tests; chạy lint/typecheck/prettier/coverage.

## Giai đoạn 6: Database Supabase, ERD, Tài liệu, Bằng chứng Swagger & Browser, MR
- [ ] T20: Chạy `node --test scripts/generate-erd.test.mjs && node scripts/generate-erd.mjs` cập nhật `docs/database/ERD.md`.
- [ ] T21: Khởi chạy service áp dụng Flyway `catalog V7` & `fulfillment V6` lên Supabase, kiểm tra backfill 45 dòng tồn kho thành công.
- [ ] T22: Thực hiện kiểm thử thực tế trên Swagger UI (Catalog + Fulfillment success/403/404/400/502) và Browser thật (Seller Create Product + Inbound x2), lưu ảnh chuẩn vào `docs/screenshots/HG-S2-6/`, dọn dữ liệu test tạm.
- [ ] T23: Cập nhật `docs/dto-contracts/` (`CATALOG_SERVICE.md`, `FULFILLMENT_SERVICE.md`, `API_GATEWAY.md`) và viết hướng dẫn chạy `docs/HG-S2-6_CATALOG_FULFILLMENT_SKU_SEPARATION.md`.
- [ ] T24: Chạy lại toàn bộ test CI sau lần sửa cuối, commit theo Conventional Commits, push nhánh lên remote và tạo Merge Request vào `dev`.
