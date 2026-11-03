# Đồng bộ source với ERD Nerum V2 đã chốt

Nguồn thiết kế là file `Nerum V2 (1).json` do chủ dự án chốt. Không sửa ERD để hợp thức hóa cột phát sinh trong code. `nexus_schema.sql` và `ERD.md` là tài liệu đối chiếu, không chạy thay Flyway.

## Thay đổi

- Catalog V4: bỏ `categories.name_vi`. Entity/API ghi một tên `name`; response giữ `nameVi = name` làm alias cho UI hiện có. Form quản trị không còn nhập hai tên.
- Commerce V3: bỏ `carts.created_at`, giới hạn `vouchers.name` còn `varchar(30)`. Nếu tên đang dài hơn 30, migration dừng và rollback, không tự cắt dữ liệu.
- Auction V3: bỏ `auctions.created_at`; giữ lịch bắt đầu/kết thúc và version.
- Fulfillment V3: bỏ `inventory_reservations.created_at` và constraint so sánh hạn với cột này. Giữ `expires_at`, số lượng và trạng thái.
- Reputation V3: bỏ `avg_rating`, `total_ratings`, `updated_at` khỏi `reputations`; giữ lịch sử `ratings`, `penalties` và validation bộ đếm giao dịch.
- User V6: bỏ `audit_logs` ngoài ERD. Nếu bảng còn dữ liệu, migration dừng để chuyển lịch sử trước.
- Audit mới ghi/đọc qua `/internal/user-audit` của notification-service, dùng đúng các cột `event_logs` trong ERD. Chỉ service account có `ROLE_PROFILE_WRITER` được gọi. User Controller vẫn kiểm tra quyền xem lịch sử của người dùng.
- JDBC ánh xạ các `Instant` trong Catalog/Cart thành `timestamp` và dùng UTC. Không đổi chúng sang `timestamptz`. API vẫn dùng `Instant` để tương thích client.

Mỗi service vẫn sở hữu một database riêng. Tiền tố `*_svc` trong bản tổng hợp biểu diễn ranh giới database, không yêu cầu gộp toàn bộ vào một database. Các bảng `user_entity`/`credential` thuộc Keycloak và do chính Keycloak quản lý; ứng dụng không tự tạo hoặc sửa các bảng nội bộ này. Các bảng hạ tầng `service_metadata`/`flyway_schema_history` không phải bảng nghiệp vụ trong ERD.

## Nâng cấp database đang có dữ liệu

1. Đưa hệ thống vào chế độ bảo trì, dừng các instance User cũ để không phát sinh audit trong lúc chuyển.
2. Sao lưu database; lưu riêng các cột sắp bỏ nếu cần tra soát (`name_vi`, các mốc tạo, thống kê reputation). Migration mới không sửa checksum các migration cũ.
3. Kiểm tra tên voucher dài hơn 30 và sửa theo quyết định nghiệp vụ trước khi chạy Commerce V3.
4. Nếu `audit_logs` có dữ liệu, chuyển sang `nexus_notification.event_logs` theo quy trình dưới đây. Không xóa nguồn nếu chưa kiểm chứng đích.
5. Triển khai notification-service trước, rồi User/Catalog/Commerce và các service còn lại. Compose cấp `NOTIFICATION_SERVICE_URL=http://notification-service:8080`; chạy riêng dùng `app.notification.base-url`, mặc định `http://localhost:8086`.

### Chuyển audit cũ

Các lệnh minh họa dùng `psql` trên cùng máy client. Cấp host/user/password bằng cơ chế PostgreSQL của môi trường, không ghi credential vào repository. File CSV chứa dữ liệu cá nhân, lưu ngoài repo ở vị trí được bảo vệ. Trong ví dụ, đổi đường dẫn CSV theo vị trí backup thực tế.

Kết nối `nexus_user` khi service cũ đã dừng, xuất các cột đúng thứ tự của `event_logs`:

```sql
\copy (SELECT id, id::text AS event_id, 'user-service' AS source_service, jsonb_build_object('userId', user_id, 'action', action, 'description', description, 'status', status, 'ipAddress', ip_address, 'userAgent', user_agent) AS payload, NULL::varchar(100) AS operator_id, created_at FROM audit_logs ORDER BY id) TO '/secure/legacy-audit.csv' CSV HEADER
```

Không suy đoán actor cũ: `user_id` cũ là người dùng đích, nên để `operator_id` NULL. Giữ nguyên `created_at` cũ vì timestamp không chứa thông tin múi giờ gốc.

Kết nối `nexus_notification` (đã chạy Notification V2). Giữ cùng một phiên psql cho khối dưới đây, bật dừng khi có lỗi. Bảng staging chỉ tồn tại trong phiên, không tạo bảng nghiệp vụ ngoài ERD:

```sql
\set ON_ERROR_STOP on
CREATE TEMP TABLE audit_transfer (LIKE event_logs);
\copy audit_transfer FROM '/secure/legacy-audit.csv' CSV HEADER
BEGIN;
INSERT INTO event_logs (id, event_id, source_service, payload, operator_id, created_at)
SELECT id, event_id, source_service, payload, operator_id, created_at FROM audit_transfer
ON CONFLICT (id) DO NOTHING;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM audit_transfer t LEFT JOIN event_logs e USING (id)
        WHERE e.id IS NULL
           OR e.event_id IS DISTINCT FROM t.event_id
           OR e.source_service IS DISTINCT FROM t.source_service
           OR e.payload IS DISTINCT FROM t.payload
           OR e.operator_id IS DISTINCT FROM t.operator_id
           OR e.created_at IS DISTINCT FROM t.created_at
    ) THEN
        RAISE EXCEPTION 'Audit transfer mismatch; source must be retained';
    END IF;
END $$;
COMMIT;
SELECT count(*) AS verified_imported_rows FROM audit_transfer;
```

Chỉ sau khi khối kiểm chứng commit thành công, số dòng khớp nguồn và bản backup đã được giữ, quản trị mới làm trống `nexus_user.audit_logs` trong thời gian bảo trì rồi chạy User V6. V6 không tự làm trống bảng. Nếu có lỗi, giữ nguồn và xử lý trước khi tiếp tục triển khai.

## Kiểm tra

- `node --test scripts/generate-erd.test.mjs`; `node scripts/generate-erd.mjs` để cập nhật sơ đồ sinh từ migration.
- `mvn verify checkstyle:check` tại các module đã sửa. Khi cấp `NEXUS_TEST_DB_URL` trỏ tới PostgreSQL tạm với user `postgres`, password rỗng, các test `FinalErdPostgresTest`/`UserAuditPostgresTest` chạy thêm Flyway, Hibernate validation, round-trip thời gian và HTTP audit. Không trỏ các test này vào DB thật.
- Sau khi package User/Catalog/Commerce/Notification/Gateway: `node scripts/smoke-erd-v2.mjs`. Script tự dựng PostgreSQL/Keycloak tạm, kiểm tra đăng ký, phân quyền, Catalog/Cart và audit vào Notification; cleanup chỉ tài nguyên của lần kiểm tra.

## Giới hạn

Audit qua HTTP vẫn best-effort như luồng ghi cũ: gửi sau commit profile, có timeout nhưng chưa có outbox/retry bền vững. Nếu Notification lỗi, profile đã commit vẫn thành công và sự kiện có thể bị mất; API đọc lịch sử trả 503 thay vì giả danh sách rỗng. Không coi bước đồng bộ ERD là đã triển khai các nghiệp vụ Auction/Fulfillment/Reputation/Notification hoặc Order/Payment còn đang là scaffold.
