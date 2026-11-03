# Commerce Service DTO Contract

> **Gateway paths:** `/api/v1/cart/**`, `/api/v1/checkout/**`, `/api/v1/orders/**`, `/api/v1/payments/**`
>
> **CURRENT:** Cart, checkout COD/VNPAY, Order Management, Payment/IPN và notification outbox
>
> **Baseline Sprint 2:** [TT-S2-1 Contract register](../TT-S2-1_ORDER_PAYMENT_BASELINE.md)
>
> **Success wrapper:** `ApiResponse<T>`; xem [COMMON.md](COMMON.md)

## 0. DTO inventory

| DTO trong source | Vai trò | Có được controller expose |
|---|---|---|
| `AddToCartRequest`, `UpdateCartItemRequest`, `MergeCartRequest` | Request body Cart | Có |
| `CartResponse`, `CartItemResponse` | Public response Cart | Có |
| `SkuInfo` | DTO nội bộ Commerce nhận từ Catalog | Không trực tiếp; ánh xạ từ `SkuSnapshot` |
| `CheckoutPreviewRequest`, `CreateOrderRequest` | Request checkout | Có; mục 4 |
| `OrderSummaryResponse`, `OrderDetailResponse`, `UpdateOrderStatusRequest`, `CancelOrderRequest` | Quản lý đơn | Có; mục 6 |
| `PaymentResponse` | Tạo/poll attempt VNPAY | Có; mục 5 |

## 1. Endpoint matrix hiện có

| Method | Path | Request | `data` khi success | HTTP | Xác thực |
|---|---|---|---|---|---|
| `GET` | `/cart` | — | `CartResponse` | `200` | JWT hợp lệ |
| `POST` | `/cart/items` | `AddToCartRequest` | `CartResponse` | `200` | JWT hợp lệ |
| `PUT` | `/cart/items/{itemId}` | `UpdateCartItemRequest` | `CartResponse` | `200` | JWT hợp lệ |
| `DELETE` | `/cart/items/{itemId}` | — | `CartResponse` | `200` | JWT hợp lệ |
| `DELETE` | `/cart/items` | — | `null` | `200` | JWT hợp lệ |
| `POST` | `/cart/merge` | `MergeCartRequest` | `CartResponse` | `200` | JWT hợp lệ |

Chủ giỏ được lấy từ `sub` của JWT đã xác minh. Header `X-User-Id` có thể được Gateway gửi kèm nhưng controller không dùng header đó để quyết định quyền sở hữu.

## 2. Request DTO

### `AddToCartRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `skuId` | `string` UUID | Có | Không null; Catalog phải tìm thấy SKU hợp lệ |
| `quantity` | `number` nguyên | Có | Tối thiểu `1` |

```json
{
  "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
  "quantity": 2
}
```

### `UpdateCartItemRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `quantity` | `number` nguyên | Có | Tối thiểu `1` |

Gửi `0` không có nghĩa xóa; dùng `DELETE /cart/items/{itemId}`.

```json
{
  "quantity": 3
}
```

### `MergeCartRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `items` | `AddToCartRequest[]` | Có | Không được rỗng; mỗi phần tử được validate theo `AddToCartRequest` |

Nếu SKU đã có trong giỏ active, service cộng dồn quantity theo quy tắc hiện tại.

```json
{
  "items": [
    {
      "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
      "quantity": 2
    },
    {
      "skuId": "2f78db6a-e71d-4ca7-8a41-43e9870bf55f",
      "quantity": 1
    }
  ]
}
```

## 3. Response DTO

### `CartResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `cartId` | `string` UUID | Không | ID giỏ |
| `userId` | `string` UUID | Không | Chủ giỏ |
| `status` | `CartStatus` | Không | `ACTIVE`, `MERGED`, `EXPIRED`, `CONVERTED` |
| `items` | `CartItemResponse[]` | Không | Danh sách dòng hàng; rỗng dùng `[]` |
| `totalItems` | `number` nguyên | Không | Số dòng SKU khác nhau |
| `totalQuantity` | `number` nguyên | Không | Tổng quantity của mọi dòng |
| `subtotal` | `number` | Không | Tổng `unitPrice * quantity`, scale 2 |
| `discountAmount` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `shippingFee` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `taxAmount` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `totalAmount` | `number` | Không | Hiện bằng subtotal sau các khoản ước tính, scale 2 |
| `expiresAt` | `string` | Có | ISO-8601 Instant |

### `CartItemResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | Cart item ID |
| `skuId` | `string` UUID | Không | SKU ID |
| `skuCode` | `string` | Không | Nếu không lấy được Catalog, mapper dùng `N/A` |
| `productName` | `string` | Không | Nếu không lấy được Catalog, mapper dùng `Unknown Product` |
| `thumbnailUrl` | `string` | Có | URL ảnh đầu tiên |
| `unitPrice` | `number` | Không | Ưu tiên giá live từ Catalog, fallback giá lưu trong cart |
| `quantity` | `number` nguyên | Không | Số lượng |
| `stockAvailable` | `number` nguyên | Có | Hiện thường `null` vì Catalog không sở hữu tồn kho |
| `isAvailable` | `boolean` | Không | SKU/product phải active; stock `null` không tự làm item unavailable |
| `subtotal` | `number` | Không | `unitPrice * quantity`, scale 2 |

Không hiển thị `stockAvailable: null` thành `0`. Đây là trạng thái “chưa xác minh tồn kho từ Fulfillment”.

Ví dụ:

```json
{
  "success": true,
  "data": {
    "cartId": "548fb87a-3182-4c8d-8539-b8248cf8e4bc",
    "userId": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
    "status": "ACTIVE",
    "items": [
      {
        "id": "8500a48b-b5de-429b-89bb-67d50157e95b",
        "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
        "skuCode": "NEXUS-BLACK-M",
        "productName": "Nexus T-Shirt",
        "thumbnailUrl": null,
        "unitPrice": 250000.00,
        "quantity": 2,
        "stockAvailable": null,
        "isAvailable": true,
        "subtotal": 500000.00
      }
    ],
    "totalItems": 1,
    "totalQuantity": 2,
    "subtotal": 500000.00,
    "discountAmount": 0.00,
    "shippingFee": 0.00,
    "taxAmount": 0.00,
    "totalAmount": 500000.00,
    "expiresAt": "2026-11-01T08:30:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-25T08:30:00.000Z"
  }
}
```

`DELETE /cart/items` là trường hợp success có `data: null`:

```json
{
  "success": true,
  "data": null,
  "error": null,
  "meta": {
    "requestId": "req_763e0ef4",
    "timestamp": "2026-10-25T08:50:00.000Z"
  }
}
```

Mọi endpoint thêm, sửa, xóa một item và merge đều trả lại toàn bộ `CartResponse` trong `data`, cùng shape với ví dụ trên. Lỗi validation và lỗi nghiệp vụ dùng JSON envelope trong [COMMON.md](COMMON.md#2-errordetail-và-validationerrordetail).

## 4. Checkout & tạo đơn — TT-S1-2

Hai endpoint sau đã có backend, đều trả `ApiResponse` v2. Buyer lấy từ subject UUID trong JWT đã xác minh; `X-User-Id` không được sử dụng.

| Method | Path | Quyền | HTTP | Request / data |
|---|---|---|---|---|
| POST | `/api/v1/orders/checkout/preview` | `CHECKOUT.START` | 200 | `CheckoutPreviewRequest` / `CheckoutPreviewResponse` |
| POST | `/api/v1/orders` | `ORDER.CREATE` | 201 kể cả replay | `CreateOrderRequest` / `CreateOrderResponse` |

Preview nhận `shippingAddress` bắt buộc (tối đa 500 ký tự), `itemIds` tùy chọn và `voucherCode` tùy chọn. Tạo đơn nhận:

```json
{
  "shippingAddress": "123 Lê Lợi, TP. Hồ Chí Minh",
  "recipientName": "Nguyễn Văn A",
  "recipientPhone": "0901234567",
  "paymentMethod": "COD",
  "sourceType": "REGULAR",
  "selectedItemIds": ["2cc1f09f-c128-45ae-97d8-f8c569120387"]
}
```

Header `Idempotency-Key` bắt buộc là UUID chuẩn; cùng key + buyer + nội dung trả đơn đã lưu trước khi đọc lại giỏ. Khác nội dung hoặc buyer trả `409 ORDER_IDEMPOTENCY_CONFLICT`. Thứ tự item IDs không ảnh hưởng fingerprint, `sourceType` bỏ trống tương đương `REGULAR`. Key được lưu lâu dài trong DB, không chỉ cache RAM. Khóa PostgreSQL theo key rồi buyer ngăn request đồng thời tạo trùng đơn/reservation và kiểm soát giới hạn đơn/ngày (ngày tại `Asia/Ho_Chi_Minh`).

`recipientName` tối đa 255 ký tự; số điện thoại 8–15 chữ số với dấu `+` tùy chọn. Item IDs là UUID, nếu có phải từ 1–50 phần tử, không null/trùng và đều thuộc giỏ ACTIVE còn hạn của buyer. Bỏ danh sách nghĩa là chọn toàn bộ giỏ. Chặn giỏ rỗng, SKU ngừng bán, quantity sai, tự mua và đơn chứa nhiều shop vì schema Order chỉ có một seller.

Backend tải giá/tên/mã/seller mới nhất từ Catalog, tính `BigDecimal` scale 2 `HALF_UP`, lưu snapshot vào `orders`/`order_items`. Preview trả `subtotal`, `discountAmount`, `shippingFee`, `taxAmount`, `totalAmount`, `appliedVoucher: null`, `items`, `isOrderable`, `warnings`. Mỗi item có `skuId`, `sellerId`, `productName`, `skuCode`, `unitPrice`, `cartUnitPrice`, `quantity`, `itemTotal`. Giá khác giỏ tạo cảnh báo; giá lúc tạo đơn luôn lấy lại từ Catalog. Preview thiếu kho trả `isOrderable: false`; bước tạo đơn dùng reserve nguyên tử làm kiểm tra cuối.

Kết quả tạo đơn có `id`, `orderNo`, `status: PENDING_PAYMENT`, `subtotal`, `shippingFee`, `discount`, `total`, `paymentMethod: COD|VNPAY`, `paymentUrl` (COD null, VNPAY là URL sandbox đã ký). Sau reserve chỉ xóa items đã mua; giỏ hết item chuyển `CONVERTED`. Đơn/items/giỏ và Payment VNPAY commit cùng transaction.

Phí ship tạm dùng `app.commerce.order.shipping-fee` (mặc định 0), VAT đã gồm trong giá nên `taxAmount = 0`. Voucher khác rỗng trả `422 CHECKOUT_VOUCHER_UNSUPPORTED`; CARD trả `422 PAYMENT_METHOD_UNSUPPORTED`, source auction trả `422 CHECKOUT_SOURCE_UNSUPPORTED`. TT-S1-3 bổ sung VNPAY sandbox, yêu cầu VND nguyên dương và cấu hình merchant/service account; thiếu cấu hình trả 503. Chưa tính ship theo cân nặng/khoảng cách.

Commerce gọi Fulfillment qua `FULFILLMENT_SERVICE_URL` và chuyển tiếp JWT cho reserve/release. Reservation hết hạn sau 15 phút. Khi reserve timeout hoặc transaction rollback sau reserve, callback gọi `/inventory/release` theo orderId; nếu release lỗi mạng/process chết, scheduler Fulfillment hoàn kho khi TTL hết. Chưa có outbox retry bền vững. Nếu kết quả commit không xác định, chỉ ghi log đối soát, không release ngay vì đơn có thể đã commit.

Các lỗi đáng chú ý: `409 INVENTORY_INSUFFICIENT`, `409 CART_SKU_NOT_AVAILABLE`, `404 CART_ITEM_NOT_FOUND`, `422 CHECKOUT_EXPIRED`, `422 CHECKOUT_MULTIPLE_SELLERS`, `422 ORDER_DAILY_LIMIT_EXCEEDED`, `422 CHECKOUT_INVALID_IDEMPOTENCY_KEY`, `502 CATALOG_INVALID_RESPONSE`/`INVENTORY_UNAVAILABLE`. Thiếu header trả 400; DTO không hợp lệ trả 422.

Flyway V4 bổ sung thông tin người nhận, phương thức và fingerprint; V5 thêm payment snapshot, reservation IDs và outbox kho sau IPN. Cần triển khai Catalog có `sellerId` trước Commerce mới. Route đọc/hủy/cập nhật đơn đã có backend; mock COD/demo phía FE còn tồn tại, VNPAY không fallback mock.

## 5. VNPAY sandbox — TT-S1-3

| Method | Path | Xác thực | Kết quả |
|---|---|---|---|
| POST | `/api/v1/payments` | JWT + ORDER.CREATE, đúng buyer | Envelope PaymentResponse; body chỉ nhận orderId |
| GET | `/api/v1/payments/{paymentId}` | JWT, đúng buyer | Envelope PaymentResponse, trạng thái DB |
| GET | `/api/v1/payments/vnpay/ipn` | HMAC-SHA512, không JWT | JSON RspCode/Message theo protocol VNPAY |

`PaymentResponse`: `paymentId`, `orderId`, `status` (PENDING/SUCCESS/FAILED/CANCELLED), `amount`, `currency: VND`, `paymentUrl`, `expiresAt`. URL dùng paymentId làm TxnRef, amount DB × 100, hạn theo cửa sổ giữ kho của Order.

IPN xác minh chữ ký/merchant rồi đối chiếu Payment→Order và amount cả hai bảng; khóa DB chặn callback trùng. Thành công cập nhật SUCCESS/PAID, thất bại xác định cập nhật FAILED/CANCELLED; terminal lặp trả 02, không tạo history/outbox lần hai. Kết quả bất thường cần đối soát.

Outbox sau IPN commit cùng Payment và worker retry confirm/release bằng service account; khác với cleanup **checkout rollback** vẫn dùng callback/TTL ở mục 4. Nếu kho hết hạn trước confirm cần đối soát/refund, chưa có hoàn tiền tự động.

Cấu hình, RspCode, giới hạn, cách test và hướng review: [PAYMENT_SANDBOX_INTEGRATION.md](../PAYMENT_SANDBOX_INTEGRATION.md).

## 6. Order Management — baseline TT-S2-1

Tất cả path dưới đây tính từ `/api/v1`. Identity lấy từ JWT; tại Gateway JWT có thể đến từ cookie HttpOnly, service vẫn nhận Bearer đã xác minh. POST/PATCH qua cookie cần `X-Nexus-Request: 1`.

| Method | Path | Quyền/phạm vi | `data` / HTTP |
|---|---|---|---|
| GET | `/orders` | JWT; mặc định đơn buyer; `role=seller`/`admin` phải đúng vai trò | `OrderSummaryResponse[]`, `meta.pagination` / 200 |
| GET | `/orders/seller` | SELLER/ADMIN, lọc shop theo service | `OrderSummaryResponse[]`, `meta.pagination` / 200 |
| GET | `/orders/{orderId}` | Buyer/chủ shop của đơn hoặc ADMIN | `OrderDetailResponse` / 200 |
| POST | `/orders/{orderId}/cancel` | BUYER/ADMIN, service kiểm tra chủ đơn | `OrderDetailResponse` / 200 |
| PATCH | `/orders/{orderId}/status` | SELLER/ADMIN, service kiểm tra chủ shop và state machine | `OrderDetailResponse` / 200 |

Danh sách nhận `status`, `startDate`, `endDate` (ISO local datetime), `page` (mặc định 0), `size` (1–100, mặc định 20), `sortBy` (mặc định `createdAt`), `sortDirection` (mặc định DESC). `role` không cấp thêm quyền. Không dùng `PageResponse` cũ hoặc Spring Page làm shape của API này.

Summary có `id`, `orderNo`, `buyerId`, `sellerId`, `sourceType`, `status`, `total`, `itemCount`, `shippingAddress`, `createdAt`. Detail thêm `auctionId`, `voucherId`, `subtotal`, `shippingFee`, `discount`, `items`, `statusHistory`. `createdAt` là Instant UTC. Detail hiện không trả `paymentUrl` hoặc thông tin người nhận; lấy URL từ response tạo đơn/payment.

Cancel nhận `{ "reason": "Đổi nhu cầu mua hàng" }`; nếu gửi body thì reason phải nonblank, tối đa 500 ký tự. Bỏ toàn bộ body được controller thay bằng lý do mặc định. Status nhận `{ "status": "PROCESSING", "reason": "Đã tiếp nhận đơn" }`; status bắt buộc thuộc enum, reason tùy chọn tối đa 500 ký tự. Enum: PENDING_PAYMENT, PAID, PROCESSING, SHIPPED, DELIVERED, COMPLETED, CANCELLED, REFUNDED.

Không được dùng API status để đánh dấu VNPAY PAID; chỉ IPN hợp lệ được làm việc đó. Chưa có refund provider nên REFUNDED bị từ chối. Hủy chỉ áp dụng đơn chưa thanh toán, không có VNPAY đang chờ; PAID không được báo hoàn tiền giả. Cancel/status khóa Order trong transaction; hủy enqueue RELEASE cùng transaction. Phân quyền/sai chủ đơn trả 403, thiếu đơn 404, chuyển trạng thái/hủy không hợp lệ 409; chuyển trùng trạng thái hợp lệ giữ lịch sử không tăng thêm.

## 7. Thông báo TT-S1-4 và transactional email

Checkout mới enqueue ORDER_CREATED; IPN terminal đầu tiên enqueue PAYMENT_SUCCESS/PAYMENT_FAILED. Snapshot buyer/orderNo/total lấy từ Order, commit cùng transaction; worker gửi HTTP sau commit với retry và event UUID cố định. Không đổi request/response checkout/payment. Xem [Notification contract](NOTIFICATION_SERVICE.md) và [demo/vận hành](../ORDER_PAYMENT_NOTIFICATIONS_DEMO.md).
