# Tích hợp sửa lỗi MR !48, !49, !50, !52

## Hành vi đã tích hợp

- Checkout và quản lý đơn dùng chung entity enum, snapshot SKU và ID phiếu kho. UUID của Order/OrderItem được ứng dụng cấp trước reserve; không dùng generated-ID khiến Hibernate hiểu nhầm đơn mới là bản ghi đã xóa.
- IPN, chuyển trạng thái và hủy đơn cùng khóa bản ghi Order trong transaction. Request chờ phải kiểm tra lại trạng thái đã commit; replay không ghi thêm history.
- Chỉ IPN VNPAY đã xác minh chữ ký được chuyển đơn VNPAY sang PAID. Hủy COD chưa thanh toán ghi history và outbox RELEASE cùng transaction.
- Chưa có provider hoàn tiền/hủy giao dịch VNPAY. API từ chối ghi REFUNDED mới, hủy đơn PAID và hủy VNPAY đang chờ; không ghi nhận đã hoàn tiền hoặc hủy giao dịch tài chính khi chưa có bằng chứng.
- Mọi API thông báo yêu cầu JWT. POST email chỉ dành ROLE_NOTIFICATION_WRITER; GET nội dung/attempts chỉ dành người nhận hoặc ADMIN.
- Commerce snapshot email từ JWT, tên người nhận, số tiền, phương thức và các dòng hàng từ DB. Worker gửi IN_APP rồi EMAIL bằng service account; lỗi gửi giữ outbox để retry, không rollback thanh toán.
- Không dùng customer@nexus.com, orderId làm userId hoặc số tiền 0 làm dữ liệu giao dịch. Kho chỉ cập nhật tồn; Commerce chịu trách nhiệm nội dung và người nhận thông báo.
- EMAIL retry giữ notification cũ và nối số attempt; advisory lock và unique index chặn worker đồng thời gửi cùng event. SMTP tắt hoặc thiếu sender không được ghi SENT. SMTP vẫn có thể gửi lặp nếu process chết sau gửi nhưng trước commit vì SMTP không có exactly-once.
- Trang kết quả chỉ dùng query để lấy ID tra cứu. Không có ID hoặc API lỗi thì chưa xác nhận; đơn COD chỉ xác nhận đã tạo đơn, không báo đã thu tiền.

## Migration và cấu hình

- Commerce chạy tới V7: thêm orders.buyer_email và snapshot JSON email vào outbox. Đơn/outbox cũ thiếu email tiếp tục IN_APP, không đoán người nhận.
- Notification chạy tới V4: giữ lịch sử email cũ và thêm khóa duy nhất cho event EMAIL. Timestamp JPA/JDBC dùng UTC theo schema hiện có.
- Keycloak access token checkout cần claim email (scope email). Client outbox có ROLE_NOTIFICATION_WRITER; secret cấp qua NOTIFICATION_CLIENT_ID/NOTIFICATION_CLIENT_SECRET.
- Compose chuyển SMTP_HOST/PORT/USERNAME/PASSWORD/AUTH/STARTTLS/MAIL_FROM cho Notification, mặc định Mailpit:1025. Timeout SMTP hữu hạn; worker Commerce chờ tối đa 30 giây cho một request Notification.
- Không sửa nội dung các migration đã có trong nhánh nguồn. Không có credential merchant hoặc SMTP thật được đưa vào repo.

## Kiểm tra

Commerce và Notification chạy cả PostgreSQL/Flyway thật qua NEXUS_TEST_DB_URL. Các suite bảo vệ checkout/IPN, rollback, callback đồng thời, lifecycle qua OrderService thật, phân quyền, hủy COD và release outbox, snapshot email, SMTP retry và event đồng thời. OrderLifecycleControllerTest chỉ kiểm tra HTTP contract với service mock; không gọi suite này là E2E nghiệp vụ.

Chạy `mvn verify checkstyle:check` cho Commerce, Notification, Fulfillment; `mvn test checkstyle:check` cho Gateway. Frontend chạy test/coverage, lint, format:check và build. Giao dịch merchant VNPAY thật và SMTP Internet cần môi trường/credential triển khai; fixture SMTP và chữ ký test không thay thế xác nhận từ merchant thật.
