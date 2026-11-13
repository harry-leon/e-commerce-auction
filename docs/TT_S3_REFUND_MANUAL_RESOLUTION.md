# Đối soát refund thủ công

## Phạm vi

V15 bổ sung audit quyết định Admin, registry receipt dùng chung worker/Admin, payment hold và log kết quả provider muộn trái ngược. Không tự chốt dữ liệu UNKNOWN/REQUIRES_REVIEW cũ và không gửi refund/query thật khi áp dụng migration.

Đối soát thủ công là **quyết định của operator dựa trên bằng chứng bên ngoài**, không phải bằng chứng đã tự gọi/xác minh VNPAY. API không tải URL hoặc kiểm tra nội dung của evidenceReference. Chỉ sử dụng kết quả cuối cùng đã đối chiếu đúng merchant, giao dịch gốc và số tiền; không dùng QueryDr của PAY gốc hoặc HTTP timeout làm bằng chứng refund thất bại.

## API

`POST /api/v1/refunds/{refundId}/manual-resolution`

- JWT có ROLE_ADMIN; actor lấy từ subject JWT, không nhận actor do body khai báo.
- `Idempotency-Key` UUID bắt buộc, gắn với actor cho thao tác đối soát.
- Refund phải đang UNKNOWN hoặc REQUIRES_REVIEW; PROCESSING chưa hết lease, REQUESTED và terminal state bị từ chối.
- Chỉ nhận decision SUCCEEDED hoặc FAILED. Amount VND nguyên phải khớp snapshot và currency phải VND.
- providerPaymentReference phải khớp giao dịch thu tiền đã lưu. Với SUCCEEDED bắt buộc providerRefundReference số dương 1–15 chữ số; với FAILED không gửi reference hoàn tiền thành công.
- evidenceReference tối đa 200 ký tự, note tối đa 500 ký tự, đều bắt buộc và không có control character. Không gửi secret hoặc raw signed response trong hai trường này.

Ví dụ fixture, không phải giao dịch thật:

```json
{
  "decision": "SUCCEEDED",
  "amount": 50000,
  "currency": "VND",
  "providerPaymentReference": "12345",
  "providerRefundReference": "67890",
  "evidenceReference": "SANDBOX-CASE-EXAMPLE",
  "note": "Đã kiểm tra kết quả cuối cùng theo hồ sơ đối soát"
}
```

Lần đầu trả 201; replay cùng actor/key và nội dung đã chuẩn hóa trả 200 với cùng resolutionId/status/decidedAt. Cùng key khác nội dung hoặc quyết định mâu thuẫn trả 409. Input thiếu bằng chứng/tiền không hợp lệ trả 400. Thiếu JWT trả 401, sai role 403, không có refund 404.

Response envelope chỉ chứa `resolutionId, refundId, status, decidedAt`, không trả note/evidence/reference tài chính. Đây là snapshot quyết định, không phải snapshot trạng thái hiện tại vĩnh viễn: sau replay cần GET refund để đọc trạng thái mới nhất nếu đã có kết quả provider muộn.

## Transaction và hạn mức

Khóa key → Order → attempt → refund. Khóa Order chia sẻ với thao tác tạo refund để quyết định giải phóng/ghi nhận hạn mức không chạy đồng thời với cấp hạn mức mới. Không giữ transaction trong lúc gọi provider; endpoint này không gọi provider.

SUCCEEDED: kiểm tra tổng succeeded/reserved của các yêu cầu khác không vượt tiền đã thu, ghi registry receipt và audit rồi đổi refund/attempt cùng transaction. Unique provider + merchant + receipt không cho dùng cùng bằng chứng hoàn tiền cho hai refund. Worker cũng ghi cùng registry để không có hai hàng rào tách biệt.

FAILED: chỉ cho chốt khi chưa có receipt thành công, ghi audit và giải phóng hạn mức của yêu cầu này. Không gửi lại attempt cũ; nếu đủ điều kiện, yêu cầu mới phải có key mới và đi qua approval mới. Không sửa Order, Payment SUCCESS, history giao hàng hoặc kho.

Lỗi INSERT audit phải rollback cả receipt và trạng thái. Audit chỉ INSERT qua API, không có endpoint sửa/xóa; không phải cơ chế chống can thiệp của tài khoản DB quản trị.

## Kết quả muộn mâu thuẫn

- Sau manual FAILED: lưu kết quả muộn vào refund_result_conflicts, chuyển refund về REVIEW, giữ quyết định FAILED cũ trong audit và tạo refund_payment_holds.
- Sau SUCCEEDED: response trùng đúng receipt được bỏ qua; kết quả trái ngược tạo conflict/hold nhưng không hạ terminal SUCCEEDED thành FAILED.
- Payment có hold không được tạo yêu cầu refund mới. Worker cũng kiểm tra hold trước claim; yêu cầu đang chờ sẽ sang REVIEW thay vì được gửi.
- Hold không tự gỡ khi chốt quyết định khác. Chưa có API gỡ hold; cần quy trình vận hành riêng sau khi đối chiếu mọi yêu cầu liên quan. Không tự xóa hold hoặc đổi SQL sang FAILED để ép gửi lại.

Hệ thống không thể thu hồi một lệnh provider đã gửi trước khi biết mâu thuẫn. Hold bảo vệ các lệnh chưa claim sau khi mâu thuẫn được ghi nhận; các attempt đã ra mạng phải tiếp tục được đối soát. Vì vậy operator không được xác nhận FAILED chỉ vì chưa nhận được ACK.

## Kiểm chứng và giới hạn

Test PostgreSQL thật/HTTP mock: replay, key conflict, hai Admin chốt trái ngược, receipt dùng lại ở payment khác, sai amount/thiếu bằng chứng, rollback khi ghi audit lỗi, kết quả muộn sau FAILED và sau SUCCEEDED. MVC kiểm tra quyền và response 201/200. Không sử dụng DB nhóm hoặc merchant thật.

V15 backfill registry từ những refund đã SUCCEEDED ở V14; không đổi giao dịch lịch sử hoặc phát event. Đối soát hiện chưa phát notification/chứng từ hay chuyển Order sang REFUNDED; các phần đó thuộc luồng tích hợp tiếp theo.

Kiểm tra local: Commerce `verify checkstyle:check` đạt **421 test, không skip**, line coverage **90,20%**. 19 test fixture đạt; migration/seed replay/reset replay/reseed của cả 7 service đạt trên PostgreSQL tạm. Provider ở biên test là HTTP mock. Chưa có nghiệm thu sandbox hoặc pipeline GitLab cho phần thay đổi V15 này.
