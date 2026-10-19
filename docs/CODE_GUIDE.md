# Hướng dẫn đọc phần code Sprint 0

Các file dưới đây có comment tiếng Việt ngay tại class/hàm hoặc khối cấu hình. Quy tắc bắt buộc cho những lần AI sửa tiếp nằm trong [`../AGENTS.md`](../AGENTS.md).

## Luồng request tổng quát

```text
React form / màn hình
  → api.ts: session, Bearer token, refresh và lỗi
  → /api/v1 qua Vite proxy (dev) hoặc nginx (Docker)
  → API Gateway tra instance từ Eureka
  → Controller: kiểm tra quyền và DTO
  → Service: xử lý quy tắc nghiệp vụ trong transaction
  → Repository / PostgreSQL
  → DTO / response → cập nhật giao diện khi thành công
```

## Frontend

| File | Vai trò và luồng chính |
| --- | --- |
| `frontend-web/src/api.ts` | Gọi backend, chọn storage theo “remember me”, gắn token, refresh chung khi nhiều request bị 401, chuyển DTO User sang model UI. |
| `frontend-web/src/sessionStore.ts` | Zustand giữ user; đồng bộ hồ sơ đã lưu và xóa token khi logout. |
| `frontend-web/src/routing.ts` | Đọc/ghi URL, query, history; nhận token từ link reset. |
| `frontend-web/src/context.tsx` | Cung cấp state cho component cũ, đồng bộ Back/Forward; một số state cart/auction vẫn là demo trong bộ nhớ. |
| `frontend-web/src/routes/AppRouter.tsx` | Chọn màn hình, bảo vệ điều hướng theo đăng nhập/vai trò; backend vẫn kiểm tra quyền thực sự. |
| `frontend-web/src/pages/Auth/AuthPages.tsx` | Login → lưu session → điều hướng; register → login; forgot/reset → gọi IAM bằng email/token. |
| `frontend-web/src/pages/SprintPages.tsx` | Tải catalog/chi tiết, phân trang và bộ lọc; ghi profile; quản trị cây danh mục qua API thật. |
| `frontend-web/src/components/Layout.tsx` | Header dùng UUID danh mục thật khi điều hướng sang bộ lọc catalog. |
| `frontend-web/src/components/UI.tsx` | Input có ID ổn định gắn label để dùng được với bàn phím, trình đọc màn hình và test. |

Trong `SprintPages.tsx`, `useLoad` quản lý loading/error và hủy request khi đổi màn; các thao tác ghi chỉ báo thành công sau response API. `revision` ở quản trị danh mục làm tải lại cây từ DB, không giả lưu dữ liệu chỉ trong state.

## Backend

| File / thành phần | Vai trò và điểm cần đọc |
| --- | --- |
| `CategoryController` | API đọc cây/chi tiết và tạo/sửa/xóa; phân quyền CATEGORY tại endpoint ghi. |
| `CategoryService` | Chặn vòng lặp cha–con, kiểm tra cả độ sâu cây con khi đổi cha, cập nhật cấp và chặn xóa khi còn con/sản phẩm. |
| `CategoryRequest`, `CategoryResponse`, `Category` | Dữ liệu nhận/trả của cây danh mục và cột tên tiếng Việt. |
| `ProductSearchService` | Ghép bộ lọc JPA: từ khóa literal, danh mục đến ba cấp, giá trên cùng một SKU ACTIVE; distinct trước phân trang. |
| `ProductController`, `ProductServiceImpl` | Chuyển bộ lọc sang service; kiểm tra quyền và quyền sở hữu khi đọc sản phẩm chưa công bố. |
| `ProductMapper` | Quy tắc MapStruct; giá trị mặc định khi tạo, bỏ qua field null khi cập nhật. File code sinh nằm trong `target`, không sửa trực tiếp. |
| `ProductRepository` | JPA Specification, truy vấn ảnh đúng entity, đếm/kiểm tra sản phẩm trực tiếp của danh mục. |
| `GlobalExceptionHandler` | Chuyển tham số query/path sai kiểu thành lỗi client 400. |
| `EmailService` | Dựng link token và gửi SMTP; local nhận thư ở Mailpit, không ghi token ra log. |
| `JwtService.extractClaims` | Parse JWT → xác minh HS256/chữ ký → kiểm tra hạn và issuer → trả claims cho AuthService kiểm tra tiếp. |
| `SecurityConfig` trong các service | Chuyển privileges và roles trong JWT thành Spring authorities, hỗ trợ cả `hasAuthority` và `hasRole`. |

Migration thêm `name_vi` được giải thích ở `backend/catalog-service/src/main/resources/db/migration/README.md`. Không thêm comment vào SQL đã áp dụng vì sẽ làm đổi checksum Flyway.

## Hạ tầng và kiểm thử

- `backend/discovery-server`: Eureka giữ registry service/instance; client đăng ký và gửi heartbeat.
- `backend/config-server`: phục vụ cấu hình dùng chung từ `configuration/application.yml`; cấu hình DB/secret riêng vẫn do service/môi trường cung cấp.
- `docker-compose.yml`: khởi động hạ tầng trước backend, Gateway trước web; tách `LOCAL_DB_*` khỏi tài khoản DB từ xa.
- Các Dockerfile backend dùng cache Maven có khóa để tái sử dụng artifact giữa các service; test chạy riêng trong CI/dev.
- `frontend-web/nginx.conf` và `vite.config.ts`: chuyển `/api` tới Gateway ở production/dev.
- `.ci/jobs.yml`, `eslint.config.js`, `vitest.config.ts`: kiểm tra thật, không bỏ qua lỗi; coverage frontend hiện chỉ đo các module được chỉ định.
- `frontend-web/package.json` và các lockfile không hỗ trợ comment JSON: ý nghĩa các script đã ghi trong `SPRINT0_HANDOVER.md`; lockfile do package manager cập nhật.
- Các test mới ghi tình huống được bảo vệ ở đầu class/khối test; Playwright mock response chỉ kiểm tra UI/request, không thay kiểm chứng backend thật.
- `scripts/smoke-sprint0.mjs`: kiểm chứng local qua Gateway, tạo fixture riêng và cleanup; cần cờ xác nhận cấp ADMIN cho tài khoản test mới.
- `scripts/generate-erd.mjs`: đọc câu lệnh CREATE TABLE của migration để xuất Mermaid; cần đối chiếu thêm các ALTER migration.

Comment mô tả hành vi hiện tại, không thay cho tài liệu API, test hay bằng chứng nghiệm thu. Các màn sprint sau còn dùng demo đã được ghi rõ trong tài liệu bàn giao.
