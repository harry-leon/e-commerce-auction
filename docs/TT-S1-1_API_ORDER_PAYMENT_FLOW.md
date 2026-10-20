# TT-S1-1 — APIs & Order/Payment flow

## 1. Mục tiêu và phạm vi

Tài liệu này đề xuất API contract và luồng trạng thái cho checkout, order và payment trước khi triển khai. Phạm vi gồm:

- Tạo Order từ giỏ hàng đã được kiểm tra lại ở backend.
- Xem danh sách và chi tiết Order, hủy Order khi hợp lệ.
- Tạo và xem Payment cho Order.
- Nhận webhook từ payment provider và đồng bộ trạng thái Payment/Order.
- Xác định trách nhiệm giữa `commerce-service`, `catalog-service` và `fulfillment-service`.
- Liệt kê cấu hình sandbox cần thiết nhưng không lưu secret thật trong Git.

Các endpoint dưới đây đi qua API Gateway. Gateway đã route `/api/v1/checkout/**`, `/api/v1/orders/**` và `/api/v1/payments/**` tới `commerce-service`.

## 2. Quy ước chung

### 2.1 Xác thực và phân quyền

- API của người dùng yêu cầu `Authorization: Bearer <JWT>`.
- Buyer chỉ được xem, thanh toán hoặc hủy Order của chính mình.
- Nhân viên có quyền phù hợp được phép xem Order phục vụ vận hành; tên permission cụ thể cần reviewer xác nhận.
- Webhook không dùng JWT người dùng. `commerce-service` phải xác minh chữ ký bằng secret của payment provider trước khi xử lý payload.

### 2.2 Idempotency

Các API tạo Order và Payment nhận header:

```http
Idempotency-Key: <unique-key>
```

- Cùng key và cùng nội dung request: trả lại kết quả đã tạo, không tạo bản ghi mới.
- Cùng key nhưng nội dung khác: trả `409 IDEMPOTENCY_CONFLICT`.
- Webhook trùng `providerRef` hoặc event ID phải được xử lý idempotent, không cập nhật trạng thái hoặc phát sự kiện hai lần.

### 2.3 Tiền tệ và thời gian

- Tiền dùng số nguyên trong JSON khi thanh toán VND; backend lưu theo `numeric(15,2)` của schema hiện tại.
- Backend tự tính `subtotal`, `shippingFee`, `discount` và `total`; không tin tổng tiền do frontend gửi lên.
- Thời gian trả về theo ISO 8601 và có timezone, ví dụ `2026-10-20T15:30:00+07:00`.

## 3. Order APIs

### 3.1 Tạo Order từ checkout

```http
POST /api/v1/checkout/orders
Authorization: Bearer <token>
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
```

Request:

```json
{
  "cartId": "3a977cb1-18b7-40bc-93ea-f433ae1b7447",
  "shippingAddress": {
    "receiverName": "Nguyễn Văn A",
    "phone": "0901234567",
    "addressLine": "01 Nguyễn Huệ",
    "ward": "Bến Nghé",
    "district": "Quận 1",
    "province": "Thành phố Hồ Chí Minh"
  },
  "voucherCode": "WELCOME10"
}
```

Luồng xử lý đề xuất:

1. Xác thực buyer và quyền sở hữu cart.
2. Đọc lại SKU, trạng thái sản phẩm và giá hiện tại từ `catalog-service`.
3. Kiểm tra và giữ tồn kho qua `fulfillment-service`.
4. Kiểm tra voucher, phí giao hàng và tính lại tổng tiền tại backend.
5. Tạo `orders`, snapshot sản phẩm vào `order_items`, ghi `order_status_history` và đánh dấu cart đã chuyển đổi trong một transaction cục bộ.
6. Nếu tạo Order thất bại sau khi giữ hàng, yêu cầu `fulfillment-service` giải phóng reservation. Cơ chế retry/compensation cụ thể cần được thống nhất khi triển khai.

Response `201 Created`:

```json
{
  "id": "29ba5d82-7868-44d8-a608-430207038ea0",
  "orderNo": "ORD-20261020-0001",
  "status": "PENDING_PAYMENT",
  "subtotal": 500000,
  "shippingFee": 30000,
  "discount": 50000,
  "total": 480000,
  "currency": "VND",
  "paymentDeadline": "2026-10-20T15:30:00+07:00",
  "createdAt": "2026-10-20T15:15:00+07:00"
}
```

### 3.2 Xem danh sách Order

```http
GET /api/v1/orders?page=0&size=20&status=PENDING_PAYMENT
Authorization: Bearer <token>
```

Response `200 OK`:

```json
{
  "content": [
    {
      "id": "29ba5d82-7868-44d8-a608-430207038ea0",
      "orderNo": "ORD-20261020-0001",
      "status": "PENDING_PAYMENT",
      "total": 480000,
      "currency": "VND",
      "createdAt": "2026-10-20T15:15:00+07:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Danh sách mặc định sắp xếp `createdAt` giảm dần. `size` tối đa đề xuất là 100.

### 3.3 Xem chi tiết Order

```http
GET /api/v1/orders/{orderId}
Authorization: Bearer <token>
```

Response `200 OK`:

```json
{
  "id": "29ba5d82-7868-44d8-a608-430207038ea0",
  "orderNo": "ORD-20261020-0001",
  "status": "PAID",
  "items": [
    {
      "skuId": "4c879a12-e807-4777-8ba1-c978a025da8b",
      "productName": "Bàn phím Nexus",
      "skuCode": "KEYBOARD-BLACK",
      "unitPrice": 500000,
      "quantity": 1
    }
  ],
  "subtotal": 500000,
  "shippingFee": 30000,
  "discount": 50000,
  "total": 480000,
  "currency": "VND",
  "shippingAddress": {
    "receiverName": "Nguyễn Văn A",
    "phone": "0901234567",
    "addressLine": "01 Nguyễn Huệ",
    "ward": "Bến Nghé",
    "district": "Quận 1",
    "province": "Thành phố Hồ Chí Minh"
  },
  "paymentStatus": "SUCCESS",
  "createdAt": "2026-10-20T15:15:00+07:00"
}
```

### 3.4 Hủy Order

```http
POST /api/v1/orders/{orderId}/cancel
Authorization: Bearer <token>
Idempotency-Key: 84fb95b8-e56c-45c0-8639-c111a17f1be1
Content-Type: application/json
```

Request:

```json
{
  "reason": "BUYER_CHANGED_MIND",
  "note": "Tôi muốn thay đổi địa chỉ nhận hàng"
}
```

Luồng xử lý đề xuất:

1. Kiểm tra quyền sở hữu và khóa Order để tránh hủy đồng thời với webhook hoặc xử lý giao hàng.
2. Chỉ cho phép hủy ở trạng thái được BA phê duyệt.
3. Chuyển Order sang `CANCELLED` và ghi `order_status_history`.
4. Yêu cầu `fulfillment-service` giải phóng tồn kho đã giữ.
5. Nếu đã thu tiền, tạo quy trình refund thay vì coi việc đổi Order sang `CANCELLED` là đã hoàn tiền.

Response `200 OK`:

```json
{
  "id": "29ba5d82-7868-44d8-a608-430207038ea0",
  "orderNo": "ORD-20261020-0001",
  "status": "CANCELLED",
  "cancelledAt": "2026-10-20T15:20:00+07:00"
}
```

## 4. Payment APIs

### 4.1 Tạo Payment

```http
POST /api/v1/orders/{orderId}/payments
Authorization: Bearer <token>
Idempotency-Key: c2a883b9-1fd0-4ac8-b860-aa96cadb155b
Content-Type: application/json
```

Request:

```json
{
  "provider": "VNPAY",
  "method": "QR",
  "returnUrl": "http://localhost:3000/payment/result"
}
```

Luồng xử lý đề xuất:

1. Kiểm tra buyer sở hữu Order và Order đang `PENDING_PAYMENT`.
2. Kiểm tra Order chưa có Payment `SUCCESS`.
3. Tạo bản ghi Payment `PENDING` với số tiền lấy từ `orders.total`.
4. Gọi sandbox provider để tạo phiên thanh toán.
5. Lưu `providerRef`, trả `paymentUrl` cho frontend.
6. Nếu provider lỗi, không đánh dấu Order đã thanh toán; Payment được chuyển `FAILED` hoặc giữ `PENDING` tùy loại lỗi và chính sách retry được duyệt.

Response `201 Created`:

```json
{
  "paymentId": "378fdc25-8504-4f45-a0c3-3fdfca21c00a",
  "orderId": "29ba5d82-7868-44d8-a608-430207038ea0",
  "provider": "VNPAY",
  "method": "QR",
  "amount": 480000,
  "currency": "VND",
  "status": "PENDING",
  "paymentUrl": "https://sandbox.example/pay/transaction-reference",
  "expiresAt": "2026-10-20T15:30:00+07:00"
}
```

### 4.2 Xem Payment

```http
GET /api/v1/payments/{paymentId}
Authorization: Bearer <token>
```

Response `200 OK`:

```json
{
  "paymentId": "378fdc25-8504-4f45-a0c3-3fdfca21c00a",
  "orderId": "29ba5d82-7868-44d8-a608-430207038ea0",
  "provider": "VNPAY",
  "providerRef": "provider-transaction-reference",
  "method": "QR",
  "amount": 480000,
  "currency": "VND",
  "status": "SUCCESS",
  "createdAt": "2026-10-20T15:16:00+07:00",
  "completedAt": "2026-10-20T15:17:00+07:00"
}
```

Frontend dùng API này để hiển thị kết quả sau khi provider redirect về. Redirect chỉ phục vụ trải nghiệm người dùng, không được tự chuyển Order sang `PAID`.

### 4.3 Xem các Payment của Order

```http
GET /api/v1/orders/{orderId}/payments
Authorization: Bearer <token>
```

Response `200 OK` là danh sách các lần thanh toán theo `createdAt` giảm dần. API giúp theo dõi các lần thử lại mà không ghi đè lịch sử Payment cũ.

## 5. Payment webhook

```http
POST /api/v1/payments/webhooks/{provider}
Content-Type: application/json
X-Provider-Signature: <signature>
```

Payload chính xác phụ thuộc provider. Payload chuẩn hóa nội bộ tối thiểu cần có:

```json
{
  "eventId": "evt-123456",
  "providerRef": "provider-transaction-reference",
  "orderNo": "ORD-20261020-0001",
  "amount": 480000,
  "currency": "VND",
  "result": "SUCCESS",
  "occurredAt": "2026-10-20T15:17:00+07:00"
}
```

Luồng xử lý bắt buộc:

1. Đọc raw request body và xác minh signature theo tài liệu provider.
2. Tìm Payment theo `providerRef`; đối chiếu Order, số tiền và tiền tệ.
3. Khóa Payment/Order hoặc dùng optimistic locking để tránh callback đồng thời.
4. Nếu event đã xử lý hoặc Payment đã ở cùng trạng thái cuối, trả thành công mà không xử lý lại.
5. Với kết quả thành công, cập nhật Payment `SUCCESS`, Order `PAID` và ghi `order_status_history` trong cùng transaction cục bộ.
6. Với kết quả thất bại hoặc bị hủy, cập nhật Payment tương ứng; Order vẫn `PENDING_PAYMENT` nếu còn thời gian thử lại.
7. Phát sự kiện cho fulfillment/notification sau khi transaction commit. Cơ chế outbox và retry cần được chốt trong thiết kế triển khai.

Webhook phải trả response theo yêu cầu của provider. Nếu chưa chọn provider, response nội bộ tạm đề xuất:

```json
{
  "received": true
}
```

Các trường hợp lỗi:

- Signature sai: trả `401 INVALID_WEBHOOK_SIGNATURE`, không thay đổi dữ liệu.
- Không tìm thấy Payment: trả mã HTTP theo yêu cầu provider và ghi log để đối soát.
- Sai số tiền hoặc Order: không cập nhật Payment/Order, ghi log cảnh báo và trả lỗi phù hợp.
- Lỗi tạm thời khi xử lý: trả lỗi để provider retry; việc xử lý lại phải idempotent.

## 6. Chuẩn response lỗi

Format này tương thích với `ApiError` hiện tại của `commerce-service`:

```json
{
  "code": "INVALID_ORDER_STATUS",
  "message": "Order cannot be cancelled in SHIPPED status",
  "details": [
    "currentStatus: SHIPPED"
  ],
  "timestamp": "2026-10-20T08:30:00Z"
}
```

| HTTP | Error code | Khi sử dụng |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Thiếu trường, sai định dạng hoặc giá trị không hợp lệ. |
| `401` | `UNAUTHORIZED` | Thiếu JWT, JWT không hợp lệ hoặc hết hạn. |
| `401` | `INVALID_WEBHOOK_SIGNATURE` | Không xác minh được chữ ký webhook. |
| `403` | `ORDER_ACCESS_DENIED` | Người dùng truy cập Order/Payment không thuộc quyền sở hữu. |
| `404` | `CART_NOT_FOUND` | Không tìm thấy cart hoặc cart không còn hoạt động. |
| `404` | `ORDER_NOT_FOUND` | Không tìm thấy Order. |
| `404` | `PAYMENT_NOT_FOUND` | Không tìm thấy Payment. |
| `409` | `IDEMPOTENCY_CONFLICT` | Dùng lại idempotency key với request khác. |
| `409` | `INVALID_ORDER_STATUS` | Trạng thái Order không cho phép thao tác hiện tại. |
| `409` | `PAYMENT_ALREADY_COMPLETED` | Order đã có Payment thành công. |
| `409` | `CONCURRENT_UPDATE` | Order, Payment hoặc tồn kho vừa bị request khác cập nhật. |
| `422` | `PRICE_CHANGED` | Giá hiện tại khác snapshot trong cart. |
| `422` | `INSUFFICIENT_STOCK` | Không đủ tồn kho để checkout. |
| `422` | `INVALID_VOUCHER` | Voucher hết hạn, hết lượt hoặc không thỏa điều kiện. |
| `502` | `PAYMENT_PROVIDER_ERROR` | Provider lỗi hoặc trả response không hợp lệ. |
| `503` | `DEPENDENCY_UNAVAILABLE` | Catalog hoặc fulfillment tạm thời không sẵn sàng. |
| `500` | `INTERNAL_ERROR` | Lỗi không dự kiến; response không để lộ stack trace hoặc secret. |

## 7. Trạng thái Order

Các trạng thái hiện có trong schema: `PENDING_PAYMENT`, `PAID`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `COMPLETED`, `CANCELLED`, `REFUNDED`.

| Từ trạng thái | Sang trạng thái | Tác nhân/sự kiện | Điều kiện chính |
| --- | --- | --- | --- |
| Khởi tạo | `PENDING_PAYMENT` | Checkout | Giá, voucher và tồn kho hợp lệ; đã giữ hàng thành công. |
| `PENDING_PAYMENT` | `PAID` | Payment webhook | Signature, Payment, số tiền và currency hợp lệ. |
| `PENDING_PAYMENT` | `CANCELLED` | Buyer hoặc timeout job | Chưa có Payment thành công; chính sách hủy/hết hạn cho phép. |
| `PAID` | `PROCESSING` | Seller/operation | Đã xác nhận tiếp nhận và xử lý Order. |
| `PROCESSING` | `SHIPPED` | Fulfillment | Shipment đã được tạo và bàn giao vận chuyển. |
| `SHIPPED` | `DELIVERED` | Fulfillment/provider | Có xác nhận giao hàng thành công. |
| `DELIVERED` | `COMPLETED` | Buyer hoặc scheduled job | Buyer xác nhận hoặc hết thời gian khiếu nại. |
| `PAID` hoặc `PROCESSING` | `REFUNDED` | Refund workflow | Provider xác nhận hoàn tiền toàn phần thành công. |

Quy tắc:

- Không cập nhật trạng thái Order trực tiếp từ frontend.
- Mỗi thay đổi phải ghi `order_status_history` gồm trạng thái cũ, trạng thái mới, lý do và thời điểm.
- Payment thất bại không tự chuyển Order sang trạng thái lỗi vì schema hiện chưa có `PAYMENT_FAILED`; Order có thể tiếp tục `PENDING_PAYMENT` để thử lại.
- Không được nhảy qua các bước vận hành, trừ khi có quy tắc nghiệp vụ được BA phê duyệt.
- Trạng thái `CANCELLED`, `COMPLETED` và `REFUNDED` được xem là trạng thái cuối trong đề xuất hiện tại.

## 8. Trạng thái Payment

Các trạng thái hiện có trong schema: `PENDING`, `SUCCESS`, `FAILED`, `CANCELLED`.

| Từ trạng thái | Sang trạng thái | Tác nhân/sự kiện | Điều kiện chính |
| --- | --- | --- | --- |
| Khởi tạo | `PENDING` | Tạo Payment | Order hợp lệ và provider tạo phiên thanh toán. |
| `PENDING` | `SUCCESS` | Webhook đã xác minh | Giao dịch thành công, đúng Order và số tiền. |
| `PENDING` | `FAILED` | Webhook hoặc truy vấn provider | Provider xác nhận giao dịch thất bại. |
| `PENDING` | `CANCELLED` | Buyer/provider/timeout | Provider xác nhận hủy hoặc phiên hết hiệu lực. |

Quy tắc:

- `SUCCESS`, `FAILED`, `CANCELLED` là trạng thái cuối của một lần Payment.
- Thử thanh toán lại tạo Payment mới để giữ lịch sử, không đưa Payment cũ về `PENDING`.
- Một Order chỉ được có một Payment `SUCCESS`; cần constraint hoặc khóa giao dịch phù hợp khi triển khai.
- `retryCount` chỉ dùng cho retry kỹ thuật của cùng yêu cầu tới provider, không đại diện cho số Payment khác nhau của Order.

## 9. Vai trò của các service

### 9.1 `commerce-service`

- Sở hữu cart, voucher, Order, Order item, Payment, refund và lịch sử trạng thái.
- Xác thực quyền sở hữu Order/Payment.
- Điều phối checkout, tính tổng tiền cuối cùng và lưu snapshot sản phẩm tại thời điểm mua.
- Tích hợp payment provider, xác minh webhook và cập nhật Payment/Order.
- Gọi catalog/fulfillment nhưng không truy cập trực tiếp database của service khác.

### 9.2 `catalog-service`

- Sở hữu product, SKU, trạng thái bán và giá hiện tại.
- Cung cấp dữ liệu để `commerce-service` kiểm tra lại cart khi checkout.
- Không sở hữu trạng thái Order hoặc Payment.

### 9.3 `fulfillment-service`

- Sở hữu tồn kho, reservation và shipment.
- Giữ hàng khi checkout, xác nhận/trừ hàng theo quy tắc đã thống nhất và giải phóng khi Order bị hủy hoặc hết hạn.
- Cung cấp trạng thái vận chuyển để Order chuyển `PROCESSING` → `SHIPPED` → `DELIVERED`.
- Phải xử lý idempotent cho reserve, confirm và release để retry không làm sai tồn kho.

## 10. Luồng tổng quát

```text
Buyer → Gateway → commerce-service: checkout cart
commerce-service → catalog-service: kiểm tra SKU và giá hiện tại
commerce-service → fulfillment-service: giữ tồn kho
commerce-service → database: tạo Order PENDING_PAYMENT

Buyer → commerce-service: tạo Payment
commerce-service → payment sandbox: tạo phiên thanh toán
commerce-service → Buyer: trả paymentUrl
Buyer → payment sandbox: thực hiện thanh toán

payment sandbox → commerce-service: webhook có signature
commerce-service → database: Payment SUCCESS + Order PAID
commerce-service → fulfillment/notification: phát sự kiện sau commit

payment sandbox → Buyer: redirect về frontend
frontend → commerce-service: đọc Payment/Order để hiển thị kết quả thật
```

## 11. Biến môi trường sandbox

Chỉ đưa tên biến và giá trị mẫu không nhạy cảm vào `.env.example`. Secret thật phải nằm trong `.env` local hoặc secret store của CI/CD và Kubernetes.

```dotenv
# Payment sandbox: commerce-service dùng để tạo giao dịch và xác minh webhook.
PAYMENT_PROVIDER=VNPAY
PAYMENT_SANDBOX_URL=
PAYMENT_MERCHANT_ID=
PAYMENT_SECRET=
PAYMENT_RETURN_URL=http://localhost:3000/payment/result
PAYMENT_WEBHOOK_URL=http://localhost:8080/api/v1/payments/webhooks/vnpay
PAYMENT_TIMEOUT_MINUTES=15
```

Quy tắc bảo mật:

- Không commit `.env`, API key, merchant secret, webhook secret hoặc tài khoản sandbox.
- Không ghi secret vào log, response lỗi, screenshot hoặc tài liệu review.
- Dùng secret khác nhau cho local, staging và production.
- Nếu secret từng bị commit, phải revoke/rotate tại provider; xóa khỏi file hiện tại là chưa đủ.

## 12. Câu hỏi cần BA/reviewer xác nhận

### Payment

1. Provider chính thức là VNPay, MoMo, Stripe hay provider khác?
2. Hệ thống hỗ trợ phương thức nào: QR, thẻ nội địa, thẻ quốc tế, ví điện tử, COD?
3. Payment hết hạn sau bao nhiêu phút? Provider hay Nexus quyết định thời điểm hết hạn?
4. Buyer được thử thanh toán lại tối đa bao nhiêu lần?
5. Có cho phép nhiều Payment `PENDING` đồng thời trên cùng một Order không?
6. Chính sách đối soát khi nhận webhook sai số tiền hoặc không tìm thấy Payment là gì?

### Order và tồn kho

7. Một cart chứa sản phẩm của nhiều seller tạo một Order hay tách thành nhiều Order?
8. Tồn kho được giữ từ lúc tạo Order hay lúc tạo Payment? Reservation tồn tại bao lâu?
9. Khi Payment thất bại, giải phóng tồn kho ngay hay chờ hết `paymentDeadline`?
10. Buyer được hủy Order ở những trạng thái nào? Có giới hạn thời gian hủy không?
11. Ai được chuyển `PAID` sang `PROCESSING`: seller, staff hay job tự động?
12. `DELIVERED` tự chuyển `COMPLETED` sau bao lâu nếu buyer không xác nhận?

### Refund và vận hành

13. Có hỗ trợ refund một phần hay chỉ toàn phần?
14. Khi hủy Order đã thanh toán, Order chuyển `CANCELLED` trước rồi chờ refund hay giữ trạng thái hiện tại tới khi refund thành công?
15. Nếu refund thất bại, trạng thái Order và quy trình xử lý thủ công là gì?
16. Permission chính xác cho staff xem/cập nhật Order là gì?
17. Cơ chế giao tiếp giữa các service dùng REST đồng bộ, event hay kết hợp cả hai?
18. Có yêu cầu outbox, retry schedule và dead-letter queue cho sự kiện Order/Payment không?

## 13. Tiêu chí hoàn tất thiết kế

- Reviewer thống nhất endpoint, request/response và error code.
- BA/reviewer duyệt state transition của Order, Payment và refund.
- Team xác nhận trách nhiệm của từng service và quy tắc giữ/giải phóng tồn kho.
- Payment provider sandbox được chọn và webhook contract được cập nhật theo tài liệu chính thức của provider.
- Biến môi trường được khai báo bằng placeholder; kiểm tra Git không chứa secret thật.
- Các quyết định đã duyệt được cập nhật lại vào tài liệu này trước khi bắt đầu implementation.
