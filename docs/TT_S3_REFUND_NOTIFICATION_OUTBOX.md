# TT-S3-05 — Commerce refund notification outbox

V16 lưu snapshot sự kiện cùng transaction INSERT/đổi trạng thái refund. Rollback refund cũng rollback event. Không backfill refund cũ. Mỗi refund có `notification_version` tăng khi đổi state; unique `(refund_id,source_version)` chặn lặp. PROCESSING không phát tin. Conflict kết quả muộn phát cảnh báo riêng, kể cả khi giữ nguyên SUCCEEDED.

Contract HTTP dùng consumer MR !145: `id`, `refundId`, `userId`, `orderId`, `orderNo`, `type`, `amount`, `occurredAt`. Buyer và amount lấy từ Commerce DB; không gửi email, note, evidence hoặc provider reference. REQUESTED/SUCCEEDED/FAILED lần lượt phát REFUND_REQUESTED/REFUND_SUCCEEDED/REFUND_FAILED; UNKNOWN/REQUIRES_REVIEW/conflict phát REFUND_REQUIRES_REVIEW. Thời gian là UTC. EventId giữ nguyên qua retry. Đây là contract hiện hành, chưa có envelope version/correlationId dùng chung toàn hệ thống.

Worker gọi riêng inbox/push bằng service account, không gọi API email chưa có template refund. Thiếu OAuth giữ backlog. Mỗi tick tối đa 10 dòng, scheduler riêng; `FOR UPDATE SKIP LOCKED` chia việc giữa instance. HTTP giữ khóa dòng outbox trong transaction nhưng không khóa Order/Payment/refund; timeout của NotificationDelivery hữu hạn. Crash sau ACK có thể gửi lại, consumer phải dedup eventId. Chỉ ACK 204 được đánh dấu DELIVERED. Không cam kết thứ tự giao giữa các event khi một event đang backoff; consumer lưu từng event riêng với occurredAt, không dùng notification để cập nhật state tài chính.

Lỗi tăng attempts, backoff 2–256 giây; lần lỗi thứ tám chuyển DEAD_LETTER. Chỉ lưu tên class lỗi, không lưu response body/message/token. Không có retry mutation refund. V17 bổ sung API replay có audit bên dưới; chưa có Admin UI hoặc cảnh báo vận hành tự động.

## Triển khai và kiểm tra

1. Merge/deploy Merchant transport !143 và refund worker/schema !144 trước producer; deploy consumer !145 trước khi bật kết nối OAuth của producer. MR producer target dev, chứa commit phụ thuộc !143/!144 cho đến khi các MR đó được merge.
2. Producer cần Commerce V16; replay cần V17, phụ thuộc MR producer !146. Notification consumer cần V6. File demo trên nhánh này chỉ đổi version Commerce, version Notification do MR !145 cập nhật.
3. Test dùng PostgreSQL tạm và HTTP mock, không dùng merchant thật, không bật flag dispatch tài chính. Kiểm tra snapshot/rollback, cùng state không thêm event, mất ACK giữ payload/id, backoff/dead letter, hai worker và OAuth thiếu.
4. Kiểm tra backlog bằng `SELECT status,count(*) FROM refund_notification_outbox GROUP BY status;`. Khi gặp DEAD_LETTER cần xác định lỗi consumer/OAuth trước khi replay; không sửa eventId/payload để né dedup.

## API replay dead letter

`POST /api/v1/refunds/deliveries/{eventId}/retry`, JWT Admin có subject UUID, header `Idempotency-Key` UUID; body `{"reason":"Đã sửa cấu hình OAuth theo incident nội bộ"}`. Lý do bắt buộc, tối đa 500 ký tự, không ghi secret/token. Gateway đã route `/api/v1/refunds/**`, không cần thêm route. OpenAPI mô tả endpoint tại RefundController.

API trả 200 với `data.auditId`; đây là xác nhận đã đưa vào hàng đợi, chưa phải giao thành công. Chỉ DEAD_LETTER mới được reset attempts=0, next_attempt_at=now(), last_error_type=null. Audit và reset commit cùng transaction; không gọi HTTP, sửa eventId/payload, trạng thái refund hay payment. Key theo actor: cùng event/lý do đã trim trả audit cũ ngay cả khi worker đã giao, key khác nội dung hoặc event chưa dead letter trả 409. Event không tồn tại trả 404; request thiếu/sai key trả 400; lý do vi phạm Bean Validation trả 422; thiếu đăng nhập trả 401, sai role trả 403. Hai Admin cùng replay một event chỉ một request được reset, request còn lại trả 409.

Chưa có API liệt kê dead letter: vận hành lấy eventId qua truy vấn DB chỉ đọc `SELECT id,refund_id,attempts,last_error_type FROM refund_notification_outbox WHERE status='DEAD_LETTER';`. Audit nằm trong refund_notification_replays. Cùng event có thể replay lần mới bằng key mới nếu đã trở lại DEAD_LETTER; mỗi lần đều lưu audit riêng. Lý do/audit không được gửi vào payload Notification.

Phạm vi còn lại: Admin UI/liệt kê dead letter/cảnh báo vận hành, refund email, envelope thống nhất, kiểm thử hai service thực với IAM và reconnect/push, sandbox provider, nối Shipping và UAT. Chưa có bằng chứng pipeline GitLab xanh chỉ từ kết quả test local.
