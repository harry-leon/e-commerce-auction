# TT-S3-06 — Commerce nhận sự kiện Shipping

`POST /api/v1/internal/commerce/shipping-events`, chỉ JWT có azp=nexus-fulfillment-service và privilege COMMERCE.SHIPPING_EVENTS. Admin/browser/Auction hoặc riêng COMMERCE.SHIPPING_READ không đủ. Chưa cấp role/client scope trong Keycloak thật; IAM owner cần cấp riêng cho Fulfillment service account trước khi nối transport. Không mở route gateway public cho namespace này.

Contract lấy từ Fulfillment outbox: eventId UUID, eventType, occurredAt UTC, shipmentId UUID, orderId UUID, carrier tối đa 30 ký tự, trackingNumber tối đa 100, status. Các type: ShipmentCreated, ShipmentPickedUp, ShipmentInTransit, ShipmentDelivered, ShipmentFailed, ShipmentReturned. Bỏ qua metadata warehouse/serviceType/shippingFee/location/reason chưa dùng; Commerce không nhận buyer hoặc giá từ producer. Type và status phải khớp. Đây chưa phải envelope version/revision chung; occurredAt được lưu làm bằng chứng, không dùng timestamp để ghi đè trạng thái mới hơn.

## Transaction và policy

V18 thêm inbox eventId, binding order/shipment duy nhất và shipping_notification_outbox. Không backfill dữ liệu cũ. Khóa eventId → Order → shipment binding; replay cùng payload đã chuẩn hóa trả ACK 204, eventId đổi nội dung trả 409. Binding không được đổi shipment/carrier/tracking sau khi đã ghi. Shipment không được gán sang order khác.

- ShipmentCreated: chỉ ghi binding/inbox, không tự chuyển PAID thành PROCESSING. Seller vẫn phải hoàn thành bước chuẩn bị hiện có.
- ShipmentPickedUp (PICKED_UP/SHIPPED) hoặc ShipmentInTransit: PROCESSING → SHIPPED; khi Order đã SHIPPED/DELIVERED/COMPLETED thì ACK và không hạ trạng thái/phát tin trùng.
- ShipmentDelivered: SHIPPED → DELIVERED; DELIVERED/COMPLETED chỉ ACK. Delivered đến trước pickup hoặc Order chưa PROCESSING trả 409, không lưu inbox để transport có thể retry sau khi dependency hợp lệ. Không tự chế thêm transition bỏ qua bước Seller.
- ShipmentFailed/ShipmentReturned: ghi review_event_type trong binding, giữ Order/Payment/refund/kho. Đây không phải bằng chứng đã hoàn tiền hoặc hoàn kho. Chưa có API/operator workflow xóa review marker.
- Transition chỉ chạy với giá đã chốt và fulfillmentStatus không REVIEW. Không tự chốt thu COD, không tạo PAID, CANCELLED, REFUNDED hoặc COMPLETED từ event Shipping.

State Order/history, binding, inbox và notification snapshot cùng transaction; lỗi bất kỳ rollback tất cả. Notification snapshot dùng buyerId/orderNo/total từ Commerce đã khóa, không chứa tracking/người nhận/evidence. Unique order/type giữ một ORDER_SHIPPED và một ORDER_DELIVERED. Event cũ được ACK nhưng không phát thêm history/thông báo.

## Transport còn lại

Consumer V18 lưu notification outbox. MR !158 bổ sung hai type ORDER_SHIPPED/ORDER_DELIVERED ở Notification; worker Commerce V19 giao inbox/push riêng, xem [runbook delivery](TT_S3_SHIPPING_NOTIFICATION_DELIVERY.md). Phải deploy consumer trước khi bật worker, không đưa loại mới vào luồng email chưa có template.

MR !157 bổ sung worker HTTP Fulfillment → Commerce mặc định tắt, retry/backoff và dead letter; replay có audit/health vẫn còn việc riêng. 409 có thể là dependency hoặc poison cần operator phân loại. Outbox/inbox tồn tại không chứng minh đã nối end-to-end. Provider sandbox, IAM thật, callback trực tiếp carrier, producer tự tạo shipment sau payment và UAT vẫn còn việc riêng.

## Lỗi và test

401 thiếu JWT; 403 sai client/quyền; 400 UUID/JSON/type sai; 422 thiếu field; 404 order không tồn tại; 409 replay đổi nội dung, binding/status/Order không phù hợp. Không trả raw payload/PII trong lỗi. ACK 204 chỉ sau transaction đã commit.

Test MVC kiểm tra service identity/validation; PostgreSQL thật kiểm tra pickup/delivered, replay/stale event, delivered trước pickup được retry, return không refund, rollback và chặn thay binding. Migration/seed kiểm tra không phát lịch sử giả. Test mock/local chưa là bằng chứng carrier/IAM hoặc pipeline GitLab xanh.
