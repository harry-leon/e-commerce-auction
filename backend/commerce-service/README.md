# commerce-service

Service **commerce** của Project Nexus.

- Package: `com.nexus.commerce` · DB riêng: `nexus_commerce` (Flyway: `src/main/resources/db/migration`)
- Chạy cả hệ thống từ thư mục gốc: `docker compose up --build`
- Chạy riêng: `mvn spring-boot:run` (cần PostgreSQL local, xem biến môi trường trong `application.yml`)
- Test: `mvn verify` (coverage >= 70%), style: `mvn checkstyle:check`
- Swagger (dev): http://localhost:8080/swagger-ui.html

Phạm vi nghiệp vụ: xem SRS (`docs/`). Cấu trúc code: xem `docs/ARCHITECTURE.md` ở gốc repo.

Checkout TT-S1-2 đã có `POST /api/v1/orders/checkout/preview` và `POST /api/v1/orders` (REGULAR/COD),
giá/seller lấy từ Catalog thật, reserve/release qua Fulfillment và chống trùng bằng PostgreSQL.
Contract, mã lỗi và giới hạn: `docs/dto-contracts/COMMERCE_SERVICE.md` mục 4.
Chạy Catalog mới có `sellerId` trước Commerce; cấu hình `CATALOG_SERVICE_URL` và `FULFILLMENT_SERVICE_URL`
(Compose đã cấp URL nội bộ). Migration V4 do Flyway chạy tự động. Phí ship mặc định 0, cấu hình
`APP_COMMERCE_ORDER_SHIPPING_FEE`; chưa có voucher hoặc báo giá vận chuyển thực tế.

TT-S1-3 bổ sung VNPAY sandbox: checkout `paymentMethod=VNPAY` trả URL đã ký, IPN xác minh chữ ký rồi cập nhật
Order/Payment và outbox confirm/release kho. Cần Flyway V5, merchant VNPAY và service account Keycloak.
Thiếu cấu hình thì VNPAY trả 503, COD vẫn chạy. Hướng dẫn setup, API, retry/đối soát và review:
`docs/PAYMENT_SANDBOX_INTEGRATION.md`. Trang sandbox demo cũ và query browser không chứng minh đã thanh toán.

Test transaction/đồng thời cần PostgreSQL **tạm, riêng cho test**, user `postgres`, trust authentication;
đặt `NEXUS_TEST_DB_URL=jdbc:postgresql://localhost:<port>/<test_db>` rồi chạy `mvn verify checkstyle:check`.
Nếu không có biến này, unit/web/HTTP tests vẫn chạy nhưng PostgreSQL tests bị skip.
CI Commerce cấp PostgreSQL riêng để các bài này luôn chạy. Không trỏ test vào DB dev/team/production:
test có tạo dữ liệu và trigger gây lỗi commit tạm thời để xác minh hoàn kho.
