# TT-S3-06 — Transport Fulfillment → Commerce

Worker giao sáu loại shipment trong fulfillment_event_outbox sang API nội bộ Commerce của MR !156. Không lấy InventoryInboundCompleted/SellerSkuRegistered hoặc aggregate khác SHIPMENT. Không đổi schema, không gọi lại carrier và không thay trạng thái shipment/Order trực tiếp.

## Cấu hình và rollout

- `app.shipping.commerce.events-enabled=false` mặc định, tương ứng `APP_SHIPPING_COMMERCE_EVENTS_ENABLED`; chỉ bật sau khi consumer !156 đã deploy và IAM được kiểm chứng.
- `app.shipping.commerce.events-delay-ms=10000` / `APP_SHIPPING_COMMERCE_EVENTS_DELAY_MS` điều khiển chu kỳ tick.
- Tái dùng URL/token-url/client-id/client-secret của CommerceShippingClient như tài liệu preparation; service account phải có COMMERCE.SHIPPING_EVENTS ngoài COMMERCE.SHIPPING_READ nếu dùng cả preview. Không dùng token Admin/Buyer và không lưu secret vào source.
- HTTP connect/read timeout 5 giây, không redirect/retry bên trong client. POST chỉ ACK 204 được coi đã giao; 401 xóa cache token cho lần retry sau. Lỗi chỉ mang status, không response body/cause nhạy cảm.

Scheduler HTTP riêng, scheduler mặc định riêng cho polling/sweeper; Spring dừng khi context đóng. Feature tắt hoặc thiếu OAuth giữ backlog nguyên vẹn. Chưa có health indicator riêng cho đường HTTP này; broker health không chứng minh Commerce reachable.

## Delivery và lỗi

Mỗi tick tối đa 10 row đến hạn. FOR UPDATE SKIP LOCKED chia việc giữa instance; transaction giữ khóa row outbox trong HTTP, không giữ khóa shipment/carrier hoặc Commerce Order. Worker kiểm tra id/type/shipmentId khớp metadata DB, orderId và occurredAt đúng định dạng trước gửi. Payload giữ nguyên snapshot/eventId khi retry; Commerce inbox chống trùng nếu mất ACK hoặc crash sau khi consumer commit.

ACK 204 đặt done=true, published_at và xóa last_error. Lỗi tăng attempts và backoff 2–256 giây. HTTP 400/422 hoặc snapshot lỗi đưa attempts=8 ngay; 409/404/401/403/5xx/network retry tối đa 8 lần rồi dừng. 409 có thể là dependency Delivered trước handover hoặc poison identity/tracking, không tự phân biệt bằng response text. Operator cần đối chiếu state/contract trước replay.

`done=false AND attempts>=8` là dead letter cho worker này; không xóa row hoặc báo done giả. Không sửa payload/id để né consumer dedup. Lỗi chỉ giữ `HTTP_status`, INVALID_SNAPSHOT hoặc tên class; không giữ thông điệp/token/PII. `last_error` chỉ có ý nghĩa vận hành, không dùng để quyết định trạng thái tài chính hoặc shipment.

V11 có [API replay Admin với audit](TT_S3_SHIPPING_EVENT_REPLAY.md), không reset attempts bằng SQL để né lịch sử. Truy vấn chỉ đọc: `SELECT id,event_type,aggregate_id,attempts,last_error FROM fulfillment_event_outbox WHERE aggregate_type='SHIPMENT' AND done=false AND attempts>=8;`. UI/list và health/alert vẫn cần bổ sung.

Không đảm bảo thứ tự tuyệt đối giữa event khi backoff. Commerce !156 giữ transition một chiều và trả 409 khi prerequisite chưa tới; backlog lâu hơn giới hạn retry cần xử lý dead letter. Worker không tự chuyển PAID→PROCESSING, không gọi createShipment, refund hoặc restock. Không có broker/Kafka producer mới trong thay đổi này; đây là HTTP transactional outbox.

## Kiểm tra và phần còn lại

PostgreSQL tạm + Flyway thật kiểm tra mất ACK giữ id/payload, backoff/dead letter, poison, không lấy event kho, hai worker không gửi cùng row và tắt/thiếu OAuth giữ backlog. HTTP localhost kiểm tra bearer, cache/401 refresh và chỉ ACK 204. Không gọi carrier/IAM thật hoặc thay flag môi trường đang dùng.

V11 bổ sung replay có audit; phần còn lại là UI/list, deploy/kiểm chứng IAM + consumer !156, HTTP health/alert và test tích hợp hai service thật/sandbox/UAT. Notification Shipping và worker Commerce → Notification thuộc !158/!159, cần review/deploy riêng. Test mock không chứng minh đã bật đường truyền hoặc đã gửi thông báo cho người dùng.
