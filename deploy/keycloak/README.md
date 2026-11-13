# Quy tắc mật khẩu Nexus

## Quyền lệnh tạo shipment từ Commerce

`nexus-realm.json` khai báo realm role `SHIPPING.ORDER_PAID`, gán cho service account và scope mapping của `nexus-commerce-service`. Endpoint `POST /api/v1/shipments/events/order-paid` bắt buộc đồng thời `azp=nexus-commerce-service` và privilege này; Buyer/Admin hoặc service token chỉ có INVENTORY.ORDER không đủ quyền. JSON không hỗ trợ comment nên giải thích cấu hình được lưu tại đây.

Realm đã tồn tại không tự nhận role từ `--import-realm`: người vận hành cần tạo role, gán vào đúng service account và client scope mapping qua Keycloak Admin, sau đó lấy token mới và kiểm tra claim. Không xóa realm để cập nhật role. Source thay đổi này chưa tự áp dụng lên IAM đang chạy và chưa tạo producer Commerce gửi event; token cũ chưa có quyền sẽ bị 403 theo chủ đích.

`nexus-realm.json` đặt cùng policy với Gateway đăng ký và `ChangePasswordRequest` ở User: mật khẩu mới dài 8–100 ký tự, có chữ hoa Unicode, chữ số và ít nhất một dấu/ký hiệu (ví dụ `@`, `!`, `#`). Khoảng trắng không được tính là ký tự đặc biệt. Không thêm yêu cầu này vào login/mật khẩu hiện tại để tránh chặn tài khoản cũ trước khi người dùng đổi mật khẩu.

Gateway trả HTTP 422 với `error.details` cho đăng ký sai policy trước khi gọi IAM. User kiểm tra bằng Bean Validation trước khi đổi mật khẩu. Keycloak áp dụng policy cho trang đặt lại mật khẩu qua email. Nhóm FE phụ trách hiển thị yêu cầu và lỗi chi tiết; thay đổi lần này không chỉnh source FE. Contract BE để FE tích hợp nằm trong `docs/dto-contracts/API_GATEWAY.md` và `docs/dto-contracts/USER_SERVICE.md`.

File JSON không hỗ trợ comment nên phần giải thích đặt tại đây. `--import-realm` không tự cập nhật realm đã tồn tại. Khi triển khai vào DB đang dùng, cần cập nhật `passwordPolicy` của đúng realm qua Admin API và đọc lại xác minh; không xóa realm hoặc đổi mật khẩu người dùng hiện có. Thay đổi policy chỉ chặn lần đặt mật khẩu mới tiếp theo, không tự thay credential cũ.

## Triển khai cùng FE/BE

Kiểm tra phiên bản backend và Flyway trước khi build lại container. Trong lần kiểm chứng local ngày 27/10/2026, database User trên Supabase mới có V1–V3 và chưa có `users.keycloak_id`; các container vẫn dùng luồng IAM cũ. Source mới yêu cầu liên kết profile với Keycloak. Không chỉ restart/rebuild toàn bộ để chạy V4/V5 vì các migration này bỏ bảng IAM và credential cũ; cần backup và chuyển tài khoản trước. V6 còn yêu cầu chuyển audit cũ sang Notification.

Kiểm thử validation BE bằng test tự động không thay đổi database nghiệp vụ. Có thể cập nhật riêng policy realm Keycloak hiện có, nhưng điều đó không làm container backend cũ tự chuyển sang luồng Keycloak hoặc cập nhật các asset FE cũ. Nhóm FE cần thay form reset token cũ bằng luồng action email đồng bộ khi triển khai đủ bộ.
