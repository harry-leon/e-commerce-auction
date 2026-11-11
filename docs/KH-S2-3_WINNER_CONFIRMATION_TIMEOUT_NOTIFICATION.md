# KH-S2-3 — Xác nhận kho người thắng, xử lý quá hạn 24h & tích hợp thông báo

Nhánh triển khai: `feature/winner-confirmation-timeout-notification` (tách từ `dev`).

## Phạm vi và luồng nghiệp vụ

### 1. Xác nhận trừ kho khi thanh toán thành công (`OrderPaid` → `InventoryCommitted`)
1. VNPAY IPN cho đơn đấu giá (`source_type = AUCTION`) ghi `Payment.status = SUCCESS`, `inventory_status = PENDING` và đưa tác vụ `COMMIT` vào `payment_inventory_tasks`.
2. `PaymentInventoryWorker` gọi `POST /api/v1/inventory/orders/{orderId}/commit` tại Fulfillment Service bằng service account `nexus-commerce-service` (`INVENTORY.ORDER`).
3. `InventoryServiceImpl.commitByOrder(orderId)` khóa bi quan phiếu giữ kho `ORDER`, trừ vĩnh viễn `reserved_qty` và `total_qty`, ghi sổ cái bất biến `inventory_ledger` (`movement_type = OUTBOUND`) và phát `InventoryCommittedEvent`; gọi lặp hoàn toàn lũy nghiệm.
4. `PaymentInventoryWorker` cập nhật `Payment.inventory_status = CONFIRMED`, chuyển `Order.status = PAID`, ghi `order_status_history` và đưa sự kiện `PAYMENT_SUCCESS` vào `commerce_notification_outbox`.

### 2. Thông báo Winner: Push thời gian thực + Email chúc mừng + Nhắc trước hạn 2h
1. Khi `OrderServiceImpl.createAuctionOrder` khởi tạo đơn thắng đấu giá mới:
   - Lấy `winnerEmail` / `winnerName` từ payload hoặc tra cứu nội bộ `GET /api/v1/internal/users/{userId}` tại `user-service` (`ServiceIdentity`: `nexus-commerce-service` + `USER.INTERNAL_READ`; fallback an toàn sang `IN_APP`/`PUSH` nếu `user-service` không khả dụng).
   - Lưu đơn với `paymentDeadline` (24 giờ) và ghi sự kiện `AUCTION_WON` vào `commerce_notification_outbox` trong cùng transaction.
2. `NotificationOutbox` đính kèm `auctionId`, `paymentDeadline`, `paymentLink` (`${FRONTEND_URL}/order-detail?id={orderId}`) và `penaltyScore`.
3. `AuctionPaymentReminderWorker` định kỳ quét các đơn `AUCTION` đang `PENDING_PAYMENT` nằm trong cửa sổ trước hạn thanh toán (`reminder-before-minutes = 120`), chưa có `Payment.status = SUCCESS` và chưa có `AUCTION_PAYMENT_REMINDER`, khóa đơn bằng `findForPaymentWithItems` và ghi tối đa một nhắc nhở nhờ khóa `UNIQUE(order_id, event_type)`.
4. `NotificationDelivery` chuyển cả hai kênh:
   - `POST /internal/commerce-notifications` (`notification-service`): lưu `IN_APP` idempotent; khi `PUSH_NOTIFICATION_ENABLED=true` (`notification.push-enabled`), lưu thêm bản ghi `PUSH` + `notification_attempts` và phát STOMP WebSocket real-time tới `/topic/users/{userId}/notifications` (bảo vệ qua `NotificationStompInterceptor`, chỉ cho chính chủ JWT subscribe).
   - `POST /api/v1/notifications/transactional-email`: gửi email chúc mừng/nhắc hạn/quá hạn qua `TransactionalEmailTemplateBuilder` kèm liên kết thanh toán và mốc deadline 24h (`GMT+7`).

### 3. Quá hạn thanh toán 24h & phục hồi nhất quán (`AuctionPaymentTimeout`)
1. `AuctionPaymentExpiryWorker` khóa từng đơn `AUCTION` còn `PENDING_PAYMENT` khi `payment_deadline <= UTC now` và chưa có thanh toán `SUCCESS`.
2. Trong cùng transaction:
   - Chuyển `Order.status = CANCELLED` (`AUCTION_PAYMENT_DEADLINE`).
   - Đưa `RELEASE` vào `payment_inventory_tasks` để Fulfillment giải phóng kho và ghi `inventory_ledger` (`RELEASE`) + `InventoryReleasedEvent`.
   - Đưa `REPUTATION_PENALTY` (`PAYMENT_TIMEOUT_PENALTY_SCORE = 10`) và `AUCTION_DEFAULTED` vào `auction_timeout_tasks` (`V12`).
   - Đưa `AUCTION_PAYMENT_TIMEOUT` vào `commerce_notification_outbox`.
3. `AuctionTimeoutSideEffectWorker` đọc `auction_timeout_tasks` bằng `FOR UPDATE SKIP LOCKED`:
   - Gọi `POST /api/v1/internal/reputations/penalties` (`nexus-commerce-service` + `REPUTATION.PENALTY`): `reputation-service` khóa hồ sơ `reputations`, kiểm tra khóa duy nhất `uq_user_penalty_ref (user_id, ref_type, ref_id)` tránh trừ hai lần, trừ 10 điểm với sàn `>= 0`, tăng `failed_tx` + `auction_failed`, cập nhật `trust_level` và phát `ReputationPenaltyAppliedEvent`.
   - Gọi `POST /api/v1/internal/auctions/{auctionId}/settlement/defaulted` (`nexus-commerce-service` + `AUCTION.SETTLEMENT_SYNC`): chuyển `auction_settlements.result` từ `SUCCESS` sang `DEFAULTED` một cách lũy nghiệm.
   - Khi Reputation/Auction/Notification lỗi mạng hoặc 401/5xx, outbox giữ lại tác vụ với backoff lũy tiến và không bao giờ làm rollback trạng thái hủy đơn hay giải phóng tồn kho.

## Migration & Keycloak

### Sửa lỗi review: JWT expiry, migration nhiều schema và đường push

- Transport Notification đóng socket mã 1008 trong tối đa một giây sau JWT hết hạn, kể cả client im lặng. Socket chưa CONNECT bị đóng sau 10 giây; disconnect/shutdown hủy timer.
- Web đi cùng origin qua nginx/Vite → Gateway /ws/notifications → Notification. Compose đặt NOTIFICATION_WS_URL=lb:ws://notification-service; chạy riêng mặc định ws://localhost:8086.
- Browser dùng cookie HttpOnly cho handshake, không đọc JWT và không đưa token vào URL. Kênh /user/queue/notifications do Spring resolve theo principal JWT; topic cũ được giữ để tương thích.
- Push chỉ phát sau commit DB. Frontend hiện toast và tải lại inbox qua REST; reconnect kiểm tra cookie bằng HTTP client, resync dữ liệu và backoff tối đa 30 giây. Logout/đổi tài khoản hủy request, timer và socket cũ.
- Push là tín hiệu best-effort, không bảo đảm giao tới browser khi offline; inbox DB và nút tải lại vẫn là nguồn dữ liệu bền vững. Simple broker chỉ phù hợp một Notification instance.
- V12 resolve constraint bằng OID của bảng trong search_path Flyway, tránh chọn nhầm schema cùng tên. Test PostgreSQL tạo hai schema để bảo vệ ca một schema đã V12 trong khi schema khác nâng từ V11.
- Khi web dùng cổng riêng như 3001, cấu hình WEB_ALLOWED_ORIGINS phải chứa đúng origin đó cho Gateway/Notification.

**Kiểm chứng lượt sửa review (11/11/2026):** Commerce 381 test và Notification 56 test qua với PostgreSQL 16 tạm riêng, không skip; cả hai qua verify/coverage gate/Checkstyle. Gateway verify qua 22 test. Frontend qua build, lint, 287 test, coverage dòng 73,72% và 6 test Playwright cho push/cookie/payment-return; REST/WS trong browser test dùng fixture, chưa thay nghiệm thu toàn bộ service với Keycloak thật.

nginx -t và Compose config qua. Prettier chỉ các file frontend thay đổi đều qua; format:check toàn repo còn báo 68 file hiện có (nhiều file checkout CRLF trên Windows). Gateway chưa có Checkstyle riêng trong pom nên gọi checkstyle:check mặc định dùng Sun rules và còn báo lỗi ở code nền; không thay toàn bộ quy ước/format service trong bản sửa này.

- **Commerce:** [V12__auction_timeout_reputation_notifications.sql](../backend/commerce-service/src/main/resources/db/migration/V12__auction_timeout_reputation_notifications.sql)
- **Notification:** [V5__auction_notification_push.sql](../backend/notification-service/src/main/resources/db/migration/V5__auction_notification_push.sql)
- **Keycloak realm (`deploy/keycloak/nexus-realm.json`):** Bổ sung quyền dịch vụ `REPUTATION.PENALTY`, `AUCTION.SETTLEMENT_SYNC`, `USER.INTERNAL_READ` cho `nexus-commerce-service`.

## Kết quả kiểm thử local (`mvn -o verify checkstyle:check`)

| Service | Tổng test chạy | Pass | Skip (thiếu env Postgres test DB) | Checkstyle | JaCoCo line coverage |
| --- | --- | --- | --- | --- | --- |
| `reputation-service` | 10 | 10 | 0 | 0 lỗi | 83.93% (256/305) |
| `notification-service` | 50 | 40 | 10 (`NEXUS_TEST_DB_URL` chưa đặt) | 0 lỗi | 82.77% (615/743) |
| `commerce-service` | 374 | 338 | 36 (`NEXUS_TEST_DB_URL` chưa đặt) | 0 lỗi | 80.95% (1555/1921) |
| `auction-service` | 148 | 148 | 0 | 0 lỗi | 83.10% (1195/1438) |
| `user-service` | 59 | 59 | 0 | 0 lỗi | 87.72% (507/578) |
| `fulfillment-service` | 75 | 73 | 2 (`FULFILLMENT_TEST_DB_URL` chưa đặt) | 0 lỗi | 81.37% (808/993) |

**Giới hạn còn lại:** Do môi trường local chưa khởi chạy container PostgreSQL test riêng (`NEXUS_TEST_DB_URL`, `FULFILLMENT_TEST_DB_URL`), 48 integration test phụ thuộc PostgreSQL (`PaymentPostgresTest`, `BaselineMigrationPostgresTest`, `CheckoutPostgresTest`, `NotificationPostgresTest`, `TransactionalEmailPostgresTest`, `InventoryPaymentRacePostgresTest`...) đã bị `@EnabledIfEnvironmentVariable` bỏ qua trong lượt chạy local và sẽ chạy trên pipeline CI khi có DB test.

