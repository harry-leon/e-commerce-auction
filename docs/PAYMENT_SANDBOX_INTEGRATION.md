# TT-S1-3 — Tích hợp VNPAY sandbox

Backend tạo URL VNPAY PAY 2.1.0 đã ký, xác minh IPN và cập nhật Order/Payment trong transaction.
FE đọc kết quả từ DB, không dùng query return URL làm bằng chứng đã trả tiền.
Tài liệu nhà cung cấp: [VNPAY PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).

## 1. Phạm vi và phụ thuộc

- Nhánh `feature/tt-s1-3-sandbox-payment` dựa trên checkout TT-S1-2 (`70e9aa8`).
- MR Commerce tạm target `feature/tt-s1-2-checkout-order` để diff chỉ chứa payment; sau khi TT-S1-2 merge phải retarget về `dev` trước khi merge payment.
- Gateway: `feature/tt-s1-3-payment-gateway`; FE: `feature/tt-s1-3-payment-web`, cùng dựa trên `dev` mới nhất lúc tách nhánh (`94150e9`). FE có commit định dạng riêng để đáp ứng CI; không sửa logic/lockfile trong commit đó.
- Catalog cần bản có `sellerId` từ nhánh `feature/tt-s1-2-catalog-seller-snapshot`.
- Commerce chạy Flyway V5; không sửa V1–V4. Gateway cần mở đúng GET IPN.
- Khi chia MR: Commerce kèm cấu hình/tài liệu, Gateway security và FE là các phần review riêng; chỉ mở thanh toán cho người dùng sau khi triển khai đủ ba phần.
- Chỉ VNPAY sandbox/VND, một attempt cho mỗi đơn, một shop mỗi đơn. Không tích hợp tiền thật, hoàn tiền/queryDR hoặc CARD.
- COD giữ hành vi tạo đơn hiện có. Bảng payment cũ không bị sửa số tiền hoặc trạng thái; các cột V5 bổ sung nullable.

## 2. Luồng chạy

1. Buyer gọi `POST /api/v1/orders` với `paymentMethod: VNPAY` và `Idempotency-Key` UUID.
2. Checkout kiểm tra cấu hình merchant/service account, tính giá live, lưu đơn, reserve kho và lưu reservation IDs.
3. Trong cùng transaction, tạo Payment PENDING, TxnRef là payment UUID. URL đã ký được lưu và trả qua `paymentUrl`.
4. FE chuyển hướng sang `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`.
5. VNPAY gọi IPN server-to-server. IPN không có JWT nên chỉ route GET này được mở công khai tại Gateway và Commerce.
6. Commerce kiểm tra HMAC-SHA512 và merchant trước khi truy cập payment, sau đó đối chiếu TxnRef, Order và amount ở cả Payment/Order.
7. Callback đầu tiên hợp lệ cập nhật trạng thái, ghi history và outbox trong cùng transaction. Callback lặp không phát sinh tác dụng phụ lần hai.
8. Worker đọc outbox, lấy JWT service account từ Keycloak và confirm/release qua API hiện có của Fulfillment.
9. Browser trở lại `/payment-result?vnp_TxnRef=...`. FE poll API có JWT, chỉ hiển thị thành công khi DB trả SUCCESS.

Khởi tạo URL ký theo tài liệu VNPAY, không cần gửi request tạo giao dịch server-to-server. `vnp_Amount` = số VND nguyên dương trong DB × 100, tối đa 12 chữ số; không nhận amount từ client. Giá có phần lẻ VND hoặc quá giới hạn bị từ chối. Timestamp ký theo `Asia/Ho_Chi_Minh`. Deadline là thời điểm tạo Order + 15 phút, không kéo dài khi mở lại URL.

IP dùng peer của HTTP request, không tin X-Forwarded-For do client tùy ý gửi; IPv6 hiện dùng fallback IPv4 loopback cho sandbox. Nếu cần IP client thật khi triển khai sau proxy, cấu hình trust proxy riêng và review trước khi thay hành vi này.

## 3. API buyer

### Checkout

`POST /api/v1/orders` vẫn dùng DTO TT-S1-2, nay hỗ trợ COD/VNPAY. Response thêm `paymentUrl` (COD là null).
Replay cùng key trả URL Payment đã lưu, kể cả khi đã hết hạn; không tạo attempt mới. Thứ tự triển khai phải có migration V5 trước khi chạy code mới.

### Tạo/lấy lại URL

```http
POST /api/v1/payments
Authorization: Bearer <JWT có ORDER.CREATE>
Content-Type: application/json

{"orderId":"<UUID đơn hàng VNPAY>"}
```

Server kiểm tra buyer sở hữu đơn, đơn PENDING_PAYMENT và có snapshot phiếu giữ kho. Cùng đơn trả attempt PENDING hiện có; attempt terminal hoặc URL hết hạn bị từ chối, chưa có retry tạo attempt thứ hai.

Payload `data` trong envelope Nexus v2:

```json
{
  "paymentId": "<UUID payment / vnp_TxnRef>",
  "orderId": "<UUID order>",
  "status": "PENDING",
  "amount": 50000.00,
  "currency": "VND",
  "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?...",
  "expiresAt": "2026-10-29T08:15:00Z"
}
```

`GET /api/v1/payments/{paymentId}` cần JWT và chỉ cho buyer sở hữu đơn đọc cùng shape. Không có API buyer tự đặt trạng thái PAID/SUCCESS.

## 4. IPN và quy tắc trạng thái

`GET /api/v1/payments/vnpay/ipn` nhận query VNPAY. Cấm tham số trùng; giới hạn 40 key, 100 ký tự/key, 2000 ký tự/value. Dữ liệu được servlet decode một lần, bỏ SecureHash/SecureHashType, sort key và form-encode UTF-8 rồi ký HMAC-SHA512. So sánh chữ ký theo byte hằng thời gian.

| Điều kiện | Payment | Order | Outbox |
|---|---|---|---|
| ResponseCode=00 và TransactionStatus=00, transactionNo hợp lệ/chưa dùng | SUCCESS | PAID | CONFIRM từng reservationId |
| TransactionStatus=02 và ResponseCode hai chữ số khác 00/07 | FAILED | CANCELLED | RELEASE theo orderId |
| Đang xử lý, nghi ngờ gian lận, đảo giao dịch hoặc kết quả không nhất quán | Giữ PENDING | Giữ nguyên | Không tạo, cần retry/đối soát |
| Callback lặp sau terminal | Giữ nguyên | Giữ nguyên | Không tạo lại |

IPN trả JSON trực tiếp đúng protocol nhà cung cấp, **không bọc envelope Nexus**:

| RspCode | Ý nghĩa |
|---|---|
| 00 | Đã commit kết quả hợp lệ, bao gồm thất bại đã ghi nhận |
| 02 | Đơn/payment đã terminal, không xử lý lại |
| 01 | TxnRef hoặc Order không tồn tại/không phù hợp |
| 04 | Số tiền callback không khớp snapshot Payment và Order |
| 97 | Chữ ký/merchant sai hoặc query bị lặp |
| 99 | Kết quả bất thường, transactionNo dùng lại hoặc lỗi xử lý/commit; cần retry/đối soát |

Không log merchant secret/token hoặc toàn bộ query callback. Payment lưu mã phản hồi, trạng thái provider và transactionNo thành công để đối soát. TransactionNo thành công có unique index, không dùng cho hai payment.

## 5. Tính đồng thời và tác vụ kho

Khởi tạo payment khóa Order. IPN khóa PostgreSQL theo TxnRef rồi khóa Order, chỉ callback đầu tiên từ PENDING được chuyển terminal. Các khóa thuộc transaction nên dùng chung nhiều instance và tự nhả khi commit/rollback. Unique index một VNPAY attempt/order là lớp bảo vệ cuối.

`payment_inventory_tasks` commit cùng trạng thái payment và history; lỗi commit rollback toàn bộ. Worker chạy mỗi 5 giây, tối đa 10 tác vụ/tick, mỗi tác vụ một transaction và dùng `FOR UPDATE SKIP LOCKED` để tránh hai instance xử lý cùng dòng.

Remote kho thành công nhưng worker chết trước commit sẽ gọi lại. Confirm chỉ xem đúng lỗi `INVENTORY_RESERVATION_ALREADY_COMMITTED` là replay thành công; release theo ref hiện idempotent. Lỗi khác tăng attempts và retry, thời gian chờ tăng 10 giây/lần, tối đa 10 phút; không âm thầm bỏ tác vụ.

Không lưu JWT buyer vào DB. Worker dùng OAuth client_credentials, cache token RAM và refresh trước expiry; kho trả 401 thì bỏ cache. Thiếu service account chặn thanh toán mới bằng 503. IPN cũ vẫn được ghi nhận khi mất cấu hình worker, outbox chờ cấu hình được sửa.

**Đối soát cần thiết:** nếu IPN đến muộn hoặc kho không hoạt động quá TTL 15 phút, phiếu có thể đã EXPIRED. Payment vẫn phải phản ánh kết quả tài chính đã xác minh, tác vụ CONFIRM giữ chưa hoàn tất và cần quản trị xử lý tồn kho/refund. Code chưa có hoàn tiền tự động hoặc cảnh báo ngoài log/DB. Callback trái ngược đến sau terminal trả 02 và cần đối soát, không tự đảo trạng thái đã ghi nhận.

Kiểm tra backlog bằng quyền vận hành trên Commerce DB:

```sql
SELECT order_id, action, attempts, next_attempt_at
FROM payment_inventory_tasks
WHERE NOT done
ORDER BY attempts DESC, next_attempt_at;
```

## 6. Cấu hình và chạy sandbox thật

Các biến sau chỉ đặt trong môi trường/.env không commit:

| Biến | Giá trị cần cấp |
|---|---|
| VNPAY_TMN_CODE | Merchant sandbox do VNPAY cấp |
| VNPAY_HASH_SECRET | HashSecret cùng merchant sandbox |
| VNPAY_RETURN_URL | URL FE, ví dụ https://frontend.example/payment-result |
| PAYMENT_INVENTORY_TOKEN_URL | Keycloak realm token endpoint; Compose có default URL nội bộ |
| PAYMENT_INVENTORY_CLIENT_ID | Confidential client cho Commerce payment worker |
| PAYMENT_INVENTORY_CLIENT_SECRET | Secret client service account |

1. Tạo client confidential trong realm Nexus, bật service accounts và cấp quyền gọi confirm/release theo policy Fulfillment. Endpoint kho hiện yêu cầu authenticated; không cấp quyền admin không cần thiết.
2. Điền credential thật qua môi trường. Không đưa secret vào FE/VITE_*, tài liệu, callback query hoặc ảnh review.
3. Đăng ký IPN URL public HTTPS tại merchant: `https://<gateway>/api/v1/payments/vnpay/ipn`. Return URL không thay thế IPN URL.
4. Chạy Catalog có sellerId, Fulfillment có tồn kho/kho mặc định, Commerce V5, Gateway và FE mới.
5. Đăng nhập buyer có ORDER.CREATE/CHECKOUT.START, thêm SKU giá VND nguyên vào giỏ và chọn VNPAY.
6. Hoàn tất thanh toán bằng dữ liệu test do VNPAY cung cấp. Kiểm tra payment SUCCESS/order PAID/history một dòng; outbox confirm chuyển done và kho giảm đúng một lần.
7. Gửi lại IPN trong môi trường test: RspCode=02, không thêm history/outbox hoặc trừ kho lần nữa.
8. Test provider báo thất bại, sửa amount/signature và cho Fulfillment tạm lỗi để kiểm tra release/retry. Sửa query browser thành success không được làm backend cập nhật hoặc FE báo đã xác nhận.

Hiện chưa chạy một giao dịch với merchant sandbox thật trong phiên phát triển này; credential và URL IPN public cần người vận hành cung cấp. Không coi test fixture hoặc trang sandbox mô phỏng cũ là xác nhận đã kết nối merchant.

## 7. Kiểm tra tự động và hướng review

Chạy từ repo root (PostgreSQL tạm riêng, user postgres/trust):

```powershell
$env:NEXUS_TEST_DB_URL='jdbc:postgresql://localhost:<port>/<test_db>'
mvn -f backend/commerce-service/pom.xml verify checkstyle:check
mvn -f backend/api-gateway/pom.xml verify checkstyle:check
```

Test DB tạo dữ liệu và trigger gây lỗi commit tạm thời, tuyệt đối không trỏ DB team/production. Không có NEXUS_TEST_DB_URL thì PostgreSQL tests bị skip. CI Commerce đã được cấp DB tạm từ TT-S1-2.

- `VnpayGatewayTest`: amount/encoding/date, chữ ký, cấu hình.
- `PaymentServiceTest`: thành công/thất bại, webhook sai, replay, quyền sở hữu, amount/transactionNo sai.
- `PaymentPostgresTest`: checkout→payment→IPN, callback đồng thời, rollback trạng thái/history/outbox và retry kho.
- `PaymentControllerTest`, `PaymentIpnSecurityTest`: protocol IPN, parameter pollution và ranh giới public/JWT.
- `InventoryServiceTokenTest`, `HttpInventoryClientTest`: OAuth token cache và confirm replay.
- FE: `vnpay-result.test.tsx`, `orders.test.tsx`, `features.test.tsx`; VNPAY không fallback mock khi API lỗi.

Reviewer tập trung khóa/trạng thái terminal, encoding ký, transaction DB/outbox, client_credentials, whitelist IPN và việc FE chỉ tin kết quả DB. Payment cần hai approval theo CONTRIBUTING trước khi merge; chưa xác nhận pipeline/approval ở tài liệu này.

## 8. Kết quả xác minh trong phiên triển khai

| Kiểm tra | Kết quả |
|---|---|
| Commerce clean verify + Checkstyle, PostgreSQL thật | 76 test pass, không skip, 0 lỗi style; line coverage theo gate 94,11% |
| Gateway tests | 13 test pass; gồm Spring Boot/Netty với HTTP thật, whitelist/query IPN, header trộn hoa/thường, login/refresh thiếu profile, đăng ký/gán BUYER và cleanup IAM lỗi |
| Gateway Checkstyle | 0 lỗi sau khi sửa 215 lỗi gốc; giữ bộ Sun Checks, không thêm suppression hoặc bỏ qua kiểm tra |
| FE test/coverage toàn bộ | 203 test pass; nhánh FE đã tích hợp dev mới nhất, line coverage 71,17% theo chỉ số gate hiện tại |
| FE typecheck/lint/build/format:check | Pass; build có cảnh báo bundle lớn, không chặn build |
| Compose/YAML và git diff --check | Pass; Compose dùng giá trị fixture và file .env rỗng tạm để kiểm tra cú pháp, không khởi động môi trường thật |
| Merchant sandbox thật / pipeline GitLab | Chưa chạy/xác nhận |

PostgreSQL test container đã được dọn sau khi kiểm tra. Ba nhánh review tách theo Commerce/Gateway/FE, không sửa các file đang xóa ở workspace `nexus` của người dùng. Chưa xác nhận pipeline hoặc approval GitLab; không tự merge.
