# TT-S3-06 — Replay Shipping notification có audit

V20 thêm shipping_notification_replays, không reset backlog khi nâng schema. Kế thừa worker/schema !159 và inbox !156; Notification !158 phải deploy trước khi bật delivery. Đây là replay thông báo Commerce → Notification, khác API replay event Fulfillment → Commerce.

`POST /api/v1/orders/shipping-notifications/{eventId}/retry`, JWT Admin subject UUID, header Idempotency-Key UUID, body `{"reason":"Đã sửa cấu hình và đối chiếu sự kiện"}`. Lý do bắt buộc, tối đa 500 ký tự, không chứa secret/token/PII. Actor lấy JWT, không nhận actor hoặc payload do body khai. Gateway đã route /api/v1/orders/**. OpenAPI mô tả endpoint tại controller.

Chỉ DEAD_LETTER được chuyển PENDING/attempts=0/next_attempt_at=now()/last_error_type=null. EventId/payload/order state không đổi, không gọi Notification/carrier trong API. Audit và reset commit cùng nhau; rollback giữ dead letter và không để audit dở dang. Khóa actor/key rồi row event ngăn hai Admin cùng reset. Cùng actor/key/event/lý do sau trim trả auditId cũ ngay cả khi worker đã giao; đổi nội dung hoặc event chưa dead letter trả 409. Body/key sai trả 400, thiếu đăng nhập 401, sai role 403, event không tồn tại 404. Response 200 data.auditId chỉ xác nhận đưa vào hàng đợi, chưa phải đã giao thông báo.

Replay lại một vòng dead letter mới dùng key mới để lưu audit mới; không đổi payload/id để né dedup. Nếu source snapshot sai, API replay không sửa nội dung: cần incident/contract fix, không lặp vô hạn. Worker vẫn cần flag/OAuth đúng. Có [health backlog cục bộ](TT_S3_COMMERCE_TRANSPORT_HEALTH.md), chưa UI/list endpoint hoặc receiver cảnh báo live; không mở snapshot/note audit công khai. Không thu tiền/refund/đổi shipment từ thao tác replay.

Test PostgreSQL thật kiểm tra audit/key/body, snapshot giữ nguyên, rollback và concurrent Admin; MVC kiểm tra 401/403, subject UUID, key bắt buộc và auditId. Test không gửi Notification/IAM thật hoặc chứng minh browser nhận push.
