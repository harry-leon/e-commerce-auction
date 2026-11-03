# Common DTO và Response Envelope

> **Nguồn:** `backend/common/src/main/java/com/nexus/common/response`
>
> **Áp dụng thực tế:** Catalog và Commerce success response; lỗi do `GlobalExceptionHandler` của các service xử lý

## 0. DTO inventory

| DTO trong source | Vai trò |
|---|---|
| `ApiResponse<T>` | Envelope cấp cao nhất |
| `ErrorDetail` | Lỗi nghiệp vụ/hệ thống |
| `ValidationErrorDetail` | Chi tiết validation theo field |
| `MetaInfo` | Request ID, thời gian và metadata tùy chọn |
| `PaginationInfo` | Phân trang offset hoặc cursor |

## 1. `ApiResponse<T>`

```ts
interface ApiResponse<T, TDetails = unknown> {
  success: boolean
  data: T | null
  error: ErrorDetail<TDetails> | null
  meta: MetaInfo
}
```

| Field | Kiểu JSON | Luôn có | Quy tắc |
|---|---|---|---|
| `success` | `boolean` | Có | `true` cho success do helper tạo; `false` cho lỗi chuẩn |
| `data` | `T \| null` | Có | Có dữ liệu khi thành công; `null` khi lỗi hoặc success không có payload |
| `error` | `ErrorDetail \| null` | Có | `null` khi thành công |
| `meta` | `MetaInfo` | Có | Luôn có `requestId`, `timestamp`; field khác chỉ xuất hiện khi có giá trị |

Ví dụ success:

```json
{
  "success": true,
  "data": { "id": "6f1f50df-8f2f-4db5-a49f-44bc63d13d2a" },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-25T08:30:00.000Z"
  }
}
```

Success không có payload, ví dụ `DELETE /cart/items`:

```json
{
  "success": true,
  "data": null,
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-25T08:30:00.000Z"
  }
}
```

## 2. `ErrorDetail` và `ValidationErrorDetail`

| Field | Kiểu JSON | Null | Ý nghĩa |
|---|---|---|---|
| `code` | `string` | Không | Mã lỗi `UPPER_SNAKE_CASE` |
| `message` | `string` | Không | Thông báo lỗi an toàn cho client |
| `details` | `TDetails \| null` | Có | Chi tiết nghiệp vụ hoặc mảng lỗi validation |

Lỗi validation trả HTTP `422` với `error.code = "VALIDATION_ERROR"`. Mỗi phần tử `details` có dạng:

| Field | Kiểu | Null | Ý nghĩa |
|---|---|---|---|
| `field` | `string` | Không | Tên field, có thể dùng dạng `items[0].quantity` |
| `code` | `string` | Không | Ví dụ `NOT_BLANK`, `NOT_NULL`, `MIN_VALUE`, `INVALID_FORMAT` |
| `message` | `string` | Không | Nội dung validation |
| `params` | `Record<string, unknown>` | Có | Ví dụ `{ "min": 1, "max": 100 }` |

Ví dụ lỗi validation của request thêm vào giỏ:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Dữ liệu yêu cầu không hợp lệ",
    "details": [
      {
        "field": "quantity",
        "code": "MIN_VALUE",
        "message": "quantity must be greater than 0",
        "params": {
          "min": 1
        }
      }
    ]
  },
  "meta": {
    "requestId": "req_b2ec3441",
    "timestamp": "2026-10-25T08:45:00.000Z"
  }
}
```

Ví dụ lỗi không tìm thấy resource:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "Không tìm thấy tài nguyên",
    "details": null
  },
  "meta": {
    "requestId": "req_a899c31e",
    "timestamp": "2026-10-25T08:46:00.000Z"
  }
}
```

Mã/message nghiệp vụ cụ thể có thể khác theo exception mà service ném ra; bốn field cấp cao nhất vẫn giữ nguyên với lỗi đi qua `GlobalExceptionHandler`.

## 3. `MetaInfo`

| Field | Kiểu JSON | Có điều kiện | Ý nghĩa |
|---|---|---|---|
| `requestId` | `string` | Luôn có trong envelope chuẩn | Đồng bộ với `X-Request-Id` nếu filter nhận/tạo được |
| `timestamp` | `string` | Luôn có | ISO-8601 UTC, có milliseconds |
| `pagination` | `PaginationInfo` | Response phân trang chuẩn | Metadata trang |
| `sort` | `string` | Khi helper truyền sort | Chuỗi sort đã áp dụng |
| `filters` | `Record<string, unknown>` | Khi helper truyền filter | Bộ lọc đã áp dụng |
| `notFoundIds` | `string[]` | Khi có truy vấn batch thiếu ID | Các ID không tìm thấy |

`PaginationInfo`:

| Field | Kiểu | Ghi chú |
|---|---|---|
| `page` | `number \| null` | Zero-based; có thể `null` với cursor pagination |
| `size` | `number` | Helper giới hạn từ `1` đến `100` |
| `totalElements` | `number \| null` | `null` với cursor pagination |
| `totalPages` | `number \| null` | `null` với cursor pagination |
| `hasNext` | `boolean` | Có trang tiếp theo |
| `hasPrevious` | `boolean \| null` | Có thể `null` với cursor pagination |
| `nextCursor` | `string \| null` | Chỉ có với cursor pagination |

## 4. HTTP status lỗi chuẩn

| HTTP | Trường hợp chính |
|---|---|
| `400` | JSON sai, UUID/query sai kiểu, thiếu parameter/header, argument không hợp lệ |
| `401` | Chưa xác thực hoặc credential không hợp lệ |
| `403` | Không đủ quyền, tài khoản bị khóa/vô hiệu hóa |
| `404` | Không có resource hoặc endpoint |
| `405` | Sai HTTP method |
| `409` | Xung đột trạng thái |
| `413` | Payload/upload quá lớn |
| `415` | Content-Type không hỗ trợ |
| `422` | Bean Validation không đạt |
| `500` | Lỗi hệ thống không dự kiến; không trả stack trace |

Lưu ý: security filter có thể sinh `401/403` trước controller. Client phải dựa vào HTTP status và không giả định mọi lỗi từ mọi tầng đều có đủ envelope nếu Gateway/security chưa được chuẩn hóa cùng handler.
