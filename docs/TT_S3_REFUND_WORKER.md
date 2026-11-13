# Refund worker, đối soát và recovery

## Phạm vi đã có

V14 bổ sung approval theo từng refund, refund_attempts và refund_reconciliation_checks. Không backfill approval cho dữ liệu V13. Worker mặc định không gửi mạng, không thay đổi trạng thái Order/Payment, không release/restock kho và chưa phát event notification/chứng từ.

## Điều kiện gửi

1. Đã triển khai Commerce cùng migration V14.
2. Merchant sandbox có quyền refund/query được kiểm chứng, IP egress hợp lệ và snapshot payment đúng merchant.
3. `VNPAY_MERCHANT_API_ENABLED=true`, `VNPAY_REFUND_WORKER_ENABLED=true`, `VNPAY_MERCHANT_SOURCE_IP` là IP literal của server. Hai flag mặc định false, không tự thay `.env` khi triển khai source.
4. Admin tạo yêu cầu refund, sau đó gọi `POST /api/v1/refunds/{id}/dispatch-approval` với body `{ "note": "Tham chiếu kiểm tra hủy/return..." }`.

Endpoint approval lưu actor/thời điểm/ghi chú và trả trạng thái hiện tại; không có nghĩa đã hoàn tiền. Người duyệt phải xác minh điều kiện nghiệp vụ ngoài endpoint: chưa có API tự xác minh carrier cancellation/return. Duyệt lặp không sửa bằng chứng cũ. Chỉ Admin có quyền; Buyer/Seller/service token không được tự duyệt. Khi worker/transport đã bật, yêu cầu được duyệt có thể được gửi ở tick kế tiếp; không dùng approval để thử UI trên dữ liệu chưa được phép refund.

## Dispatch

- Scheduler riêng `refundTaskScheduler`, tick 5 giây, mỗi tick tối đa 10 yêu cầu và 10 query; không chặn các scheduler hạn đơn/outbox khác.
- Transaction claim khóa row bằng SKIP LOCKED, lưu UUID attempt/requestId, payload snapshot không có chữ ký/secret và lease 120 giây, đổi refund sang PROCESSING rồi commit.
- HTTP chạy sau commit, ngoài transaction. Mỗi refund có tối đa một attempt theo unique DB; không tự gửi lại mutation sau timeout, restart hoặc lỗi commit kết quả.
- HTTP response phải được VnpayMerchantClient xác minh chữ ký/command/merchant/ref/amount. Worker chỉ nhận SUCCEEDED từ refund type 02, ResponseCode 00, TransactionStatus 00 và provider refund reference hợp lệ/chưa được dùng ở refund khác. Mapping còn cần nghiệm thu merchant sandbox trước khi bật.
- Mã đang xử lý 94/05/06 vào UNKNOWN. Kết quả âm/bất thường khác vào REQUIRES_REVIEW; chưa tự ghi FAILED và giải phóng hạn mức. Cả UNKNOWN/REVIEW tiếp tục chiếm hạn mức.
- Nếu response đến sau lease recovery, ghi bằng chứng vào attempt và chuyển REVIEW; không ghi đè recovery thành thành công âm thầm.

## Recovery và đối soát

Lease hết hạn PROCESSING → UNKNOWN, giữ attempt/requestId gốc. Recovery vẫn chạy khi tắt flag mạng, không phát thêm refund. Không đảm bảo provider xử lý đúng một lần bằng cách retry: bảo đảm của hệ thống là không tự gửi attempt mutation thứ hai cho cùng refund khi chưa rõ kết quả.

UNKNOWN được query PAY gốc tối đa ba lượt, cách nhau ít nhất 60 giây. Mỗi lượt lưu requestId trước network, dùng lease thời gian để phân chia giữa instance. QueryDr không chứng minh một refund attempt đã hoàn tất: response chỉ lưu làm bằng chứng, không dùng PAY success để ghi refund SUCCEEDED. Hết ba lượt vào REVIEW; query chết ở lượt thứ ba cũng được recovery đưa vào REVIEW sau lease. V15 có [API đối soát thủ công](TT_S3_REFUND_MANUAL_RESOLUTION.md) với audit, idempotency và receipt registry; không tự giải phóng hạn mức chỉ dựa trên query.

Provider thành công nhưng DB finalize rollback: attempt PROCESSING đã commit vẫn còn. Sau lease, recovery đưa UNKNOWN; không tự gửi lại. Bằng chứng response có thể chưa được commit trong tình huống này, cần vận hành đối chiếu provider bằng requestId/payment reference. Không có distributed transaction với provider.

## Vận hành

Theo dõi refund_requests.status/review_reason/reconcile_count, refund_attempts.request_id/status/error_type/lease_expires_at và refund_reconciliation_checks. Log chỉ có refund ID và tên loại lỗi, không raw response, signed payload, secret hoặc exception cause. Không tự sửa DB sang FAILED để gửi lại yêu cầu chưa rõ kết quả.

Rollback source trước V14 chỉ sau khi tắt worker/transport và xử lý các attempt đang chạy. Không rollback/xóa migration hoặc mất attempt đang UNKNOWN. Chưa bật flag, duyệt yêu cầu hay chạy refund/query thật trong phiên phát triển này.

## Kiểm chứng

Test PostgreSQL riêng + HTTP mock bảo vệ: approval/flag, hai instance tranh claim, commit trước HTTP, snapshot không lưu hash, timeout/query không giả success, process crash, rollback finalize, response muộn và query cuối chết. MVC kiểm tra quyền approval. Đây không phải nghiệm thu provider sandbox. Không chạy test vào DB nhóm.

Kết quả local: Commerce `verify checkstyle:check` đạt 411 test, không skip, line coverage 89,47%. 19 test fixture đạt; migration V14, seed replay/reset replay/reseed của cả 7 service đạt trên PostgreSQL tạm. Chưa xác minh pipeline GitLab hoặc deploy worker lên Compose đang dùng.
