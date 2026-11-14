# TT-S3-06 — Health Commerce → Notification Shipping

Indicator shippingNotificationTransport kiểm tra backlog shipping_notification_outbox, không gọi Notification/IAM và không kiểm chứng browser đã nhận tin. Kế thừa worker/replay !159/!161; consumer !158 cần deploy trước khi bật delivery. Không đổi schema/secret/flag live.

Worker tắt: UP/enabled=false/connectivityVerified=false, không query DB/OAuth. Worker bật thiếu cấu hình: OUT_OF_SERVICE/OAUTH_NOT_CONFIGURED. Khi bật và có OAuth, health đếm PENDING và DEAD_LETTER, bỏ qua DELIVERED. Có dead letter hoặc pending quá tuổi thì OUT_OF_SERVICE. Lỗi DB trả DOWN/DATABASE_UNAVAILABLE, không lộ SQL/exception. UP chỉ nghĩa cấu hình/backlog cục bộ không thấy lỗi, không chứng minh Notification reachable.

Ngưỡng `app.shipping.notification-backlog-max-age-seconds` / `APP_SHIPPING_NOTIFICATION_BACKLOG_MAX_AGE_SECONDS`, mặc định 300 giây, tối thiểu 1. Tuổi tính created_at kể cả đang backoff, không phụ thuộc next_attempt_at. Metadata chỉ mode/count/threshold/flag/reason, không eventId/userId/payload/URL/credential.

Actuator root công khai chỉ status; chỉ JWT Admin thấy component/details ở `/actuator/health/shippingNotificationTransport`. show-components/show-details when_authorized, roles ADMIN cũng áp dụng health component khác cho Admin. Không mở thêm metrics/refresh hoặc route gateway.

## Cảnh báo vận hành

- Disabled trong môi trường kỳ vọng delivery: kiểm tra rollout/flag, không coi UP là đã giao.
- OAuth thiếu: đối chiếu cấu hình/quyền NOTIFICATION_WRITER; không log secret/token.
- deadLetters>0 hoặc overdue>0 kéo dài: kiểm tra consumer !158, backlog và runbook replay có audit; không đổi id/payload hoặc bỏ qua contract.
- DOWN: kiểm tra DB/quyền/migration; indicator này không probe mạng, không tự kết luận Notification outage.

Đây là tín hiệu health và hướng dẫn điều kiện cảnh báo, chưa cài receiver email/Slack/Prometheus trên môi trường thật. Root aggregate có thể 503, ảnh hưởng Compose nếu dùng root health. Không dùng backlog làm liveness hoặc tự restart liên tục. Không tự thêm indicator vào liveness/readiness groups; vận hành chọn readiness theo policy. Query queue mỗi lần đọc health nên cần giới hạn tần suất khi backlog lớn.

Test unit bảo vệ policy/ẩn lỗi DB; PostgreSQL thật kiểm tra count/status/age; Spring Actuator thật trên schema riêng kiểm tra anonymous/Buyer không thấy metadata và Admin thấy disabled/connectivityVerified=false. Test ERD được giới hạn current_schema() để schema health/migration không gây đếm nhầm. Chưa E2E/IAM/browser reconnect/provider sandbox/UAT hoặc xác minh pipeline GitLab.
