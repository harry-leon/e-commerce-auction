# TT-S3-06 — Contract cho worker sau claim

Commerce bổ sung API nội bộ để Fulfillment nối kiện đã khai báo với handoff và kiểm tra lại tài chính trước dispatch. Không migration hoặc gateway route mới, không bật flag/IAM hoặc gọi carrier.

- `GET /api/v1/internal/commerce/shipping-handoffs/{orderId}` cần azp nexus-fulfillment-service + COMMERCE.SHIPPING_DISPATCH. Trả id/orderId/state WAITING_PARCEL hoặc CLAIMED; 404 nếu chưa có ý định, không tự tạo task.
- `POST /api/v1/internal/commerce/shipping-dispatch-claims/{claimId}/preflight` cần thêm COMMERCE.SHIPPING_READ vì trả snapshot người nhận. Caller subject UUID phải là tài khoản đã giữ claim; tài khoản khác trả 404. Không nhận actor/quyền/amount từ body.

Preflight khóa Order → Payment và dùng chung điều kiện với cấp claim: tiền/kho đã xác nhận, giá khớp, không refund/hold/review, không có shipment binding. Với claim đã tồn tại, task phải CLAIMED và UUID claim/handoff/parcel khớp. Trả claim kèm snapshot checkout hiện tại dưới cùng transaction, không ghép email/merchant/providerRef hoặc token. Thiếu điều kiện trả 409; flag SHIPPING_DISPATCH_CLAIM_ENABLED mặc định false trả 503. Lỗi giữ ApiResponse/ApiError chung.

POST claim replay vẫn chỉ trả lịch sử; preflight là bước riêng để chặn hold/review phát sinh sau claim. Endpoint không xóa/gia hạn claim và không cho phép retry carrier. Snapshot phản ánh một thời điểm, không hứa mọi trạng thái không thể thay đổi sau khi response rời Commerce. Refund mới tiếp tục bị claim chặn; kết quả tài chính muộn và ingress dispatch cũ cần quy trình vận hành/recovery đã nêu trong runbook claim.

Fulfillment phải giữ job/key/claim bền vững, chỉ gọi ShippingService có durable guard sau preflight và dấu DISPATCHING commit. Mất ACK claim được replay đúng key; mất ACK carrier không được dùng hai API mới để gửi lại mutation. READ/DISPATCH phải cấp đúng service account trước khi bật worker; không cấp cho Admin/browser/Auction. Đây chưa phải kiểm chứng IAM/provider sandbox hoặc UAT.

Kiểm tra: PostgreSQL IPN/kho/handoff/claim thật rồi preflight, kiểm tra snapshot/caller và hold muộn; replay vẫn giữ claim nhưng preflight bị 409. MVC bảo vệ identity, privilege kép, 404/503 theo service và caller UUID. Unit flag tắt không chạm DB. Toàn bộ Commerce clean verify/Checkstyle đạt 490 test, 0 fail/error/skip. Pipeline GitLab chưa xác nhận.
