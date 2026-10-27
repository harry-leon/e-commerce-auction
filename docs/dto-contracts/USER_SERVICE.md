# User Service DTO Contract

> **Gateway paths:** `/api/v1/users/**`, `/api/v1/roles/**`, `/api/v1/auth/forgot-password`, `/api/v1/auth/reset-password`, `/api/v1/auth/change-password`
>
> **Success wrapper hiện tại:** JSON DTO/array/Page/Map trực tiếp, không bọc `ApiResponse<T>`
>
> **Error wrapper:** envelope chuẩn trong [COMMON.md](COMMON.md), trừ response `410` của reset-password

## 0. DTO inventory

| DTO trong source | Vai trò | Có được controller expose |
|---|---|---|
| `CreateUserProfileRequest` | Tạo profile sau khi Gateway tạo IAM user | Có, nội bộ |
| `UpdateUserRequest` | Cập nhật profile | Có |
| `CreateRoleRequest` | Tạo realm role | Có |
| `UpdateRoleRequest` | Cập nhật realm role | Có |
| `ForgotPasswordRequest` | Yêu cầu email reset | Có |
| `ChangePasswordRequest` | Đổi credential khi đăng nhập | Có |
| `UserResponse` | Profile + role/privilege từ IAM | Có |
| `RoleResponse` | Role + composite privilege | Có |
| `ActivityHistoryResponse` | Một audit log của user | Có |
| `PagedResponse<T>` | Wrapper phân trang tự định nghĩa | **Không**; controller hiện trả `Page<T>` trực tiếp |

## 1. Endpoint matrix

### User

| Method | Path | Request | Success response | HTTP | Quyền |
|---|---|---|---|---|---|
| `GET` | `/users/me` | — | `UserResponse` | `200` | `PROFILE.VIEW` |
| `PUT` | `/users/me` | `UpdateUserRequest` | `UserResponse` | `200` | `PROFILE.UPDATE` |
| `GET` | `/users/{id}` | — | `UserResponse` | `200` | `USER.VIEW` |
| `GET` | `/users` | Query phân trang | `SpringPage<UserResponse>` | `200` | `USER.LIST` |
| `PUT` | `/users/{id}` | `UpdateUserRequest` | `UserResponse` | `200` | `USER.UPDATE` |
| `DELETE` | `/users/{id}` | — | `{ "message": string }` | `200` | `USER.DELETE` |
| `PUT` | `/users/{id}/roles` | `string[]` không null | `UserResponse` | `200` | role `ADMIN` |
| `GET` | `/users/me/activity-history` | Query phân trang | `SpringPage<ActivityHistoryResponse>` | `200` | `PROFILE.VIEW` |
| `GET` | `/users/{id}/activity-history` | Query phân trang | `SpringPage<ActivityHistoryResponse>` | `200` | `USER.VIEW` |
| `POST` | `/users` | `CreateUserProfileRequest` | `UserResponse` | `201` | nội bộ: role `PROFILE_WRITER` và header `X-Profile-Subject` |

### Role và password

| Method | Path | Request | Success response | HTTP | Quyền |
|---|---|---|---|---|---|
| `GET` | `/roles` | — | `RoleResponse[]` | `200` | `ROLE.LIST` |
| `GET` | `/roles/{id}` | — | `RoleResponse` | `200` | `ROLE.VIEW` |
| `POST` | `/roles` | `CreateRoleRequest` | `RoleResponse` | `201` | `ROLE.CREATE` |
| `PUT` | `/roles/{id}` | `UpdateRoleRequest` | `RoleResponse` | `200` | `ROLE.UPDATE` |
| `DELETE` | `/roles/{id}` | — | `{ "message": string }` | `200` | `ROLE.DELETE` |
| `POST` | `/auth/forgot-password` | `ForgotPasswordRequest` | `{ "message": string }` | `200` | Public |
| `POST` | `/auth/change-password` | `ChangePasswordRequest` | `{ "message": string }` | `200` | JWT hợp lệ |
| `POST` | `/auth/reset-password` | Không dùng | lỗi migration flow | `410` | Public |

## 2. Request DTO

### `UpdateUserRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `fullName` | `string` | Có | Không blank, tối đa `255` ký tự |
| `phone` | `string` | Không | `null`, rỗng hoặc `10–11` chữ số; không có khoảng trắng/dấu phân cách |
| `identifyNumber` | `string` | Không | Chưa có validation trong DTO |
| `gender` | `string` | Không | `MALE`, `FEMALE`, `OTHER`; validator chấp nhận chữ thường rồi service chuẩn hóa |
| `dateOfBirth` | `string` | Không | `YYYY-MM-DD` |
| `address` | `string` | Không | Tối đa `500` ký tự |

Đây là DTO cập nhật toàn phần: `fullName` luôn bắt buộc. Không dùng như JSON Merge Patch.

```json
{
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567",
  "identifyNumber": "079203001234",
  "gender": "MALE",
  "dateOfBirth": "2003-05-20",
  "address": "Quận 1, TP. Hồ Chí Minh"
}
```

### `CreateUserProfileRequest` — chỉ Gateway/service account dùng

Các field profile giống request đăng ký trong [API_GATEWAY.md](API_GATEWAY.md), không có `password`. Header `X-Profile-Subject` bắt buộc chứa Keycloak user UUID. FE không gọi trực tiếp endpoint này.

```json
{
  "email": "buyer@nexus.vn",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567",
  "identifyNumber": "079203001234",
  "gender": "MALE",
  "dateOfBirth": "2003-05-20",
  "address": "Quận 1, TP. Hồ Chí Minh"
}
```

### `CreateRoleRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `code` | `string` | Có | Không blank, tối đa `50` ký tự |
| `name` | `string` | Có | Không blank, tối đa `100` ký tự |
| `description` | `string` | Không | Tối đa `500` ký tự |
| `privilegeCodes` | `string[]` | Không | Tập mã privilege Keycloak; `null` được xử lý như tập rỗng khi tạo |

```json
{
  "code": "CATALOG_MANAGER",
  "name": "Quản lý danh mục",
  "description": "Quản lý sản phẩm và danh mục",
  "privilegeCodes": ["PRODUCT.LIST", "PRODUCT.UPDATE", "CATEGORY.UPDATE"]
}
```

### `UpdateRoleRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `name` | `string` | Có | Không blank, tối đa `100` ký tự |
| `description` | `string` | Không | Tối đa `500` ký tự |
| `privilegeCodes` | `string[]` | Không | Danh sách composite privilege mới |

```json
{
  "name": "Quản lý danh mục nâng cao",
  "description": "Quản lý sản phẩm, SKU và danh mục",
  "privilegeCodes": ["PRODUCT.LIST", "PRODUCT.UPDATE", "CATEGORY.UPDATE"]
}
```

`code` không nằm trong DTO update nên không được đổi qua endpoint này.

Body của `PUT /users/{id}/roles` là JSON array mã role, ví dụ `["ADMIN", "SELLER"]`. Service hiện chấp nhận cả dạng có prefix (`ROLE_ADMIN`) và không prefix (`ADMIN`), nhưng response luôn bỏ prefix. Không gửi `null`.

```json
["ADMIN", "SELLER"]
```

### Password request

| DTO | Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|---|
| `ForgotPasswordRequest` | `email` | `string` | Có | Không blank, đúng định dạng email |
| `ChangePasswordRequest` | `currentPassword` | `string` | Có | Không blank |
| `ChangePasswordRequest` | `newPassword` | `string` | Có | Từ `8` đến `100` ký tự, có chữ in hoa, chữ số và dấu/ký hiệu; khoảng trắng không tính là ký tự đặc biệt. Vi phạm trả `422` kèm từng lỗi trong `error.details` |

Forgot-password luôn trả cùng một thông báo dù email có tồn tại hay không để tránh lộ tài khoản. Change-password cập nhật credential tại Keycloak và logout session; FE phải đưa người dùng về màn hình đăng nhập.

```json
{
  "email": "buyer@nexus.vn"
}
```

```json
{
  "currentPassword": "old-example-password",
  "newPassword": "NexusExample123!"
}
```

## 3. Response DTO

### `UserResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không trong dữ liệu hợp lệ | ID profile nội bộ |
| `email` | `string` | Không | Email tài khoản |
| `phone` | `string` | Có | Số điện thoại |
| `fullName` | `string` | Không | Họ tên hiển thị |
| `identifyNumber` | `string` | Có | Tên field hiện tại là `identifyNumber`, không phải `idNumber` |
| `gender` | `string` | Có | Thường là `MALE`, `FEMALE`, `OTHER` |
| `dateOfBirth` | `string` | Có | `YYYY-MM-DD` |
| `address` | `string` | Có | Địa chỉ |
| `status` | `string` | Không | Trạng thái profile, FE hiện map `ACTIVE` thành active và giá trị khác thành locked |
| `createdAt` | `string` | Có | ISO local datetime, chưa cam kết timezone |
| `updatedAt` | `string` | Có | ISO local datetime, chưa cam kết timezone |
| `roles` | `string[]` | Không theo mapper hiện tại | Mã realm role ứng dụng đã bỏ prefix `ROLE_`, ví dụ `ADMIN`, `BUYER` |
| `privileges` | `string[]` | Không theo mapper hiện tại | Mã composite privilege từ Keycloak |

Success response của `GET /users/me`, `GET /users/{id}` và các endpoint update trả JSON thô:

```json
{
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
  "updatedAt": "2026-10-25T09:40:12",
  "roles": ["BUYER"],
  "privileges": ["PROFILE.VIEW", "PROFILE.UPDATE"]
}
```

### `RoleResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | ID role do Keycloak cấp |
| `code` | `string` | Không | Mã role đã bỏ prefix `ROLE_`, ví dụ `ADMIN` |
| `name` | `string` | Không theo dữ liệu do service tạo | Tên hiển thị lấy từ attribute `displayName`, fallback về `code` |
| `description` | `string` | Có | Mô tả role |
| `privileges` | `string[]` | Không | Composite privilege codes |

```json
{
  "id": "819e82b6-13d4-4c84-9fd8-32a4059db914",
  "code": "CATALOG_MANAGER",
  "name": "Quản lý danh mục",
  "description": "Quản lý sản phẩm và danh mục",
  "privileges": ["PRODUCT.LIST", "PRODUCT.UPDATE", "CATEGORY.UPDATE"]
}
```

`GET /roles` trả trực tiếp một JSON array gồm các object trên, không có key `data`:

```json
[
  {
    "id": "819e82b6-13d4-4c84-9fd8-32a4059db914",
    "code": "CATALOG_MANAGER",
    "name": "Quản lý danh mục",
    "description": "Quản lý sản phẩm và danh mục",
    "privileges": ["PRODUCT.LIST", "PRODUCT.UPDATE", "CATEGORY.UPDATE"]
  }
]
```

### `ActivityHistoryResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | ID audit log |
| `action` | `string` | Không | Ví dụ `LOGIN`, `REGISTER`, `USER_UPDATED` |
| `description` | `string` | Có | Mô tả thao tác |
| `status` | `string` | Không | Ví dụ `SUCCESS`, `FAILED` |
| `createdAt` | `string` | Không | ISO local datetime |

### `SpringPage<T>`

Hai endpoint activity và endpoint list user trả trực tiếp `Page<T>` của Spring Data. FE chỉ nên phụ thuộc các field ổn định đang cần: `content`, `number`, `size`, `totalElements`, `totalPages`, `first`, `last`, `empty`. Đây là contract tạm thời thuộc `CONTRACT-004`, không phải `PagedResponse<T>` dù class đó tồn tại trong source.

Query chung: `page` mặc định `0`, `size` theo Spring default nếu không truyền; `sort=field,asc|desc`. List user mặc định sort `fullName,asc`; activity mặc định `createdAt,desc`. List user có thêm `keyword` tùy chọn.

Ví dụ rút gọn response `GET /users?page=0&size=20`:

```json
{
  "content": [
    {
      "id": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
      "email": "buyer@nexus.vn",
      "phone": "0901234567",
      "fullName": "Nguyễn Văn An",
      "identifyNumber": null,
      "gender": "MALE",
      "dateOfBirth": "2003-05-20",
      "address": null,
      "status": "ACTIVE",
      "createdAt": "2026-10-25T08:15:30",
      "updatedAt": "2026-10-25T09:40:12",
      "roles": ["BUYER"],
      "privileges": ["PROFILE.VIEW"]
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "offset": 0,
    "paged": true,
    "unpaged": false,
    "sort": {
      "empty": false,
      "sorted": true,
      "unsorted": false
    }
  },
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false,
  "sort": {
    "empty": false,
    "sorted": true,
    "unsorted": false
  }
}
```

Ví dụ response activity page dùng cùng wrapper, chỉ thay phần tử trong `content`:

```json
{
  "content": [
    {
      "id": "b815fbda-799b-4e16-a06e-950590c67a22",
      "action": "USER_UPDATED",
      "description": "Cập nhật hồ sơ cá nhân",
      "status": "SUCCESS",
      "createdAt": "2026-10-25T09:40:12"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false
}
```

Các response message trả JSON thô:

```json
{
  "message": "Đã xóa người dùng thành công"
}
```

`DELETE /roles/{id}` dùng cùng shape và trả message:

```json
{
  "message": "Đã xóa vai trò thành công"
}
```

```json
{
  "message": "Nếu tài khoản tồn tại, hãy làm theo liên kết đặt lại mật khẩu trong email."
}
```

Response đổi mật khẩu thành công:

```json
{
  "message": "Đổi mật khẩu thành công. Vui lòng đăng nhập lại."
}
```

`POST /auth/reset-password` luôn trả HTTP `410` với payload ngoại lệ chưa theo envelope đầy đủ:

```json
{
  "success": false,
  "error": {
    "code": "AUTH_RESET_FLOW_CHANGED",
    "message": "Hãy yêu cầu email mới và đặt mật khẩu tại trang Keycloak."
  }
}
```

## 4. TypeScript tối thiểu phía FE

```ts
interface UserResponse {
  id: string
  email: string
  phone: string | null
  fullName: string
  identifyNumber: string | null
  gender: string | null
  dateOfBirth: string | null
  address: string | null
  status: string
  createdAt: string | null
  updatedAt: string | null
  roles: string[]
  privileges: string[]
}
```

UI model có thêm avatar, reputation hoặc trust level phải được tạo qua mapper/default riêng; các field đó chưa thuộc `UserResponse`.
