# Notification Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/notifications/**`, `/api/v1/event-logs/**`
>
> **Trạng thái:** `CURRENT` cho hộp thư IN_APP và ingestion Commerce. Email/push/SMS, preferences, realtime và event log quản trị vẫn `PLANNED`.

## Contract hiện tại

API hộp thư có JWT subject UUID, không nhận userId để chọn người dùng. Envelope theo [COMMON.md](COMMON.md).

| Method | Path qua Gateway | Kết quả |
|---|---|---|
| GET | `/api/v1/notifications?page=0&size=20` | `ApiResponse<Inbox>` của JWT hiện tại |
| POST | `/api/v1/notifications/{id}/read` | `ApiResponse<Void>`, data=null; lặp vẫn 200 |
| POST | `/api/v1/notifications/read-all` | `ApiResponse<Void>`, chỉ hộp thư hiện tại |

`page`: 0–10000, `size`: 1–100; sai trả 400 INVALID_PAGE. JWT subject sai UUID trả 401 INVALID_SUBJECT. Read ID không tồn tại/thuộc người khác trả 404 NOTIFICATION_NOT_FOUND. Anonymous trả 401.

GET data mẫu (meta/error theo envelope chung):

```json
{
  "content": [{
    "id": "11111111-1111-4111-8111-111111111111",
    "orderId": "22222222-2222-4222-8222-222222222222",
    "type": "PAYMENT_SUCCESS",
    "title": "Thanh toán thành công",
    "content": "Đơn ORD-DEMO • 50000.00 VND",
    "read": false,
    "createdAt": "2026-10-29T01:02:03Z"
  }],
  "totalElements": 1,
  "unreadCount": 1
}
```

Chỉ trả channel IN_APP, status SENT/READ. Sort `created_at DESC,id DESC`; unreadCount tính cả hộp thư. Timestamp lưu UTC theo thời điểm sự kiện Commerce; sent_at là lúc nhận. orderId/type nullable cho IN_APP cũ. Không trả buyerId, token, IP, địa chỉ/số điện thoại hoặc lỗi nội bộ. Nội dung nghiệp vụ bằng tiếng Việt.

## Ingestion nội bộ

`POST /internal/commerce-notifications`, JWT service account có ROLE_NOTIFICATION_WRITER, không route qua Gateway. Tất cả field bắt buộc:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "userId": "33333333-3333-4333-8333-333333333333",
  "orderId": "22222222-2222-4222-8222-222222222222",
  "orderNo": "ORD-DEMO",
  "type": "PAYMENT_SUCCESS",
  "amount": 50000.00,
  "occurredAt": "2026-10-29T01:02:03Z"
}
```

UUID chuẩn, orderNo nonblank tối đa 100 ký tự, amount ≥0 tối đa 13 chữ số nguyên/2 thập phân, occurredAt ISO instant. Commerce giữ id qua retry, lấy userId/amount từ Order. Service account là ranh giới tin cậy; Notification không truy cập DB Commerce.

| type | Title do server sinh |
|---|---|
| ORDER_CREATED | Đã tạo đơn hàng |
| PAYMENT_SUCCESS | Thanh toán thành công |
| PAYMENT_FAILED | Thanh toán thất bại, đơn đã hủy |

Content `Đơn {orderNo} • {amount scale 2} VND`. Không nhận title/content tùy ý. Notification SENT và attempt SUCCESS cùng transaction. Trả 204 khi mới hoặc lặp cùng payload; retry giữ READ. Cùng eventId đổi snapshot trả 409 NOTIFICATION_EVENT_CONFLICT. Validation trả 422, enum/JSON sai trả 400. Unique partial event_id chỉ áp sự kiện Commerce mới.

`/internal/user-audit` tiếp tục phục vụ lịch sử hồ sơ với ROLE_PROFILE_WRITER, độc lập thông báo đơn/thanh toán. `/api/v1/event-logs/**` chưa có public controller. Setup/demo: [ORDER_PAYMENT_NOTIFICATIONS_DEMO.md](../ORDER_PAYMENT_NOTIFICATIONS_DEMO.md).

Endpoint chẩn đoán mẫu khác khi gọi trực tiếp service:

| Method | Path trực tiếp | Response | Ghi chú |
|---|---|---|---|
| `GET` | `/api/v1/ping` | `{ "service": string, "status": "UP", "time": string }` | Demo, sẽ xóa khi có nghiệp vụ thật |
| `GET` | `/api/v1/whoami` | `{ "subject": string }` | Cần principal; demo |

Gateway không route hai endpoint mẫu theo prefix Notification, nên FE không sử dụng chúng như public contract.

Các API ngoài phần CURRENT ở trên không được FE coi là đã triển khai.
