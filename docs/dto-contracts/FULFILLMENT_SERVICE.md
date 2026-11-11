# Fulfillment Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/inventory/**`, `/api/v1/warehouses/**`, `/api/v1/shipping/**`, `/api/v1/shipments/**`
>
> **Trạng thái:** `CURRENT` — Đã hoàn thành nghiệp vụ Quản lý tồn kho cấp SKU, Khóa giữ hàng Checkout với Concurrency Control ([KH-S1-2]), Vòng đời tồn kho đấu giá ([KH-S2-1]), và Tách biệt `skuCode` nội bộ qua `seller_skus` khi nhập kho lần đầu ([HG-S2-6])

## 1. Tổng quan kiến trúc & Quy tắc nghiệp vụ

- **Tách biệt Catalog `skuId` và Fulfillment `skuCode` (`seller_skus`) — [HG-S2-6]:**
  - Catalog Service chỉ sinh `skuId` (UUID) và lưu thông tin biến thể/giá gốc; **không** sinh hay lưu `skuCode`.
  - Fulfillment Service sở hữu độc quyền bảng `seller_skus` (`id`, `seller_id`, `sku_id` unique, `sku_code`, `status`) và sequence `fulfillment_sku_code_seq`.
  - Khi gọi `POST /api/v1/inventory/inbound` lần đầu cho một `skuId`, `CatalogSkuClient` tra cứu `GET /api/v1/skus/{id}` để xác thực SKU tồn tại (`404 RESOURCE_NOT_FOUND` nếu thiếu) và xác thực quyền sở hữu Seller (`403 AUTH_FORBIDDEN` nếu khác chủ shop; Admin bỏ qua kiểm tra chủ sở hữu), sau đó `SellerSkuRegistrar` (`REQUIRES_NEW`) cấp phát mã duy nhất dạng `FSKU-XXXXXXXX` (8 chữ số padding 0). Các lần nhập kho tiếp theo cho cùng `skuId` tái sử dụng nguyên bản ghi `seller_skus` và `skuCode` đó.
- **Quản lý tồn kho cấp SKU:** Theo dõi chính xác 4 trạng thái tồn theo từng SKU và kho hàng:
  - `total_qty`: Tổng số lượng thực tế trong kho.
  - `available_qty`: Tồn khả dụng có thể đặt mua = `total_qty - reserved_qty - unavailable_qty`.
  - `reserved_qty`: Số lượng đang bị tạm giữ bởi các đơn Checkout hoặc các phiên Đấu giá.
  - `unavailable_qty`: Hàng lỗi, hỏng hoặc cách ly.
  - *Tuyệt đối không quản lý ở cấp Product cha.*
- **Khóa giữ hàng khi Checkout (Atomic Reservation):**
  - Nhận yêu cầu giữ hàng từ Commerce Service khi người dùng checkout.
  - Áp dụng Pessimistic Locking (`SELECT ... FOR UPDATE`) và Optimistic Locking (`@Version`) trên bảng `inventories`.
  - Sắp xếp items theo `skuId` tăng dần trước khi khóa nhằm triệt tiêu hoàn toàn nguy cơ Deadlock giữa các luồng.
  - Nghiêm cấm bán vượt số lượng tồn (`ALLOW_OVERSELL = false`): lập tức từ chối và trả về mã lỗi `INVENTORY_INSUFFICIENT` nếu không đủ tồn khả dụng.
- **Vòng đời tồn kho Đấu giá chuyên biệt (Auction Inventory Lifecycle - SRS §3.6.1):**
  - **Khóa giữ hàng khi tạo phiên (Auction Reservation):** Khi Seller tạo phiên đấu giá (`AuctionCreated`), tiến hành khóa giữ kho với loại tham chiếu `ref_type = AUCTION`, số lượng giữ mặc định là 1 đơn vị.
  - **Thiết lập TTL phiên đấu giá:** TTL của phiếu giữ kho bao quát toàn bộ thời gian diễn ra phiên đấu giá (`[start_time, end_time]`). Phiên phải thỏa mãn giới hạn thời gian: `AUCTION_MIN_DURATION_MINUTES = 60` (1 giờ) và `AUCTION_MAX_DURATION_HOURS = 168` (7 ngày).
  - **Gia hạn thời gian giữ kho (Anti-sniping Extension):** Khi phiên đấu giá bị kéo dài do Anti-sniping (`ANTI_SNIPING_EXTENSION_MINUTES = 5`), hệ thống tiếp nhận API/sự kiện `AuctionExtendedEvent` để tự động kéo dài `expires_at` của phiếu giữ kho tương ứng, ngăn chặn việc kho bị giải phóng nhầm khi phiên vẫn đang mở.
  - **Hủy phiên & Giải phóng tồn kho (Release Inventory):** Khi phiên bị Seller/Admin hủy (`AuctionCancelled`) hoặc kết thúc mà không có người đặt giá (`AuctionFailed`), tức thời giải phóng số lượng giữ trước, hoàn trả về `available_qty` trong kho và ghi nhận sổ cái `inventory_ledger` với loại giao dịch `RELEASE` (đảm bảo tính Idempotent).
  - **Chuyển tiếp trạng thái giữ kho sang Đơn hàng (Transition to Order):** Khi chốt phiên thành công và xác định người thắng cuộc (`AuctionSettled`), chuyển đổi mã tham chiếu từ `auction_id` (`AUCTION`) sang `order_id` (`ORDER`) mà không cần nhả kho rồi giữ lại từ đầu. Cơ chế này triệt tiêu hoàn toàn hiện tượng "Reserve kép" (Double Reservation) và ngăn chặn người mua Buy Now cướp mất sản phẩm của Winner, đồng thời thiết lập thời hạn thanh toán cho Winner (`AUCTION_PAYMENT_DEADLINE_MINUTES = 1440` - 24 giờ).
- **Cơ chế Hết hạn & Tự động hoàn kho (Auto-release):**
  - Phiếu giữ hàng Checkout thông thường tự động hết hạn sau 30 phút (`INVENTORY_RESERVATION_TIMEOUT_MINUTES = 30`).
  - Phiếu giữ hàng Đấu giá hết hạn theo `end_time` (hoặc sau khi được gia hạn bởi Anti-sniping).
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
| `POST` | `/api/v1/inventory/auctions/{auctionId}/reserve` | `Authenticated` | Khóa giữ hàng khi Seller tạo phiên đấu giá (Auction Reservation, TTL từ startTime đến endTime) |
| `POST` | `/api/v1/inventory/auctions/{auctionId}/extend` | `Authenticated` | Gia hạn thời gian giữ kho khi phiên bị kéo dài do Anti-sniping (+5 phút hoặc mốc mới) |
| `POST` | `/api/v1/inventory/auctions/{auctionId}/release` | `Authenticated` | Tức thời giải phóng hàng giữ kho khi phiên bị hủy hoặc thất bại không có bid (RELEASE - Idempotent) |
| `POST` | `/api/v1/inventory/auctions/{auctionId}/transition-to-order` | `Authenticated` | Chuyển tiếp phiếu giữ kho sang Đơn hàng của người thắng cuộc mà không nhả kho (Transition to Order) |
| `POST` | `/api/v1/inventory/reservations/{id}/confirm` | `Authenticated` | Xác nhận đơn hàng thành công theo ID phiếu giữ, trừ tồn kho vật lý (OUTBOUND - Idempotent) |
| `POST` | `/api/v1/inventory/orders/{orderId}/commit` | `Authenticated` | Xác nhận trừ kho thực tế cho toàn bộ SKU của đơn hàng khi thanh toán thành công (OUTBOUND - Idempotent) |
| `POST` | `/api/v1/inventory/reservations/{id}/release` | `Authenticated` | Giải phóng phiếu giữ hàng theo ID khi hủy đơn hoặc lỗi thanh toán (RELEASE - Idempotent) |
| `POST` | `/api/v1/inventory/release` | `Authenticated` | Giải phóng tất cả phiếu giữ hàng của một đơn hàng (theo `refType`, `refId` - Idempotent) |
| `POST` | `/api/v1/inventory/orders/{orderId}/release` | `Authenticated` | Giải phóng tồn kho giữ tạm theo mã đơn hàng khi hủy đơn hoặc lỗi thanh toán (RELEASE - Idempotent) |
| `POST` | `/api/v1/inventory/events/order-paid` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderPaid`, trừ vĩnh viễn reserved_qty và total_qty (Idempotent) |
| `POST` | `/api/v1/inventory/events/order-payment-failed` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderPaymentFailed`, hoàn trả reserved_qty về available_qty (Idempotent) |
| `POST` | `/api/v1/inventory/events/order-cancelled` | `Authenticated` | Webhook/Sync: Tiếp nhận sự kiện `OrderCancelled`, hoàn trả reserved_qty về available_qty (Idempotent) |
| `POST` | `/api/v1/inventory/inbound` | `ADMIN`, `SELLER` | Nhập kho hàng hóa cho Catalog `skuId`, xác thực chủ sở hữu với Catalog, cấp phát/tái sử dụng `skuCode` trong `seller_skus`, tăng `total_qty` và ghi sổ cái `INBOUND` |
| `POST` | `/api/v1/inventory/adjust` | `ADMIN` | Điều chỉnh kiểm kê tồn kho thực tế, ghi sổ cái `ADJUSTMENT` |
| `GET` | `/api/v1/inventory/ledger` | `ADMIN`, `SUPPORT` | Tra cứu lịch sử Sổ cái tồn kho bất biến (phân trang `@ParameterObject`, lọc theo `skuId`/`refId`) |
| `GET` | `/api/v1/inventory/skus` | `ADMIN`, `SELLER` | Danh sách `seller_skus` tổng hợp tồn kho theo các kho (lọc `keyword`, `status`, `sellerId`; Seller tự giới hạn theo bản thân) |
| `GET` | `/api/v1/inventory/skus/{id}` | `ADMIN`, `SELLER` | Chi tiết `seller_skus` theo `id` hoặc `skuId` kèm phân bổ tồn kho từng kho hàng |
| `POST` | `/api/v1/inventory/reservations/expire-sweeper` | `ADMIN`, `SUPPORT` | Kích hoạt quét giải phóng phiếu quá hạn thủ công (QA / Dev) |

---

## 3. Cấu trúc Payload & Data Transfer Objects (DTO)

### 3.1. Phản hồi tồn kho SKU (`InventoryStockResponse`)
```json
{
  "success": true,
  "data": {
    "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "sellerSkuId": "a73088a4-5007-4c17-b278-4fdad9679cf8",
    "skuCode": "FSKU-00000047",
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

### 3.3. Yêu cầu khóa giữ hàng cho phiên Đấu giá (`ReserveAuctionInventoryRequest`)
```json
{
  "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "warehouseId": "a0000000-0000-0000-0000-000000000001",
  "startTime": "2026-11-05T08:00:00Z",
  "endTime": "2026-11-06T08:00:00Z",
  "quantity": 1
}
```

### 3.4. Yêu cầu gia hạn giữ kho Anti-sniping (`ExtendAuctionReservationRequest`)
```json
{
  "extensionMinutes": 5,
  "newEndTime": "2026-11-06T08:05:00Z"
}
```

### 3.5. Yêu cầu chuyển tiếp giữ kho sang Đơn hàng Winner (`TransitionAuctionToOrderRequest`)
```json
{
  "orderId": "e22e6b12-984e-4f01-9dc5-d143c7b286f7",
  "paymentDeadlineMinutes": 1440,
  "paymentDeadline": "2026-11-07T08:05:00Z"
}
```

### 3.6. Phản hồi kết quả giữ hàng (`ReservationResponse`)
```json
{
  "success": true,
  "data": {
    "refType": "AUCTION",
    "refId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
    "status": "PENDING",
    "items": [
      {
        "reservationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "skuId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
        "warehouseId": "a0000000-0000-0000-0000-000000000001",
        "quantity": 1,
        "status": "PENDING",
        "expiresAt": "2026-11-06T08:05:00Z"
      }
    ],
    "expiresAt": "2026-11-06T08:05:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req-12345",
    "timestamp": "2026-11-04T10:00:00Z"
  }
}
```

### 3.7. Phản hồi lỗi khi hết hàng (`INVENTORY_INSUFFICIENT`)
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVENTORY_INSUFFICIENT",
    "message": "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua: SKU 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d yêu cầu 1, hiện khả dụng 0",
    "details": null
  },
  "meta": {
    "requestId": "req-12345",
    "timestamp": "2026-10-27T10:00:00Z"
  }
}
```

### 3.8. Yêu cầu nhập kho & cấp phát `skuCode` (`StockInboundRequest`)
```json
{
  "skuId": "26579df2-d4db-4b35-ad06-4f491b122a9e",
  "warehouseId": null,
  "quantity": 10,
  "refId": null,
  "unitCost": 150000
}
```

- `skuId` (`UUID`, bắt buộc): Mã biến thể thuộc Catalog. Fulfillment gọi `GET /api/v1/skus/{id}` tới Catalog; nếu không tồn tại trả `404 RESOURCE_NOT_FOUND`, nếu Seller không phải chủ sở hữu trả `403 AUTH_FORBIDDEN` (`ADMIN` bỏ qua kiểm tra chủ sở hữu).
- `warehouseId` (`UUID`, tùy chọn): Mặc định chọn kho `WH_001` khi truyền `null`.
- `quantity` (`integer`, bắt buộc, `>= 1`), `unitCost` (`number`, tùy chọn, `>= 0`), `refId` (`UUID`, tùy chọn).

### 3.9. Danh sách & chi tiết SKU kho (`SellerSkuResponse` / `SellerSkuDetailResponse`)
```json
{
  "success": true,
  "data": {
    "id": "a73088a4-5007-4c17-b278-4fdad9679cf8",
    "sellerSkuId": "a73088a4-5007-4c17-b278-4fdad9679cf8",
    "sellerId": "8a8a1950-f5f0-4fd8-8001-ea1a39ccd3f9",
    "skuId": "26579df2-d4db-4b35-ad06-4f491b122a9e",
    "skuCode": "FSKU-00000047",
    "status": "ACTIVE",
    "totalQty": 15,
    "availableQty": 15,
    "reservedQty": 0,
    "unavailableQty": 0,
    "warehouses": [
      {
        "skuId": "26579df2-d4db-4b35-ad06-4f491b122a9e",
        "sellerSkuId": "a73088a4-5007-4c17-b278-4fdad9679cf8",
        "skuCode": "FSKU-00000047",
        "warehouseId": "a0000000-0000-0000-0000-000000000001",
        "warehouseCode": "WH_001",
        "totalQty": 15,
        "availableQty": 15,
        "reservedQty": 0,
        "unavailableQty": 0
      }
    ],
    "createdAt": "2026-11-11T12:53:55Z",
    "updatedAt": "2026-11-11T12:53:58Z"
  }
}
```

- Với `GET /api/v1/inventory/skus`, từng phần tử `SellerSkuResponse` thay `warehouses` bằng trường `warehouseCount` (số lượng kho đang lưu trữ SKU này) và bọc `ApiResponse<List<SellerSkuResponse>>` kèm `meta.pagination`. Các trường sort hợp lệ gồm `id`, `sellerId`, `skuId`, `skuCode`, `status`, `createdAt`, `updatedAt`.

---

## 4. Danh mục Domain Events (SRS §2.8)

### 4.1. Sự kiện phát sinh từ Fulfillment Service (Outbound Events)
1. `InventoryCheckedEvent`: Phát khi kiểm tra tính khả dụng của SKU.
2. `InventoryReservedEvent`: Phát khi hàng được khóa giữ thành công cho đơn hàng hoặc phiên đấu giá.
3. `InventoryReleasedEvent`: Phát khi lệnh giữ hàng được mở khóa hoàn trả về tồn kho khả dụng.
4. `InventoryReservationExpiredEvent`: Phát khi lệnh giữ hàng bị hết hạn TTL và tự động hoàn trả.
5. `InventoryCommittedEvent`: Phát khi đơn hàng thanh toán thành công và hàng hóa được chốt xuất kho (OUTBOUND).

### 4.2. Sự kiện tiếp nhận để đồng bộ tồn kho (Inbound Events)
1. `OrderPaidEvent`: Nhận khi đơn hàng thanh toán thành công; kích hoạt commit xuất kho thực tế (trừ vĩnh viễn `reserved_qty` và `total_qty`, ghi sổ cái `OUTBOUND`, phát sinh `InventoryCommittedEvent`, đảm bảo Idempotent).
2. `OrderPaymentFailedEvent`: Nhận khi thanh toán thất bại; kích hoạt giải phóng giữ kho (giảm `reserved_qty` hoàn về `available_qty`, ghi sổ cái `RELEASE`, phát sinh `InventoryReleasedEvent`, đảm bảo Idempotent).
3. `OrderCancelledEvent`: Nhận khi đơn hàng bị hủy bỏ; kích hoạt giải phóng giữ kho (giảm `reserved_qty` hoàn về `available_qty`, ghi sổ cái `RELEASE`, phát sinh `InventoryReleasedEvent`, đảm bảo Idempotent).
4. `AuctionCreatedEvent`: Nhận khi Seller tạo phiên đấu giá; kích hoạt khóa giữ kho với `refType = AUCTION`, thời hạn TTL bao quát toàn bộ phiên (`[startTime, endTime]`), nghiêm cấm bán vượt tồn (`ALLOW_OVERSELL = false`).
5. `AuctionExtendedEvent`: Nhận khi phiên đấu giá kích hoạt Anti-sniping; tự động kéo dài thời hạn TTL của phiếu giữ kho thêm 5 phút hoặc mốc `newEndTime`, tránh việc hàng bị giải phóng nhầm khi phiên vẫn đang mở.
6. `AuctionCancelledEvent`: Nhận khi Seller hoặc Admin hủy phiên đấu giá; tức thời giải phóng phiếu giữ kho, hoàn trả về `available_qty`, ghi sổ cái `RELEASE` (Idempotent).
7. `AuctionFailedEvent`: Nhận khi phiên đấu giá kết thúc mà không có người đặt giá; tức thời giải phóng phiếu giữ kho, hoàn trả về `available_qty`, ghi sổ cái `RELEASE` (Idempotent).
8. `AuctionSettledEvent`: Nhận khi phiên đấu giá chốt người thắng cuộc thành công và khởi tạo đơn hàng; kích hoạt chuyển tiếp giữ hàng sang đơn hàng (`Transition to Order`), cập nhật `refType = ORDER`, `refId = orderId` và gia hạn thời hạn thanh toán 24 giờ cho người thắng mà không nhả kho rồi giữ lại từ đầu, triệt tiêu nguy cơ Double Reservation và ngăn chặn cạnh tranh từ luồng Mua ngay (Buy Now).
