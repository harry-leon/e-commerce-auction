# api-gateway

Spring Cloud Gateway: điểm vào duy nhất cho FE. Định tuyến `/api/v1/*` và WebSocket thuần `/ws/auctions` tới từng service (xem `application.yml`).

Gateway xác minh JWT và hỗ trợ cookie HttpOnly cho web. Login/refresh không trả token trong JSON; refresh đọc cookie, logout xóa cookie. FE dùng proxy cùng origin và header `X-Nexus-Request: 1` để chống CSRF. Chi tiết hợp đồng: [API_GATEWAY.md](../../docs/dto-contracts/API_GATEWAY.md).

HTTPS mặc định dùng cookie Secure. Khi chạy HTTP localhost trực tiếp, đặt `AUTH_COOKIE_SECURE=false`; Compose demo đã cấu hình mặc định này. Sau khi cập nhật cần rebuild/restart Gateway và đăng nhập lại. Logout hiện chỉ xóa cookie, chưa thu hồi session tại Keycloak.

TT-S2-2 mở CORS theo `WEB_ALLOWED_ORIGINS`, giữ kiểm tra CSRF cookie và kiểm tra Origin tại endpoint WebSocket Auction.
Xem [hợp đồng STOMP và service identity](../../docs/TT-S2-2_GATEWAY_STOMP_SECURITY.md). Việc cần làm tiếp: rate limiting.
Khi thêm endpoint mới cho service, kiểm tra `Path=` của route tương ứng.
