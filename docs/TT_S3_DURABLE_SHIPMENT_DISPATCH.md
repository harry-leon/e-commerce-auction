# TT-S3-06 — Dấu gửi vận đơn bền vững

V9 thêm shipment_dispatch_attempts, không backfill hoặc gửi lại shipment cũ. Mỗi order chỉ một attempt ở tầng guard. Nhánh kế thừa !153 (preparation), !150 (mutation không retry) và !142 (service identity). Commerce snapshot !152 cần cho preparation, chưa tự tạo shipment từ payment.

## Luồng hiện tại

1. ShippingServiceImpl kiểm tra shipment/key hiện có và resolve kho/carrier như trước. Spring bắt buộc tiêm ShipmentDispatchGuard; thiếu guard phải dừng trước carrier.
2. Guard đòi transaction shipment thật. Serialize CarrierCreateShipmentRequest đã chuẩn hóa (kho xuất, người nhận, định tuyến, cân nặng, tổng/COD), INSERT attempt UNKNOWN trong transaction REQUIRES_NEW rồi commit trước khi gọi carrier. Payload chứa PII giao hàng, chỉ lưu DB, không log hoặc trả về API công khai; quyền DB/backup phải theo policy dữ liệu đơn hàng hiện có.
3. Unique order_id chặn request khác key/instance và restart; không giữ row lock attempt trong HTTP. V10 giữ row khóa lifecycle riêng trong transaction shipment để recovery chờ dispatch đang chạy, rồi đọc lại state trước HTTP. UNKNOWN cũng bao gồm đang gửi/chưa biết, không đồng nghĩa thất bại. Không xóa hoặc reset theo timeout/tuổi bản ghi.
4. Carrier trả tracking hợp lệ: lưu tracking độc lập trước khi trả về ShippingServiceImpl. Shipment/history/outbox vẫn lưu trong transaction hiện hành. Chỉ afterCompletion COMMITTED kèm ACK đã lưu mới đánh dấu attempt COMMITTED. Rollback hoặc crash giữ UNKNOWN; tracking nếu đã nhận vẫn được giữ để đối soát.
5. Timeout/exception/ACK thiếu tracking giữ UNKNOWN, lưu tên class lỗi thay vì error message/body. Không retry hoặc giải phóng attempt tự động. Nếu ghi nhận lỗi/callback DB cũng thất bại thì attempt đã commit vẫn chặn gửi lại. Nếu callback sau commit không ghi được COMMITTED, UNKNOWN có thể tồn tại dù shipment đã lưu; endpoint tạo shipment vẫn trả shipment theo lookup/key hiện có trước guard.

REQUIRES_NEW cần thêm kết nối DB ngoài transaction shipment; pool phải đủ capacity cho mức đồng thời triển khai. Lớp orchestration hiện vẫn giữ transaction shipment trong HTTP như source cũ; guard chỉ tách commit dấu gửi và không bổ sung khóa Order/Payment bên Commerce. Không gọi provider thật trong kiểm thử.

## Đối soát và giới hạn

Request đã có attempt mà chưa tìm thấy shipment sẽ trả 409 SHIPMENT_DISPATCH_UNRESOLVED, không gọi carrier lần nữa. Đọc `SELECT id,order_id,carrier_code,status,tracking_number,last_error_type,created_at FROM shipment_dispatch_attempts WHERE status='UNKNOWN';` để phân loại. Snapshot request chỉ mở cho operator có quyền DB khi thực sự cần đối soát; không copy PII lên ticket/log.

V10 bổ sung [API Admin xác nhận tracking có audit và khôi phục shipment local](TT_S3_SHIPMENT_DISPATCH_RESOLUTION.md). Chưa có query-by-reference ở adapter hoặc workflow xác nhận không tồn tại để gửi lại; guard tiếp tục chặn retry mutation kể cả lỗi trước khi carrier nhận lệnh. Circuit OPEN vẫn giữ UNKNOWN sau claim. Không xóa row hoặc đổi status để ép gửi lại. Recovery thủ công không thay thế provider sandbox/E2E hoặc chứng minh đã đủ điều kiện bật producer tự động.

Chưa chuẩn hóa lại validation dữ liệu request tạo shipment hiện có hoặc sửa luồng OrderPaid dùng dữ liệu mặc định; preparation !153 mới preview. Không tự refund/restock, không sửa callback Order hoặc mở quyền mới. Demo reset không dùng để xóa dấu gửi của một order đã có attempt; muốn thử lại cần order fixture mới, không xóa lịch sử để né guard.

## Test

PostgreSQL tạm chạy migration thật ở schema UUID: carrier ACK rồi rollback giữ tracking, instance mới bị chặn; outer commit mới COMMITTED; caller nuốt exception rồi commit vẫn UNKNOWN; hai request đồng thời chỉ một supplier carrier được gọi; gọi ngoài transaction bị chặn trước INSERT. Unit test orchestration dùng guard fixture có ghi chú, không thay thế bằng chứng PostgreSQL. H2 thêm bảng fixture cho các test toàn luồng sẵn có, không dùng H2 để suy ra tính đúng khóa PostgreSQL.
