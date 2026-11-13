# TT-S3-06 — Đối soát thủ công và khôi phục shipment

V10 thêm lifecycle lock và audit shipment_dispatch_resolutions. Nhánh kế thừa !154 (guard V9), !153/!150/!142; cần merge theo dependency. Trước khi mở API recovery trên môi trường thật, drain các instance/request dùng binary V9 cũ chưa giữ lifecycle lock; V10 backfill row khóa nhưng không thể buộc binary cũ tuân thủ khóa mới. Không gọi carrier thật trong thay đổi này.

## API Admin

`POST /api/v1/shipments/dispatches/{attemptId}/manual-resolution`, JWT Admin có subject UUID, header Idempotency-Key UUID. Body ví dụ:

```json
{"trackingNumber":"TRACKING_FROM_CARRIER","shippingFee":25000,"evidenceReference":"CASE-INTERNAL-123","note":"Đã kiểm tra đơn và vận đơn trên portal carrier"}
```

Tracking/fee là xác nhận thủ công do Admin đối chiếu bên ngoài, không phải kết quả query tự động. Chỉ xác nhận khi carrier thực sự đã có vận đơn đúng order/kho/người nhận; không đưa secret/token/PII vào note hoặc evidence. Tracking tối đa 100 ký tự, fee không âm/tối đa 15 chữ số/2 số lẻ, evidence 200 ký tự, note 500 ký tự và không rỗng.

API trả 200 với auditId/shipmentId. Thiếu JWT 401, sai role 403, subject sai UUID 401; thiếu/sai key hoặc command không hợp lệ 400; attempt không tồn tại 404; không phải UNKNOWN/tracking mâu thuẫn/snapshot hoặc kho/carrier không còn phù hợp trả 409. Gateway đã có /api/v1/shipments/** bao phủ endpoint mới. Không thêm role/public endpoint.

## Luồng và tính nhất quán

- Khóa actor/key để replay cùng nội dung trả audit cũ; key đổi attempt/tracking/fee/evidence/note trả 409. Nội dung chuỗi so sánh sau trim. Replay không đọc lại trạng thái để tạo audit mới.
- Lấy lifecycle lock theo order rồi khóa attempt; phải chờ dispatch đang chạy commit/rollback. Guard mới cũng đọc lại state sau khi lấy lifecycle lock để không gọi carrier nếu Admin đã chốt trong khoảng sau claim commit.
- Chỉ UNKNOWN được chốt. Nếu guard đã nhận tracking thì Admin phải dùng đúng tracking đó. Khi chưa nhận tracking, Admin có thể nhập tracking đã đối soát ngoài hệ thống. Tracking đã được gán shipment khác bị chặn; advisory lock tracking chia việc giữa các request recovery. Dữ liệu legacy/luồng khác chưa có unique tracking toàn DB, không coi kiểm tra này là bảo đảm toàn cục cho mọi writer.
- Nếu shipment cùng order đã tồn tại do callback guard lỗi sau commit, chỉ liên kết khi carrier/tracking/fee khớp, không sửa trạng thái hoặc phát lại ShipmentCreated.
- Nếu shipment chưa có, khôi phục từ snapshot gốc: kho/carrier/người nhận/địa chỉ/khối lượng/giá trị đơn. Chỉ fee/tracking từ bằng chứng Admin. Tạo shipment PENDING, history MANUAL_CONFIRMED và một ShipmentCreated outbox; PENDING không xác nhận đã lấy/giao hàng. Không áp lại quy tắc miễn phí hiện tại; fee là snapshot đối soát, freeShippingApplied giữ false vì không có bằng chứng về policy miễn phí gốc.
- Audit, shipment/history/outbox và attempt COMMITTED cùng transaction. Bất kỳ lỗi/rollback nào giữ UNKNOWN và không để lại shipment/audit nửa chừng. Không gọi createShipment, trackShipment hoặc reserve/commit/restock trong API này; không cập nhật Order/Payment/refund.

## Giới hạn và vận hành

Đây là recovery bằng xác nhận thủ công, chưa kiểm chứng tracking tự động qua provider. Không có nhánh xác nhận không tồn tại/hủy attempt để gửi lại. UNKNOWN thiếu bằng chứng tiếp tục bị chặn; không xóa/đổi row để ép retry. Chưa có UI/list endpoint: operator có quyền DB lấy attemptId qua truy vấn chỉ đọc như tài liệu guard !154. Snapshot PII chỉ lưu DB, không trả trong response/audit hoặc log.

Audit không cho ghi đè quyết định terminal. Nếu tracking sai, vận đơn sai người nhận hoặc carrier báo mâu thuẫn cần xử lý incident, không mở lại lệnh tạo đơn bằng API này. Fulfillment outbox có bản ghi không chứng minh đã chuyển tới Commerce/Notification; producer/consumer liên service, sandbox và UAT vẫn còn việc riêng.

## Bằng chứng test

PostgreSQL tạm chạy migration/repository thật để kiểm tra recovery + outbox/audit một lần, rollback nguyên tử, concurrent Admin và tracking mâu thuẫn/thiếu bằng chứng. Guard test kiểm tra lifecycle lock có waiter PostgreSQL thật khi dispatch đang chờ ACK. MVC test kiểm tra 401/403, actor JWT, key bắt buộc và auditId. Không dùng tài khoản hoặc provider thật.
