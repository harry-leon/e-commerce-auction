# Hướng Dẫn Khởi Chạy Và Kiểm Thử API Dự Án Nexus (Sprint 2)

Tài liệu này cung cấp hướng dẫn đầy đủ về cách khởi động hạ tầng, chạy các microservices backend, frontend web, cũng như hướng dẫn kiểm thử Swagger UI và Postman theo từng kịch bản (Success, Validation Error, Business Error, Timeout/Fallback).

---

## 1. Yêu cầu môi trường & Chuẩn bị
- **Java:** OpenJDK 21 (LTS)
- **Node.js & npm:** Node.js v20+ / npm v10+
- **Docker & Docker Compose:** Docker Desktop 24+
- **Maven:** Apache Maven 3.9+ (hoặc dùng `mvn` có sẵn trong hệ thống)

---

## 2. Khởi động hạ tầng cơ sở dữ liệu & dịch vụ dùng chung (Docker Compose)

Khởi chạy PostgreSQL, Redis, RabbitMQ, Keycloak và Supabase/Local Database:

```bash
docker compose up -d postgres-user postgres-catalog postgres-commerce redis rabbitmq keycloak
```

Kiểm tra trạng thái container:
```bash
docker compose ps
```

- **Keycloak Realm UI:** `http://localhost:8080/auth/` (hoặc cổng cấu hình Keycloak)
- **PostgreSQL Databases:** Cổng 5432 (user), 5433 (catalog), 5434 (commerce), v.v.

---

## 3. Khởi động các Microservices Backend

Chạy các microservice theo thứ tự phụ thuộc:

### 3.1. Discovery Service (Eureka)
```bash
cd backend/discovery-service
mvn spring-boot:run
```
- Dashboard Eureka: `http://localhost:8761`

### 3.2. API Gateway
```bash
cd backend/api-gateway
mvn spring-boot:run
```
- Gateway URL: `http://localhost:8080`
- Swagger UI tập trung: `http://localhost:8080/swagger-ui.html`

### 3.3. Catalog Service
```bash
cd backend/catalog-service
mvn spring-boot:run
```
- Port: `8082`
- Swagger UI: `http://localhost:8082/swagger-ui/index.html`

### 3.4. Commerce Service
```bash
cd backend/commerce-service
mvn spring-boot:run
```
- Port: `8083`
- Swagger UI: `http://localhost:8083/swagger-ui/index.html`

---

## 4. Khởi động Giao diện Frontend Web

```bash
cd frontend-web
npm install
npm run dev
```
- Ứng dụng Web: `http://localhost:3000`

---

## 5. Hướng Dẫn Kiểm Thử Swagger UI Theo Từng API & Kịch Bản

### 5.1. Auth & Mã Hóa RSA Password API (API Gateway)
*Endpoint: `http://localhost:8080/swagger-ui.html` hoặc `http://localhost:8080/v3/api-docs`*

#### Kịch bản 1: Lấy RSA Public Key
- **API:** `GET /api/v1/auth/public-key`
- **Mục đích:** Frontend lấy public key để mã hóa mật khẩu trước khi gửi qua network.
- **Kết quả kỳ vọng (200 OK):**
```json
{
  "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

#### Kịch bản 2: Đăng nhập với mật khẩu mã hóa RSA (Thành công)
- **API:** `POST /api/v1/auth/login`
- **Request Body:**
```json
{
  "username": "buyer1",
  "password": "<RSA_OAEP_BASE64_ENCRYPTED_PASSWORD>"
}
```
- **Kết quả kỳ vọng (200 OK):** Gateway tự động giải mã RSA trước khi gọi Identity Provider (Keycloak) và trả về Access Token + Refresh Token:
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

#### Kịch bản 3: Đăng nhập sai thông tin (Lỗi xác thực 401)
- **Request Body:** Username hoặc Password không đúng sau khi giải mã.
- **Kết quả kỳ vọng (401 Unauthorized):**
```json
{
  "timestamp": "2026-11-12T16:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Bad credentials"
}
```

#### Kịch bản 4: Validation Error (Lỗi định dạng đầu vào 400)
- **Request Body:** Gửi payload rỗng hoặc thiếu username:
```json
{
  "username": "",
  "password": ""
}
```
- **Kết quả kỳ vọng (400 Bad Request):** Báo lỗi validation bắt buộc nhập thông tin.

---

### 5.2. Commerce Service - Cart & Direct Buy Pricing API
*Endpoint Swagger Commerce:* `http://localhost:8083/swagger-ui/index.html` (hoặc thông qua Gateway `http://localhost:8080/swagger-ui.html`)

#### Kịch bản 1: Thêm sản phẩm vào giỏ hàng với SKU hợp lệ (200 OK)
- **API:** `POST /api/v1/cart/items`
- **Headers:** `Authorization: Bearer <TOKEN>`
- **Request Body:**
```json
{
  "skuId": "sku-macbook-pro-16-01",
  "quantity": 1
}
```
- **Kết quả kỳ vọng (200 OK):** Commerce Service gọi Catalog Client lấy thông tin SkuInfo và giá bán động, lưu vào giỏ hàng:
```json
{
  "cartId": "cart-101",
  "items": [
    {
      "skuId": "sku-macbook-pro-16-01",
      "quantity": 1,
      "unitPrice": 45000000,
      "subtotal": 45000000
    }
  ],
  "totalAmount": 45000000
}
```

#### Kịch bản 2: Lỗi Validation số lượng không hợp lệ (422 / 400 Validation Error)
- **Request Body:**
```json
{
  "skuId": "sku-macbook-pro-16-01",
  "quantity": -5
}
```
- **Kết quả kỳ vọng (400 Bad Request / 422 Unprocessable Entity):** Báo lỗi số lượng phải lớn hơn 0.

#### Kịch bản 3: Sku không tồn tại (404 Not Found)
- **Request Body:**
```json
{
  "skuId": "sku-non-existent-99999",
  "quantity": 1
}
```
- **Kết quả kỳ vọng (404 Not Found):** `Sku not found in Catalog service`.

#### Kịch bản 4: Xử lý Timeout / Catalog Down (Circuit Breaker & Fallback 504 / 503)
- **Tình huống:** Catalog Service bị dừng hoặc timeout quá 30s.
- **Kết quả:** `HttpCatalogClient` ghi log cảnh báo rõ ràng, không làm crash Commerce Service và trả thông báo lỗi hệ thống phù hợp.

---

### 5.3. Frontend Web Resilience & Circuit Breaker (Clean Console)
- **Truy cập:** `http://localhost:3000/home`
- **Tình huống Backend Offline:**
  1. Dừng backend hoặc ngắt mạng.
  2. Vite Proxy tự động bọc lỗi thành payload `BACKEND_OFFLINE` (HTTP 200).
  3. `auctions.ts` kích hoạt Circuit Breaker trong 30 giây, chuyển sang Fallback Mock Data mượt mà.
  4. `BuyerPages.tsx` và `notificationStream.ts` tự động chuyển sang backoff thông minh (15s–60s) và lắng nghe sự kiện `online`.
  5. **Kiểm tra DevTools Console:** **Clean 0 lỗi đỏ (0 unhandled exceptions)**.

---

## 6. Bộ Postman Collection
Bộ test collection được lưu trữ tại:
`docs/postman/nexus_auction_session_management.postman_collection.json`
Chứa các request mẫu đầy đủ cho Auction Session, Dynamic Pricing, Payment và Order.
