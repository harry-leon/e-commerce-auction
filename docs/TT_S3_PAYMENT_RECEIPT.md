# TT-S3-07 — Biên nhận thu tiền nội bộ

Phần đầu của invoice/receipt theo phân công Tiến Thương. Đây là bản tải snapshot thanh toán nội bộ, **không phải hóa đơn thuế** hoặc xác nhận provider sandbox đã nghiệm thu. Không phát hành số hóa đơn điện tử, không gọi nhà cung cấp hóa đơn, không thu/hoàn tiền hoặc đổi trạng thái Order.

## Contract

`GET /api/v1/payments/{paymentId}/receipt` yêu cầu JWT Buyer sở hữu đơn hoặc Admin. Seller/service privilege không thay thế quyền này. Subject JWT phải là UUID. Không nhận buyerId hoặc cờ Admin trong query/body. Gateway đã có route `/api/v1/payments/**`, không cần mở namespace mới.

Thành công: HTTP 200, `application/json`, `Content-Disposition: attachment; filename="payment-receipt-{paymentId}.json"`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Body là document trực tiếp, không bọc `ApiResponse`. Lỗi dùng ApiError hiện có:

| HTTP | Ý nghĩa |
| --- | --- |
| 400 | UUID đường dẫn sai |
| 401 | Chưa xác thực hoặc subject không phải UUID |
| 403 | Thiếu role Buyer/Admin |
| 404 | Payment/Order không tồn tại hoặc Buyer không sở hữu đơn |
| 409 | Chưa đủ bằng chứng thu tiền hoặc snapshot không hợp lệ |

Document version 1 có `documentType=INTERNAL_PAYMENT_RECEIPT`, `receiptId=PAY-{paymentId}`, paymentId, orderId, paidAt, currency VND, method và prices. `prices` gồm subtotal/shippingFee/discount/total, các dòng orderItemId/skuId/quantity/unitPrice/itemTotal từ JSONB của Payment. Không bổ sung tên hàng/giá hiện tại từ Catalog, thông tin merchant, providerRef, paymentUrl, email, địa chỉ hay số điện thoại.

Chỉ VNPAY SUCCESS với responseCode/transactionStatus 00, providerRef và completedAt được chấp nhận. Snapshot phải có dòng hàng, ID dòng không trùng, số lượng dương, các số tiền VND nguyên không âm; tổng dòng khớp subtotal, tổng sau phí/giảm giá khớp total và amount Payment. Không dùng `Order.total`, `estimatedTotal` hoặc `priceFinalized` để thay bằng chứng. Dữ liệu lịch sử thiếu snapshot trả 409 thay vì tự dựng chứng từ.

## Tải lại và refund

Endpoint chỉ đọc trong transaction; không có migration, bảng phát hành hoặc counter số chứng từ. Mã biên nhận được suy ra từ paymentId, paidAt lấy completedAt, không dùng thời điểm tải. Retry cùng Payment không tạo bản ghi/financial side effect. Payment SUCCESS của mua thường hoặc đấu giá dùng cùng cấu trúc snapshot, không dùng giá khởi điểm Auction. COD chưa có Payment cùng bằng chứng này nên chưa hỗ trợ.

Biên nhận phản ánh khoản thu gốc, không khẳng định đã giao hàng, còn bao nhiêu tiền có thể hoàn hoặc refund đã thành công. Trạng thái Order sau đó không thay đổi giá thu gốc. API không cung cấp lịch sử điều chỉnh/refund. Đây **chưa phải** registry chứng từ bất biến/chứng từ điều chỉnh hoàn chỉnh: nếu dữ liệu Payment bị sửa trái contract ở DB thì bản tải sẽ phản ánh dữ liệu đó. Cần chốt policy BA, cơ chế phát hành/audit, liên kết điều chỉnh refund, retention, định dạng PDF và provider hóa đơn trước khi đánh dấu toàn bộ TT-S3-07 hoàn tất.

## Kiểm tra

- Unit: quyền chủ đơn/Admin, subject sai, trạng thái chưa xác nhận, snapshot/tổng/bằng chứng hỏng, ID ổn định khi Order đổi state.
- MVC: khách/Seller/service bị chặn, Buyer/Admin tải file với header riêng tư, lỗi 400/404/409, DTO không xuất các trường nhạy cảm.
- PostgreSQL: checkout/attempt và IPN HMAC fixture qua transaction thật, round-trip JSONB rồi tải lại, IPN trùng không làm đổi document/tăng Payment/outbox; sai Buyer trả 404.

Các test dùng fixture/mock, không phải giao dịch merchant thật, nghiệm thu IAM triển khai hoặc UAT trình duyệt. Chưa thêm UI hoặc PDF; FE tải JSON bằng bearer session hiện có, không gửi token qua URL. Review contract với BA/Commerce và quyền với IAM trước merge.

Kiểm chứng local 13/11/2026: `mvn -f backend/commerce-service/pom.xml clean verify checkstyle:check` với `NEXUS_TEST_DB_URL` trỏ cluster PostgreSQL 18 tạm đạt exit 0; 445 test, 0 fail/error/skip, coverage LINE 2256/2496 (90,38%). Có 9 unit receipt, 3 MVC receipt và 1 ca receipt mới trong PaymentPostgresTest. Cluster tạm đã dừng. Không đặt URL DB triển khai vào biến test và không bỏ `clean` khi đổi nhánh có bộ migration khác nhau. Pipeline GitLab chưa được xác nhận.
