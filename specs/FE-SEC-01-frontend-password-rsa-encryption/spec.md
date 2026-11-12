# Đặc tả kỹ thuật: Mã hóa mật khẩu phía Frontend bằng RSA (FE-SEC-01)

## 1. Mục tiêu và Bối cảnh (Background & Goals)
- **Vấn đề:** Khi người dùng nhập mật khẩu tại màn hình Đăng nhập (Login), Đăng ký (Register) hoặc Đổi mật khẩu (Change Password), tab **Network > Payload** trên DevTools của trình duyệt hiển thị mật khẩu dạng plain text (`{ "email": "buyer@nexus.vn", "password": "Nexus123@" }`), gây lo ngại về an toàn thông tin khi màn hình bị quay chụp hoặc bị quan sát từ DevTools.
- **Mục tiêu:** Mã hóa mật khẩu ngay trên trình duyệt Frontend bằng thuật toán mã hóa bất đối xứng **RSA (RSA-OAEP with SHA-256)** trước khi request được gửi qua mạng tới API Gateway. Khi xem trong DevTools, trường mật khẩu chỉ hiển thị chuỗi Base64 đã được mã hóa (`encryptedPassword`), không thể suy ngược ra mật khẩu gốc.
- **Xác thực:** API Gateway sử dụng Private Key tương ứng để giải mã chuỗi Base64 thành mật khẩu gốc, sau đó chuyển tiếp cho Keycloak IAM xử lý xác thực và phân quyền theo chuẩn OAuth2 Resource Owner Password Credentials Grant.

---

## 2. Các quyết định kiến trúc đã làm rõ (Clarified Architectural Decisions)

Qua phiên phỏng vấn làm rõ yêu cầu (/grill-me & /speckit-clarify):

| # | Tiêu chí quyết định | Lựa chọn đã thống nhất | Lý do / Đánh đổi |
|---|---|---|---|
| 1 | **Thuật toán mã hóa** | **RSA-OAEP (2048-bit, SHA-256)** | Chuẩn mã hóa bất đối xứng quốc tế, được hỗ trợ gốc bởi Web Crypto API (`window.crypto.subtle`) trên tất cả trình duyệt hiện đại, không cần cài đặt thêm thư viện nặng. |
| 2 | **Phạm vi áp dụng** | **Toàn diện (Login, Register, Change Password)** | Bảo vệ đồng bộ mật khẩu ở tất cả các luồng nhập liệu của người dùng, không để lộ plain text ở bất kỳ endpoint nào. |
| 3 | **Phân phối Public Key** | **Endpoint động `GET /api/v1/auth/public-key`** | Gateway cung cấp Public Key dạng PEM/SPKI; Frontend tự động gọi lấy và lưu vào bộ nhớ cache, hỗ trợ Gateway xoay key (key rotation) linh hoạt. |
| 4 | **Nội dung mã hóa** | **Mã hóa trực tiếp chuỗi mật khẩu thô (Raw Password)** | Đơn giản, nhẹ nhàng, Frontend chỉ cần mã hóa chuỗi UTF-8 của mật khẩu thành chuỗi Base64, Gateway giải mã trực tiếp. |
| 5 | **Tính tương thích ngược (Compatibility)** | **Cơ chế lai (Hybrid Fallback)** | Gateway ưu tiên giải mã trường `encryptedPassword` (hoặc chuỗi mã hóa RSA trong `password`); nếu là mật khẩu thường chưa mã hóa, Gateway vẫn cho phép xử lý để không làm gián đoạn unit test, integration test và test script nội bộ. |

---

## 3. Thiết kế chi tiết (Detailed Design)

### 3.1. API Gateway (Backend)
1. **Quản lý cặp khóa RSA (`RsaKeyProvider`):**
   - Khởi tạo cặp khóa RSA-2048 (hoặc nạp từ cấu hình nếu có).
   - Expose Public Key qua endpoint `GET /api/v1/auth/public-key` (cho phép truy cập công khai trong `GatewaySecurityConfig`).
   - Cung cấp tiện ích giải mã `decrypt(String base64Encrypted)` bằng Private Key với thuật toán `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`.
2. **Cập nhật `AuthGatewayController`:**
   - Tại `POST /api/v1/auth/login`: Trích xuất `encryptedPassword` (hoặc `password`), giải mã nếu là chuỗi RSA để lấy mật khẩu thô trước khi gửi tới Keycloak.
   - Tại `POST /api/v1/auth/register`: Giải mã mật khẩu trước khi kiểm tra `PasswordPolicy` và tạo người dùng trên Keycloak.
3. **Cập nhật route đổi mật khẩu:**
   - Giải mã mật khẩu mới trước khi forward sang User Service.

### 3.2. Frontend Web (Client)
1. **Module mã hóa Web Crypto API (`src/utils/crypto.ts`):**
   - Hàm `fetchPublicKey()`: Gọi `GET /api/v1/auth/public-key`, import Public Key vào `CryptoKey` của Web Crypto API và cache trong bộ nhớ.
   - Hàm `encryptPassword(password: string): Promise<string>`: Sử dụng `window.crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, encodedPassword)` và chuyển kết quả thành chuỗi Base64.
2. **Cập nhật `client.ts` / Form đăng nhập & đăng ký:**
   - Trong `LoginPage.tsx`: Trước khi submit form, gọi `encryptPassword(password)` và gửi request với trường `encryptedPassword` (loại bỏ trường plain text `password`).
   - Trong `RegisterPage.tsx`: Tương tự, mã hóa mật khẩu trước khi gửi tới `/api/v1/auth/register`.
   - Trong form Đổi mật khẩu: Mã hóa mật khẩu cũ và mới.

---

## 4. Tiêu chí nghiệm thu (Acceptance Criteria)

- [ ] **AC-1:** Khi mở tab **Network > Payload** trong DevTools trình duyệt lúc Đăng nhập hoặc Đăng ký, payload request **không** chứa mật khẩu dạng plain text, thay vào đó là chuỗi Base64 `encryptedPassword`.
- [ ] **AC-2:** Đăng nhập với tài khoản `buyer@nexus.vn` / `Nexus123@` thành công `200 OK`, lưu phiên và chuyển hướng bình thường.
- [ ] **AC-3:** Đăng ký tài khoản mới với mật khẩu đã mã hóa thành công `201 Created`.
- [ ] **AC-4:** Endpoint `GET /api/v1/auth/public-key` trả về Public Key hợp lệ với status `200 OK`.
- [ ] **AC-5:** Test case kiểm thử tự động xác nhận tính đúng đắn của quá trình mã hóa tại Frontend và giải mã tại Gateway.
