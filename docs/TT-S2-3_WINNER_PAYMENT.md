# TT-S2-3 — Thanh toán winner và IPN idempotency

Nhánh triển khai: `feature/tt-s2-3-winner-payment`, dựa trên handover `b48afbf` của Khang.
Phạm vi: VNPAY sandbox cho đơn Commerce `sourceType=AUCTION`; không triển khai refund provider hoặc penalty settlement.

## Luồng và API

1. Handover tạo một đơn winner, lưu `payment_deadline`, chuyển kho AUCTION → ORDER rồi attach reservation IDs.
   Nếu attach thất bại thì không lưu `settlement.orderId`; scheduler được phép replay các bước idempotent.
   Commerce khóa transaction theo auctionId khi tạo đơn và khóa Order khi attach, tránh insert trùng khi gọi đồng thời.
2. Winner gọi `POST /api/v1/payments` với JSON `{ "orderId": "<UUID>" }` và quyền `ORDER.CREATE`.
   Backend kiểm tra chủ đơn, PENDING_PAYMENT, deadline đã lưu và reservation IDs. Giá lấy từ DB.
   Marker `paymentMethod=AUCTION` được đổi sang VNPAY dưới khóa Order; không cần tạo lại checkout giỏ hàng.
   Thiếu deadline trả `409 AUCTION_PAYMENT_NOT_READY`; thiếu phiếu trả `409 PAYMENT_RESERVATIONS_MISSING`.
3. URL/TxnRef được lưu, replay không sinh attempt mới hoặc gia hạn deadline. Đơn winner dùng mốc settlement, không dùng hạn 15 phút của đơn thường.
4. IPN xác minh HMAC/merchant/amount, khóa TxnRef → Order → provider transaction reference.
   `00/00` ghi `Payment.status=SUCCESS`, `inventory_status=PENDING` và một tác vụ `COMMIT` cùng transaction.
   Order còn PENDING_PAYMENT, chưa phát thông báo PAYMENT_SUCCESS.
5. Worker gọi `POST /api/v1/inventory/orders/{orderId}/commit` bằng service account Commerce/INVENTORY.ORDER.
   Fulfillment khóa toàn bộ phiếu trong một transaction, cạnh tranh với khóa sweeper.
   Worker chỉ nhận receipt đúng ORDER/orderId, đúng tập reservation IDs và mọi phiếu CONFIRMED.
6. Kho thành công: cùng transaction Commerce ghi `inventory_status=CONFIRMED`, Order PAID, history và notification outbox.
   Response mất hoặc Commerce rollback: tác vụ chưa done; retry cùng orderId không trừ kho lần hai.
7. Kho trả `INVENTORY_RESERVATION_EXPIRED` (bao gồm phiếu RELEASED): Order CANCELLED, tiền giữ SUCCESS và inventory REVIEW.
   Không reserve lại, không ghi REFUNDED và không gửi thông báo thành công. Hỗ trợ phải đối soát/hoàn tiền thủ công.

Browser return vẫn chỉ dùng `vnp_TxnRef` để gọi `GET /api/v1/payments/{paymentId}` có JWT của chủ đơn.
Query `vnp_ResponseCode=00` không cập nhật DB, localStorage hoặc PAID. Nút thanh toán ở chi tiết đơn winner chỉ dùng orderId server.

| PaymentResponse.status | Ý nghĩa hiển thị |
| --- | --- |
| PENDING | Chưa nhận IPN cuối, URL còn hạn |
| EXPIRED | Link hết hạn, chưa có kết quả thu tiền cuối |
| PROCESSING | IPN đã ghi nhận thu tiền, đang chờ xác nhận kho; không trả lần hai |
| SUCCESS | Với winner: tiền hợp lệ và kho đã commit, Order PAID |
| REQUIRES_REVIEW | Đã thu tiền nhưng đơn/kho không thể xác nhận; liên hệ hỗ trợ, chưa hoàn tiền tự động |
| FAILED | IPN xác nhận thất bại |

IPN đầu tiên được commit trả RspCode 00; callback terminal lặp trả 02 và không tạo history/outbox lần hai.
Sai chữ ký trả 97, sai amount 04, transaction reference dùng lại trả 99. Lỗi DB rollback trả 99 cho provider retry.
RspCode 00 chỉ xác nhận đã tiếp nhận kết quả tài chính, không có nghĩa Order đã PAID hoặc đã giao hàng.

## Deadline và cạnh tranh

`AuctionPaymentExpiryWorker` quét tối đa 100 đơn mỗi 10 giây (`app.payment.auction.expiry-poll-ms`).
Mỗi đơn được khóa và kiểm tra lại deadline/payment trong transaction riêng:

- Chưa có Payment SUCCESS: chuyển CANCELLED, ghi history và enqueue RELEASE một lần. Không chuyển payment PENDING sang FAILED vì IPN còn có thể tới trễ.
- Đã có Payment SUCCESS đang đợi kho: không hủy, để worker xác định kết quả kho.
- IPN thu tiền sau khi đơn đã CANCELLED: lưu tiền SUCCESS/inventory REVIEW, không tạo COMMIT hay hồi sinh đơn.

Inventory sweeper lấy **ID ứng viên**, sau đó mới khóa/nạp entity. Không nạp entity PENDING trước chờ khóa vì cache JPA có thể giữ trạng thái cũ khi callback vừa commit.
Đơn PAID không thể có RELEASE do deadline: bên thu tiền và bên timeout cùng khóa Order, còn commit/release/sweep cùng khóa phiếu Fulfillment.
Release replay bỏ qua phiếu CONFIRMED; luồng admin/seller không được tự đánh dấu đơn AUCTION/VNPAY PAID.

## Migration, vận hành và giới hạn

- Commerce V9 thêm `payments.inventory_status` nullable và action outbox COMMIT; không sửa migration cũ hoặc backfill tác vụ lịch sử.
- Cần triển khai Fulfillment với sửa sweeper, Commerce với V8/V9, và bản handover có retry attach. **Dừng instance/worker Commerce cũ trước khi bật producer COMMIT**: worker cũ coi action khác CONFIRM là RELEASE. Không chạy hai phiên bản này cùng DB; trước rollback phải dừng producer và xử lý hết COMMIT/pending inventory.
- Worker kho mặc định poll 5 giây, tối đa 10 tác vụ/tick; lỗi mạng/401/5xx giữ backlog và retry với khoảng chờ tối đa 10 phút. Theo dõi `payment_inventory_tasks.attempts`, `done`, `next_attempt_at` và payment có inventory REVIEW/PENDING.
- `Payment.status=SUCCESS` là kết quả thu tiền, không được dùng riêng để quyết định giao đơn winner. Chỉ Order PAID + inventory CONFIRMED mới đủ điều kiện giao.
- Nginx access log chỉ chứa path/status, không query/Referer. IPN không ghi error log có nguyên URI; theo dõi HTTP status bằng access log. Backend không ghi stack/message exception IPN và tắt DEBUG web/JDBC bind để tránh lộ URL đã ký/token.
- Cấu hình merchant/service account vẫn lấy từ môi trường như nền payment. Đăng ký IPN URL public trên merchant; return URL là trang `/payment-result`.
- Chưa có đối soát tự động qua API query VNPAY hoặc hoàn tiền tự động. REVIEW cần người vận hành xác minh tiền và xử lý với provider; không sửa status thành REFUNDED khi chưa hoàn thật.
- Chưa thực hiện một giao dịch merchant sandbox thật trong phiên triển khai này; test dùng chữ ký fixture, PostgreSQL riêng và HTTP fixture/mock ở biên service. Shipping của winner thuộc luồng LD-S2-03.

## Kiểm thử có thể tái lập

Chỉ dùng DB test riêng, user postgres không mật khẩu trên loopback; không trỏ các biến dưới vào DB demo/production.

```powershell
$env:NEXUS_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:<port>/commerce_test'
mvn -f backend/commerce-service/pom.xml test checkstyle:check
$env:FULFILLMENT_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:<port>/fulfillment_test'
mvn -f backend/fulfillment-service/pom.xml '-Dtest=InventoryServiceTest,InventoryPaymentRacePostgresTest' test checkstyle:check
mvn -f backend/auction-service/pom.xml '-Dtest=HttpCommerceOrderClientTest,AuctionOrderHandoverServiceTest,AuctionOrderHandoverSchedulerTest' test checkstyle:check
cd frontend-web
npm run test -- src/test/vnpay-result.test.tsx src/test/winner-payment.test.tsx src/test/payment.test.ts
npm run build
```

`PaymentPostgresTest` kiểm tra callback song song, handover đồng thời, timeout vs IPN, kho hết hạn, lỗi HTTP/retry và đúng một history/notification.
`InventoryPaymentRacePostgresTest` giữ transaction commit mở, đợi sweeper thật chờ row lock rồi commit; kiểm tra không có RELEASE sau CONFIRMED và không xuất kho sau EXPIRED.
`PaymentControllerTest` kiểm tra lỗi IPN không ghi payload nhạy cảm; frontend kiểm tra giả query thành công, PROCESSING/REVIEW và chặn URL ngoài sandbox.

### Kết quả ngày 10/11/2026

| Kiểm tra | Kết quả |
| --- | --- |
| Commerce `verify checkstyle:check`, PostgreSQL test riêng | 342 test pass, không skip; JaCoCo line 91,07%, vượt gate 70% |
| Auction 3 suite handover/client/scheduler + Checkstyle | 15 test pass |
| Fulfillment InventoryServiceTest + InventoryPaymentRacePostgresTest + Checkstyle | 32 test pass, gồm 2 ca PostgreSQL thật |
| Gateway PaymentIpnSecurityTest | 1 test pass |
| Frontend 3 file payment/return/winner | 20 test pass |
| Frontend toàn bộ suite trước khi mở MR | 280 test trong 19 file pass; line coverage 73,30%; ESLint pass |
| Frontend TypeScript, production build, Prettier các file payment thay đổi | Pass; Vite còn cảnh báo bundle >500 kB |
| `git diff --check` | Pass |

Gateway không có cấu hình Checkstyle riêng trong pom; gọi `checkstyle:check` mặc định dùng ruleset khác và báo 27 lỗi sẵn có ở Java Gateway (package-info/độ dài dòng).
TT-S2-3 chỉ sửa YAML logging tại Gateway, không sửa các Java file này hoặc nới ruleset để che lỗi.
PostgreSQL test là cluster 18.6 tạm trên loopback, đã dừng sau khi chạy; không chạy vào DB dùng chung. Flyway hiện cảnh báo bản PostgreSQL mới hơn phiên bản đã kiểm chứng của thư viện, nhưng migration/validation và các test đều pass.

`npm run format:check` toàn frontend còn 35 file fail có sẵn trên `origin/dev`; không có lỗi format mới do phần payment.
Ảnh UI có nhãn HTTP fixture và hướng dẫn tái lập nằm tại `docs/test-evidence/tt-s2-3/README.md` (được bàn giao cùng MR frontend).
