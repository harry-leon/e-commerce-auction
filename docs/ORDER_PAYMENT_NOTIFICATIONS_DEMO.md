# TT-S1-4 — Thông báo đơn/thanh toán và demo

## Phạm vi và luồng

Kênh triển khai Sprint 1: **IN_APP**. Buyer nhận ORDER_CREATED sau checkout, PAYMENT_SUCCESS khi IPN chuyển đơn PAID, PAYMENT_FAILED khi IPN thất bại xác định chuyển CANCELLED. Cần team xác nhận ba case/kênh này khi review; chưa có email/push/SMS.

Commerce ghi snapshot buyer/orderNo/total vào commerce_notification_outbox cùng transaction đơn/IPN, không gọi HTTP trong checkout/IPN. Rollback không có sự kiện ma, replay không thêm sự kiện. Worker dùng service account riêng, tối đa 10 sự kiện/tick (mặc định 5 giây), SELECT FOR UPDATE SKIP LOCKED. Lỗi log eventId/orderId/errorType, tăng attempts, retry từ 10 giây tăng dần tối đa 10 phút, không tự bỏ sự kiện. Thiếu OAuth cảnh báo một lần/instance và giữ pending. Snapshot không chứa JWT/thông tin giao hàng.

Notification ghi theo event UUID duy nhất. Mất ACK sau commit được retry nhưng không tạo tin/reset READ. notification_attempts lưu một SUCCESS khi đưa tin vào inbox; lần HTTP lỗi theo dõi tại outbox Commerce vì Notification có thể chưa nhận request. Mọi truy vấn/READ ràng buộc user_id theo JWT. FE phân trang/tải lại thủ công, không fallback mock và không hiển thị nút email/push chưa hoạt động.

## Cấu hình và phụ thuộc

Phụ thuộc Catalog seller snapshot, checkout TT-S1-2 và Commerce/Gateway/Web TT-S1-3. Nhánh TT-S1-4 tích hợp các nhánh này để kiểm thử. Khi publish tách MR Notification → Commerce → Web và ghi rõ dependency; không push trực tiếp dev. Payment cần hai approvals theo CONTRIBUTING.

1. Notification chạy migration V3, Commerce V6. Không sửa migration cũ, không backfill lịch sử.
2. Keycloak tạo role **ROLE_NOTIFICATION_WRITER**, client confidential riêng (ví dụ nexus-commerce-notification), bật service accounts/client authentication, tắt browser/direct access grants. Chỉ gán ROLE_NOTIFICATION_WRITER cho service account và bật scope roles để role xuất hiện trong realm_access.roles. Không cấp ADMIN/PROFILE_WRITER/manage-users, không cấp role writer cho BUYER.
3. Tạo secret tại Keycloak, cấp ngoài repo. `deploy/keycloak/nexus-realm.json` chỉ thêm định nghĩa role cho realm mới, không tạo client với secret mặc định. Import không cập nhật realm cũ: thêm role/client ở môi trường đã có dữ liệu, không reset volume nhóm.
4. Commerce nhận env:

| Env | Mục đích |
|---|---|
| NOTIFICATION_SERVICE_URL | URL Notification; Compose đã có http://notification-service:8080 |
| NOTIFICATION_TOKEN_URL | Keycloak nội bộ /realms/nexus-realm/protocol/openid-connect/token |
| NOTIFICATION_CLIENT_ID | Client riêng ở bước 2 |
| NOTIFICATION_CLIENT_SECRET | Secret cấp ngoài repo |
| APP_NOTIFICATION_POLL_MS | Tùy chọn, mặc định 5000 |

Compose chuyển tiếp TOKEN_URL/CLIENT_ID/CLIENT_SECRET. VNPAY và service account inventory theo [PAYMENT_SANDBOX_INTEGRATION.md](PAYMENT_SANDBOX_INTEGRATION.md). Thiếu OAuth thông báo không chặn COD/VNPAY, giữ backlog; thiếu merchant/inventory vẫn chặn VNPAY mới theo TT-S1-3.

## Demo tự động với dữ liệu riêng

Java 21, Node, Docker đang chạy, nexus-common đã install. Build `mvn package` tại backend/commerce-service, notification-service, api-gateway; sau đó tại root:

```powershell
node scripts/smoke-order-notifications.mjs
```

Script tạo container PostgreSQL tên ngẫu nhiên, DB riêng Commerce/Notification, chạy ba JAR thật và gọi HTTP qua Gateway. Dữ liệu sinh mỗi lần: hai buyer UUID, seller khác buyer, SKU TT4-DEMO giá 25.000 VND, quantity 2, một đơn success và một đơn failure, mỗi đơn 50.000 VND. Tạo cart/preview/checkout qua API, không seed DB nghiệp vụ trực tiếp. JWT ký RSA, service xác minh JWKS.

Catalog, Fulfillment, OAuth và VNPAY là **fixture cục bộ**, không kết nối merchant/tài khoản thật. Script bảo vệ các tình huống:

- Cart → preview → checkout và replay cùng key giữ nguyên orderId.
- Chữ ký IPN sai bị từ chối, success/failure hợp lệ đổi DB, callback lặp trả 02.
- Notification 503 giữ PAID/SUCCESS hoặc CANCELLED/FAILED, worker tăng attempts và retry.
- Mất ACK sau khi Notification đã lưu vẫn chỉ 4 tin/4 attempt SUCCESS cho hai đơn.
- Đúng orderNo/amount/buyer; buyer khác không thấy/READ được, buyer thường không được ingestion.
- Read/read-all, inventory confirm/release outbox.

Log ở thư mục tạm in cuối. Finally dừng PID/container do script tạo và xóa dữ liệu DB tạm; không đọc .env, không sửa DB/volume/tiến trình dev nhóm.

## Demo thủ công trên môi trường tích hợp

1. Chuẩn bị buyer A/B đăng nhập thật, seller khác buyer, SKU ACTIVE có seller snapshot, giá nguyên VND, tồn kho đủ ≥4; dùng công cụ seed Catalog/Fulfillment của nhóm. Ghi IDs thật trong ghi chú demo riêng, không commit token/mật khẩu.
2. A thêm quantity 2 vào giỏ, preview và checkout VNPAY. Ghi orderId/paymentId/amount. Vào hộp thư, Tải lại đến khi thấy ORDER_CREATED đúng orderNo/amount.
3. Thanh toán bằng sandbox chính thức. Chờ IPN xác thực và payment API SUCCESS/đơn PAID; browser return chưa đủ để kết luận đã trả tiền. Tải lại hộp thư thấy PAYMENT_SUCCESS.
4. Tạo đơn khác, thực hiện thất bại/hủy sandbox xác định; khi callback FAILED được chấp nhận sẽ có PAYMENT_FAILED và CANCELLED. Trạng thái không rõ chờ đối soát, không tạo tin thành công giả.
5. B không thấy tin của A; POST read ID của A trả 404. A READ rồi reload vẫn giữ trạng thái.
6. Trong môi trường demo riêng, dừng Notification rồi tạo/thanh toán đơn. Kết quả vẫn đúng, backlog/log lỗi có attempts. Bật lại và chờ next_attempt_at: giao đúng một lần. Không dừng Notification dùng chung khi chưa thống nhất với nhóm.

## Vận hành và test

Tại DB Commerce của môi trường được phép:

```sql
SELECT id,order_id,event_type,attempts,next_attempt_at
FROM commerce_notification_outbox WHERE NOT done ORDER BY next_attempt_at;
```

Sửa mạng/OAuth/role rồi worker tự retry. 409 payload conflict cần đối soát snapshot/eventId; không đổi buyer/amount để ép ACK hoặc xóa pending âm thầm. Chưa có alert/dead-letter UI. Worker giữ row lock outbox tối đa token + delivery timeout (~10 giây), không khóa Order/Payment; process chết sau remote commit được replay cùng UUID.

Backend: `mvn clean verify checkstyle:check`. Để chạy cả Postgres test, đặt NEXUS_TEST_DB_URL tới **DB PostgreSQL tạm riêng mỗi service**, username postgres/password rỗng theo fixture hiện có; không dùng DB nghiệp vụ. Thiếu biến thì chạy unit/HTTP mock, skip Postgres. FE: npm run lint, typecheck, format:check, coverage, build.

Kết quả local ngày 29/10/2026: Commerce 80 test không skip, Checkstyle 0, coverage dòng 94,54%; Notification 17 test không skip với PostgreSQL. Kiểm tra clean Notification theo CI không có DB cũng qua coverage gate (89,77%, 5 test Postgres skip). FE 209 test, coverage dòng 71,60%, lint/typecheck/format/build qua; build còn cảnh báo bundle >500 kB. Smoke ba ứng dụng thật qua Gateway đã qua cả success/failure, mất kết nối, mất ACK và quyền hộp thư. Các kết quả này không thay cho pipeline GitLab hoặc kiểm thử merchant thật.

## Giới hạn review/demo

- Smoke chưa chứng minh chạy merchant VNPAY thật/public IPN hoặc toàn bộ Catalog/Fulfillment/Keycloak thật cùng lúc.
- Chưa thông báo shipping/hủy thủ công/refund/auction, chưa preferences/email/push/SMS/realtime/badge toàn ứng dụng. Nội dung hiện tiếng Việt, IN_APP luôn bật cho ba case.
- Ghi outbox là phần transaction nghiệp vụ: lỗi DB khi enqueue rollback cùng transaction; lỗi HTTP gửi sau commit không đổi kết quả order/payment.
- Giữ chính sách một lần thanh toán mỗi đơn, TTL 15 phút và yêu cầu đối soát/refund của TT-S1-3. COD chưa có xác nhận giao hàng/thu tiền thật.
