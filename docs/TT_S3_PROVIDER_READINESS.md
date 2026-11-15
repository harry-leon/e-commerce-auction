# TT-S3-02 — Chuẩn bị provider và callback

## Kiểm tra đã làm

- `.env` có giá trị cho VNPAY_TMN_CODE, VNPAY_HASH_SECRET, VNPAY_RETURN_URL, AUCTION_CLIENT_SECRET và COMMERCE_CLIENT_SECRET. Chỉ kiểm tra có/không, không xuất giá trị. Điều này không chứng minh credential còn hợp lệ, đúng merchant hoặc được cấp quyền refund.
- Realm JSON đọc hợp lệ; SHIPPING.ORDER_PAID có khai báo role, gán service account Commerce và scope mapping. Chưa cập nhật IAM đang chạy.
- Chưa gửi refund, chưa tạo giao dịch tài chính mới, chưa đăng ký callback lên provider.

## Nguồn chính thức và quyết định tích hợp

[Tài liệu VNPAY QueryDr/Refund](https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html) mô tả hai command qua POST JSON đến endpoint sandbox `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction`. RequestId cần duy nhất; checksum của QueryDr dùng chuỗi trường theo thứ tự tài liệu, không tái dùng phép ký URL thanh toán.

Tài liệu phân biệt mã refund `94` (đang xử lý yêu cầu đã gửi) và trạng thái giao dịch `05/06` (đang xử lý/chuyển yêu cầu tới ngân hàng). Vì vậy adapter phải phân biệt tiếp nhận yêu cầu với hoàn tất tài chính; không mặc định HTTP 200 hoặc ResponseCode 00 luôn cho phép ghi Order REFUNDED. Cần chốt mapping kết quả cuối cùng với merchant trước khi bật worker.

Không tự tạo refund callback chỉ dựa trên IPN payment. Worker cần xác minh contract response/query và phương thức đối soát refund của merchant; QueryDr mô tả kết quả giao dịch thanh toán, chưa đủ bằng chứng đây là API tra cứu một refund attempt cụ thể.

## Các điều kiện còn thiếu trước khi test sandbox refund

| Điều kiện | Đầu ra cần có | Trạng thái |
| --- | --- | --- |
| Quyền merchant | Xác nhận refund/query được bật và loại giao dịch được phép | Chưa xác minh |
| Transaction gốc | Một giao dịch sandbox thực, mã giao dịch, ngày giao dịch và số tiền đã được kiểm chứng | Chưa chọn giao dịch; không lấy fixture DEMO_ONLY |
| Xác minh response | Chuỗi checksum, timestamp/timezone, mapping mã cuối cùng và xử lý response trùng | Đã có codec/HTTP và unit fixture; mapping tài chính/duplicate worker chưa triển khai |
| Egress/IP | IP merchant gửi request, TLS và timeout hợp lệ với môi trường thử | Chưa xác minh |
| Callback ingress | Phân biệt payment IPN, browser return và carrier webhook; URL HTTPS/method/secret riêng | Chưa đăng ký hoặc kiểm chứng public ingress trong bước này |
| Unknown/recovery | Cách tra cứu refund sau mất ACK, điều kiện gửi lại được provider bảo đảm | Chưa chốt với merchant |

Không yêu cầu chia sẻ secret qua chat. Những thông tin cần cung cấp cho bước test thật là tài liệu/quyền merchant, giao dịch sandbox được chọn và cơ chế đối soát refund được provider hỗ trợ. Có thể tiếp tục làm adapter offline bằng fixture trong khi các điều kiện này đang chờ; không được đánh dấu sandbox pass từ fixture.

## Adapter offline đã triển khai

`VnpayMerchantProtocol` xây request refund toàn phần type 02 và QueryDr cho giao dịch PAY gốc; dùng thời điểm tạo PAY theo GMT+7, amount VND nguyên nhân 100 và chuỗi HMAC riêng. Response được so checksum cùng command/merchant/ref/amount. Query có phần promotion trong chuỗi ký khác refund. Không nhận chữ ký đúng như bằng chứng đã cập nhật Order.

`VnpayMerchantClient` chỉ gọi endpoint HTTPS sandbox cố định; mặc định tắt qua `VNPAY_MERCHANT_API_ENABLED=false`. RefundDispatchWorker cần thêm flag riêng, IP egress và approval từng refund; xem [runbook worker](TT_S3_REFUND_WORKER.md). Caller lưu requestId/trạng thái trước gửi; HTTP không redirect hoặc tự retry. Timeout/I/O, HTTP không hợp lệ hoặc response không xác minh được đều trả lỗi kết quả chưa rõ, không mang raw response/cause nhạy cảm. Client kiểm tra body tối đa 64 KiB sau khi nhận byte[], chưa có streaming cap trước cấp phát.

Đã kiểm tra 4 test protocol bằng vector HMAC độc lập và 3 test HTTP mock: tắt mặc định không chạm mạng, lỗi mạng không retry/không lộ cause, response 200 không ký bị từ chối. Worker đã có mapping và recovery được test PostgreSQL/HTTP mock; vẫn chưa gửi refund/query thật.

Fulfillment đã bỏ 13 unused import, bổ sung import RequestParam còn thiếu: `verify checkstyle:check` pass, 168 test gồm 166 pass và 2 ca DB skip. Test bảo vệ endpoint OrderPaid có 3 ca pass. Những thay đổi import chỉ phục vụ compile/lint, không đổi nghiệp vụ của các file liên quan.
