# Báo cáo Triển khai & Hướng dẫn Vận hành — HG-S2-6: Tách biệt Catalog `skuId` và Fulfillment `skuCode` (`seller_skus`)

- **Mã Issue / Story:** HG-S2-6 (Decouple Catalog SKU ID & Fulfillment SKU Code)
- **Nhánh phát triển:** `feature/HG-S2-6-decouple-catalog-fulfillment-sku` -> `dev`
- **Thành phần ảnh hưởng:** `backend/catalog-service`, `backend/fulfillment-service`, `backend/commerce-service`, `backend/api-gateway`, `frontend-web`, `docs/`
- **Ngày hoàn thành:** 11/11/2026

---

## 1. Tổng quan Kiến trúc & Nguyên tắc Nghiệp vụ

Triển khai tách biệt ranh giới sở hữu dữ liệu giữa **Catalog Service** và **Fulfillment Service** theo kiến trúc Microservices phân tán:

1. **Catalog Service (`nexus_catalog`):**
   - Sở hữu định danh biến thể `skus.id` (`skuId` dạng UUID), thuộc tính biến thể (`attributes` dạng `jsonb`), `original_price` (giá nhập / giá tối thiểu `minPrice`) và trạng thái (`ACTIVE` / `INACTIVE`).
   - **Không lưu cột `sku_code` và không tự sinh `skuCode` kho.** Migration `V7__remove_sku_code_from_skus.sql` đã xóa cột `sku_code` khỏi bảng `skus` và bổ sung `catalog_event_outbox`.
   - Tính giá bán động qua `DynamicPricingService` (`sellingPrice = originalPrice * (1 + max(0, marginRate + seasonFactor + marketAdjustmentRate))`) và tổng hợp khoảng giá `minPrice`..`maxPrice` theo các SKU đang `ACTIVE`.
   - Cung cấp `GET /api/v1/skus/{id}` (kèm alias `GET /api/v1/products/skus/{id}`) trả về `SkuSnapshot` gồm `skuId`, `productId`, `productName`, `thumbnailUrl`, `price`, `originalPrice`, `stockQuantity` (`null`), `isActive`, `sellerId`.
   - Khi cập nhật sản phẩm (`PUT /api/v1/products/{id}`), trường tùy chọn `skus[].id` giúp giữ nguyên `skuId` cũ để không làm đứt liên kết sang `seller_skus` bên Fulfillment.

2. **Fulfillment Service (`nexus_fulfillment`):**
   - Sở hữu độc quyền bảng `seller_skus` (`id`, `seller_id`, `sku_id` `UNIQUE`, `sku_code`, `status`, `created_at`, `updated_at`), sequence `fulfillment_sku_code_seq`, cột `inventories.seller_sku_id` (`FK -> seller_skus.id`) và `fulfillment_event_outbox` qua migration `V6__seller_skus_and_sku_code_sequence.sql`.
   - Khi nhập kho lần đầu (`POST /api/v1/inventory/inbound`) cho một `skuId`:
     - `CatalogClient` (`HttpCatalogClient`) gọi `GET /api/v1/skus/{id}` xác thực Catalog SKU tồn tại (`404 RESOURCE_NOT_FOUND` nếu không tồn tại) và kiểm tra quyền sở hữu Seller (`403 AUTH_FORBIDDEN` nếu khác chủ shop; vai trò `ADMIN` được bỏ qua kiểm tra chủ sở hữu).
     - `SellerSkuRegistrar` (chạy transaction `REQUIRES_NEW`) sinh mã `FSKU-XXXXXXXX` từ `fulfillment_sku_code_seq` và tạo bản ghi `seller_skus`.
   - Các lần nhập kho tiếp theo cho cùng `skuId` **tái sử dụng nguyên bản ghi `seller_skus` và `skuCode` đã cấp**.
   - Cung cấp API tra cứu kho của người bán:
     - `GET /api/v1/inventory/skus` (`@ParameterObject`, `@PageableDefault`, Criteria `JpaSpecificationExecutor` với whitelist sort `id, sellerId, skuId, skuCode, status, createdAt, updatedAt` và giới hạn page size `[1..100]`).
     - `GET /api/v1/inventory/skus/{id}` (tra cứu theo `seller_skus.id` hoặc Catalog `skuId`, kèm phân bổ tồn kho từng kho).

3. **Frontend (`frontend-web`) & API Gateway (`backend/api-gateway`):**
   - API Gateway mở route `/api/v1/skus/**`, `/api/v1/pricing-rules/**` trỏ về `catalog-service`.
   - Giao diện Seller (`CreateProductPage`, `SellerProductsPage`, `SellerInventoryPage`):
     - Form tạo/sửa sản phẩm chỉ khai báo biến thể (`attributes`), `originalPrice` (hiển thị preview giá bán ước tính từ `/api/v1/pricing-rules/preview`) và bảo toàn `Catalog skuId` khi cập nhật; **không nhập hay yêu cầu `skuCode`**.
     - Trang quản lý kho (`SellerInventoryPage`) chọn biến thể theo `Catalog skuId`, gọi `POST /api/v1/inventory/inbound`, hiển thị mã `FSKU-XXXXXXXX` được Fulfillment cấp phát tự động cùng modal chi tiết `Warehouse Breakdown`.

---

## 2. Database Migrations & ERD

- **Catalog Service (`nexus_catalog`):**
  - `backend/catalog-service/src/main/resources/db/migration/V6__dynamic_pricing_and_sku_original_price.sql`: cấu trúc `pricing_rules` và chuyển giá SKU sang `original_price`.
  - `backend/catalog-service/src/main/resources/db/migration/V7__remove_sku_code_from_skus.sql`: `ALTER TABLE skus DROP COLUMN IF EXISTS sku_code;` + `catalog_event_outbox`.
- **Fulfillment Service (`nexus_fulfillment`):**
  - `backend/fulfillment-service/src/main/resources/db/migration/V6__seller_skus_and_sku_code_sequence.sql`: tạo `fulfillment_sku_code_seq`, bảng `seller_skus`, cột `inventories.seller_sku_id`, backfill dữ liệu cũ và tạo `fulfillment_event_outbox`.
- **Tài liệu hợp đồng & ERD đã cập nhật:**
  - `docs/database/ERD.md`
  - `docs/dto-contracts/CATALOG_SERVICE.md`
  - `docs/dto-contracts/FULFILLMENT_SERVICE.md`
  - `docs/dto-contracts/API_GATEWAY.md`

---

## 3. Bằng chứng Kiểm thử Thực tế (Swagger UI & Real Chrome Browser)

Toàn bộ ảnh chụp màn hình được lưu tại `docs/screenshots/HG-S2/` gồm **10 kịch bản Swagger UI** và **4 kịch bản End-to-End trên trình duyệt thật**.

### 3.1. Swagger UI — Catalog Service (`http://127.0.0.1:8082/swagger-ui/index.html`)

| Kịch bản | HTTP | Kết quả xác minh | File ảnh |
|---|---|---|---|
| Tạo Product không gửi `skuCode` / `price` | `201` | Trả về `skus[0].id` (Catalog UUID), giá động `price`, `originalPrice`, `minPrice`/`maxPrice` | `create-product_success_201.png` |
| Cập nhật Product truyền kèm `skus[].id` | `200` | Bảo toàn nguyên `skuId` cũ, cập nhật tên/giá gốc và tính lại giá bán động | `update-product_preserve-sku-id_200.png` |
| Tra cứu `GET /api/v1/skus/{id}` | `200` | Trả `SkuSnapshot` gồm `skuId`, `productId`, `sellerId`, `price`, `originalPrice`, `stockQuantity: null` | `get-catalog-sku_success_200.png` |

![Create Product Success 201](screenshots/HG-S2/create-product_success_201.png)

![Update Product Preserve SKU ID 200](screenshots/HG-S2/update-product_preserve-sku-id_200.png)

![Get Catalog SKU Success 200](screenshots/HG-S2/get-catalog-sku_success_200.png)

### 3.2. Swagger UI — Fulfillment Service (`http://127.0.0.1:8085/swagger-ui/index.html`)

| Kịch bản | HTTP | Kết quả xác minh | File ảnh |
|---|---|---|---|
| Nhập kho lần đầu `POST /api/v1/inventory/inbound` | `200` | Tự cấp `sellerSkuId` và mã `FSKU-00000047` trong `seller_skus`, `totalQty = 10` | `inbound-stock_first-generate-fsku_200.png` |
| Nhập kho lặp lại cùng `skuId` | `200` | Tái sử dụng đúng `sellerSkuId` & `skuCode = FSKU-00000047`, cộng dồn `totalQty = 15` | `inbound-stock_repeat-reuse-fsku_200.png` |
| Seller khác nhập kho cho SKU không thuộc sở hữu | `403` | Từ chối với `AUTH_FORBIDDEN` nhờ kiểm tra `sellerId` từ Catalog `SkuSnapshot` | `inbound-stock_wrong-seller_403.png` |
| Nhập kho với `skuId` không tồn tại trên Catalog | `404` | Từ chối với `RESOURCE_NOT_FOUND` | `inbound-stock_missing-catalog-sku_404.png` |
| Nhập kho với `quantity = 0` | `422` | Bean Validation chặn `@Min(1)` với `VALIDATION_ERROR` | `inbound-stock_invalid-quantity_422.png` |
| Danh sách SKU kho `GET /api/v1/inventory/skus` | `200` | Trả danh sách `seller_skus` kèm tồn kho tổng hợp và phân trang hợp lệ | `list-seller-skus_success_200.png` |
| Chi tiết SKU kho `GET /api/v1/inventory/skus/{id}` | `200` | Trả thông tin `seller_skus` kèm mảng tồn kho theo từng kho `warehouses[]` | `get-seller-sku-detail_success_200.png` |

![Inbound First Generate FSKU 200](screenshots/HG-S2/inbound-stock_first-generate-fsku_200.png)

![Inbound Repeat Reuse FSKU 200](screenshots/HG-S2/inbound-stock_repeat-reuse-fsku_200.png)

![Inbound Wrong Seller 403](screenshots/HG-S2/inbound-stock_wrong-seller_403.png)

![Inbound Missing Catalog SKU 404](screenshots/HG-S2/inbound-stock_missing-catalog-sku_404.png)

![Inbound Invalid Quantity 422](screenshots/HG-S2/inbound-stock_invalid-quantity_422.png)

![List Seller SKUs 200](screenshots/HG-S2/list-seller-skus_success_200.png)

![Get Seller SKU Detail 200](screenshots/HG-S2/get-seller-sku-detail_success_200.png)

### 3.3. Real Chrome Browser End-to-End (`http://localhost:5173`)

| Bước E2E | Kết quả xác minh trên giao diện thật | File ảnh |
|---|---|---|
| Tạo sản phẩm mới từ trang Seller | Không có ô nhập `skuCode`; chỉ nhập biến thể & giá nhập `originalPrice`, hiển thị `Estimated Marketplace Selling Price` theo rule Catalog | `browser_create-product-no-skucode.png` |
| Mở modal sửa sản phẩm | Hiển thị và giữ nguyên `Catalog ID` (UUID biến thể) của Catalog | `browser_edit-product-preserve-sku-id.png` |
| Nhập kho lần đầu tại Inventory | Cấp phát tự động `Fulfillment SKU` dạng `FSKU-XXXXXXXX` từ biến thể Catalog | `browser_inbound-first-assign-fsku.png` |
| Nhập kho lần 2 & mở chi tiết Warehouse Breakdown | Cộng dồn tồn kho trên cùng `FSKU-XXXXXXXX` và hiển thị rõ `Catalog skuId` bên cạnh `Seller SKU Record ID` | `browser_inbound-repeat-reuse-and-detail.png` |

![Browser Create Product No SkuCode](screenshots/HG-S2/browser_create-product-no-skucode.png)

![Browser Edit Product Preserve SkuId](screenshots/HG-S2/browser_edit-product-preserve-sku-id.png)

![Browser Inbound First Assign FSKU](screenshots/HG-S2/browser_inbound-first-assign-fsku.png)

![Browser Inbound Repeat Reuse And Detail](screenshots/HG-S2/browser_inbound-repeat-reuse-and-detail.png)

---

## 4. Hướng dẫn Khởi chạy & Kiểm thử Cục bộ (Local Run Guide)

### 4.1. Điều kiện tiên quyết
- Java 21 JDK & Maven 3.9+
- Node.js 22+ & npm (cho `frontend-web` và `scripts/generate-erd.mjs`)
- PostgreSQL / Supabase đã có schema `nexus_catalog`, `nexus_fulfillment`, `nexus_user`, `nexus_commerce`, `nexus_auction`
- Keycloak IAM container (`http://127.0.0.1:8180`) cho đăng nhập/JWT thật

### 4.2. Trình tự build & kiểm thử CI cục bộ

> Nếu `backend/common` có thay đổi, cài đặt local snapshot trước khi verify từng service (theo `.ci/templates.yml`):

```powershell
mvn -B --no-transfer-progress -f backend/common/pom.xml install -DskipTests
mvn -B --no-transfer-progress -f backend/common/pom.xml test
```

Kiểm tra từng service backend bị ảnh hưởng (theo `.ci/jobs.yml` và `.ci/erd-alignment.yml`):

```powershell
mvn -B --no-transfer-progress -f backend/catalog-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/fulfillment-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/commerce-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/api-gateway/pom.xml verify
```

Kiểm tra đồng bộ ERD & `frontend-web`:

```powershell
node --test scripts/generate-erd.test.mjs
cd frontend-web
npm run lint
npm run typecheck
npm run format:check
npm run coverage
```

### 4.3. Khởi chạy Stack Cục bộ (Swagger + Frontend)

1. Khởi chạy lần lượt các service với biến môi trường trỏ tới DB & Keycloak:
   - `user-service` (`:8081`)
   - `catalog-service` (`:8082`) — tự áp dụng Flyway tới `V7__remove_sku_code_from_skus.sql`
   - `fulfillment-service` (`:8085`, `CATALOG_SERVICE_URL=http://127.0.0.1:8082`) — tự áp dụng Flyway tới `V6__seller_skus_and_sku_code_sequence.sql`
   - `api-gateway` (`:8080`, `AUTH_COOKIE_SECURE=false` khi chạy HTTP local)
2. Khởi chạy `frontend-web` dev server (Vite proxy `/api` sang `http://localhost:8080`):
   ```powershell
   cd frontend-web
   npm run dev -- --port 5173
   ```
3. Địa chỉ truy cập kiểm thử:
   - Catalog Swagger UI: `http://127.0.0.1:8082/swagger-ui/index.html`
   - Fulfillment Swagger UI: `http://127.0.0.1:8085/swagger-ui/index.html`
   - Web Application: `http://localhost:5173`
