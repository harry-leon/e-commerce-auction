# Compose, CI và chuẩn bị review Sprint 2

Ngày kiểm tra local: 13/11/2026. Đây là kết quả kiểm tra source/local; không xác nhận pipeline GitLab hoặc UAT môi trường chung đã đạt.

## Thay đổi

- Compose bật JSON ECS cho các backend dùng khối môi trường chung. Handover ghi `auctionId`, `settlementId`, `orderId`; worker xác nhận payment ghi `auctionId`, `orderId`, `paymentId`. Log trong transaction chưa chứng minh commit; trạng thái đơn phải đọc lại qua API.
- Mật khẩu bootstrap Keycloak lấy từ `KEYCLOAK_ADMIN_PASSWORD` trong `.env`; không đổi mật khẩu của realm đã tồn tại. `.env.example` chỉ liệt kê biến trống. Mật khẩu local mới được sinh khi biến chưa có, không in ra log.
- CI thêm validate Compose, test fixture/IAM và kiểm tra migration/seed/reset bằng cluster PostgreSQL 16 riêng. Frontend chỉ được format bằng Prettier, không đổi hành vi, để đáp ứng `frontend-web:lint` đang gọi `format:check`. JSON/lockfile không thêm comment.
- Fixture cập nhật schema hiện tại và reset có fingerprint/checkpoint/hàng rào FK. Hướng dẫn và giới hạn nằm trong [DEMO_DATA.md](DEMO_DATA.md).
- Test concurrency bid dùng entity snapshot riêng cho từng lần đọc, tương ứng persistence context riêng của hai transaction; giữ kiểm tra một thành công và một optimistic conflict.

## Kiểm tra trước review

| Kiểm tra | Kết quả local |
| --- | --- |
| Frontend lint, TypeScript/production build, format | Pass; bundle còn cảnh báo trên 500 kB |
| Frontend coverage | 359 test / 28 file pass, line coverage 74,39% |
| PostgreSQL fixture | Migration của cả 7 service, seed replay, reset replay và reseed pass; chặn FK CASCADE ngoài fixture pass |
| Compose config/build/up | Pass; cả 11 image build thành công; `up -d --wait --wait-timeout 240` exit 0 trên PostgreSQL local |
| Smoke môi trường Compose | 6/6 pass: web, health Gateway, products, auctions, orders 401 và WebSocket upgrade rồi từ chối CONNECT ẩn danh |
| Backend verify | User, Catalog, Auction, Commerce, Fulfillment, Notification, Reputation, Gateway, Config và Discovery pass; Common install/test pass |
| Backend Checkstyle | Auction và Commerce pass, gồm các phần sửa log/test |
| Fixture Node | 19 test pass, gồm contract schema và giữ timestamp User khi reset |
| Test DB bị skip | Catalog 1, Commerce 37, Fulfillment 2, Notification 11; chưa cấp DB integration riêng khi chạy Maven |
| GitLab pipeline | Chưa xác minh |
| UAT tích hợp với Keycloak/VNPAY thật | Chưa chạy trong phiên này |
| Seed plan trên Compose hiện tại | Bị chặn: email demo trùng tài khoản IAM nhưng thiếu bằng chứng sở hữu phù hợp. Không ghi đè/gán role/reset password; chưa apply/reset trên DB hiện tại |

Chạy lại smoke chỉ đọc bằng `node scripts/check-compose-readiness.mjs`; kết quả đã loại dữ liệu nhạy cảm tại [readiness.json](test-evidence/tt-s2-4/readiness.json). Máy có override local được Git ignore, web ở cổng 3001. Không dùng kết quả này để suy ra Compose trên máy khác hoặc pipeline GitLab đã đạt. Các container local được giữ chạy sau kiểm tra.

## Kịch bản UAT cần ghi bằng chứng

Mỗi ca lưu commit SHA, môi trường, giờ kiểm tra, kết quả thực tế và ID nghiệp vụ; không chụp token/secret/query VNPAY đã ký.

| Ca | Kết quả cần kiểm tra | Trạng thái |
| --- | --- | --- |
| Khởi động Compose | Service health, Gateway route HTTP và WS hoạt động | Pass smoke HTTP/WS ẩn danh; chưa có WS đăng nhập trên Compose |
| Phân quyền | Buyer/seller/admin đúng quyền; token sai/hết hạn bị chặn | Pass orders 401 và CONNECT ẩn danh bị chặn; chưa chạy ma trận role |
| Đấu giá | Seller tạo phiên và giữ kho; hai buyer bid, anti-sniping đúng | Chưa chạy |
| Handover | Một settlement tạo đúng một order winner, reservation được attach | Chưa chạy |
| Payment | Winner mở VNPAY; return chờ backend, IPN lặp không tạo PAID hai lần | Chưa chạy |
| Timeout/recovery | IPN sát hạn, kho hết hạn/mất ACK không dẫn đến PAID và RELEASE cùng lúc | Chưa chạy |
| Logs | Tra được auction → settlement → order qua JSON, không có secret | Chưa chạy |
| Fixture | Reset trên demo riêng, reseed thành công, không xóa volume/Flyway | Chưa chạy trên Compose |

Không đánh dấu task Done chỉ vì source có sẵn hoặc test local pass. Cần URL pipeline xanh và kết quả UAT thực tế trước khi kết luận môi trường chung sẵn sàng.
