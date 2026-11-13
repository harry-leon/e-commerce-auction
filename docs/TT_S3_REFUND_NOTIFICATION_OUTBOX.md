# TT-S3-05 — Commerce refund notification outbox

V16 lưu snapshot sự kiện cùng transaction INSERT/đổi trạng thái refund. Rollback refund cũng rollback event. Không backfill refund cũ. Mỗi refund có `notification_version` tăng khi đổi state; unique `(refund_id,source_version)` chặn lặp. PROCESSING không phát tin. Conflict kết quả muộn phát cảnh báo riêng, kể cả khi giữ nguyên SUCCEEDED.

Contract HTTP dùng consumer MR !145: `id`, `refundId`, `userId`, `orderId`, `orderNo`, `type`, `amount`, `occurredAt`. Buyer và amount lấy từ Commerce DB; không gửi email, note, evidence hoặc provider reference. REQUESTED/SUCCEEDED/FAILED lần lượt phát REFUND_REQUESTED/REFUND_SUCCEEDED/REFUND_FAILED; UNKNOWN/REQUIRES_REVIEW/conflict phát REFUND_REQUIRES_REVIEW. Thời gian là UTC. EventId giữ nguyên qua retry. Đây là contract hiện hành, chưa có envelope version/correlationId dùng chung toàn hệ thống.

Worker gọi riêng inbox/push bằng service account, không gọi API email chưa có template refund. Thiếu OAuth giữ backlog. Mỗi tick tối đa 10 dòng, scheduler riêng; `FOR UPDATE SKIP LOCKED` chia việc giữa instance. HTTP giữ khóa dòng outbox trong transaction nhưng không khóa Order/Payment/refund; timeout của NotificationDelivery hữu hạn. Crash sau ACK có thể gửi lại, consumer phải dedup eventId. Chỉ ACK 204 được đánh dấu DELIVERED. Không cam kết thứ tự giao giữa các event khi một event đang backoff; consumer lưu từng event riêng với occurredAt, không dùng notification để cập nhật state tài chính.

Lỗi tăng attempts, backoff 2–256 giây; lần lỗi thứ tám chuyển DEAD_LETTER. Chỉ lưu tên class lỗi, không lưu response body/message/token. Không có retry mutation refund. Chưa có API/Admin UI replay dead letter hoặc cảnh báo vận hành tự động; không coi phần vận hành transport đã hoàn tất.

## Triển khai và kiểm tra

1. Merge/deploy Merchant transport !143 và refund worker/schema !144 trước producer; deploy consumer !145 trước khi bật kết nối OAuth của producer. MR producer target dev, chứa commit phụ thuộc !143/!144 cho đến khi các MR đó được merge.
2. Flyway nâng Commerce lên V16; Notification consumer cần V6. File demo trên nhánh producer chỉ đổi version Commerce, version Notification do MR !145 cập nhật.
3. Test dùng PostgreSQL tạm và HTTP mock, không dùng merchant thật, không bật flag dispatch tài chính. Kiểm tra snapshot/rollback, cùng state không thêm event, mất ACK giữ payload/id, backoff/dead letter, hai worker và OAuth thiếu.
4. Kiểm tra backlog bằng `SELECT status,count(*) FROM refund_notification_outbox GROUP BY status;`. Khi gặp DEAD_LETTER cần xác định lỗi consumer/OAuth trước khi chuẩn bị replay có audit; không sửa eventId/payload để né dedup.

Phạm vi còn lại: replay có phân quyền/audit, refund email, envelope thống nhất, kiểm thử hai service thực với IAM và reconnect/push, sandbox provider, nối Shipping và UAT. Chưa có bằng chứng pipeline GitLab xanh chỉ từ kết quả test local.
