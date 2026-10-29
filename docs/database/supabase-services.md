# Database các service trên Supabase

Compose nhận URL/tài khoản từ `<SERVICE>_DB_URL`, `<SERVICE>_DB_USERNAME`, `<SERVICE>_DB_PASSWORD` trong `.env` (không commit). Để trống thì dùng PostgreSQL local. Ví dụ JDBC không chứa credential:

```text
jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=require&currentSchema=nexus_auction,public
```

`<SERVICE>_DB_SCHEMAS=nexus_<service>` giới hạn schema Flyway quản lý. Không thêm public vào biến này: public chứa extension dùng chung, không thuộc service. `public` chỉ nằm sau schema nghiệp vụ trong JDBC currentSchema để migration Catalog tìm thấy pg_trgm. Mỗi schema có flyway_schema_history riêng; không bật baseline tự động hoặc sửa migration cũ.

Các schema sử dụng: nexus_user, nexus_catalog, nexus_commerce, nexus_auction, nexus_fulfillment, nexus_notification, nexus_reputation. Keycloak giữ schema keycloak. Nhóm *_svc trong tài liệu tổng hợp là ranh giới service; các schema *_svc cũ và schema legacy được giữ nguyên để đối chiếu, không phải đích ghi của Compose.

Sau khi cấu hình, chạy `docker compose up -d --build` để service chạy Flyway khi khởi động. Kiểm tra version cuối: User V6, Catalog V4, Commerce V3, Auction V3, Fulfillment V4, Notification V2, Reputation V3. Pool của sáu service bổ sung mặc định tối đa 3 kết nối/service, minimum-idle 0; cân đối hạn mức Supabase trước khi tăng số instance.

Lần đồng bộ này chạy Flyway thật trên Supabase và sao chép bảng Catalog hiện có sang nexus_catalog trong transaction, đối chiếu dữ liệu trước commit. Bản gốc catalog_svc được giữ nguyên; thay đổi tiếp theo của ứng dụng chỉ ghi nexus_catalog, không đồng bộ hai chiều. Không chuyển dữ liệu từ schema User legacy sang Reputation vì dữ liệu cũ cần quyết định nghiệp vụ riêng.

Tạo đủ bảng không đồng nghĩa triển khai đầy đủ API nghiệp vụ. Cấu hình trên áp dụng cho Docker Compose; chạy IDE cần cấp DB_URL/DB_USERNAME/DB_PASSWORD và SPRING_FLYWAY_SCHEMAS tương ứng cho từng service, IDE không tự nạp .env gốc.
