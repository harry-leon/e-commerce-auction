# Hướng Dẫn Khởi Chạy & Kiểm Thử Reputation Service + Auction Eligibility (Sprint 3)

Tài liệu này hướng dẫn cách chạy cục bộ `reputation-service`, `auction-service` và `api-gateway` cho các task **HG-S3-1**, **HG-S3-2**, **HG-S3-3**, kèm kết quả kiểm thử tự động và kiểm thử trực tiếp trên Swagger UI với PostgreSQL 16 (Flyway v3).

---

## 1. Tổng quan kiến trúc & tính năng đã hoàn thiện

| Issue | Phạm vi | Thành phần thay đổi |
|---|---|---|
| **HG-S3-1** | Đánh giá hai chiều Buyer ↔ Seller sau giao dịch `DELIVERED`/`COMPLETED`, xác thực đơn hàng qua `commerce-service`, chống tự đánh giá, giới hạn tối đa 1 lần mỗi bên/đơn | `reputation-service` (`Rating`, `RatingController`, `HttpOrderVerificationClient`), `api-gateway` (`/api/v1/ratings/**`) |
| **HG-S3-2** | Cộng/trừ điểm uy tín theo sao (`5→+3`, `4→+1`, `3→0`, `2→-3`, `1→-5`), nâng/hạ `TrustLevel`, sự kiện nội bộ (`UserRatedEvent`, `ReputationScoreUpdatedEvent`, `TrustLevelChangedEvent`), kiểm tra điều kiện đấu giá (`BID >= 40`, `CREATE_AUCTION >= 50`) | `reputation-service` (`ReputationServiceImpl`, `ReputationController`, `ReputationInternalController`), `auction-service` (`HttpReputationEligibilityClient`, `AuctionServiceImpl`, `BiddingServiceImpl`) |
| **HG-S3-3** | Gắn cờ khiếu nại đánh giá (`rateeId` hoặc `ADMIN`/`SUPPORT`), tra cứu kiểm duyệt `Admin/Support`, điều chỉnh điểm thủ công có lý do kiểm toán, phán quyết khiếu nại (`DISMISS`/`INVALIDATE`) idempotent | `reputation-service` (`RatingController`, `ReputationController`, `ReputationServiceImpl`, `SecurityConfig`) |

---

## 2. Biến môi trường cấu hình chính

### `reputation-service` (port mặc định `8087`)
- `DB_URL` (mặc định `jdbc:postgresql://localhost:5432/nexus_reputation`)
- `DB_USERNAME` / `DB_PASSWORD` (mặc định `nexus` / `nexus`)
- `KEYCLOAK_URL` (mặc định `http://localhost:8180`, trỏ tới endpoint JWKS)
- `KEYCLOAK_ISSUER` (mặc định `http://localhost:8180/realms/nexus-realm`)
- `COMMERCE_SERVICE_URL` (mặc định `http://localhost:8083`, dùng để xác thực đơn hàng `GET /api/v1/orders/{orderId}`)
- `ORDER_VERIFICATION_ENABLED` (mặc định `true`)
- `MIN_REPUTATION_TO_BID` (mặc định `40`)
- `MIN_REPUTATION_TO_SELL` (mặc định `50`)
- `FEATURE_REPUTATION_ENFORCEMENT` (mặc định `true`)

### `auction-service`
- `REPUTATION_SERVICE_URL` (mặc định `http://localhost:8087`)
- `FEATURE_REPUTATION_ENFORCEMENT` (mặc định `true`)

---

## 3. Cách khởi chạy cục bộ để kiểm thử Swagger UI

### Bước 1: Khởi động PostgreSQL & Keycloak (hoặc hạ tầng Docker Compose)
Đảm bảo PostgreSQL đã sẵn sàng với database `nexus_reputation`. Khi `reputation-service` khởi động, Flyway tự động chạy migration `V1` → `V2` → `V3` tạo đủ 3 bảng: `reputations`, `penalties`, `ratings`.

### Bước 2: Chạy `reputation-service`
```bash
mvn -B --no-transfer-progress -f backend/reputation-service/pom.xml spring-boot:run
```
Hoặc chạy từ file JAR đã đóng gói:
```bash
java -jar backend/reputation-service/target/reputation-service-0.0.1-SNAPSHOT.jar
```

### Bước 3: Mở Swagger UI
- **Swagger UI:** `http://localhost:8087/swagger-ui.html` (chuyển hướng tới `/swagger-ui/index.html`)
- **OpenAPI Spec:** `http://localhost:8087/v3/api-docs`
- Nhấn nút **Authorize**, dán JWT Bearer token (Keycloak access token hoặc token có `realm_access.roles`: `USER`, `SUPPORT`, `ADMIN`) vào mục `bearer-jwt` để thử trực tiếp các endpoint cần đăng nhập.

---

## 4. Bằng chứng kiểm thử tự động & Swagger UI trực tiếp (Playwright + PostgreSQL 16)

### 4.1. Kiểm tra Maven / Checkstyle / JaCoCo theo `.ci` & `.gitlab`
- `mvn -B --no-transfer-progress -f backend/common/pom.xml install -DskipTests`: build & cài module `nexus-common`.
- `mvn -B --no-transfer-progress -f backend/reputation-service/pom.xml checkstyle:check verify`: Checkstyle 0 lỗi, toàn bộ unit/MockMvc/JPA test pass, JaCoCo `LINE >= 70%`.
- `mvn -B --no-transfer-progress -f backend/auction-service/pom.xml checkstyle:check verify`: Checkstyle 0 lỗi, toàn bộ unit/client/MockMvc test pass, JaCoCo `LINE >= 70%`.
- `mvn -B --no-transfer-progress -f backend/api-gateway/pom.xml test`: toàn bộ test Gateway routing & security pass.

### 4.2. Kết quả chạy trực tiếp qua Playwright Chromium trên Swagger UI (`http://127.0.0.1:8087/swagger-ui/index.html`) + PostgreSQL 16 (`39/39` kịch bản PASS)

| Nhóm API | Kịch bản kiểm thử | HTTP kỳ vọng | Kết quả thực tế |
|---|---|---|---|
| **Swagger UI Interactive** | Nhập Bearer JWT qua modal `Authorize`, gọi `POST /api/v1/ratings` (Buyer → Seller 5 sao) | `201` | `201 Created` (`score: 100 → 103`) |
| **Swagger UI Interactive** | Gọi `GET /api/v1/reputations/{userId}` cho Seller | `200` | `200 OK` (`score=103`, `avgRating=5.0`) |
| **Ratings (`HG-S3-1`)** | Gọi `GET /reputations/me` hoặc `POST /ratings` không có JWT | `401` | `401 Unauthorized` |
| **Ratings Validation** | Gửi `POST /ratings` với `score = 6` ngoài khoảng `1..5` | `422` | `422` (`VALIDATION_ERROR`) |
| **Ratings Business Rules** | Tự đánh giá chính mình (`rateeId == raterId`) | `400` | `400` (`INVALID_PARAM_VALUE`) |
| **Ratings Business Rules** | Đơn hàng chưa giao (`status = PENDING`) | `409` | `409` (`REVIEW_ORDER_NOT_DELIVERED`) |
| **Ratings Business Rules** | Đơn hàng không tồn tại tại `commerce-service` | `404` | `404` (`ORDER_NOT_FOUND`) |
| **Ratings RBAC** | Tài khoản ngoài giao dịch (`outsider`) gửi đánh giá | `403` | `403` (`REVIEW_NOT_OWNER`) |
| **Ratings Idempotency** | Cùng 1 bên gửi đánh giá lần 2 cho cùng `orderId` | `409` | `409` (`REVIEW_ALREADY_EXISTS`) |
| **Ratings 2 chiều** | Seller đánh giá ngược Buyer 2 sao (`100 - 3 = 97`) | `201` | `201 Created`; `GET /ratings/order/{orderId}` trả 2 bản ghi |
| **Eligibility & Penalty (`HG-S3-2`)** | User JWT gọi `/internal/reputations/penalties` | `403` | `403 Forbidden` |
| **Eligibility & Penalty (`HG-S3-2`)** | `nexus-commerce-service` trừ 55đ (`100 → 45`) → `BID` pass (`45 >= 40`), `CREATE_AUCTION` bị từ chối (`45 < 50`) | `200` | `eligible=true` (`BID`), `eligible=false` (`CREATE_AUCTION`) |
| **Eligibility & Penalty (`HG-S3-2`)** | Trừ thêm 10đ quá hạn thanh toán đấu giá (`45 → 35`, `TrustLevel = LOW`); replay cùng `refId` | `200` | `newScore=35`, `trustLevel=LOW`; replay `idempotentReplay=true` |
| **Eligibility Internal** | `nexus-auction-service` kiểm tra `GET /internal/reputations/{userId}/eligibility?action=BID` | `200` | `eligible=false` (`score=35 < 40`) |
| **Transaction Summary** | `nexus-commerce-service` gọi `POST /internal/reputations/transactions` (`SUCCESS`, `auction=true`) | `200` | `totalTx=1`, `auctionWon=1` |
| **Dispute & RBAC (`HG-S3-3`)** | Người ngoài gắn cờ khiếu nại đánh giá vs chính chủ `rateeId` gắn cờ | `403` / `200` | `outsider → 403`; `ratee → 200` (`isDisputed=true`), bị ẩn khỏi `GET /ratings/user/{id}` |
| **Admin/Support Lookup** | Buyer gọi `/admin/users` vs `SUPPORT` lọc `trustLevel=LOW` & `disputedOnly=true` | `403` / `200` | `buyer → 403`; `SUPPORT → 200` |
| **Admin Manual Adjustment** | `SUPPORT` gọi `POST /admin/adjustments`; `ADMIN` gửi `scoreDelta=500`; `ADMIN` gửi `scoreDelta=+20` hợp lệ | `403` / `422` / `200` | `SUPPORT → 403`; `500 → 422`; `ADMIN → 200` (`score: 35 → 55`), lưu audit `[ADMIN ...]` |
| **Admin Dispute Resolve** | `SUPPORT` gọi `resolve` vs `ADMIN` gọi `INVALIDATE` & gọi lại kiểm tra idempotent | `403` / `200` | `SUPPORT → 403`; `ADMIN → 200`, hoàn tác điểm Seller `103 → 100`, replay giữ nguyên `100` |
