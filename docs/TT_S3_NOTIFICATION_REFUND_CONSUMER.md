# Notification refund consumer và broker health

## Contract mở rộng

Endpoint hiện có `POST /internal/commerce-notifications` tiếp tục yêu cầu ROLE_NOTIFICATION_WRITER. Bổ sung REFUND_REQUESTED, REFUND_SUCCEEDED, REFUND_FAILED và REFUND_REQUIRES_REVIEW. Các event này bắt buộc refundId UUID và amount VND nguyên dương. Các event cũ không gửi refundId; constructor Java Sprint 1/2 và JSON cũ vẫn được hỗ trợ.

V6 thêm notifications.refund_id nullable, không FK sang Commerce và không backfill tin lịch sử. EventId là khóa chống lặp, refundId là ngữ cảnh của từng yêu cầu; không dùng orderId/type để gộp hai refund khác nhau. Retry đúng payload ACK 204, đổi refund/người nhận/nội dung/ngữ cảnh đã lưu trên cùng UUID trả 409. Tin READ không bị reset khi retry.

Inbox REST trả thêm refundId nullable, vẫn lọc theo JWT subject. Push sau commit mang refundId; không push trước commit hoặc rollback. Nội dung chỉ xác nhận hoàn tiền với REFUND_SUCCEEDED do backend phát; REQUESTED/REVIEW không báo đã hoàn tiền và không hướng dẫn thanh toán lại. Không đưa provider reference, evidence hoặc credential vào event.

## Health và vận hành

Health contributor `notificationTransport` theo dõi BrokerAvailabilityEvent của simple STOMP broker hiện có. Khi push bật mà broker chưa chạy/đã dừng, trả OUT_OF_SERVICE; chủ động tắt push không làm inbox/email DOWN. Component chỉ công khai trạng thái cho Admin qua `/actuator/health/notificationTransport`; show-details vẫn never.

Transport hiện là SIMPLE_BROKER_SINGLE_INSTANCE, không phải broker ngoài hoặc push bền vững. Inbox là nguồn dữ liệu để resync sau reconnect; health UP không chứng minh browser đã nhận thông báo. Khi deploy đầy đủ service, có thể cấu hình readiness group gồm `readinessState,db,notificationTransport`; không đưa broker dependency vào liveness và không cấu hình group này cho test context chủ động loại datasource/health contributor.

## Phụ thuộc và giới hạn

Consumer/Migration V6 cần triển khai trước producer refund ở Commerce. Thay đổi này chưa phát sự kiện từ worker/manual-resolution, chưa thêm refund email template và chưa tích hợp broker ngoài/multi-instance. Role writer hiện hành được giữ tương thích; chưa chuyển toàn bộ notification ingress sang kiểm tra azp riêng.

Kiểm chứng local: Notification `verify checkstyle:check` pass, 61 test không skip, line coverage 88,70%; PostgreSQL tạm và SMTP/HTTP test fixture, không dùng DB nhóm. Test bảo vệ refund context, validation, replay/READ, rollback/push và lifecycle health. Migration/seed/reset/reseed cả 7 service qua với Notification V6. Chưa xác nhận pipeline GitLab hoặc E2E provider thật.

Phần kiểm chứng transport tiếp theo: [STOMP ký RSA/JWKS thật, quyền kênh, reconnect và broker stop/start](TT_S3_NOTIFICATION_STOMP_SECURITY.md). Lần chạy 13/11/2026 đạt 65 test không skip, coverage LINE 89,02%, clean verify/Checkstyle exit 0 với PostgreSQL tạm. JWKS/issuer là fixture loopback, không phải IAM triển khai; chưa kiểm tra browser/Gateway/HTTPS hoặc resync inbox sau offline.
