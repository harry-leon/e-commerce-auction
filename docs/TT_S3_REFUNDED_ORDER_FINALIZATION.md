# Chốt Order sau refund toàn phần đã xác minh

## Hành vi và phạm vi policy hiện thực

Admin gọi `POST /api/v1/refunds/{refundId}/finalize-order` với `Idempotency-Key` UUID và body `{"note":"Tham chiếu kiểm tra hủy đơn trước dispatch"}`. Mặc định trả 503 `REFUND_FINALIZATION_DISABLED`. Cấu hình `app.payment.refund-finalization-enabled` / `APP_PAYMENT_REFUND_FINALIZATION_ENABLED` mặc định false, đã map vào Commerce Compose. Chưa thay `.env` hoặc bật trên môi trường nhóm.

Đây là thao tác ghi nhận trạng thái **sau khi đã có bằng chứng refund**, không phải gửi tiền. Chỉ nhận Order PAID/PROCESSING, VNPAY, priceFinalized, đúng một Payment SUCCESS/inventory CONFIRMED và refund toàn phần bằng cả snapshot thu lẫn Order.total. Chặn Order REVIEW, payment REVIEW, hold/conflict, refund chưa đủ registry/nguồn bằng chứng, refund khác còn giữ hạn mức, claim hoặc shipping binding. Không hỗ trợ COD, partial refund, Order đã giao/đã hủy hoặc hủy carrier/return.

Validation biên nhận tái dùng RefundReceiptService/PaymentReceiptService: SUCCEEDED đơn lẻ không đủ, registry phải khớp attempt PROVIDER hoặc audit MANUAL, không có hold/conflict và snapshot thu hợp lệ. Merchant/reference snapshot refund phải khớp Payment, không nhận DEMO_ONLY hoặc reference chưa xác minh. Admin dùng đối soát MANUAL vẫn chịu trách nhiệm bằng chứng ngoài hệ thống; API không tự xác minh URL/bank statement.

Trong một transaction: khóa actor/key → Order → các attempt theo id → refund theo id → payment theo id; kiểm tra bằng chứng → ghi audit V23 → Order REFUNDED → history có auditId. Rollback thu hồi đồng thời cả ba. Cùng actor/key/refund/note trả 200 và audit cũ; mới 201; đổi nội dung hoặc tạo quyết định khác cho Order đã chốt trả 409. Actor chỉ từ JWT UUID/ROLE_ADMIN; Buyer/Seller/service không được gọi. Replay là đọc quyết định cũ, không tái xác nhận tình trạng hiện tại hoặc gửi tác dụng phụ.

API trả envelope hiện có, chỉ auditId/orderId/refundId/status/createdAt, không trả note hoặc provider reference. UUID/key/body sai 400; không JWT 401; sai role 403; refund không tồn tại 404; điều kiện không hợp lệ 409; flag tắt 503. Gateway `/api/v1/refunds/**` hiện bao phủ route mới; không mở namespace internal hoặc quyền service mới.

## Không phát sinh tác dụng phụ tài chính/kho

Không gọi provider, không sửa Payment/refund/registry, không phát lại notification refund, không enqueue release/restock. Biên nhận thu gốc giữ nguyên. Handoff có thể còn WAITING_PARCEL nhưng Order REFUNDED không đạt điều kiện claim/preflight; không xóa dấu dispatch để thử gửi lại.

Chỉ kiểm tra được claim/binding trong Commerce, không chứng minh carrier chưa nhận một lệnh từ đường legacy bỏ qua claim hoặc một thao tác ngoài hệ thống. Không bật khi còn ingress dispatch không được kiểm soát. Chưa có xác nhận nghiệp vụ cho hàng đã bàn giao, phí carrier, COD/partial hoặc chứng từ thuế; review policy với BA/nhóm trước rollout, không coi feature flag hay test pass là BA đã duyệt.

## Khóa đường đi vòng qua phán quyết tranh chấp

Luồng mới trên dev `resolve-dispute` từng cho lựa chọn `refunded` ghi REFUNDED rồi enqueue release kho mà không có bằng chứng thu/hoàn; `partial_refund` ghi COMPLETED. Hai lựa chọn này hiện trả lỗi nghiệp vụ và không ghi Order/history/kho. `released` giữ hành vi ghi COMPLETED hiện có, không thực hiện chuyển tiền provider. Order REFUNDED bị chặn mở lại qua `released`. Frontend có thể vẫn hiện lựa chọn refund nhưng phải hiển thị lỗi từ backend; phần UI quản trị riêng chưa sửa trong MR này.

Các comment/OpenAPI liên quan được sửa đúng hành vi. DTO vẫn parse các lựa chọn cũ để tương thích request shape; parse thành công không đồng nghĩa được phép đổi trạng thái tài chính.

## Migration, rollout và rollback

V23 tạo `refunded_order_finalizations`, unique Order/refund và actor/key, lưu previous_status/note/actor/thời điểm. Không sửa migration cũ, không backfill quyết định hoặc đổi Order lịch sử. Demo schema Commerce lên 23, không seed audit giả. Triển khai migration cùng binary, giữ flag false cho đến khi có review và bằng chứng môi trường.

Tắt flag chặn cả yêu cầu mới và replay HTTP; không xóa audit/đổi Order về PAID hoặc rollback migration để “hủy” quyết định. Kết quả provider mâu thuẫn đến muộn vẫn cần hold/review theo workflow hiện có; audit finalization là lịch sử, không tự đảo Order/kho.

## Kiểm tra

PostgreSQL bảo vệ receipt/registry, full amount, inventory CONFIRMED, trạng thái Order, hold, claim/binding, rollback và hai caller replay đồng thời. MVC bảo vệ role/status/envelope; OrderServiceTest bảo vệ phán quyết tranh chấp không giả refund và không mở lại Order đã hoàn tiền. Test fixture chỉ mock biên provider/kho; không thay thế sandbox/UAT.

Local ngày 15/11/2026: Commerce `clean verify checkstyle:check` exit 0, **513 test**, 0 fail/error/skip, JaCoCo LINE **91,62% (2669/2913)**; PostgreSQL tạm đã dừng. 19 test Node PASS, migration/seed replay/reset replay/reseed cả 7 service PASS (Commerce 23); cluster đã dừng. Compose config với credentials giả PASS; không khởi động môi trường nhóm. Chưa xác minh pipeline GitLab, provider sandbox hoặc policy BA đã được duyệt.
