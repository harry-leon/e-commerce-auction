# TT-S3-06 — Điều kiện nối Commerce–Shipping

Rà source ShippingServiceImpl cho thấy OrderPaid hiện chỉ mang orderId, tạo CreateShipmentRequest với người nhận/địa chỉ trống và weight 500g mặc định. Chưa có producer Commerce gọi endpoint này. Chưa bật tự động tạo vận đơn từ payment trong thay đổi này.

## Sửa retry trước khi nối producer

CarrierResilienceManager chỉ cho phép retry/fallback với hai thao tác đọc đã biết: quote, trackShipment. createShipment và tên thao tác khác gọi action tối đa một lần trong mỗi invocation. Mạch OPEN vẫn chặn trước HTTP; lỗi ghi nhận vào circuit breaker và trả về caller, không dùng fallback để giả đã tạo vận đơn. Key idempotency local chỉ có tác dụng sau khi lưu shipment, không chứng minh carrier đã khử trùng khi mất ACK.

Giới hạn: chưa có dispatch attempt bền vững trước HTTP hoặc query-by-reference để phục hồi tạo vận đơn UNKNOWN. Caller/client gọi lại endpoint vẫn có thể gửi một lệnh carrier mới nếu kết quả trước chưa lưu DB. Thay đổi này chỉ bỏ retry tự động bên trong resilience manager, chưa cam kết exactly-once tại carrier. Không bật producer retry tự động trước khi xử lý giới hạn đó.

## Thứ tự còn lại

1. Contract snapshot giao hàng Commerce: recipient/address, district/ward, khối lượng thực và kho; không đoán mặc định hoặc truy cập DB khác service.
2. Lưu dispatch attempt/command trước HTTP, key ổn định theo order, trạng thái UNKNOWN khi mất ACK; chỉ retry mutation khi carrier có contract idempotency đã kiểm chứng hoặc đã đối soát chắc chắn chưa tạo.
3. Outbox Commerce sau payment/kho xác nhận, service account đúng quyền; không tạo shipment từ browser return hoặc payment đang REVIEW.
4. Inbox sự kiện shipment ở Commerce, kiểm tra nguồn/quyền/dedup và chuyển state đúng chiều; phát notification cùng transaction, không tự refund/restock từ callback giao thất bại.
5. Test hai service với IAM và carrier sandbox, timeout/restart/mất ACK/concurrent; mock pass chưa phải bằng chứng sandbox hoặc UAT.

Phần producer tiếp theo: [ý định bàn giao Commerce sau xác nhận tiền/kho](TT_S3_SHIPPING_HANDOFF_OUTBOX.md), V21 và cổng mặc định tắt. WAITING_PARCEL chỉ là việc chờ đóng gói; chưa có consumer/transport hoặc quyền dispatch tuần tự với refund. Không dùng row này để bỏ qua kiểm tra lại tài chính, kiện thật hoặc guard trước HTTP carrier.
