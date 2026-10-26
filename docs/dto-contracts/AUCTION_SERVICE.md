# Auction Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/auctions/**`, `/api/v1/bids/**`, `/ws/auctions/**`
>
> **Trạng thái:** `PLANNED` — chưa có controller hoặc DTO nghiệp vụ đấu giá trong source

## Contract hiện tại

Không có request/response DTO nghiệp vụ nào được công bố cho FE. Các model `Auction`, `AuctionBid` trong `frontend-web/src/types.ts` hiện là UI/mock model, không phải bằng chứng về payload Backend.

Service chỉ có endpoint chẩn đoán mẫu khi gọi trực tiếp service:

| Method | Path trực tiếp | Response | Ghi chú |
|---|---|---|---|
| `GET` | `/api/v1/ping` | `{ "service": string, "status": "UP", "time": string }` | Demo, sẽ xóa khi có nghiệp vụ thật |
| `GET` | `/api/v1/whoami` | `{ "subject": string }` | Cần principal; demo |

Hai endpoint này không nằm dưới prefix Auction mà Gateway đang route, vì vậy FE không sử dụng chúng như public contract.

## Điều kiện chuyển sang `CURRENT`

Phải có controller, DTO request/response, enum/validation, kiểm thử API và cập nhật file này. Tối thiểu cần chốt contract tạo/duyệt auction, danh sách/chi tiết auction, đặt bid, lịch sử bid, WebSocket event và settlement trước khi FE bỏ mock.
