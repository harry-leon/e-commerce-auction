# Fulfillment Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/inventory/**`, `/api/v1/warehouses/**`, `/api/v1/shipping/**`, `/api/v1/shipments/**`
>
> **Trạng thái:** `CURRENT` — Đã hoàn thành nghiệp vụ Quản lý tồn kho cấp SKU và Khóa giữ hàng Checkout với Concurrency Control ([KH-S1-2])

## 1. Tổng quan kiến trúc & Quy tắc nghiệp vụ

- **Quản lý tồn kho cấp SKU:** Theo dõi chính xác 4 trạng thái tồn theo từng SKU và kho hàng:
  - `total_qty`: Tổng số lượng thực tế trong kho.
  - `available_qty`: Tồn khả dụng có thể đặt mua = `total_qty - reserved_qty - unavailable_qty`.
  - `reserved_qty`: Số lượng đang bị tạm giữ bởi các đơn Checkout.
  - `unavailable_qty`: Hàng lỗi, hỏng hoặc cách ly.
  - *Tuyệt đối không quản lý ở cấp Product cha.*
- **Khóa giữ hàng khi Checkout (Atomic Reservation):**
  - Nhận yêu cầu giữ hàng từ Commerce Service khi người dùng checkout.
  - Áp dụng Pessimistic Locking (`SELECT ... FOR UPDATE`) và Optimistic Locking (`@Version`) trên bảng `inventories`.
  - Sắp xếp items theo `skuId` tăng dần trước khi khóa nhằm triệt tiêu hoàn toàn nguy cơ Deadlock giữa các luồng.
  - Nghiêm cấm bán vượt số lượng tồn (`ALLOW_OVERSELL = false`): lập tức từ chối và trả về mã lỗi `INVENTORY_INSUFFICIENT` nếu không đủ tồn khả dụng.
- **Cơ chế Hết hạn & Tự động hoàn kho (Auto-release):**
  - Phiếu giữ hàng tự động hết hạn sau 30 phút (`INVENTORY_RESERVATION_TIMEOUT_MINUTES = 30`).
  - Background Scheduler định kỳ quét các phiếu PENDING quá hạn, hoàn trả `reserved_qty` về lại `available_qty`, cập nhật trạng thái `EXPIRED` và phát sự kiện `InventoryReservationExpired`.
- **Sổ cái tồn kho bất biến (Immutable Inventory Ledger):**
  - Mọi biến động số lượng (`INBOUND`, `OUTBOUND`, `RESERVE`, `RELEASE`, `ADJUSTMENT`) được ghi nhận bất biến vào bảng `inventory_ledger`.
  - Chỉ cho phép INSERT và SELECT, tuyệt đối cấm UPDATE và DELETE.

---

## 2. Danh mục API Endpoints

| Method | Endpoint | Quyền / Xác thực | Mô tả chức năng |
|---|---|---|---|
| `GET` | `/api/v1/inventory/sku/{skuId}` | Public | Lấy 4 trạng thái tồn của SKU tại kho chỉ định (hoặc kho mặc định `WH_001`) |
| `GET` | `/api/v1/inventory/sku/{skuId}/all` | Public | Lấy danh sách tồn kho của SKU trên tất cả các kho lưu trữ |
| `POST` | `/api/v1/inventory/check` | Public | Kiểm tra số lượng tồn khả dụng cho danh sách SKU (Cart / Checkout pre-validation) |
| `POST` | `/api/v1/inventory/reserve` | `Authenticated` | Khóa giữ hàng nguyên tử khi Checkout (Atomic Reservation) |
| `POST` | `/api/v1/inventory/reservations/{id}/confirm` | `Authenticated` | Xác nhận đơn hàng thành công theo ID phiếu giữ, trừ tồn kho vật lý (OUTBOUND - Idempotent) |
| `POST` | `/api/v1/inventory/orders/{orderId}/commit` | `Authenticated` | Xác nhận trừ kho thực tế cho toàn bộ SKU của đơn hàng khi thanh toán thành công (OUTBOUND - Idempotent) |
| `POST` | `/api/v1/inventory/reservations/{id}/release` | `Authenticated` | Giải phóng phiếu giữ hàng theo ID khi hủy đơn hoặc lỗi thanh toán (RELEASE - Idempotent) |
| `POST` | `/api/v1/inventory/release` | `Authenticated` | Giải phóng tất cả phiếu giữ hàng của một đơn hàng (theo `refType`, `refId` - Idempotent) |
| `POST` | `/api/v1/inventory/orders/{orderId}/release` | `Authenticated` | Giải phóng tồn kho giữ tạm theo mã đơn hàng khi hủy đơn hoặc lỗi thanh toán (RELEASE - Idempotent) |
| `POST` | `/api/v1/inventory/events/order-paid` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderPaid`, trừ vĩnh viễn reserved_qty và total_qty (Idempotent) |
| `POST` | `/api/v1/inventory/events/order-payment-failed` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderPaymentFailed`, hoàn trả reserved_qty về available_qty (Idempotent) |
| `POST` | `/api/v1/inventory/events/order-cancelled` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderCancelled`, hoàn trả reserved_qty về available_qty (Idempotent) |
| `POST` | `/api/v1/inventory/inbound` | `ADMIN`, `SELLER` | Nhập kho hàng hóa cho SKU, tăng `total_qty` và ghi sổ cái `INBOUND` |
| `POST` | `/api/v1/inventory/adjust` | `ADMIN` | Điều chỉnh kiểm kê tồn kho thực tế, ghi sổ cái `ADJUSTMENT` |
| `GET` | `/api/v1/inventory/ledger` | `ADMIN`, `SUPPORT` | Tra cứu lịch sử Sổ cái tồn kho bất biến (phân trang, lọc theo `skuId`/`refId`) |
| `POST` | `/api/v1/inventory/reservations/expire-sweeper` | `ADMIN`, `SUPPORT` | Kích hoạt quét giải phóng phiếu quá hạn 30 phút thủ công (QA / Dev) |

---

## 3. Cấu trúc Payload & Data Transfer Objects (DTO)

### 3.1. Phản hồi tồn kho SKU (`InventoryStockResponse`)
```json
{
  "success": true,
  "data": {
    "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "warehouseId": "a0000000-0000-0000-0000-000000000001",
    "warehouseCode": "WH_001",
    "totalQty": 100,
    "availableQty": 80,
    "reservedQty": 15,
    "unavailableQty": 5
  },
  "error": null,
  "meta": {
    "requestId": "req-12345",
    "timestamp": "2026-10-27T10:00:00Z"
  }
}
```

### 3.2. Yêu cầu khóa giữ hàng khi Checkout (`CreateReservationRequest`)
```json
{
  "refType": "ORDER",
  "refId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
  "items": [
    {
      "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "quantity": 2,
      "warehouseId": "a0000000-0000-0000-0000-000000000001"
    }
  ],
  "timeoutMinutes": 30
}
```

### 3.3. Phản hồi kết quả giữ hàng (`ReservationResponse`)
```json
{
  "success": true,
  "data": {
    "refType": "ORDER",
    "refId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
    "status": "PENDING",
    "items": [
      {
        "reservationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
        "warehouseId": "a0000000-0000-0000-0000-000000000001",
        "quantity": 2,
        "status": "PENDING",
        "expiresAt": "2026-10-27T10:30:00Z"
      }
    ],
    "expiresAt": "2026-10-27T10:30:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req-12345",
    "timestamp": "2026-10-27T10:00:00Z"
  }
}
```

### 3.4. Phản hồi lỗi khi hết hàng (`INVENTORY_INSUFFICIENT`)
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVENTORY_INSUFFICIENT",
    "message": "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua: SKU 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d yêu cầu 10, hiện khả dụng 3",
    "details": null
  },
  "meta": {
    "requestId": "req-12345",
    "timestamp": "2026-10-27T10:00:00Z"
  }
}
```

---

## 4. Danh mục Domain Events (SRS §2.8)

### 4.1. Sự kiện phát sinh từ Fulfillment Service (Outbound Events)
1. `InventoryCheckedEvent`: Phát khi kiểm tra tính khả dụng của SKU.
2. `InventoryReservedEvent`: Phát khi hàng được khóa giữ thành công cho đơn hàng.
3. `InventoryReleasedEvent`: Phát khi lệnh giữ hàng được mở khóa hoàn trả.
4. `InventoryReservationExpiredEvent`: Phát khi lệnh giữ hàng bị hết hạn 30 phút và tự động hoàn trả.
5. `InventoryCommittedEvent`: Phát khi đơn hàng thanh toán thành công và hàng hóa được chốt xuất kho (OUTBOUND).

### 4.2. Sự kiện tiếp nhận để đồng bộ tồn kho (Inbound Events)
1. `OrderPaidEvent`: Nhận khi đơn hàng thanh toán thành công qua Webhook sandbox/gateway; kích hoạt commit xuất kho thực tế (trừ vĩnh viễn `reserved_qty` và `total_qty`, ghi sổ cái `OUTBOUND`, phát sinh `InventoryCommittedEvent`, đảm bảo Idempotent).
2. `OrderPaymentFailedEvent`: Nhận khi thanh toán thất bại; kích hoạt giải phóng giữ kho (giảm `reserved_qty` hoàn về `available_qty`, ghi sổ cái `RELEASE`, phát sinh `InventoryReleasedEvent`, đảm bảo Idempotent).
3. `OrderCancelledEvent`: Nhận khi đơn hàng bị hủy bỏ; kích hoạt giải phóng giữ kho (giảm `reserved_qty` hoàn về `available_qty`, ghi sổ cái `RELEASE`, phát sinh `InventoryReleasedEvent`, đảm bảo Idempotent).
