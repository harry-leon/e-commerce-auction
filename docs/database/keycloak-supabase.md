# Keycloak dùng Supabase ở môi trường local

Keycloak vẫn chạy trong Docker và mở tại `http://localhost:8180`. Dữ liệu IAM được lưu trong schema riêng `keycloak` trên Supabase, tách khỏi schema `nexus_user` của User Service. Keycloak quản lý schema này bằng Liquibase; không thêm bảng IAM vào Flyway của User Service hoặc sửa các bảng nội bộ bằng tay.

## Cấu hình

Điền các biến sau trong `.env` đã được Git bỏ qua; `.env.example` chỉ chứa tên biến, không chứa thông tin truy cập thật:

- `KEYCLOAK_DB_URL`: JDBC URL PostgreSQL của Supabase, có `sslmode=require` và `currentSchema=keycloak`.
- `KEYCLOAK_DB_SCHEMA=keycloak`.
- `KEYCLOAK_DB_USERNAME`, `KEYCLOAK_DB_PASSWORD`: thông tin truy cập PostgreSQL, không dùng Supabase anon key/service-role API key.
- `KEYCLOAK_DB_POOL_MAX_SIZE=5`: giới hạn số session DB của Keycloak; pool bắt đầu và tối thiểu là 1.
- `KEYCLOAK_ADMIN_CLIENT_SECRET`: giữ đúng secret của client `nexus-admin-cli` đã có trong realm. Thay `.env` không tự thay secret của realm đã import.

Kết nối hiện dùng Supabase session pooler cổng `5432`, phù hợp với kết nối JDBC giữ lâu và prepared statements. Không đổi sang transaction pooler cổng `6543` cho cấu hình này. Xem [hướng dẫn kết nối Supabase](https://supabase.com/docs/guides/database/connecting-to-postgres) và [cấu hình DB/schema của Keycloak](https://www.keycloak.org/server/db).

Sau khi database đích đã có schema/dữ liệu hợp lệ, cập nhật riêng container IAM:

```powershell
docker compose up -d --no-deps keycloak
docker compose logs -f keycloak
```

`--no-deps` dùng khi PostgreSQL local/Mailpit và các service cần thiết đã chạy; không tạo lại backend hoặc áp dụng Flyway lên database nghiệp vụ. Compose vẫn có cấu hình dự phòng PostgreSQL local nếu để trống các biến DB remote.

## Dữ liệu và kiểm chứng khi chuyển

Khi chuyển từ DB local, dừng Keycloak trước khi dump để không bỏ sót thay đổi. Sao lưu DB IAM ra ngoài repository, chuyển vào schema đích chưa tồn tại và so sánh số dòng tất cả bảng trước khi khởi động lại. Không ghi đè schema Keycloak đang có dữ liệu. Backup IAM có credential/key nhạy cảm, không commit hoặc chia sẻ công khai.

Giữ database local `nexus_keycloak` để đối chiếu/khôi phục; chuyển đích không xóa dữ liệu nguồn. Bản local là snapshot tại thời điểm chuyển, không đồng bộ những tài khoản hoặc mật khẩu thay đổi sau đó trên Supabase.

Các kiểm tra cần thực hiện:

- URL discovery `/realms/nexus-realm/.well-known/openid-configuration` hoạt động và issuer vẫn là URL public local.
- Client credentials của `nexus-admin-cli` lấy được token và có `ROLE_PROFILE_WRITER`.
- SMTP trong realm vẫn là `mailpit:1025` và Keycloak kết nối được tới đó.
- Role Supabase `anon` và `authenticated` không có quyền sử dụng schema `keycloak`.
- User Service vẫn dùng schema `nexus_user`; việc chuyển Keycloak không tự di chuyển tài khoản từ luồng xác thực cũ hoặc cập nhật các container backend đang chạy bản cũ.
