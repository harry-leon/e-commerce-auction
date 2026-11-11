# Tài liệu Triển khai & Kiểm thử — HG-S2-5: Dynamic Pricing Engine & Deferred Order Payment Snapshots

- **Mã Issue / Nhánh:** `HG-S2-5` / `feature/HG-S2-5-dynamic-pricing-checkout-flow`
- **Thành phần ảnh hưởng:** `backend/catalog-service`, `backend/commerce-service`, `backend/api-gateway`, `frontend-web`, `docs/`, `specs/HG-S2-5-dynamic-pricing-checkout/`
- **Migration Supabase:** Catalog `V6__dynamic_pricing_and_sku_original_price.sql`, Commerce `V10__payment_attempt_price_snapshots.sql`

---

## 1. Tổng quan Kiến trúc & Quy tắc Nghiệp vụ

1. **Giá nhập tối thiểu tại SKU (`skus.original_price` = `minPrice`):**
   - Bỏ cột `skus.price` thông qua Flyway Catalog `V6`; chỉ giữ `skus.original_price` (`NOT NULL`, `>= 0`) làm mức giá gốc / giá tối thiểu của từng SKU biến thể.
   - `SkuRequest` bắt buộc nhập `originalPrice` khi tạo/cập nhật sản phẩm.
2. **Công thức Tính giá Động tại Backend (`DynamicPricingService`):**
   - Bảng `pricing_rules` (`PricingRule`) quản lý tỷ suất lợi nhuận (`marginRate`), hệ số theo mùa (`seasonFactor`) và điều chỉnh thị trường (`marketAdjustmentRate`) theo cấp danh mục hoặc fallback toàn hệ thống (`category_id IS NULL`, rule mặc định `+15%`).
   - Công thức:
     $$\text{sellingPrice} = \text{originalPrice} \times \bigl(1 + \max(0, \text{marginRate} + \text{seasonFactor} + \text{marketAdjustmentRate})\bigr)$$
   - Thứ tự áp dụng rule: danh mục hiện tại → danh mục cha → danh mục ông → toàn cục (`priority DESC`), luôn đảm bảo $\text{sellingPrice} \ge \text{originalPrice}$.
   - `ProductResponse` trả về khoảng giá tổng hợp `minPrice` và `maxPrice` từ các SKU đang `ACTIVE`.
3. **Tạm tính trên Frontend vs. Chốt giá Chính thức trên Backend:**
   - **Frontend (`frontend-web`):** tự tính giá tạm tính trên giỏ hàng/checkout (`estimated*`) và hiển thị thông báo mức giá sẽ được BE xác thực lại khi thanh toán; không gửi tổng tiền để BE tin tưởng.
   - **Tạo đơn (`POST /api/v1/orders`):** lưu `estimatedSubtotal`, `estimatedShippingFee`, `estimatedDiscount`, `estimatedTotal` và `OrderItem.estimatedUnitPrice` với `priceFinalized = false`. Toàn bộ cột giá chính thức (`subtotal`, `shippingFee`, `discount`, `total`, `OrderItem.unitPrice`) giữ `NULL`.
   - **Tạo lượt thanh toán (`POST /api/v1/payments`):** BE truy vấn giá bán động mới nhất từ `catalog-service`, đóng băng `amount` và JSON `priceSnapshot` (`PaymentPriceSnapshot`) trên Payment Attempt, giữ kho theo `inventoryRefId = payment.id`, đồng thời vẫn giữ giá chính thức trên Order là `NULL`.
   - **IPN Thành công (`GET /api/v1/payments/vnpay/ipn`, `RspCode = "00"`):** BE xác thực chữ ký HMAC SHA-512 và tính idempotent, ghi nhận `OrderItem.unitPrice` + các tổng chính thức từ `priceSnapshot` (`priceFinalized = true`), sau đó `PaymentInventoryWorker` commit kho và chuyển Order sang `PAID`.
   - **IPN Thất bại:** giữ nguyên giá chính thức trên Order là `NULL` (`priceFinalized = false`), nhả reservation của attempt lỗi, giữ Order ở `PENDING_PAYMENT` để người mua nhấn **Retry VNPAY Payment** với `Idempotency-Key` UUID mới.

---

## 2. Hướng dẫn Chạy Local & Kiểm thử với Supabase

### 2.1. Chuẩn bị biến môi trường

Cấu hình file `.env` (hoặc biến môi trường shell) trỏ về PostgreSQL/Supabase với quyền chạy Flyway cho từng schema:

```powershell
$env:DB_USERNAME="<DB_USERNAME>"
$env:DB_PASSWORD="<DB_PASSWORD>"
# URL cho catalog-service cần currentSchema=nexus_catalog,public
# URL cho commerce-service cần currentSchema=nexus_commerce,public
$env:VNPAY_TMN_CODE="DEMO_TMN"
$env:VNPAY_HASH_SECRET="<VNPAY_HASH_SECRET>"
$env:VNPAY_RETURN_URL="http://localhost:5173/payment-result"
```

### 2.2. Build & Kiểm thử CI cục bộ

```bash
# Backend Checkstyle, Unit Tests, Coverage (JaCoCo) & Packaging
mvn -B --no-transfer-progress -f backend/catalog-service/pom.xml checkstyle:check verify
mvn -B --no-transfer-progress -f backend/commerce-service/pom.xml clean checkstyle:check verify
mvn -B --no-transfer-progress -f backend/api-gateway/pom.xml verify

# Frontend Lint, Typecheck, Prettier Format Check & Coverage
cd frontend-web
npm run lint
npm run typecheck
npm run format:check
npm run coverage

# Kiểm tra đồng bộ ERD từ Flyway migrations
cd ..
node --test scripts/generate-erd.test.mjs
node scripts/generate-erd.mjs
```

### 2.3. Chạy dịch vụ & truy cập Swagger / UI

1. Chạy `catalog-service` (port `8082`), `commerce-service` (port `8083`) và `api-gateway` (port `8080`) với biến `KEYCLOAK_URL`, `KEYCLOAK_ISSUER`, `CATALOG_SERVICE_URL=http://localhost:8082`, `COMMERCE_SERVICE_URL=http://localhost:8083`, `PAYMENT_INVENTORY_TOKEN_URL`, `PAYMENT_INVENTORY_CLIENT_ID`, `PAYMENT_INVENTORY_CLIENT_SECRET`.
2. Chạy frontend:
   ```bash
   cd frontend-web
   PORT=5173 API_PROXY_TARGET=http://localhost:8080 npm run dev
   ```
3. Địa chỉ kiểm tra:
   - Swagger Catalog Service: `http://localhost:8082/swagger-ui.html`
   - Swagger Commerce Service: `http://localhost:8083/swagger-ui.html`
   - Ứng dụng Web: `http://localhost:5173`

---

## 3. Bằng chứng Kiểm thử Thực tế (Swagger UI & Trình duyệt)

Toàn bộ ảnh chụp được lưu tại `docs/screenshots/HG-S2-5/`, chụp trực tiếp từ Swagger UI của `catalog-service`/`commerce-service` và trình duyệt Chromium qua `frontend-web` (đồng bộ DB Supabase).

### 3.1. Swagger UI — Catalog Service (`http://localhost:8082/swagger-ui.html`)

| Kịch bản | Kết quả | File ảnh |
|---|---|---|
| Xem trước giá bán động từ `originalPrice=1000000` (rule mặc định `+15%` → `1150000`) | `200 OK` | `preview-dynamic-price_success_200.png` |
| Role `SELLER` gọi `POST /api/v1/pricing-rules` tạo rule mới | `403 AUTH_FORBIDDEN` | `create-pricing-rule_unauthorized_403.png` |
| Role `ADMIN` gửi payload sai validation (`name` rỗng, `marginRate: -0.5`) | `422 VALIDATION_ERROR` | `create-pricing-rule_invalid-payload_422.png` |
| Role `ADMIN` tạo rule `HG-S2-5 Electronics Peak (+25%)` cho danh mục Electronics | `201 Created` | `create-pricing-rule_success_201.png` |
| Role `SELLER` tạo sản phẩm nhưng thiếu `skus[0].originalPrice` | `422 VALIDATION_ERROR` | `create-product_missing-original-price_422.png` |
| Role `SELLER` tạo sản phẩm đa SKU dùng `originalPrice` (`minPrice=1250000`, `maxPrice=2500000`) | `201 Created` | `create-product_success_201.png` |

### 3.2. Swagger UI — Commerce & VNPAY (`http://localhost:8083/swagger-ui.html`)

| Kịch bản | Kết quả | File ảnh |
|---|---|---|
| `POST /api/v1/orders` tạo đơn với `Idempotency-Key` UUID → `priceFinalized=false`, `subtotal/total=null`, `estimatedTotal=1250000` | `201 Created` | `create-order_deferred-price_201.png` |
| `POST /api/v1/payments` lấy giá mới từ Catalog, đóng băng `amount=1250000` và `priceSnapshot` | `200 OK` | `start-payment_snapshot-frozen_200.png` |
| `GET /api/v1/payments/vnpay/ipn` với chữ ký HMAC SHA-512 hợp lệ → `{"RspCode": "00", "Message": "Confirm Success"}` | `200 OK` | `vnpay-ipn_success-finalize_200.png` |
| `GET /api/v1/orders/{orderId}` sau IPN & commit kho → `status="PAID"`, `priceFinalized=true`, `unitPrice=1250000` | `200 OK` | `get-order-detail_finalized-after-ipn_200.png` |

### 3.3. Kiểm thử Trình duyệt Thực tế (`http://localhost:5173`)

| Luồng màn hình | Mô tả xác minh | File ảnh |
|---|---|---|
| Admin (`/admin-system-config`) | Bảng `Dynamic Pricing Rules` + `Test Dynamic Price Simulator` tính thử ra `1,150,000.00` | `ui-admin_pricing-rules_console.png` |
| Seller (`/seller-create-product`) | Nhập `Min / Cost Price (VND) = 1000000`, hiển thị live `Estimated Dynamic Selling Price: 1,250,000.00 (+25.0%)` | `ui-seller_create-product_dynamic-preview.png` |
| Buyer (`/product-detail`) | Hiển thị khoảng giá `1,250,000.00 – 2,500,000.00` và đổi giá tức thời khi chọn từng chip SKU | `ui-buyer_product-detail_range-and-sku.png` |
| Buyer (`/checkout`) | Hiển thị giá tạm tính trên giỏ hàng kèm ghi chú giá chính thức được BE kiểm tra lại khi thanh toán | `ui-buyer_checkout_provisional-estimate.png` |
| Buyer (`/payment-result`) | Nhận mã lỗi VNPAY `24`, hiển thị `Payment failed` và nút `Retry VNPAY Payment` | `ui-buyer_vnpay-retry_after-failure.png` |
| Buyer (`/order-detail`) | Đơn đã thanh toán qua IPN hiển thị trạng thái `Paid`, mốc `Payment Confirmed` và tổng `1,250,000.00` | `ui-buyer_order-detail_finalized.png` |

---

## 4. Tổng kết Kiểm tra Tự động

- **`backend/catalog-service`:** `mvn -B --no-transfer-progress -f backend/catalog-service/pom.xml checkstyle:check verify` — `0` Checkstyle violations, `134` tests run (`0` failures, `0` errors, `1` skipped Docker Postgres test), JaCoCo coverage check passed.
- **`backend/commerce-service`:** `mvn -B --no-transfer-progress -f backend/commerce-service/pom.xml clean checkstyle:check verify` — `0` Checkstyle violations, `358` tests run (`0` failures, `0` errors, `31` skipped Docker Postgres tests), JaCoCo coverage check passed.
- **`backend/api-gateway`:** `mvn -B --no-transfer-progress -f backend/api-gateway/pom.xml verify` — `21` tests run (`0` failures, `0` errors, `0` skipped).
- **`frontend-web`:** `npx prettier --check` (all 17 modified files passed), `npm run typecheck` (passed), `npm run lint` (passed), `npm run coverage` (`20` test files, `284` tests passed, `0` failed, line coverage `72.11%`).
- **ERD Alignment:** `node --test scripts/generate-erd.test.mjs` (`3` passed, `0` failed) & `node scripts/generate-erd.mjs` (đồng bộ `docs/database/ERD.md` theo V6 Catalog & V10 Commerce).
