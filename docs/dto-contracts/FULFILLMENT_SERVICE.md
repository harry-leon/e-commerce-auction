# Fulfillment Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/inventory/**`, `/api/v1/warehouses/**`, `/api/v1/shipping/**`, `/api/v1/shipments/**`
>
> **Trạng thái:** `PLANNED` — chưa có controller hoặc DTO nghiệp vụ fulfillment trong source

## Contract hiện tại

Không có request/response DTO tồn kho, kho hàng hoặc vận chuyển được công bố cho FE. Các model `Shipment` và `ShipmentEvent` trong `frontend-web/src/types.ts` là UI/mock model, chưa phải payload Backend.

Service chỉ có endpoint chẩn đoán mẫu khi gọi trực tiếp service:

| Method | Path trực tiếp | Response | Ghi chú |
|---|---|---|---|
| `GET` | `/api/v1/ping` | `{ "service": string, "status": "UP", "time": string }` | Demo, sẽ xóa khi có nghiệp vụ thật |
| `GET` | `/api/v1/whoami` | `{ "subject": string }` | Cần principal; demo |

Gateway không route hai endpoint mẫu theo prefix Fulfillment, nên FE không sử dụng chúng như public contract.

## Liên quan tới contract hiện có

`stockQuantity` trong `Catalog.SkuSnapshot` và `Commerce.CartItemResponse.stockAvailable` hiện là `null` vì Fulfillment chưa cung cấp API tồn kho. FE phải hiểu là “chưa xác minh”, không quy đổi thành hết hàng.

## Điều kiện chuyển sang `CURRENT`

Phải có controller, DTO, enum/validation và test cho inventory, reservation, warehouse, shipment/tracking. Contract giữ hàng cần chốt rõ idempotency, thời hạn reservation và trạng thái trước khi Commerce tích hợp checkout.
