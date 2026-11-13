# TT-S3-06 — Kiểm thử tích hợp Shipping bằng IAM thật cô lập

`scripts/check-shipping-flow.mjs` chạy ba JAR Fulfillment/Commerce/Notification, PostgreSQL tạm và Keycloak 24.0.5 trong container riêng. Không đọc .env/realm đang dùng, không cấp quyền IAM live, không gửi email hoặc gọi merchant/carrier. Docker daemon phải chạy với local npipe/unix context và có image `quay.io/keycloak/keycloak:24.0.5` (cùng tag Compose); harness không tự pull hoặc chạy trên remote Docker context.

## Điều kiện và cách chạy

Node có fetch/parseArgs (đã kiểm chứng Node 22), Java 21, Maven với nexus-common đã install/cache, PostgreSQL bin và Docker local. Checkout phải có consumer !156, worker !157/!159, Notification !158, replay !160/!161 và health !162/!163. Bản sửa scheduler độc lập cũng bắt buộc nếu polling/sweeper đều tắt. Có thể truyền ba root checkout khác nhau để kiểm tra các MR chưa merge; script chỉ build service tương ứng trong mỗi root.

```powershell
$mavenTask = Get-ChildItem C:/Users/LEGION/.m2/wrapper -Filter mvn.cmd -Recurse | Select-Object -First 1 -ExpandProperty FullName
node scripts/check-shipping-flow.mjs --pg-bin 'C:/Program Files/PostgreSQL/18/bin' --maven $mavenTask
```

Thêm `--commerce-root <checkout> --fulfillment-root <checkout> --notification-root <checkout>` khi test các ref khác nhau. Mặc định build ba JAR bằng package, không chạy lại toàn bộ unit test. `--skip-build` chỉ dùng khi các JAR đúng source đã được build trước đó; report ghi SHA256 và reusedArtifact để tránh nhầm artifact cũ. checkoutHead ghi ref đang kiểm chứng, không thay thế checksum artifact hoặc bằng chứng source clean.

Credential/client/user/realm đều sinh mới trong temp, không in token/password. Chỉ notifier có NOTIFICATION_WRITER; Fulfillment có SHIPPING_EVENTS/SHIPPING_READ; client sai có SHIPPING_EVENTS để kiểm chứng azp thay vì vô tình chỉ thiếu role. Account Admin/Buyer riêng với subject UUID thật do IAM phát. Application dùng config JSON env allowlist, DB/port loopback riêng, merchant/refund/email và polling/sweeper bị tắt; worker Shipping được bật trong fixture này.

## Các ca kiểm chứng

1. Keycloak import realm thật, cấp grant thật; ba app tự xác minh JWT bằng JWKS/issuer.
2. Anonymous 401, Buyer/Admin/client sai 403 khi ghi shipping event nội bộ. Client sai thực sự mang cùng privilege.
3. Fulfillment outbox → Commerce inbox/Order → Notification inbox qua HTTP thật. Proxy chỉ forward request/JWT rồi cố ý trả 503 sau khi consumer đã commit Pickup/Delivered để mô phỏng mất ACK; không giả xử lý nghiệp vụ.
4. Hai worker retry cùng eventId/payload, Order DELIVERED, chỉ hai IN_APP; READ đặt trước retry không bị reset. Replay cũ không hạ state; cùng UUID đổi tracking trả 409; người khác không đọc được inbox owner.
5. Hai API Admin replay thật giữ auditId và READ. Dead letter cho bước này được seed/set bằng SQL fixture để kiểm tra thao tác recovery, không tuyên bố đã chờ đủ tám lần lỗi HTTP; retry budget đã có test PostgreSQL riêng ở các service.
6. Proxy giữ response sau consumer ACK, dừng thật JVM Commerce khi transaction outbox còn mở; DB giữ PENDING. Khởi động lại cùng DB, worker giao lại đúng event, không nhân bản thông báo hoặc tạo payment.
7. Health root không lộ component công khai; Admin thấy connectivityVerified=false như thiết kế health cục bộ.

Đơn PROCESSING và outbox được seed để giới hạn test ở transport. Đây **không** là bằng chứng checkout/thanh toán/provider thành công, tạo vận đơn tại carrier hoặc policy hủy/refund đã nghiệm thu. Không test browser/STOMP reconnect, SMTP, carrier sandbox, ingress HTTPS, backup/restore, tải lớn hoặc IAM đang triển khai. Các phần đó còn UAT riêng.

## Scheduler và cleanup

ShippingEventSchedulingConfig phải tự EnableScheduling; trước đó nó dựa vào annotation ở hai job có feature flag. Test hồi quy chỉ nạp config HTTP chứng minh worker vẫn tick khi không có polling/sweeper; integration tắt cả hai job và vẫn chạy worker thật.

Normal success/error đều dừng đúng child JVM, container ID và PostgreSQL do lượt đó tạo, rồi xóa realm credential file. Log/report/DB tạm được giữ cho điều tra; không commit credential hoặc toàn bộ log. Nếu runner bị kill cứng, finally không được bảo đảm: dùng resources.json trong artifact để dọn đúng container/pgdata của lượt đó, không xóa container/volume hoặc process ngoài fixture. Docker Desktop được người vận hành bật trước và không bị harness tắt.

Report PASS chỉ hợp lệ khi mọi case và cleanup đều thành công. Đây là kiểm thử tích hợp cục bộ với Keycloak thật trong realm cô lập, không phải nghiệm thu môi trường triển khai hay GitLab pipeline xanh.

Báo cáo ngày 13/11/2026 được sinh từ lượt chạy thật tại [evidence JSON](evidence/tt-s3-06-shipping-flow-2026-11-13.json): bảy nhóm kiểm thử PASS và cleanup=true. Ba JAR đã build thành công trước lượt chạy cuối; lần xác nhận cuối dùng --skip-build với checksum artifact được ghi trong report. Không commit log hoặc credential của realm fixture.
