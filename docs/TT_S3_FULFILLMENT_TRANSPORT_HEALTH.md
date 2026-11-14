# TT-S3-06 — Health HTTP outbox Fulfillment

Indicator `shippingEventTransport` phản ánh worker !157 và backlog shipment, không gọi Commerce/IAM/carrier. Nhánh kế thừa replay !160; không đổi schema hoặc flag môi trường thật. `UP` là cấu hình/backlog cục bộ không phát hiện vấn đề, **không chứng minh downstream reachable**. Metadata connectivityVerified=false và mode=LOCAL_BACKLOG_ONLY ghi rõ giới hạn.

Worker tắt: UP, enabled=false, không query DB/xin token. Worker bật nhưng thiếu OAuth: OUT_OF_SERVICE/OAUTH_NOT_CONFIGURED, không thử mạng. Khi bật và có cấu hình, query chỉ sáu type shipment chưa done, không đếm backlog kho/SKU. Có dead letter (attempts>=8) hoặc pending quá tuổi thì OUT_OF_SERVICE; DB lỗi trả DOWN/DATABASE_UNAVAILABLE, không lộ SQL/exception/credential. Row đã done không tính backlog.

Ngưỡng tuổi qua `app.shipping.commerce.backlog-max-age-seconds` / `APP_SHIPPING_COMMERCE_BACKLOG_MAX_AGE_SECONDS`, mặc định 300 giây, tối thiểu 1. Tuổi tính created_at, không phải next_attempt_at: backoff kéo dài vẫn bị cảnh báo trễ. Không tự replay/reset, thay trạng thái Order hoặc gọi carrier từ health.

Actuator root chỉ lộ status cho anonymous/Buyer. Chỉ JWT Admin được xem component/details ở `/actuator/health/shippingEventTransport`; details chỉ count/threshold/flag/reason, không eventId, payload, URL hoặc secret. Cấu hình show-components/show-details when_authorized, roles ADMIN cũng áp dụng các health component khác của Fulfillment cho Admin. Không mở metrics/refresh mới hoặc cấp IAM thật.

## Quy tắc cảnh báo triển khai

- Worker được kỳ vọng chạy nhưng enabled=false: kiểm tra rollout/flag; không coi UP là delivery hoạt động.
- OUT_OF_SERVICE do OAuth: kiểm tra secret/client scope/consumer !156, không ghi token vào log/ticket.
- deadLetters>0 hoặc overdue>0 liên tiếp: operator đọc runbook transport/replay có audit, đối chiếu 409 dependency/poison trước replay. Không xóa row hoặc mở lại createShipment.
- DOWN do DB: kiểm tra kết nối/quyền/migration; không đánh đồng với Commerce outage vì indicator không probe remote.

Đây là tín hiệu health và runbook, chưa cấu hình receiver gửi email/Slack/Prometheus alert trên môi trường thật. Root aggregate có thể trả 503 khi indicator suy giảm; nếu Compose dùng root health, container có thể bị đánh dấu unhealthy. Không đưa backlog vào liveness hoặc tự restart liên tục để chữa dead letter. Liveness/readiness group hiện tại không tự được thêm indicator này; vận hành chọn readiness group theo policy triển khai. Query health đọc queue mỗi lần được gọi, cần kiểm soát tần suất probe khi backlog lớn.

Test unit bảo vệ disabled/OAuth/DB error sanitization; PostgreSQL thật kiểm tra count/age/dead letter/ACK; Spring MVC thật kiểm tra anonymous/Buyer không lộ details và Admin thấy disabled mà không hiểu nhầm connectivity. Không gọi downstream hoặc thay thế E2E/IAM/UAT.
