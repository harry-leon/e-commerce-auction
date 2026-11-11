# Tài liệu bàn giao Inventory & Warehouse (Vinh Khang → Linh Đang & Thương)

## 1. Phạm vi đã hoàn thiện

Phần Inventory & Warehouse (`fulfillment-service`, kèm tra cứu `skuCode` ở `catalog-service`) đã được hoàn thiện để Frontend (Đang) tích hợp màn hình quản lý kho/nhập kho/điều chỉnh/sổ cái và phối hợp luồng thanh toán với Commerce Service (Thương):

- **Quản lý danh mục kho hàng (`/api/v1/warehouses/**`)**: liệt kê có phân trang và lọc (`activeOnly`, `type`, `q`), xem chi tiết theo ID/mã kho, xem tồn kho tại một kho, tạo/cập nhật kho cho Admin.
- **Quản trị tồn kho & lịch sử biến động (`/api/v1/inventory/**`)**: đọc tồn kho phân trang theo SKU/kho, đọc theo `skuCode`, đọc hàng loạt (`POST /stocks/batch`), nhập kho (`POST /inbound`), điều chỉnh kiểm kê (`POST /adjust`), tra cứu sổ cái bất biến đa bộ lọc (`GET /ledger`) và đối soát tồn kho (`GET /reconcile`).
- **Phân quyền & phạm vi dữ liệu Seller**: Seller chỉ đọc/nhập/điều chỉnh/tra sổ cái/đối soát được cho các SKU thuộc quyền sở hữu của chính mình (xác minh trực tiếp qua `catalog-service`, không tin `sellerId` client gửi lên).
- **Chống lặp thao tác (Idempotency)**: hỗ trợ header `Idempotency-Key` (hoặc trường `idempotencyKey` trong body) và `refId` cho nhập kho/điều chỉnh; kiểm tra khóa theo `(refType, refId)` cho cả `ORDER` và `AUCTION` khi reserve/confirm/release.
- **Typed API Client sẵn sàng cho Frontend**: `frontend-web/src/api/inventory.ts` (đã có unit test `frontend-web/src/api/inventory.test.ts`).

---

## 2. Dữ liệu kho hàng & tồn kho mẫu

### 2.1. Kho hàng được Flyway seed tự động
Khi khởi động `fulfillment-service`, migration `V4` và `V5__warehouse_inventory_enhancements.sql` bảo đảm sẵn 4 kho:

| Mã kho (`code`) | ID tham chiếu | Loại (`type`) | Ghi chú |
|---|---|---|---|
| `WH_001` | Tra cứu động qua `GET /api/v1/warehouses/code/WH_001` | `STANDARD` | Kho mặc định; nếu `warehouseId` bỏ trống, backend tự chọn `WH_001`. |
| `WH_002` | `a0000000-0000-0000-0000-000000000002` | `STANDARD` | Kho Miền Bắc Nexus (Hanoi Hub) |
| `WH_003` | `a0000000-0000-0000-0000-000000000003` | `STANDARD` | Kho Miền Trung Nexus (Da Nang Hub) |
| `WH_RETURN` | `a0000000-0000-0000-0000-000000000004` | `RETURN` | Kho Hàng Hoàn Trả Nexus (Return Center) |

### 2.2. Script khởi tạo tồn kho mẫu (`scripts/seed-sample-inventory.mjs`)
- Xem trước payload mà không cần service đang chạy:
  ```bash
  node scripts/seed-sample-inventory.mjs --dry-run
  ```
- Nạp tồn kho mẫu idempotent cho tất cả SKU của 1 Seller hoặc danh sách `skuCode` qua API Gateway (dùng `Idempotency-Key` cố định theo từng lô nên chạy lại không làm tăng kho gấp đôi, sau đó tự gọi `/inventory/reconcile` kiểm tra `matched === true`):
  ```bash
  node scripts/seed-sample-inventory.mjs --gateway http://localhost:8080/api/v1 --seller-id <SELLER_UUID> --token <JWT>
  node scripts/seed-sample-inventory.mjs --sku-codes NX-KB-01,NX-MS-01 --token <JWT>
  ```

---

## 3. Hướng dẫn Frontend (Đang) tích hợp thay thế mock

File `frontend-web/src/api/inventory.ts` đã bọc đầy đủ kiểu TypeScript khớp DTO backend và envelope `ApiResponse`:

- `listWarehouses(params)` → `GET /api/v1/warehouses`
- `getWarehouseByCode(code)` → `GET /api/v1/warehouses/code/{code}`
- `createWarehouse(request)` / `updateWarehouse(warehouseId, request)` → `POST/PUT /api/v1/warehouses` (chỉ Admin)
- `listInventory(params)` → `GET /api/v1/inventory` (Seller tự động chỉ nhận các SKU của mình)
- `listWarehouseInventory(warehouseId, params)` → `GET /api/v1/warehouses/{warehouseId}/inventory`
- `getStockBySku(skuId, warehouseId?)` / `getStockBySkuCode(skuCode, warehouseId?)` / `getBatchStock(request)`
- `inboundStock(request, idempotencyKey?)` → `POST /api/v1/inventory/inbound`
- `adjustStock(request, idempotencyKey?)` → `POST /api/v1/inventory/adjust`
- `getInventoryLedger(params)` → `GET /api/v1/inventory/ledger`
- `reconcileInventory(params)` → `GET /api/v1/inventory/reconcile`
- `createInventoryIdempotencyKey(prefix)` → tạo khóa chống lặp; **lưu ý**: sinh 1 key cho mỗi lần mở modal/lần bấm gửi và **giữ nguyên key đó nếu bấm Thử lại** khi gặp lỗi mạng.

### Lưu ý khi chuyển `SellerPages.tsx` từ mock sang API thật:
1. Payload `/inventory/inbound` dùng trường `reason` (không phải `note`), và hỗ trợ truyền `skuId` hoặc `skuCode` (`skuCode` sẽ được `fulfillment-service` phân giải qua `catalog-service`).
2. Payload `/inventory/adjust` hỗ trợ đặt tổng tồn trực tiếp `targetTotalQty` hoặc điều chỉnh tương đối `deltaQty`, `unavailableDeltaQty` (bắt buộc `reason`). Backend chặn mọi thao tác làm âm tồn kho hoặc làm `totalQty < reservedQty + unavailableQty` (trả `409 INVENTORY_INSUFFICIENT`).
3. Toàn bộ danh sách phân trang trả mảng trong `response.data` và siêu dữ liệu trang trong `response.meta.pagination` (`listInventory`, `listWarehouses`, `getInventoryLedger` trả `PaginatedResult<T>` với `{ items, pagination, meta }`).

---

## 4. Ma trận phân quyền (RBAC)

| Nhóm API | Công khai | `BUYER` | `SELLER` | `SUPPORT` | `ADMIN` / `INVENTORY.MANAGE` | Service Token (`INVENTORY.ORDER` / `INVENTORY.AUCTION`) |
|---|---|---|---|---|---|---|
| `GET /inventory/sku/**`, `POST /inventory/check`, `POST /inventory/stocks/batch` | Cho phép | Cho phép | Cho phép | Cho phép | Cho phép | Cho phép |
| `GET /warehouses`, `GET /warehouses/{id}`, `GET /warehouses/code/{code}` | 401 | 403 | Cho phép | Cho phép | Cho phép | - |
| `POST /warehouses`, `PUT /warehouses/{id}` | 401 | 403 | 403 | 403 | Cho phép | - |
| `GET /inventory`, `GET /warehouses/{id}/inventory`, `GET /inventory/ledger`, `GET /inventory/reconcile` | 401 | 403 | Chỉ SKU của chính Seller | Đọc toàn hệ thống | Đọc toàn hệ thống | - |
| `POST /inventory/inbound`, `POST /inventory/adjust` | 401 | 403 | Chỉ SKU của chính Seller | 403 | Cho phép | - |
| `POST /inventory/reservations/expire-sweeper` | 401 | 403 | 403 | Cho phép | Cho phép | - |
| `POST /inventory/reserve`, `/orders/**`, `/events/**`, `/reservations/*/confirm\|release` | 401 | 403 | 403 | 403 | 403 | Chỉ `nexus-commerce-service` + `INVENTORY.ORDER` |
| `POST /inventory/auctions/**` | 401 | 403 | 403 | 403 | 403 | Chỉ `nexus-auction-service` + `INVENTORY.AUCTION` |

---

## 5. Phối hợp Commerce Service (Thương) & Kiểm tra bất biến

- **Reserve lặp lại (`ORDER` / `AUCTION`)**: cùng `refId` với đúng SKU/kho/số lượng và trạng thái `PENDING` sẽ trả về phiếu hiện tại (không trừ tồn khả dụng hai lần); khác SKU/số lượng/kho trả `ORDER_IDEMPOTENCY_CONFLICT`; đã `CONFIRMED` trả `INVENTORY_RESERVATION_ALREADY_COMMITTED`; đã `RELEASED` hoặc `EXPIRED` trả `INVENTORY_RESERVATION_EXPIRED`.
- **Commit khi thanh toán (`commitByOrder` / `confirmReservation` / `order-paid`)**:
  - Phiếu `PENDING`: trừ `reserved_qty` và `total_qty`, chuyển trạng thái `CONFIRMED` và ghi 1 bản ghi `OUTBOUND`.
  - Nếu phiếu đã chuyển `RELEASED` hoặc `EXPIRED` do thanh toán lỗi/hủy/hết hạn trước đó → từ chối bằng `INVENTORY_RESERVATION_EXPIRED` (Commerce xử lý hoàn tiền thay vì xuất kho sai). Nếu đã `CONFIRMED` → idempotent trả về phiếu hiện có, không ghi `OUTBOUND` lần 2.
- **Release khi thanh toán thất bại/hết hạn (`releaseByOrder` / `order-payment-failed` / `autoReleaseExpiredReservations`)**:
  - Sweeper dùng `findExpiredIds` rồi khóa dòng `findByIdForUpdate` và kiểm tra lại `PENDING` + `expiresAt <= now` sau khi lấy khóa, tránh ghi `RELEASE` đè lên phiếu vừa `CONFIRMED` trong tranh chấp đa luồng PostgreSQL.
- **Công thức đối soát (`GET /api/v1/inventory/reconcile`)**:
  - `ledgerTotalQty = Σ abs(INBOUND) - Σ abs(OUTBOUND) + Σ signed(ADJUSTMENT)`
  - `ledgerReservedQty = Σ abs(RESERVE) - Σ abs(RELEASE) - Σ abs(OUTBOUND)`
  - `matched = nonNegative && totalQty == ledgerTotalQty && reservedQty == ledgerReservedQty && reservedQty == activeReservationQty`.
