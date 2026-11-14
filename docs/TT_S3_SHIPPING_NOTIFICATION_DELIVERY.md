# TT-S3-06 — Commerce → Notification Shipping

V19 thêm trạng thái delivery/attempts/backoff cho shipping_notification_outbox của !156. Backlog V18 trở thành PENDING, không sinh snapshot mới hoặc đổi Order; worker mặc định tắt. Consumer Notification !158 phải deploy trước khi bật worker. Worker Fulfillment → Commerce thuộc !157; các MR phải review/merge/deploy riêng theo service, không suy ra đã nghiệm thu toàn luồng từ bảng outbox.

## Cấu hình

`app.shipping.notification-enabled=false` / `APP_SHIPPING_NOTIFICATION_ENABLED` là flag bật delivery. `app.shipping.notification-delay-ms=10000` / `APP_SHIPPING_NOTIFICATION_DELAY_MS` điều khiển tick. Dùng NotificationDelivery với OAuth NOTIFICATION_WRITER hiện có; thiếu cấu hình giữ backlog. Chưa thay secret hoặc bật flag môi trường đang chạy. Đọc hướng dẫn cấu hình NOTIFICATION_* của luồng outbox hiện có, không truyền token qua browser.

Scheduler shippingNotificationScheduler riêng, không giữ luồng refund HTTP hoặc quét hạn đơn. Mỗi tick tối đa 10 row đến hạn, FOR UPDATE SKIP LOCKED trong HTTP chỉ giữ row outbox. NotificationDelivery có timeout hữu hạn và chỉ ACK 204 mới hoàn tất. Không gọi send() email: Shipping chưa có template, chỉ dùng sendInbox() cho IN_APP/PUSH. Không gọi carrier/Shipment API, không thay trạng thái tài chính.

Payload giữ nguyên id/userId/orderId/orderNo/type/amount/occurredAt từ snapshot Commerce. Worker kiểm tra id/order/type khớp row, userId UUID và occurredAt đúng định dạng trước HTTP; sai snapshot đưa DEAD_LETTER ngay. Mất ACK/crash sau commit consumer gửi lại cùng id/payload, Notification dedup và không reset READ. Test worker không thay thế bằng chứng người dùng đã nhận push trên browser.

Lỗi delivery tăng attempts và backoff 2–256 giây; lần lỗi thứ tám DEAD_LETTER. Chỉ lưu tên class/INVALID_SNAPSHOT, không message/body/token. Không retry liên tục trong cùng tick hoặc xóa sự kiện. Chưa phân loại HTTP status riêng trong worker này; lỗi consumer/OAuth đều retry hữu hạn rồi giữ để operator đối soát.

Truy vấn chỉ đọc `SELECT id,order_id,event_type,attempts,last_error_type FROM shipping_notification_outbox WHERE status='DEAD_LETTER';`. V20 có [API replay Admin với audit](TT_S3_SHIPPING_NOTIFICATION_REPLAY.md), health cục bộ có [runbook riêng](TT_S3_COMMERCE_TRANSPORT_HEALTH.md). Không reset id/payload bằng SQL để né dedup; UI/list và receiver cảnh báo live vẫn chưa triển khai.

## Bằng chứng và giới hạn

Notification !158 có test PostgreSQL cho hai type, owner scoping, READ/dedup và replay đổi nội dung. Commerce có test PostgreSQL cho mất ACK giữ snapshot, ACK-only, concurrent worker, tắt/thiếu OAuth, retry hữu hạn/dead letter và poison. NotificationDelivery HTTP contract đã có test localhost riêng, không gửi email Shipping.

V20 bổ sung replay có audit; còn UI/list, test hai service thật với IAM/browser reconnect, email Shipping, producer tự tạo shipment sau payment và UAT. Chưa bật flags hoặc gọi provider sandbox. Delivered thông báo trạng thái Order, không có nghĩa thu COD/refund/COMPLETED. Không cam kết thứ tự giao khi event cũ đang backoff; consumer lưu từng thông báo riêng và không dùng chúng cập nhật trạng thái tài chính.
