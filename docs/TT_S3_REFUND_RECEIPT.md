# TT-S3-07 — Biên nhận hoàn tiền liên kết khoản thu gốc

Phụ thuộc API biên nhận thu tiền ở MR !165. Đây là document hoàn tiền nội bộ, không phải hóa đơn thuế/credit note theo pháp luật hoặc chứng nhận ngân hàng đã ghi có. Không thay workflow hủy/refund, không gửi lại tiền, không mở tự động restock và không sửa biên nhận thu gốc.

## API và quyền

`GET /api/v1/refunds/{refundId}/receipt` nhận JWT Buyer chủ đơn hoặc Admin, subject UUID. Seller/service privilege không thay role người dùng. Quyền sở hữu dùng cùng PaymentReceiptService của biên nhận thu gốc; trước khi trả lỗi trạng thái phải kiểm tra chủ đơn. Không tồn tại và sai chủ cùng 404 RECEIPT_NOT_FOUND. Không nhận actor/cờ Admin/amount từ request. Gateway hiện đã bao phủ `/api/v1/refunds/**`, không mở thêm namespace.

Trả JSON trực tiếp (không bọc ApiResponse) với `Content-Disposition: attachment; filename="refund-receipt-{refundId}.json"`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Lỗi dùng ApiError chung: 400 UUID sai, 401 thiếu JWT/subject UUID, 403 sai role, 404 không thuộc quyền truy cập, 409 chưa đủ bằng chứng. Lỗi từ biên nhận thu gốc giữ nguyên HTTP status, không giả document thành công.

Document version 1 gồm:

| Trường | Ý nghĩa |
| --- | --- |
| documentType | INTERNAL_REFUND_RECEIPT |
| receiptId / refundId | REFUND-{refundId} và UUID yêu cầu hoàn |
| originalReceiptId / paymentId | PAY-{paymentId}, liên kết khoản thu gốc |
| orderId | Đơn gốc, phải khớp biên nhận thu tiền |
| amount / currency | Số tiền hoàn được registry xác nhận, VND nguyên dương, không vượt tiền thu |
| recordedAt | Thời điểm registry ghi nhận bằng chứng; không phải thời điểm ngân hàng ghi có |
| evidenceSource | PROVIDER: kết quả worker; MANUAL: Admin đã đối soát theo workflow hiện có |

Không xuất providerRef/merchantCode, sourceId, actor Admin, evidenceReference, note, token hoặc thông tin giao hàng. MANUAL không được mô tả thành provider tự xác nhận qua callback.

## Điều kiện xuất

Refund phải SUCCEEDED; registry `refund_provider_receipts` phải khớp provider/merchant/amount/currency với request. PROVIDER cần sourceId khớp attempt SUCCEEDED và providerRef; MANUAL cần sourceId khớp audit quyết định SUCCEEDED, reference/amount/currency. Biên nhận Payment gốc cũng phải đủ bằng chứng và snapshot hợp lệ. Thiếu registry hoặc chỉ cập nhật status SUCCEEDED không đủ.

Có payment hold hoặc conflict của refund thì trả 409 dù status terminal đang được giữ SUCCEEDED để phục vụ audit. API không gỡ hold hoặc tự giải quyết mâu thuẫn. Các lần đọc dùng transaction REPEATABLE_READ để không ghép dữ liệu trước và sau đối soát đang commit; lỗi đọc DB giữ lỗi server, không fallback sang document thiếu bằng chứng.

ID và recordedAt lấy từ dữ liệu đã ghi, không cấp số hoặc timestamp mới lúc tải. Retry không tạo Payment/refund/audit/outbox. Biên nhận thu gốc vẫn phản ánh tiền đã thu, không tự trừ refund. Không có migration/registry phát hành mới; document này là bản xuất từ registry tài chính hiện có, chưa chống sửa DB bằng chữ ký số hoặc kho lưu chứng từ bất biến.

## Kiểm chứng và phần còn lại

Unit/MVC bảo vệ quyền, subject, trạng thái chưa hoàn tất, nguồn bằng chứng, giới hạn tiền và header riêng tư. PostgreSQL chạy Payment/IPN/worker hoặc đối soát Admin bằng fixture qua transaction thật; kiểm tra hai nguồn, replay ổn định, biên nhận thu gốc giữ nguyên, registry sai/thiếu và mâu thuẫn kết quả muộn bị chặn. Các ca fault injection sửa/xóa registry chỉ dùng DB test, không phải API vận hành.

Chạy `mvn -f backend/commerce-service/pom.xml clean verify checkstyle:check` với `NEXUS_TEST_DB_URL` chỉ trỏ PostgreSQL tạm. Không dùng DB triển khai. Test fixture không chứng minh merchant/carrier sandbox, IAM triển khai hoặc UAT đã đạt. Chưa triển khai hóa đơn điện tử, PDF/UI, registry phát hành bất biến/retention hoặc policy điều chỉnh do BA duyệt; TT-S3-07 vẫn cần nghiệm thu các phần đó theo phạm vi nhóm chốt.

Kết quả local 13/11/2026: clean verify và Checkstyle exit 0, 460 test/0 fail/0 error/0 skip; JaCoCo LINE 2288/2528 (90,51%). Phần mới gồm 10 unit, 3 MVC, 2 ca PostgreSQL và mở rộng ca mâu thuẫn muộn hiện có để chặn tải biên nhận. PostgreSQL tạm đã dừng; pipeline GitLab chưa được xác nhận.
