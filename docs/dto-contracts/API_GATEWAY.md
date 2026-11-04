# API Gateway DTO Contract

> **Base URL phía FE:** `/api/v1`
>
> **Nguồn:** `AuthGatewayController` và route trong `api-gateway/application.yml`
>
> **Quy chuẩn Response:** Toàn bộ Auth Gateway tuân thủ chuẩn `ApiResponse<T>` theo [COMMON.md](COMMON.md).

> **VNPAY IPN:** Chỉ `GET /api/v1/payments/vnpay/ipn` được phép không có JWT để provider gửi callback.
> Commerce xác minh HMAC trước cập nhật DB và trả JSON `RspCode`/`Message` theo protocol VNPAY.
> POST IPN và các API payment buyer vẫn cần JWT. Chi tiết: [TT-S1-3](../PAYMENT_SANDBOX_INTEGRATION.md).

## 1. Endpoint matrix

| Method | Path | Request | Success response | HTTP | Xác thực |
|---|---|---|---|---|---|
| `POST` | `/auth/login` | `LoginRequest` | `ApiResponse<AuthPayload>` | `200` | Public |
| `POST` | `/auth/refresh` | `{}` + cookie HttpOnly | `ApiResponse<AuthPayload>` | `200` | Public |
| `POST` | `/auth/refresh-token` | `{}` + cookie HttpOnly | `ApiResponse<AuthPayload>` | `200` | Public, alias |
| `POST` | `/auth/logout` | Cookie + header CSRF | Không có body | `204` | Public |
| `POST` | `/auth/register` | `RegisterRequest` | `ApiResponse<UserResponse>` | `201` | Public |

Login/register dùng Content-Type: application/json. Refresh không cần body.

### Cookie và chống CSRF

Login/refresh đặt cookie host-only nexus_access (Path=/) và nexus_refresh (Path=/api/v1/auth), HttpOnly, SameSite=Strict, Secure. JSON chỉ trả user, expiresIn, tokenType; không trả token. Hạn cookie theo expires_in/refresh_expires_in của IAM.

FE gọi cùng origin qua proxy /api/v1 với credentials: include và X-Nexus-Request: 1. Gateway yêu cầu header này cho request thay đổi dữ liệu dùng cookie và POST auth; CORS không mở cho origin khác. Gateway đổi access cookie thành Bearer, xác minh JWT rồi chuyển tiếp service.

POST /auth/logout trả 204 và xóa cả hai cookie đúng path, không cần access token còn hạn. Logout hiện chưa thu hồi session IAM; JWT bị sao chép vẫn có hiệu lực tới hạn.

Mặc định AUTH_COOKIE_SECURE=true cho HTTPS. Chạy trực tiếp bằng HTTP localhost đặt AUTH_COOKIE_SECURE=false; Compose demo mặc định false. Sau thay đổi phải rebuild/restart Gateway và đăng nhập lại; token cũ trong storage bị xóa.

## 2. Request DTO

### `LoginRequest`

| Field | Kiểu | Bắt buộc | Validation/xử lý |
|---|---|---|---|
| `email` | `string` | Có nếu không gửi `username` | Dùng làm username đăng nhập |
| `username` | `string` | Có nếu không gửi `email` | Được ưu tiên hơn `email` nếu cả hai cùng có |
| `password` | `string` | Có | Gateway chuyển trực tiếp cho Keycloak |

```json
{
  "email": "buyer@nexus.vn",
  "password": "example-password"
}
```

### Refresh

Token chỉ lấy từ cookie nexus_refresh; bỏ hỗ trợ refreshToken/refresh_token trong JSON. Gửi POST /auth/refresh với header X-Nexus-Request: 1; browser tự gửi cookie.

### `RegisterRequest`

| Field | Kiểu | Bắt buộc | Validation/xử lý |
|---|---|---|---|
| `email` | `string` | Có | Trim, lowercase, phải chứa `@`; user-service kiểm tra email chuẩn lần nữa |
| `password` | `string` | Có | Từ `8` đến `100` ký tự, có chữ in hoa, chữ số và dấu/ký hiệu; khoảng trắng không tính là ký tự đặc biệt. Chỉ gửi sang Keycloak, không lưu trong profile DB |
| `fullName` | `string` | Có | Không rỗng, trim, tối đa `255` tại user-service |
| `phone` | `string` | Không | `null`/rỗng hoặc đúng 10 chữ số ASCII, bắt đầu `03`, `05`, `07`, `08`, `09`; không nhận `+84`, dấu phân cách hay khoảng trắng. Sai trả 422 theo field `phone` trước khi gọi IAM; chưa xác minh OTP |
| `identifyNumber` | `string` | Có | Đúng 12 chữ số ASCII, giữ số 0 đầu; thiếu/sai kiểu/sai định dạng trả 422 với `error.details[].field=identifyNumber` trước khi gọi IAM. Chưa xác minh danh tính hoặc kiểm tra trùng CCCD |
| `gender` | `"MALE" \| "FEMALE" \| "OTHER" \| ""` | Không | Phân biệt hoa thường tại request đăng ký |
| `dateOfBirth` | `string` | Không | `YYYY-MM-DD` |
| `address` | `string` | Không | Tối đa `500` ký tự |

Gateway bỏ qua role do client gửi và luôn gán `ROLE_BUYER`. Nếu tạo profile thất bại sau khi tạo user IAM, Gateway cố gắng rollback user Keycloak.

```json
{
  "email": "buyer@nexus.vn",
  "password": "example-password",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567",
  "identifyNumber": "079203001234",
  "gender": "MALE",
  "dateOfBirth": "2003-05-20",
  "address": "Quận 1, TP. Hồ Chí Minh"
}
```

## 3. Success response

### `AuthPayload`

```ts
interface AuthPayload {
  expiresIn: number
  tokenType: 'Bearer'
  user: UserResponse
}
```

Dữ liệu `AuthPayload` được bọc bên trong trường `data` của `ApiResponse<AuthPayload>`. `user` là nội dung `UserResponse` lấy từ User Service; chi tiết field xem [USER_SERVICE.md](USER_SERVICE.md#3-response-dto).

Ví dụ response của login/refresh (`200 OK`):

```json
{
  "success": true,
  "data": {
    "expiresIn": 3600,
    "tokenType": "Bearer",
    "user": {
      "id": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
      "email": "buyer@nexus.vn",
      "phone": "0901234567",
      "fullName": "Nguyễn Văn An",
      "identifyNumber": "079203001234",
      "gender": "MALE",
      "dateOfBirth": "2003-05-20",
      "address": "Quận 1, TP. Hồ Chí Minh",
      "status": "ACTIVE",
      "createdAt": "2026-10-25T08:15:30",
      "updatedAt": "2026-10-25T08:15:30",
      "roles": ["BUYER"],
      "privileges": ["PROFILE.VIEW", "PROFILE.UPDATE"]
    }
  },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-27T14:00:00.000Z"
  }
}
```

### Register response

`POST /auth/register` trả về `ApiResponse<UserResponse>` (`201 Created`). Trường `data` chứa thông tin hồ sơ vừa tạo từ User Service. Endpoint này chưa cấp token, FE chuyển người dùng về màn hình đăng nhập để xác thực.

```json
{
  "success": true,
  "data": {
    "id": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
    "email": "buyer@nexus.vn",
    "phone": "0901234567",
    "fullName": "Nguyễn Văn An",
    "identifyNumber": "079203001234",
    "gender": "MALE",
    "dateOfBirth": "2003-05-20",
    "address": "Quận 1, TP. Hồ Chí Minh",
    "status": "ACTIVE",
    "createdAt": "2026-10-25T08:15:30",
    "updatedAt": "2026-10-25T08:15:30",
    "roles": ["BUYER"],
    "privileges": ["PROFILE.VIEW", "PROFILE.UPDATE"]
  },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f1",
    "timestamp": "2026-10-27T14:00:00.000Z"
  }
}
```

## 4. Error response

Gateway auth tuân thủ chuẩn `ApiResponse` lỗi:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AUTH_CREDENTIALS_INVALID",
    "message": "Email hoặc mật khẩu không chính xác hoặc tài khoản bị khóa",
    "details": null
  },
  "meta": {
    "requestId": "req_8cb579f2",
    "timestamp": "2026-10-27T14:00:00.000Z"
  }
}
```

Các mã lỗi thường gặp: `AUTH_CREDENTIALS_INVALID` (401), `AUTH_REFRESH_TOKEN_EXPIRED` (401), `INVALID_ARGUMENT` (400), `VALIDATION_ERROR` (422), `REGISTRATION_FAILED` (400/409), `INTERNAL_SERVER_ERROR` (500).

## 5. Phân tuyến service

| Route prefix | Đích |
|---|---|
| `/users/**`, `/roles/**`, một số `/auth/**` | User Service |
| `/products/**`, `/categories/**` | Catalog Service |
| `/cart/**`, `/checkout/**`, `/orders/**`, `/payments/**` | Commerce Service |
| `/auctions/**`, `/bids/**`, `/ws/auctions/**` | Auction Service |
| `/inventory/**`, `/warehouses/**`, `/shipping/**`, `/shipments/**` | Fulfillment Service |
| `/notifications/**`, `/event-logs/**` | Notification Service |
| `/reputations/**` | Reputation Service |

Route tồn tại trong Gateway không đồng nghĩa endpoint nghiệp vụ đã được service triển khai. Kiểm tra trạng thái trong file contract của từng service trước khi tích hợp.
