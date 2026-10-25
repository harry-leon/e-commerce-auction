# Notification Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/notifications/**`, `/api/v1/event-logs/**`
>
> **Trạng thái:** `PLANNED` — chưa có controller hoặc DTO nghiệp vụ notification/event log trong source

## Contract hiện tại

Không có request/response DTO notification hoặc event log được công bố cho FE. `Notification` và `EventLog` trong `frontend-web/src/types.ts` hiện là UI/mock model.

Service chỉ có endpoint chẩn đoán mẫu khi gọi trực tiếp service:

| Method | Path trực tiếp | Response | Ghi chú |
|---|---|---|---|
| `GET` | `/api/v1/ping` | `{ "service": string, "status": "UP", "time": string }` | Demo, sẽ xóa khi có nghiệp vụ thật |
| `GET` | `/api/v1/whoami` | `{ "subject": string }` | Cần principal; demo |

Gateway không route hai endpoint mẫu theo prefix Notification, nên FE không sử dụng chúng như public contract.

## Điều kiện chuyển sang `CURRENT`

Phải chốt tối thiểu DTO danh sách notification, đánh dấu đã đọc, số lượng chưa đọc, event log quản trị và cơ chế realtime nếu có; sau đó thêm controller/test và cập nhật file này.
