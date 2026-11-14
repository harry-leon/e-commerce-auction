# TT-S3-06 — Notification Shipping consumer

API Commerce notification hiện có hỗ trợ thêm ORDER_SHIPPED và ORDER_DELIVERED. Payload giữ id/userId/orderId/orderNo/type/amount/occurredAt, không có refundId, tracking hoặc PII người nhận. Dùng lại quyền NOTIFICATION_WRITER, owner scoping, dedup eventId, retry sau READ và push sau commit. Không đổi schema hoặc mở endpoint mới.

Thông điệp Shipping chỉ xác nhận trạng thái Order được Commerce gửi; không xác nhận đã thu COD, hoàn tiền hoặc hoàn tất đơn. Đây chỉ là inbox/push, chưa bổ sung template email Shipping. Consumer phải deploy trước khi bật worker shipping_notification_outbox ở Commerce; MR !156 mới ghi snapshot, chưa giao HTTP trong phiên bản đó.

Test PostgreSQL thật cho cả hai type bảo vệ quyền owner, dedup, giữ READ và 409 khi đổi type của eventId cũ. Test không chứng minh IAM thật/browser đã nhận push hoặc UAT. Không đổi schema nên không tăng demo version Notification.
