# [HG-S2] Hướng dẫn Chạy & Bằng chứng Kiểm thử Dynamic Product Pricing & SKU Flow

Tài liệu này tổng hợp kiến trúc thực tế, hướng dẫn khởi chạy cục bộ, bộ kiểm thử theo `.ci/` và bằng chứng Swagger / Browser cho issue **[HG-S2]** trên nhánh `feature/HG-S2-implement-dynamic-product-pricing-payment-calculation-flow`.

> Xem thêm tài liệu kiến trúc liên quan đã có trong `docs/`:
> - [HG-S2-6_CATALOG_FULFILLMENT_SKU_SEPARATION.md](HG-S2-6_CATALOG_FULFILLMENT_SKU_SEPARATION.md) — Tách biệt Catalog `skuId` và Fulfillment `skuCode` (`FSKU-XXXXXXXX`), migration V6/V7 và luồng FE Seller.
> - [PAYMENT_ATTEMPT_PRICING.md](PAYMENT_ATTEMPT_PRICING.md) — Chốt giá chính thức theo Payment Attempt tại `commerce-service` (migration V10/V11).
> - [dto-contracts/CATALOG_SERVICE.md](dto-contracts/CATALOG_SERVICE.md) & [dto-contracts/FULFILLMENT_SERVICE.md](dto-contracts/FULFILLMENT_SERVICE.md) — Contract request/response chính thức.

---

## 1. Luồng nghiệp vụ hiện có trong mã nguồn

1. **Quản trị quy tắc giá & tính giá động (`catalog-service`):**
   - Migration `V6__dynamic_pricing_and_sku_original_price.sql` chuẩn hóa `skus.original_price >= 0` làm giá nhập tối thiểu (`minPrice`), bỏ cột `skus.price` cố định và tạo bảng `pricing_rules` (kèm rule mặc định `+15%` toàn hệ thống).
   - `DynamicPricingService` tính giá bán hiện tại theo công thức:
     `sellingPrice = originalPrice * (1 + max(0, marginRate + seasonFactor + marketAdjustmentRate))` (làm tròn `HALF_UP` 2 chữ số, không bao giờ nhỏ hơn `originalPrice`).
   - Thứ tự chọn rule đang hiệu lực (`isActive = true` trong khoảng `startDate..endDate`): danh mục trực tiếp → danh mục cha/ông (tối đa 3 cấp) → rule toàn hệ thống (`categoryId = null`), sắp xếp theo `priority DESC, createdAt DESC`.
   - Seller xem trước giá tại `GET /api/v1/pricing-rules/preview`; Admin quản trị CRUD qua `/api/v1/pricing-rules` (`ADMIN`).
2. **Tách biệt Catalog SKU và Fulfillment SKU (`catalog-service` + `fulfillment-service`):**
   - `V7__remove_sku_code_from_skus.sql` (Catalog) loại bỏ `sku_code` khỏi bảng `skus`; Seller tạo/sửa sản phẩm chỉ khai báo biến thể (`attributes`) và `originalPrice`.
   - `V6__seller_skus_and_sku_code_sequence.sql` (Fulfillment) tạo bảng `seller_skus` ánh xạ `(seller_id, sku_id)` ↔ `sku_code` (`FSKU-XXXXXXXX`) sinh tự động trong lần nhập kho đầu tiên và giữ nguyên ở các lần nhập/tồn kho tiếp theo.
3. **Tính tiền tạm tính ở FE & chốt giá tại BE (`frontend-web` + `commerce-service`):**
   - FE (`frontend-web/src/utils/pricingEngine.ts`) tính giá tạm tính (Estimated Amount) trên giỏ hàng/checkout; BE tuyệt đối không tin tổng tiền từ client.
   - `CheckoutService` (`commerce-service`) truy vấn giá live từ `CatalogClient` để dựng `estimatedSubtotal` + `shippingFee` khi tạo đơn (`POST /api/v1/orders`); giá chính thức (`officialUnitPrice`, `officialTotal`, `priceFinalized = true`) chỉ chốt khi xác thực thanh toán thành công (xem [PAYMENT_ATTEMPT_PRICING.md](PAYMENT_ATTEMPT_PRICING.md)).

---

## 2. Hướng dẫn chạy cục bộ và kiểm thử trên Swagger UI

### 2.1. Yêu cầu môi trường
- Java 21, Maven 3.9+
- PostgreSQL 16+ (local hoặc container Docker)
- Keycloak (hoặc JWKS/issuer nội bộ cho test Swagger API cần JWT)
- Node.js 22+ & npm (để chạy `frontend-web`)

### 2.2. Khởi chạy hạ tầng và các Service liên quan
1. Cài `common` vào local Maven repository (bắt buộc trước khi build/chạy từng service):
   ```bash
   mvn -f backend/common/pom.xml install -DskipTests
   ```
2. Khởi động database và các microservice cần thiết:
   - `catalog-service` (port mặc định trong Docker/dev: `8082`, standalone `8080`):
     ```bash
     cd backend/catalog-service
     SERVER_PORT=8082 DB_URL=jdbc:postgresql://localhost:5432/nexus_catalog DB_USERNAME=postgres DB_PASSWORD=<password> mvn spring-boot:run
     ```
   - `fulfillment-service` (port `8085`), `commerce-service` (port `8083`), `api-gateway` (port `8080`): chạy tương tự với `DB_URL` riêng của từng service (chi tiết cổng và env xem [HG-S2-6_CATALOG_FULFILLMENT_SKU_SEPARATION.md](HG-S2-6_CATALOG_FULFILLMENT_SKU_SEPARATION.md#4-h%C6%B0%E1%BB%9Bng-d%E1%BA%ABn-ch%E1%BA%A1y-c%E1%BB%A5c-b%E1%BB%99-local-run-guide)).
3. Mở Swagger UI trực tiếp:
   - Catalog Service (Pricing Rules, Products, SKUs): `http://127.0.0.1:8082/swagger-ui/index.html`
   - Fulfillment Service (Inbound, Seller SKUs, Inventory): `http://127.0.0.1:8085/swagger-ui/index.html`
   - Commerce Service (Orders, Checkout, Payments): `http://127.0.0.1:8083/swagger-ui/index.html`
4. Khởi chạy Frontend dev server:
   ```bash
   cd frontend-web
   npm ci
   npm run dev
   ```
   Truy cập `http://localhost:5173`.

---

## 3. Bộ lệnh kiểm thử theo workflow CI (`.ci/jobs.yml`, `.ci/erd-alignment.yml`)

```bash
# 1. Cài & test common
mvn -B --no-transfer-progress -f backend/common/pom.xml install -DskipTests
mvn -B --no-transfer-progress -f backend/common/pom.xml test

# 2. Checkstyle & verify (Unit + Integration + JaCoCo >= 70%) cho các service bị ảnh hưởng
mvn -B --no-transfer-progress -f backend/api-gateway/pom.xml verify
mvn -B --no-transfer-progress -f backend/catalog-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/fulfillment-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/commerce-service/pom.xml checkstyle:check verify

# 3. Kiểm tra ERD script
node --test scripts/generate-erd.test.mjs

# 4. Frontend lint, typecheck, format check, test coverage (>= 70%) & build
cd frontend-web
npm run lint
npm run typecheck
npx prettier --end-of-line auto --check "src/**/*.{ts,tsx,css}" "e2e/**/*.ts" "*.{js,ts,json}"
npm run coverage
npm run build
```

> Lưu ý: Các test class hậu tố `*PostgresTest` sử dụng `@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL")` / `FULFILLMENT_TEST_DB_URL` và tự động skip ở máy local nếu chưa trỏ DB test Postgres chuyên dụng.

---

## 4. Bằng chứng Swagger UI & Browser (`docs/screenshots/HG-S2/`)

### 4.1. API Quy tắc tính giá động (`PricingRuleController` — Catalog Service)

| Trường hợp kiểm thử | Endpoint & HTTP Status | Ảnh bằng chứng |
|---|---|---|
| Lấy danh sách quy tắc đang hiệu lực (Public) | `GET /api/v1/pricing-rules` → `200` | [pricing-rules-list_success_200.png](screenshots/HG-S2/pricing-rules-list_success_200.png) |
| Lấy toàn bộ quy tắc quản trị (Admin) | `GET /api/v1/pricing-rules/all` → `200` | [pricing-rules-list-all_success_200.png](screenshots/HG-S2/pricing-rules-list-all_success_200.png) |
| Xem trước giá bán động hợp lệ | `GET /api/v1/pricing-rules/preview` → `200` | [pricing-rules-preview_success_200.png](screenshots/HG-S2/pricing-rules-preview_success_200.png) |
| Xem trước giá với `categoryId` không tồn tại | `GET /api/v1/pricing-rules/preview` → `404` | [pricing-rules-preview_missing-category_404.png](screenshots/HG-S2/pricing-rules-preview_missing-category_404.png) |
| Tạo quy tắc giá mới (Admin) | `POST /api/v1/pricing-rules` → `201` | [pricing-rules-create_success_201.png](screenshots/HG-S2/pricing-rules-create_success_201.png) |
| Từ chối tạo quy tắc khi role là `SELLER` | `POST /api/v1/pricing-rules` → `403` | [pricing-rules-create_forbidden-seller_403.png](screenshots/HG-S2/pricing-rules-create_forbidden-seller_403.png) |
| Lỗi nghiệp vụ khoảng ngày (`endDate < startDate`) | `POST /api/v1/pricing-rules` → `400` | [pricing-rules-create_invalid-dates_400.png](screenshots/HG-S2/pricing-rules-create_invalid-dates_400.png) |
| Lỗi validation `@NotBlank` khi thiếu `name` | `POST /api/v1/pricing-rules` → `422` | [pricing-rules-create_missing-name_422.png](screenshots/HG-S2/pricing-rules-create_missing-name_422.png) |
| Cập nhật quy tắc giá (Admin) | `PUT /api/v1/pricing-rules/{id}` → `200` | [pricing-rules-update_success_200.png](screenshots/HG-S2/pricing-rules-update_success_200.png) |
| Cập nhật quy tắc không tồn tại | `PUT /api/v1/pricing-rules/{id}` → `404` | [pricing-rules-update_not-found_404.png](screenshots/HG-S2/pricing-rules-update_not-found_404.png) |
| Xóa quy tắc giá (Admin) | `DELETE /api/v1/pricing-rules/{id}` → `204` | [pricing-rules-delete_success_204.png](screenshots/HG-S2/pricing-rules-delete_success_204.png) |

### 4.2. API Sản phẩm, Nhập kho & Luồng trình duyệt Seller (`HG-S2-6`)

| Trường hợp kiểm thử | Ảnh bằng chứng |
|---|---|
| Tạo sản phẩm không cần `skuCode` (`201`) | [create-product_success_201.png](screenshots/HG-S2/create-product_success_201.png) |
| Sửa sản phẩm giữ nguyên `Catalog skuId` (`200`) | [update-product_preserve-sku-id_200.png](screenshots/HG-S2/update-product_preserve-sku-id_200.png) |
| Tra cứu SKU nội bộ Catalog (`200`) | [get-catalog-sku_success_200.png](screenshots/HG-S2/get-catalog-sku_success_200.png) |
| Nhập kho lần 1 sinh `FSKU-...` (`200`) | [inbound-stock_first-generate-fsku_200.png](screenshots/HG-S2/inbound-stock_first-generate-fsku_200.png) |
| Nhập kho lần 2 giữ nguyên `FSKU-...` (`200`) | [inbound-stock_repeat-reuse-fsku_200.png](screenshots/HG-S2/inbound-stock_repeat-reuse-fsku_200.png) |
| Từ chối nhập kho sai Seller (`403`) | [inbound-stock_wrong-seller_403.png](screenshots/HG-S2/inbound-stock_wrong-seller_403.png) |
| Nhập kho SKU không tồn tại (`404`) | [inbound-stock_missing-catalog-sku_404.png](screenshots/HG-S2/inbound-stock_missing-catalog-sku_404.png) |
| Validate số lượng nhập kho `< 1` (`422`) | [inbound-stock_invalid-quantity_422.png](screenshots/HG-S2/inbound-stock_invalid-quantity_422.png) |
| Danh sách Seller SKUs (`200`) | [list-seller-skus_success_200.png](screenshots/HG-S2/list-seller-skus_success_200.png) |
| Chi tiết Seller SKU kèm tồn kho (`200`) | [get-seller-sku-detail_success_200.png](screenshots/HG-S2/get-seller-sku-detail_success_200.png) |
| Browser: Seller tạo sản phẩm không có `skuCode` | [browser_create-product-no-skucode.png](screenshots/HG-S2/browser_create-product-no-skucode.png) |
| Browser: Seller sửa sản phẩm bảo toàn `skuId` | [browser_edit-product-preserve-sku-id.png](screenshots/HG-S2/browser_edit-product-preserve-sku-id.png) |
| Browser: Nhập kho lần 1 tự cấp mã `FSKU` | [browser_inbound-first-assign-fsku.png](screenshots/HG-S2/browser_inbound-first-assign-fsku.png) |
| Browser: Nhập kho lần 2 & xem chi tiết | [browser_inbound-repeat-reuse-and-detail.png](screenshots/HG-S2/browser_inbound-repeat-reuse-and-detail.png) |

> Bằng chứng Swagger phần Checkout / Payment Attempt trên `commerce-service` xem tại [docs/test-evidence/payment-attempt-pricing/README.md](test-evidence/payment-attempt-pricing/README.md).
