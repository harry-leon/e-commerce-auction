# TT-S3-06 — Replay event Fulfillment → Commerce có audit

V11 thêm shipping_event_replays, không reset backlog khi nâng schema. Kế thừa worker !157 và schema Fulfillment V10; cần consumer Commerce !156 trước khi bật transport. Đây là replay sự kiện HTTP, không phải gửi lại lệnh carrier hoặc replay thông báo Commerce → Notification.

`POST /api/v1/shipments/events/{eventId}/retry`, JWT Admin có subject UUID, header Idempotency-Key UUID, body `{"reason":"Đã sửa IAM và đối chiếu trạng thái Order"}`. Lý do bắt buộc, tối đa 500 ký tự, không đưa secret/token/PII vào đó. Actor không nhận từ body. Gateway đã route /api/v1/shipments/**.

Chỉ sáu type shipment của worker (Created/PickedUp/InTransit/Delivered/Failed/Returned), aggregate_type=SHIPMENT, done=false và attempts>=8 được replay. Event kho/SKU, pending hoặc đã ACK bị 409; event không tồn tại 404; request/key/reason sai 400; thiếu JWT/subject sai 401, sai role 403. Response 200 data.auditId chỉ xác nhận vào hàng đợi, không khẳng định Commerce đã nhận hoặc carrier đã tạo/giao hàng.

Audit lưu actor/key/event/reason/previous_attempts. Khóa actor/key rồi row outbox; audit và reset attempts=0,next_attempt_at=now(),last_error=null cùng transaction. Không thay eventId/payload/done hoặc dispatch attempt, không gọi HTTP/carrier trong API. Worker chỉ gửi snapshot cũ ở tick sau khi flag/OAuth đầy đủ. Hai Admin cùng event chỉ một request reset; cùng actor/key/event/reason sau trim trả auditId cũ ngay cả khi worker đã ACK. Key khác nội dung trả 409. Một vòng dead letter mới cần key mới để có audit riêng.

Phải xử lý nguyên nhân trước replay: 409 có thể Delivered trước handover hoặc identity/tracking mâu thuẫn; replay không sửa snapshot/state hoặc bỏ qua policy Commerce. Poison không thể sửa bằng cách đổi payload/id trong API này; cần incident và xử lý contract/source, không lặp vô hạn. V11 không cấp lại quyền createShipment và không xóa UNKNOWN tài chính/dispatch.

Có [health backlog cục bộ](TT_S3_FULFILLMENT_TRANSPORT_HEALTH.md), chưa UI/list endpoint hoặc receiver cảnh báo live. Operator lấy eventId từ truy vấn chỉ đọc trong runbook transport; audit không công khai qua response. API replay không thay thế IAM/sandbox/E2E/UAT hoặc chứng minh thông báo đã đến browser. Test PostgreSQL thật bảo vệ key/body/audit, rollback, concurrent Admin và không reset event kho; MVC kiểm tra 401/403/key/actor JWT UUID.
