# TT-S2-1 — Sprint 1 Order/Payment baseline và Contract register

Ngày đối chiếu: 03/11/2026 (Asia/Saigon). Baseline nguồn: `origin/dev` tại `007f7bd19b4e45cecaeb49fcf370a763ca32a907`.

Phạm vi: khóa hợp đồng checkout/order/payment, inbox/outbox và transactional email trước khi mở luồng đấu giá Sprint 2. Tài liệu này mô tả source đã có và kết quả kiểm tra, không xác nhận pipeline/review thay người phụ trách Gate A.

## 1. Nguồn tích hợp

| Nhánh đầu vào | Tip đối chiếu | Kết quả với dev |
|---|---|---|
| `origin/feat/hg-s1-4-order-management` | `88b42e0` | Đã là ancestor của dev |
| `origin/feature/tt-s1-3-sandbox-payment` | `7d1012a` | Đã là ancestor của dev |
| `origin/feature/transactional-email-e2e` | `89eeaac` | Đã là ancestor của dev |
| `origin/feature/tt-s1-4-notifications` | các patch `1efc2f8`, `a04025b`, `2947a3a`, `ff2cf6e` | `git cherry origin/dev <branch>` đều `-`: đã có patch tương đương; không cherry-pick lặp |

Không cần merge lại code cũ hoặc thay implementation OrderService bằng nhánh payment. Bổ sung TT-S2-1 gồm contract hiện hành và regression test migration. Migration đã chạy giữ nguyên tên, nội dung và checksum.

## 2. Register public API

Prefix `/api/v1`. Gateway nhận cookie HttpOnly `nexus_access`, xác minh JWT và chuyển Bearer tới service; không lấy chủ đơn từ header X-User-Id. POST/PATCH/DELETE bằng cookie yêu cầu `X-Nexus-Request: 1`. Client backend dùng Bearer vẫn được hỗ trợ. Login/refresh không trả token qua JSON.

| ID | Method/path | Quyền và phạm vi | Request → kết quả |
|---|---|---|---|
| ORD-01 | POST `/orders/checkout/preview` | `CHECKOUT.START`; giỏ của sub | address, itemIds?, voucherCode? → 200 preview; thiếu kho vẫn 200 nhưng isOrderable=false |
| ORD-02 | POST `/orders` | `ORDER.CREATE`; giỏ của sub | CreateOrderRequest + UUID Idempotency-Key → 201 CreateOrderResponse, kể cả replay |
| ORD-03 | GET `/orders` | JWT; buyer mặc định, role=seller/admin phải đúng vai trò | Bộ lọc/phân trang → 200 list summary + meta.pagination |
| ORD-04 | GET `/orders/seller` | SELLER/ADMIN; service lọc shop | Bộ lọc/phân trang → 200 list summary |
| ORD-05 | GET `/orders/{id}` | Chủ đơn/chủ shop/ADMIN | UUID → 200 detail; người ngoài bị từ chối |
| ORD-06 | POST `/orders/{id}/cancel` | BUYER/ADMIN; kiểm tra chủ đơn và trạng thái | Body tùy chọn; nếu có, reason nonblank → 200 detail; không cancel VNPAY đang chờ/đơn đã thu tiền |
| ORD-07 | PATCH `/orders/{id}/status` | SELLER/ADMIN; đúng shop/state machine | status, reason? → 200 detail; không tự đánh dấu VNPAY PAID hoặc REFUNDED |
| PAY-01 | POST `/payments` | `ORDER.CREATE`, buyer sở hữu đơn | orderId → 200 PaymentResponse/URL đã lưu |
| PAY-02 | GET `/payments/{id}` | JWT, buyer sở hữu đơn | paymentId → 200 PaymentResponse, đọc trạng thái DB |
| PAY-03 | GET `/payments/vnpay/ipn` | Public đúng GET; HMAC-SHA512/merchant hợp lệ | query VNPAY → JSON RspCode/Message, không envelope |

`/preview` và `/create` trong mô tả ticket là tên bước nghiệp vụ, không phải alias endpoint. Baseline thực tế dùng ORD-01/ORD-02; không có POST `/orders/preview` hoặc `/orders/create`.

Shape DTO và ví dụ: [Commerce](dto-contracts/COMMERCE_SERVICE.md), [Gateway](dto-contracts/API_GATEWAY.md), [common envelope](dto-contracts/COMMON.md). Không thay CreateOrderResponse bằng OrderDetailResponse: response tạo đơn có paymentUrl, detail hiện không có field này. `selectedItemIds` là ID dòng giỏ, không phải skuId; tên field preview là `itemIds`.

## 3. Register notification/event

| ID | Producer → Consumer | Endpoint/shape | Bảo đảm hiện tại |
|---|---|---|---|
| EVT-01 | Checkout/IPN → Commerce DB | `commerce_notification_outbox` | ORDER_CREATED/PAYMENT_SUCCESS/PAYMENT_FAILED commit cùng đơn/payment |
| EVT-02 | NotificationWorker → Notification | POST `/internal/commerce-notifications`; id, userId, orderId, orderNo, type, amount, occurredAt | ROLE_NOTIFICATION_WRITER; 204 khi mới/replay đúng payload; 409 khi UUID có payload khác; không reset READ |
| EVT-03 | NotificationWorker → Email | POST `/api/v1/notifications/transactional-email`; OrderEmailRequest | Service account; UUID event ổn định; worker chỉ done khi email SENT; retry không gửi lại SENT |
| EVT-04 | Buyer → Inbox | GET `/notifications`, POST `/notifications/{id}/read`, POST `/notifications/read-all` | Chỉ dữ liệu user hiện tại; không cho buyer gọi ingestion/email như writer |

Email snapshot gồm recipientEmail/recipientName, items, paymentMethod và các field đơn liên quan; type ánh xạ ORDER_CREATED→ORDER_CREATED, PAYMENT_SUCCESS→ORDER_PAID, PAYMENT_FAILED→ORDER_PAYMENT_FAILED. Snapshot từ Order/JWT lúc tạo đơn, không lấy dữ liệu nhận từ FE khi gửi lại.

IN_APP và EMAIL được xử lý sau commit bằng HTTP, không phải Kafka và không gọi SMTP bên trong checkout/IPN. Worker dùng FOR UPDATE SKIP LOCKED; lỗi cấu hình hoặc gửi giữ backlog và retry. SMTP chưa có exactly-once: nếu process chết sau SMTP trước commit, có thể gửi lại; không mô tả là bảo đảm không trùng tuyệt đối.

Hợp đồng chi tiết: [Notification](dto-contracts/NOTIFICATION_SERVICE.md). Credential chỉ lưu môi trường, không thuộc register.

## 4. Register migration

Version đánh số riêng theo service. Ticket nhắc V4/V5/V6 nhưng Notification hiện dừng ở V4; không tạo V5/V6 rỗng hoặc đổi số migration để khớp tên ticket.

| Service/version | Thay đổi | Ảnh hưởng dữ liệu cũ |
|---|---|---|
| Commerce V4 | Thông tin người nhận, payment_method, request_hash | Cột mới nullable; không đổi tổng tiền/trạng thái |
| Commerce V5 | Payment URL/merchant/expiry, reservation snapshots, inventory outbox | Cột nullable; unique VNPAY attempt/order và success provider_ref cần dữ liệu cũ không trùng |
| Commerce V6 | Notification outbox unique(order_id,event_type) | Không backfill thông báo lịch sử |
| Commerce V7 | buyer_email, email_payload | Đơn cũ email NULL, payload {}; không tự gửi email đoán địa chỉ |
| Notification V3 | order_id/event_type và index inbox/event | Giữ audit cũ; unique chỉ áp event Commerce |
| Notification V4 | Unique event EMAIL | Giữ notification/attempts; bản EMAIL trùng event chỉ bị bỏ event_id, ưu tiên giữ bản SENT |

Điều kiện triển khai: sao lưu database trước upgrade, kiểm tra Flyway history/checksum; không dùng clean hoặc ddl-auto=create trên DB dùng chung. Catalog phải trả sellerId trong SKU snapshot; Fulfillment phải có kho mặc định/tồn khả dụng; cấu hình OAuth worker/merchant phải đủ trước tạo VNPAY mới.

## 5. Transaction, khóa và bảo vệ tài chính

- Checkout giữ thứ tự advisory lock **idempotency key → buyer**, rồi đọc replay trước khi đọc lại giỏ; cùng key khác buyer/payload trả 409. Không đổi thành khóa RAM hoặc xóa key khi restart.
- Order update/cancel khóa Order trong transaction; kiểm tra vai trò và chủ shop/chủ đơn tại service, ghi history cùng trạng thái. Không có API buyer đặt PAID/SUCCESS.
- IPN xác minh chữ ký và merchant trước truy cập DB, kiểm tra amount cả Payment/Order; amount = VND nguyên ×100. Cấm query trùng key. Sau đó khóa payment theo TxnRef và Order.
- `00/00` hợp lệ: Payment SUCCESS / Order PAID, enqueue CONFIRM; thất bại xác định: FAILED/CANCELLED, enqueue RELEASE. Callback terminal lặp trả 02, không tạo history/outbox hoặc trừ kho lần hai.
- IPN sai chữ ký trả 97, lệch amount trả 04, không tìm thấy trả 01; bất thường/commit lỗi trả 99 để retry/đối soát. HTTP 200 không tự có nghĩa thanh toán thành công.
- Worker kho dùng service account, retry sau commit; phiếu kho hết hạn trước IPN thành công cần đối soát. Checkout rollback cleanup vẫn phụ thuộc callback/TTL nếu HTTP release lỗi; chưa có bù trừ bền vững cho mọi lỗi reserve mạng.
- Return URL chỉ phục vụ trình duyệt; query `vnp_ResponseCode=00` không đủ chứng minh đã trả tiền. FE và test đọc PAY-02/ORD-05 từ DB sau IPN.

## 6. Bằng chứng kiểm tra Gate A

PostgreSQL 16 tạm, database riêng `commerce_baseline` và `notification_baseline`; không chạy test vào database demo hoặc Supabase. Mỗi ca migration thêm schema UUID riêng và dọn trong finally. Không sửa migration cũ.

| Kiểm tra ngày 03/11/2026 | Kết quả |
|---|---|
| Commerce `verify checkstyle:check` | 309 test, 0 failure/error/skip; coverage toàn report 89,74%; gate Maven pass |
| Notification `verify checkstyle:check` | 47 test, 0 failure/error/skip; coverage toàn report 88,24%; gate Maven pass |
| Gateway `verify checkstyle:check` | 16 test, 0 failure/error/skip; cookie/JWT, public GET IPN và route proxy pass |
| Fresh migration | Commerce V1→V7, Notification V1→V4; chạy lại không phát sinh migration |
| Upgrade có dữ liệu | Commerce V3→V7 giữ PAID/SUCCESS/amount, không backfill outbox; Notification V2→V4 giữ email/attempt và khử event trùng theo policy |
| Checkout concurrency/rollback | CheckoutPostgresTest: cùng key/khác key đồng thời, thiếu kho, lỗi commit và cleanup |
| IPN/Order kết hợp | PaymentPostgresTest: checksum/amount sai, callback đồng thời/replay, rollback history/outbox, retry kho/email, lifecycle/status/cancel đồng thời |
| RBAC HTTP/state machine | OrderControllerTest, CheckoutControllerTest, PaymentControllerTest, OrderLifecycleControllerTest và access-control/state-machine suites đều pass |
| Inbox/email PostgreSQL | NotificationPostgresTest, UserAuditPostgresTest, TransactionalEmailPostgresTest pass; SMTP dùng mock, không gửi thư thật từ test |

Đã kiểm tra thủ công một giao dịch sandbox của phiên làm việc: Payment SUCCESS và Order PAID qua database sau callback. Bằng chứng này không thay thế acceptance của provider/pipeline và không xác nhận mọi worker production đã cấu hình.

Để tái lập, tạo PostgreSQL tạm (user postgres, trust, chỉ bind loopback), tạo hai database riêng và dùng port được Docker cấp:

```powershell
# Container và DB chỉ dùng test, không dùng volume của compose demo.
docker run -d --name nexus-baseline-test -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1::5432 postgres:16-alpine
# Chờ pg_isready báo accepting connections trước khi createdb.
docker exec nexus-baseline-test pg_isready -U postgres
docker exec nexus-baseline-test createdb -U postgres commerce_baseline
docker exec nexus-baseline-test createdb -U postgres notification_baseline
docker port nexus-baseline-test 5432
# NEXUS_TEST_DB_URL chỉ trỏ DB tạm; các test dùng user postgres và password rỗng.
$env:NEXUS_TEST_DB_URL='jdbc:postgresql://127.0.0.1:<test-port>/commerce_baseline'
mvn -f backend/commerce-service/pom.xml verify checkstyle:check
$env:NEXUS_TEST_DB_URL='jdbc:postgresql://127.0.0.1:<test-port>/notification_baseline'
mvn -f backend/notification-service/pom.xml verify checkstyle:check
# Sau khi giữ báo cáo, dọn đúng container test vừa tạo.
docker rm -f nexus-baseline-test
```

Không có NEXUS_TEST_DB_URL thì integration suite bị skip: không dùng một lần chạy bị skip làm bằng chứng Gate A. Báo cáo chi tiết sinh tại `backend/<service>/target/surefire-reports` và `target/site/jacoco`; không commit log chứa dữ liệu phiên.

## 7. Trạng thái bàn giao

Source Sprint 1 đã hợp nhất vào dev; phần register và migration regression của TT-S2-1 được chuẩn bị trên nhánh feature để review vào dev. Chưa đánh dấu Gate A hoàn tất hoặc issue closed khi chưa có pipeline xanh và review theo CONTRIBUTING (payment cần hai approval). Reviewer theo ticket: fsa_HungHTQ và fsa_KhangLV.

Giới hạn giữ nguyên: chỉ checkout REGULAR, một shop/đơn, COD/VNPAY sandbox; chưa checkout AUCTION/CARD, chưa refund provider, chưa ship theo khoảng cách. Đây là ranh giới baseline cho Sprint 2, không được âm thầm báo các phần chưa triển khai là đã hoạt động.
