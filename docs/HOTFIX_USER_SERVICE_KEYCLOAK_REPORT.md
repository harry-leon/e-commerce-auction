# BÁO CÁO TỔNG HỢP HOTFIX: TÁCH DATABASE USER SERVICE, TÍCH HỢP KEYCLOAK & MÃ HÓA MẬT KHẨU CLIENT

**Mã task / Issue:** `HG-S1-HF-1`  
**Nhánh thực hiện:** `hotfix/HG-S1-HF-1-split-user-db-keycloak`  
**Nhánh đích:** `dev`  
**Ngày hoàn tất:** 23/10/2026  

---

## 1. Bối cảnh & Mục tiêu (Problem & Objectives)

1. **Tuân thủ nguyên tắc Microservices Database-per-Service**:
   - `user-service` ban đầu bị ôm đồm quá nhiều trách nhiệm: vừa lưu thông tin hồ sơ (Profile), vừa quản lý thông tin xác thực/mật khẩu/reset token (IAM), vừa lưu dữ liệu đánh giá và uy tín (Reputations/Ratings).
   - Cần tách biệt hoàn toàn:
     - **IAM / Xác thực & Phân quyền**: Chuyển giao hoàn toàn cho **Keycloak (Auth Server)**.
     - **Đánh giá / Uy tín**: Chuyển giao cho **`reputation-service`** độc lập.
     - **Hồ sơ cá nhân**: Giữ tại **`user-service`**, liên kết với Keycloak thông qua trường định danh `keycloak_id` (claim `sub` trong JWT).

2. **Kiến trúc Single Point of Entry (BFF via API Gateway)**:
   - Ẩn toàn bộ các microservices và Keycloak sau **API Gateway** (`http://localhost:8080`). Client tuyệt đối không gọi trực tiếp các service con.
   - API Gateway đóng vai trò Backend-For-Frontend (BFF) điều phối luồng Đăng ký và Đăng nhập tập trung.

3. **Bảo mật mật khẩu End-to-End (Zero-Trust)**:
   - Mật khẩu thô (plain-text) không được phép rời khỏi trình duyệt của người dùng. Mật khẩu phải được băm một chiều (SHA-256) ngay tại Client trước khi gửi request xuống API Gateway và Keycloak.

4. **Quản trị thời hạn Token độc lập**:
   - Thời hạn của Access Token và Refresh Token không bị hardcode trong source code dự án, có thể cấu hình linh hoạt tại runtime qua Keycloak Admin Console.

---

## 2. Chi tiết các thành phần đã triển khai (Changes by Component)

### A. Auth Server (Keycloak)
- **Tập tin cấu hình Realm**: `deploy/keycloak/nexus-realm.json`
- **Realm**: `nexus-realm`
- **Clients**:
  - `nexus-web`: Client public dành cho ứng dụng Frontend Web / Mobile.
  - `nexus-admin-cli`: Client bí mật (`client_credentials`) dành cho API Gateway gọi Keycloak Admin REST API.
- **Roles tập trung**: `ROLE_BUYER` (mặc định), `ROLE_SELLER`, `ROLE_ADMIN`, `ROLE_SUPPORT`.
- **Cấu hình thời hạn Token**:
  - `accessTokenLifespan`: 900 giây (15 phút).
  - `ssoSessionIdleTimeout`: 1800 giây (30 phút).
  - `ssoSessionMaxLifespan`: 36000 giây (10 giờ).
  - `offlineSessionIdleTimeout`: 2592000 giây (30 ngày).

### B. API Gateway (`backend/api-gateway`)
- **`AuthGatewayController.java`**:
  - `POST /api/v1/auth/login`: Nhận email và mật khẩu (chuỗi SHA-256 hex từ client), gọi Keycloak Direct Access Grant để lấy cặp Token (Access + Refresh).
  - `POST /api/v1/auth/refresh-token`: Cấp mới token trực tiếp từ Keycloak.
  - `POST /api/v1/auth/register` (Luồng điều phối Saga 2 bước):
    1. Gọi Keycloak Admin API tạo tài khoản và credentials an toàn, trích xuất `keycloak_id` từ header `Location`.
    2. Chuyển tiếp hồ sơ sang `user-service` (`POST /api/v1/users`) với header `X-User-Id: <keycloak_id>` và `X-User-Roles: ROLE_BUYER`. **Tuyệt đối không gửi mật khẩu**.
    3. Thực hiện bù trừ (Saga Rollback) xóa tài khoản Keycloak nếu bước lưu hồ sơ thất bại.
- **`JwtHeaderForwardingFilter.java`**:
  - Giải mã chữ ký số RS256 của JWT bằng JWKS cached từ Keycloak.
  - Trích xuất định danh và gắn vào HTTP Headers chuyển tiếp tới microservices con:
    - `X-User-Id: <keycloak sub>`
    - `X-User-Roles: ROLE_BUYER,...`
    - `X-User-Privileges: PROFILE.VIEW,...`
- **`application.yml`**: Định tuyến route sạch sẽ, ẩn toàn bộ service phía sau.

### C. User Service (`backend/user-service`)
- **Cơ sở dữ liệu (Flyway `V4__split_iam_and_reputation.sql`)**:
  - Bổ sung trường `keycloak_id VARCHAR(255) UNIQUE`.
  - Chuyển `password_hash` sang `NULL`, xóa `token_version`.
  - `DROP` các bảng IAM cũ: `roles`, `privileges`, `user_roles`, `role_privileges`, `password_reset_tokens`.
  - `DROP` các bảng Uy tín & Đánh giá: `reputations`, `ratings`, `penalties`.
- **Loại bỏ Keycloak client**:
  - Xóa bỏ hoàn toàn package `com.nexus.user.client` và class `KeycloakAdminClient.java`.
  - `user-service` hoàn toàn độc lập, không kết nối trực tiếp với Keycloak.
- **`HeaderAuthenticationFilter.java`**:
  - Đọc các header `X-User-Id`, `X-User-Roles`, `X-User-Privileges` nạp vào `SecurityContext` của Spring Security.
- **`UserController.java` & `UserService.java`**:
  - Thêm endpoint `POST /api/v1/users` tạo hồ sơ người dùng liên kết với `keycloak_id`.
- **Cập nhật DTOs**:
  - `RegisterRequest`, `ChangePasswordRequest`, `ResetPasswordRequest`: nới `@Size(min = 6, max = 100)` để tương thích chuỗi băm SHA-256 (64 ký tự hex).

### D. Reputation Service (`backend/reputation-service`)
- Tạo mới microservice độc lập quản lý Đánh giá (Reviews/Ratings) và Điểm uy tín (Reputations).
- Flyway migrations `V1__init_reputation_schema.sql` và `V2__seed_reputation_data.sql`.
- Chạy cổng độc lập `8087` (nội bộ).

### E. Frontend Web (`frontend-web`)
- **`frontend-web/src/utils/crypto.ts`**:
  - Tiện ích băm mật khẩu bằng Web Crypto API chuẩn (`SHA-256`).
- **`frontend-web/src/features/auth/AuthPages.tsx`**:
  - Tích hợp băm SHA-256 tại các luồng: Đăng nhập (`LoginPage`), Đăng ký (`RegisterPage`), Quên mật khẩu (`ForgotPasswordPage`).
  - Mật khẩu gốc vẫn được validate độ mạnh tại form để hướng dẫn người dùng, nhưng chỉ có chuỗi băm hex được gửi qua mạng.
- **`frontend-web/src/api/client.ts`**:
  - Tự động refresh token khi gặp `401 Unauthorized` theo đồng hồ của Keycloak, hoàn toàn không hardcode thời gian.

---

## 3. Kết quả chạy Pipeline CI/CD cục bộ (Verification Results)

Tất cả các job kiểm tra theo đúng tiêu chuẩn `.gitlab-ci.yml` và `.ci/jobs.yml` đều đạt 100%:

| Job / Thành phần | Công cụ kiểm tra | Kết quả | Ghi chú |
| :--- | :--- | :---: | :--- |
| **`mr-title-check`** | Conventional Commits Regex | ✅ **PASS** | `fix(auth): split user db, integrate keycloak and client password hashing` |
| **`user-service:lint`** | Maven Checkstyle (`checkstyle:check`) | ✅ **PASS** | 0 vi phạm (0 Checkstyle violations) |
| **`user-service:test`** | Maven Surefire / JaCoCo (`mvn verify`) | ✅ **PASS** | **70/70 tests passed**, coverage đạt chuẩn |
| **`api-gateway:test`** | Maven Compiler & Test | ✅ **PASS** | BUILD SUCCESS |
| **`reputation-service:test`** | Maven Compiler & Test | ✅ **PASS** | BUILD SUCCESS |
| **`frontend-web:lint`** | ESLint (`eslint src`) | ✅ **PASS** | 0 lỗi linter |
| **`frontend-web:test`** | Vitest (`vitest run`) | ✅ **PASS** | **146/146 tests passed** (100%) |

---

## 4. Hướng dẫn kiểm tra nghiệm thu (Acceptance Testing)

1. **Khởi động hạ tầng**:
   ```bash
   docker compose up -d postgres keycloak
   ```
2. **Kiểm tra Keycloak Admin Console**:
   - Truy cập: `http://localhost:8180/admin` (User/Pass: `admin`/`admin`).
   - Kiểm tra Realm: `nexus-realm` đã có sẵn Roles và Token Lifespans (15 phút / 30 phút).
3. **Kiểm tra Frontend & Gateway**:
   - Mở ứng dụng Frontend tại `http://localhost:3000`.
   - Đăng ký tài khoản mới: Mở Network tab của DevTools, quan sát payload gửi lên `/api/v1/auth/register` có trường `password` là chuỗi hex SHA-256 (64 ký tự).
   - Đăng nhập: Nhận JWT từ Keycloak, gọi API xem hồ sơ cá nhân `/api/v1/users/me` thành công.
