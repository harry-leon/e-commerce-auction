# Đồng bộ source với ERD Nerum V2

## Mục tiêu và phạm vi

Nhánh `bugfix/erd-v2-keycloak-alignment` tách từ `origin/dev`.
MR nhắm vào `dev` theo nhánh tích hợp thực tế; tài liệu Gitflow cũ ghi `develop`.

Sửa các luồng đang có để chạy với schema sau migration V4 và thiết kế IAM mới:
profile, đăng ký/đăng nhập/refresh, role, mật khẩu, phân quyền và giỏ hàng.
Các nghiệp vụ chưa có như checkout/payment, bidding, reserve/shipping và batch reputation
vẫn là backlog, không được xem là hoàn thành chỉ vì đã có bảng hoặc route.

Đây là thay đổi liên service cần được review cùng nhau: token từ Gateway phải được
User/Catalog/Commerce và các service còn lại xác minh theo cùng cơ chế.

## Các thay đổi

### Common và bảo mật

- Thêm `KeycloakAuthorities`: đọc `realm_access.roles`, `roles`, `privileges`.
- Giữ `ROLE_ADMIN` nguyên dạng, không tạo `ROLE_ROLE_ADMIN`.
- Realm role có dấu chấm là privilege nguyên tử, ví dụ `PRODUCT.CREATE`.
- Không tự cấp quyền bằng bảng mapping hardcode trong Java; Keycloak composite là nguồn quyền.
- Chuyển decoder các domain sang JWKS RS256 và kiểm tra issuer; bỏ decoder HS256.
- User Service bỏ filter tạo Authentication từ `X-User-*`.
- Cart dùng subject của JWT, bỏ qua header định danh để không truy cập giỏ người khác.
- Gateway xóa header định danh từ client; sửa filter để chain chạy đúng một lần.
- Service account tạo profile dùng quyền riêng `ROLE_PROFILE_WRITER`.
- Chỉ ADMIN được gán role; Support có `USER.UPDATE` không được cấp role ADMIN.

### User Service

- `User` chỉ ánh xạ bảng profile; bỏ credential, token version và quan hệ IAM cũ.
- Xóa entity/repository Role, Privilege, PasswordResetToken, Reputation đã bị V4 drop.
- Xóa AuthService/JwtService/EmailService cũ và DTO chỉ còn phục vụ luồng IAM cũ.
- Role API giữ đường dẫn/DTO, chuyển sang Keycloak realm roles/composites.
- Role ID trả về hiện là ID Keycloak, không dùng ID từ database IAM cũ.
- Bốn role nền tảng được bảo vệ khỏi thao tác xóa.
- `/users/me` tra `keycloak_id` từ JWT sub rồi dùng profile ID.
- Profile mới dùng cùng UUID với subject; hồ sơ cũ có ID riêng vẫn tra được qua keycloak_id.
- Tìm kiếm chỉ trả hồ sơ chưa xóa mềm.
- Xóa profile: khóa IAM và logout trước, sau đó cập nhật trạng thái hồ sơ.
- Password change kiểm tra mật khẩu tại Keycloak và xác nhận userinfo.sub đúng tài khoản.
- Không lưu reset token vào DB; Keycloak gửi email UPDATE_PASSWORD.
- Giữ `audit_logs` làm bảng kỹ thuật ghi thay đổi hồ sơ, dù ERD minh họa chỉ vẽ users.
- Bỏ cấu hình token lifespan/SMTP cục bộ không còn sử dụng; policy nằm ở Keycloak.

### Gateway

- Login/refresh trả `accessToken`, `refreshToken`, `expiresIn`, `tokenType`, `user`
  tương thích frontend; profile được lấy bằng token thật.
- Register tạo tài khoản Keycloak, gán BUYER rõ ràng, rồi gọi tạo profile bằng service token.
- Không nhận role do frontend yêu cầu khi đăng ký.
- Bù trừ xóa tài khoản mới nếu gán role hoặc tạo profile thất bại; lỗi bù trừ có log.
- Email chưa được xác minh không bị đánh dấu emailVerified=true.
- Thêm route forgot/reset/change-password tới adapter User Service.
- Tách HTTP URL gọi profile của BFF khỏi URI `lb://` dùng trong Gateway routing.
- Timeout hữu hạn cho HTTP client; bỏ secret admin mặc định và message lỗi nội bộ trả client.

### Catalog và Commerce

- Thêm `GET /api/v1/products/skus/{id}` trả snapshot giá/trạng thái phục vụ Cart.
- Lookup SKU đọc product/images trong transaction, không truy cập database Fulfillment.
- `HttpCatalogClient` là mặc định; `MockCatalogClient` chỉ hoạt động với profile `catalog-mock`.
- SKU 404 được xem là unavailable; lỗi Catalog trả lỗi, không fallback giá giả.
- `stockQuantity/stockAvailable=null` nghĩa là chưa kiểm tra Fulfillment.
- Cart có thể thêm SKU đang bán khi chưa biết tồn; checkout tương lai bắt buộc reserve kho.
- Khi sửa số lượng, SKU đã ngừng bán bị từ chối.
- Không còn mặc định mọi UUID đều là SKU hợp lệ với giá 299000 và tồn 100.

### Frontend

- Giữ contract login/refresh cũ.
- mapUser chấp nhận role có/không prefix trong giai đoạn chuyển đổi.
- Quên mật khẩu chỉ gửi yêu cầu email; người dùng đặt mật khẩu ở trang IAM qua liên kết email.
- Bỏ form nhập reset token và helper hash client không được sử dụng.
- Đổi mật khẩu thành công tiếp tục xóa session và điều hướng đăng nhập lại.
- Lệnh smoke-sprint0.mjs cũ nay chuyển tới smoke ERD V2; không bootstrap ADMIN bằng SQL nữa.

### Docker và CI

- Domain Dockerfiles build từ repository root và cài nexus-common trong build stage.
- Thêm Dockerfile reputation-service còn thiếu.
- Sửa port reputation trong Compose để đúng mapping 8087:8080.
- Thêm .dockerignore để không gửi .env, Git, target, node_modules vào build context.
- Thêm override CI riêng, giữ nguyên file jobs sinh theo mẫu.
- Job Common chạy suite các service phụ thuộc khi converter thay đổi.
- Realm import lấy admin secret từ môi trường; composite privileges và service-account permissions
  được khai báo trong deploy/keycloak/nexus-realm.json.

## Migration V5 và dữ liệu cũ

Không sửa V1–V4. V5 thực hiện:

1. Chuyển keycloak_id từ varchar sang UUID; chuỗi rỗng thành NULL.
2. Thêm updated_at và backfill từ created_at.
3. Drop password_hash, failed_login_attempts, locked_until đã chuyển trách nhiệm cho IAM.

Trước khi áp dụng trên database có dữ liệu:

- Sao lưu database user và Keycloak.
- Kiểm kê hồ sơ chưa có keycloak_id; tạo/liên kết đúng tài khoản IAM trước khi yêu cầu họ đăng nhập.
- Sửa keycloak_id không hợp lệ bằng dữ liệu xác minh; migration cố ý dừng khi cast UUID thất bại.
- Di chuyển credential phù hợp trước khi drop; V5 không tự chuyển BCrypt hash sang Keycloak.
- Lưu ý V4 đã drop các bảng IAM/reputation; MR này không phục hồi dữ liệu đã mất bởi V4.
- Không chạy `docker compose down -v` để nâng cấp database dùng chung.

Rollback phải dùng backup hoặc migration bù đã thiết kế riêng; không hạ source về bản dùng
IAM cũ trên schema V4/V5. Cột password đã drop không thể lấy lại bằng rollback source.

## Cấu hình bắt buộc

- KEYCLOAK_ADMIN_CLIENT_SECRET: tự sinh, đặt trong .env local hoặc CI secret; không commit giá trị.
- KEYCLOAK_ISSUER: issuer public, mặc định http://localhost:8180/realms/nexus-realm.
- KEYCLOAK_URL: URL IAM mà backend truy cập, Compose dùng http://keycloak:8080.
- USER_PROFILE_URL: HTTP URL dành cho BFF, Compose dùng http://user-service:8080.
- USER_SERVICE_URL của Gateway vẫn dùng lb://user-service.
- CATALOG_SERVICE_URL của Commerce dùng http://catalog-service:8080.

Với realm đã tồn tại, `--import-realm` không cập nhật realm cũ.
Quản trị phải áp dụng composite privileges, service-account roles và secret vào realm hiện hành
qua Keycloak Admin API/console hoặc quy trình export/import có kiểm soát.
Không reset volume chung chỉ để nạp realm mới.

Realm JSON là cấu hình Keycloak, không phải SQL migration của user-service.
Hai bảng user_entity/credential trong ERD chỉ minh họa ownership; ứng dụng không truy cập trực tiếp.

## Tác động API và phối hợp nhóm

| API/hành vi | Sau thay đổi | Thành phần cần kiểm tra |
|---|---|---|
| POST /auth/login, /auth/refresh | Camel-case token + user; Keycloak xác thực | Frontend, Gateway |
| POST /auth/register | Trả profile HTTP 201; UI đăng nhập sau đăng ký | Frontend, Gateway, User |
| POST /users | Chỉ service account PROFILE_WRITER và header X-Profile-Subject | Gateway, User |
| GET/PUT /users/me | Tra keycloak_id, không giả định profile ID bằng sub | User, Frontend |
| GET/POST/PUT/DELETE /roles | Adapter Keycloak; role ID cũ không dùng lại | Admin UI |
| PUT /users/{id}/roles | ADMIN; không ghi user_roles | Admin UI, User |
| POST /auth/forgot-password | Keycloak gửi action email | Frontend, Keycloak SMTP |
| POST /auth/reset-password | HTTP 410; token cũ phải yêu cầu email mới | Frontend |
| POST /auth/change-password | Keycloak đổi mật khẩu/logout session | Frontend, User |
| GET /products/skus/{id} | Endpoint snapshot mới dưới route products hiện hữu | Catalog, Commerce |
| Cart stockAvailable | Có thể null; chưa có Fulfillment thì không tuyên bố đã kiểm tra tồn | Commerce, Buyer UI |
| HS256 JWT cũ | Không còn được chấp nhận; người dùng đăng nhập lại | Toàn bộ service |

Access token đã phát hành vẫn có thể hợp lệ đến expiry dù IAM đã logout/disable.
Profile /me chặn hồ sơ không ACTIVE, nhưng chưa có cơ chế introspection/denylist tức thời toàn hệ thống.
Các mutation liên Keycloak/PostgreSQL không phải distributed transaction; khi bù trừ thất bại cần
đối soát tài khoản mồ côi, và thay đổi role cần refresh/login lại để nhận claim mới.

## Kiểm chứng

Kết quả kiểm tra local ngày 24/10/2026:

| Thành phần | Test | Kết quả |
|---|---:|---|
| Common | 14 | Pass |
| User | 48 | Pass, coverage gate >=70%, Checkstyle pass |
| Catalog | 128 | Pass, coverage gate >=70%, Checkstyle pass |
| Commerce | 30 | Pass, coverage gate >=70%, Checkstyle pass |
| Auction | 7 | Pass, coverage gate >=70%, Checkstyle pass |
| Fulfillment | 7 | Pass, coverage gate >=70%, Checkstyle pass |
| Notification | 5 | Pass, coverage gate >=70%, Checkstyle pass |
| Gateway | 2 | Pass |
| Reputation | 0 | Compile/package pass; chưa có suite nghiệp vụ |
| Frontend | 146 | Pass; build/typecheck/lint pass |

- Smoke PostgreSQL 16 + Keycloak 24 thật: pass, gồm upgrade V4→V5 có dữ liệu cũ và Role CRUD.
- Docker build User/Reputation: pass với root context và nexus-common.
- Docker Compose config: pass; YAML CI được kiểm tra cú pháp bằng parser Prettier.
- Ba file frontend thay đổi đều qua Prettier.
- format:check toàn frontend vẫn fail trên 46 file không sửa trong MR; không format hàng loạt code của nhóm.
- Chưa xác nhận pipeline GitLab hoặc môi trường staging; trạng thái pipeline phải được xem trên MR sau khi push.
- Smoke không gửi email reset thật hoặc tự động bấm email action; phần adapter email có test HTTP giả lập.

Các lệnh kiểm tra:

```powershell
mvn -f backend/common/pom.xml install
mvn -f backend/user-service/pom.xml verify checkstyle:check
mvn -f backend/catalog-service/pom.xml verify checkstyle:check
mvn -f backend/commerce-service/pom.xml verify checkstyle:check
mvn -f backend/api-gateway/pom.xml verify
node scripts/smoke-erd-v2.mjs
cd frontend-web
npm run build
npm test
npm run lint
npm run format:check
```

Smoke ERD V2 tạo PostgreSQL/Keycloak tạm với tên ngẫu nhiên, chạy JAR local,
kiểm tra Flyway/Hibernate, register/login/refresh/profile, chống header giả,
Keycloak composites, Role CRUD, Catalog lookup/Cart và đổi mật khẩu.
V5 được thử cả trên schema V4 có hồ sơ cũ để kiểm tra giữ ID và backfill updated_at.
Cleanup chỉ xóa container/process
của lần test đó. Không dùng .env, volume hoặc tài khoản thật của nhóm.

## Phần ERD và backlog còn lại

- Bỏ khối catalog_svc.products trùng, giữ seller_id.
- Bổ sung categories.name_vi; auctions/carts/inventory_reservations.created_at.
- Bổ sung reputations.avg_rating, total_ratings, updated_at.
- Thống nhất vouchers.name với migration varchar(255); sửa lỗi kiểu credential_data.
- ratings liên kết ratee_id với reputations(user_id) trong migration; chỉnh ký hiệu FK trên sơ đồ cho đúng.
- Các ID xuyên service là tham chiếu logic, không thêm FK tới DB khác.
- Chưa triển khai đầy đủ Order/Payment/Voucher/Refund/Invoice, Auction/Bid/Settlement,
  Fulfillment reservation/shipment, Notification consumer/retry, Reputation aggregate.
- Outbox/Inbox, timestamptz toàn hệ thống, reserve idempotency và tối ưu batch SKU là đề xuất tiếp theo;
  không tự ý thêm schema hoặc contract mới khi nghiệp vụ tương ứng chưa triển khai.

## Review và merge

MR phải có pipeline xanh, review của các thành viên User/Gateway/Catalog/Commerce/Frontend
và ít nhất một approval theo CONTRIBUTING.md. Không tự merge vào dev.
Ưu tiên kiểm tra staging realm và migration trên bản sao dữ liệu trước khi merge/deploy.
