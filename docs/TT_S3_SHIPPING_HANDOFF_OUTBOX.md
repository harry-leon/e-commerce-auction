# TT-S3-06 — Producer ý định bàn giao sau tiền/kho

Phần đầu của luồng tự động tạo shipment: Commerce ghi việc cần chuẩn bị giao hàng sau khi VNPAY đã xác nhận và kho commit. Chưa có transport/consumer Fulfillment hoặc tự gửi carrier. WAITING_PARCEL là ý định, **không phải quyền dispatch**.

## Phụ thuộc và schema

Nhánh kế thừa Commerce Shipping !156, !159, !161 và !163; merge các dependency trước để V18–V20 có mặt trước V21. Không phụ thuộc receipt !165–!166 hoặc test Notification !167. Không triển khai V21 trước rồi bật out-of-order để né thứ tự migration.

V21 thêm `shipping_handoff_tasks`: UUID id/orderId/paymentId, amount VND, state WAITING_PARCEL và createdAt. Unique theo Order/Payment chặn việc trùng qua IPN, worker nhiều instance hoặc restart. Không lưu địa chỉ/điện thoại, merchant/providerRef hoặc token. Không backfill đơn cũ, không phát event cho dữ liệu demo/lịch sử; không xóa hoặc cập nhật record cũ khi replay.

## Producer và cổng cấu hình

`app.shipping.handoff-enabled` / `SHIPPING_HANDOFF_ENABLED` mặc định false. Bật chỉ mở ghi DB, không mở mutation carrier/provider. Giá trị cấu hình được đọc khi khởi tạo bean. Khi tắt, đơn mới vẫn hoàn tất nghiệp vụ tiền/kho như trước nhưng không ghi handoff; bật sau không tự tạo lại việc cho đơn cũ đã CONFIRMED. Cần quy trình backfill có kiểm chứng riêng nếu triển khai muộn, không ép worker/SQL để suy ra quyền gửi hàng.

PaymentInventoryWorker giữ khóa Order → commit kho bằng ref ổn định → chốt Payment.inventoryStatus CONFIRMED và Order PAID → ghi handoff → history/notification → commit transaction. `ShippingHandoffOutbox.enqueue` yêu cầu transaction MANDATORY; lỗi ghi DB hoặc deferred commit rollback cả PAID/CONFIRMED/task.done/handoff. Retry commit kho cùng ref theo contract idempotent hiện có rồi ghi lại đúng một ý định. Không thêm network call hoặc transaction độc lập.

Chỉ VNPAY SUCCESS với bằng chứng IPN 00/00, completedAt/reference/merchant, giá chính thức khớp amount/snapshot, kho CONFIRMED, thông tin người nhận không trống được xét. Chặn DEMO_ONLY, COD, dữ liệu chưa chốt/thiếu, Order fulfillment REVIEW. Truy vấn trong khóa Order còn chặn refund khác FAILED của bất kỳ Payment cùng đơn, payment hold/review, nhiều giao dịch SUCCESS hoặc shipping binding đã có. Không thay trạng thái tiền hoặc tạo refund khi bị skip.

## Những phần bắt buộc trước dispatch

1. Fulfillment lưu/duyệt kiện thật: warehouse, carrier/service, weightGrams, district/ward; không dùng 500g hoặc địa chỉ mặc định. Preview hiện có chưa lưu các dữ liệu này.
2. Contract bàn giao và service identity; consumer phải revalidate Payment/Order/refund/hold lúc nhận, không dùng amount/state của task làm authorization.
3. Claim/quyền dispatch bền vững, tuần tự với tạo refund/hủy để giải quyết race sau khi handoff đã ghi. Refund có thể xuất hiện sau WAITING_PARCEL; producer này không khóa giao dịch tài chính vô hạn hoặc tự hủy task.
4. Transport/ACK/recovery và durable dispatch guard hiện có trước HTTP carrier; mất ACK không được gửi mutation mới. Replay/backfill cần audit riêng, không xóa dấu gửi để thử lại.
5. IAM/provider sandbox, callback HTTPS và UAT trên bản tích hợp. Chưa bật producer hoặc gọi carrier trên môi trường đang dùng trong thay đổi này.

Không có API mới, không đổi event schema ngoài mạng hoặc gateway. Health Shipping notification hiện tại không theo dõi bảng WAITING_PARCEL này; chưa gắn SLA/alert hoặc chính sách retention. Có row handoff không chứng minh đã có shipment, đã bàn giao carrier hoặc đủ điều kiện bật tự động dispatch.

## Kiểm thử

Unit kiểm tra flag tắt/COD/dữ liệu thiếu không chạm DB. PostgreSQL chạy Flyway thật cho REGULAR/AUCTION, IPN trước kho không tạo việc; hai worker và replay giữ id/snapshot; refund/hold ngăn ý định; deferred commit rollback rồi retry; gọi enqueue ngoài transaction bị chặn. Baseline migration bảo vệ không backfill. Dataset Commerce nâng 21 để seed/reset/reseed kiểm tra đúng schema mới, không seed handoff giả.

Kiểm chứng local 13/11/2026: Commerce `clean verify checkstyle:check` exit 0, 453 test/0 fail/error/skip; JaCoCo LINE 2385/2612 (91,31%). Có 5 ca PostgreSQL mới và 2 unit producer. 19 test demo Node đạt; cả 7 service qua migration/seed replay/reset replay/reseed trên PostgreSQL tạm. Các cluster tạm đã dừng; không gọi carrier/provider hoặc đổi cấu hình triển khai. Pipeline GitLab chưa xác nhận.
