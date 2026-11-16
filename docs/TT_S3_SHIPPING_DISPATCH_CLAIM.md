# TT-S3-06 — Claim dispatch tuần tự với tạo refund

Kế thừa Commerce producer !168/V21. V22 thêm shipping_dispatch_claims và state CLAIMED cho handoff; không cấp claim/backfill hoặc đổi state task lịch sử. Claim giữ quyền cho một handoff/Order/parcel và chặn refund mới, không gọi carrier hoặc chứng minh shipment đã tạo.

## API và IAM

`POST /api/v1/internal/commerce/shipping-dispatch-claims`, header `Idempotency-Key` UUID, body `{"handoffId":"UUID","parcelId":"UUID"}`. Cần đồng thời azp nexus-fulfillment-service và privilege COMMERCE.SHIPPING_DISPATCH; SHIPPING_READ, Admin/browser hoặc Auction không thay thế được. Caller audit lấy subject UUID từ JWT, không nhận từ body. Không mở route gateway cho namespace nội bộ.

Mới trả 201, replay cùng caller/key/body trả 200 với claimId/orderId/handoffId/parcelId/createdAt cũ. Thiếu UUID 400, subject sai 401, sai identity/quyền 403, không có handoff 404, state/key/tài chính không phù hợp 409, mặc định tắt 503. Dùng ApiResponse/ApiError hiện có, không xuất PII/merchant/providerRef hoặc token.

`app.shipping.dispatch-claim-enabled` / `SHIPPING_DISPATCH_CLAIM_ENABLED` mặc định false, độc lập SHIPPING_HANDOFF_ENABLED. IAM owner phải cấp privilege mới riêng cho service account Fulfillment, kiểm tra token thật trước bật; không cấp cho role người dùng. Thay đổi này chưa sửa realm/IAM triển khai hoặc bật flag ở môi trường đang dùng.

## Khóa và điều kiện

Claim khóa caller/key → parcel → Order → Payment. Tạo refund dùng cùng khóa Order trước kiểm tra claim và INSERT, nên chỉ một bên có thể thắng. Kiểm tra tài chính được đọc lại sau khóa, không dùng snapshot WAITING_PARCEL làm quyền gửi hàng.

- Order VNPAY PAID/PROCESSING, giá đã chốt, thông tin người nhận đầy đủ, không fulfillment REVIEW.
- Payment SUCCESS, kho CONFIRMED, kết quả 00/00, completedAt/reference/merchant hợp lệ, không DEMO_ONLY; tiền VND nguyên dương khớp Order/task/snapshot.
- Chỉ một SUCCESS của đơn; không refund khác FAILED, payment hold/review hoặc shipment binding đã có.
- Task còn WAITING_PARCEL; chưa có claim theo Order hoặc parcel. Parcel UUID do Fulfillment khai báo, Commerce chưa tự xác minh dữ liệu/kho/route bên Fulfillment.

INSERT claim và UPDATE task CLAIMED cùng transaction; rollback không giữ quyền hoặc key giả. Refund mới sau claim trả REFUND_SHIPPING_CLAIMED 409; replay refund đã tồn tại vẫn trả audit cũ, không tạo thêm giao dịch. Refund được ghi sau handoff nhưng trước claim sẽ chặn claim.

## Timeout, replay và phạm vi bảo vệ

Không có TTL, tự xóa claim hoặc API giải phóng: timeout/crash không chứng minh carrier chưa nhận. Tạm thời claim giữ chặn refund vô thời hạn tới khi có quy trình cancel/return/recovery được kiểm chứng và audit. Không bật trên môi trường thật khi chưa có consumer/recovery vận hành.

Replay là đọc kết quả đã chốt, không kiểm chứng lại eligibility hiện tại và không cấp quyền retry carrier. Fulfillment phải lưu claim/key/parcel bền vững, tái kiểm tra review phát sinh muộn và chỉ dùng durable dispatch guard trước HTTP. Claim không thay guard và không đảm bảo exactly-once tại carrier.

Hàng rào này bảo vệ claim với **tạo refund mới trong binary mới**. API dispatch cũ/manual chưa được chuyển sang claim; kết quả tài chính muộn vẫn có thể đặt hold sau claim. Trước bật phải drain binary Commerce cũ, nối/restrict mọi ingress dispatch liên quan, kiểm chứng consumer/preflight, IAM và recovery. Không giả đây đã là luồng tự động shipment hoặc hủy/refund sau giao carrier hoàn chỉnh.

## Kiểm thử và thứ tự tích hợp

PostgreSQL chạy IPN/commit kho/producer thật để tạo handoff, không INSERT task giả cho test quyền tài chính. Test claim/replay, chặn refund sau claim, refund/hold trước claim, race hai transaction và rollback state/key. MVC bảo vệ azp/privilege, caller UUID, key, 201/200; unit cổng tắt không chạm DB/khóa. Fixture không chứng minh IAM/provider sandbox hoặc UAT.

Merge Commerce !168 cùng các dependency của nó trước V22. Phần worker tích hợp kế tiếp cần Fulfillment !171 cùng dependency V11/V12; khi ghép dataset giữ Commerce 22 và Fulfillment 12. Receipt !165–!166 không phải dependency. Pipeline GitLab chưa xác nhận; không tự merge hoặc bật flag/call carrier trong thay đổi này.

Kiểm chứng local 14/11/2026: Commerce clean verify checkstyle:check exit 0 với PostgreSQL 18 tạm; 460 test/0 fail/error/skip, JaCoCo LINE 2430/2656 (91,49%). Phần mới gồm 4 ca PostgreSQL, 2 MVC và 1 unit cổng tắt. Node demo 19 test PASS; migration/seed replay/reset replay/reseed cả 7 service PASS với Commerce 22. Cluster tạm đã dừng; không gọi provider hoặc thay cấu hình triển khai.
