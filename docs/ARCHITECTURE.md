# Cấu trúc code (Spring MVC)

```
controller/     Controller (@RestController), DTO, exception handler. Chỉ gọi service/.
service/        Service (@Service), điều phối nghiệp vụ, transaction.
entity/         Entity (@Entity), enums, quy tắc nghiệp vụ.
repository/     Repository (@Repository, Spring Data JPA), custom query.
config/         Cấu hình Spring (security, openapi, kafka...).
```

Chiều phụ thuộc: `controller -> service -> repository -> entity`.

## Nguyên tắc bắt buộc (SRS mục 4)
- Mỗi service có DB riêng, không truy cập DB của service khác; giao tiếp qua API hoặc event (Kafka).
- API có version (`/api/v1`), có OpenAPI, giữ tương thích ngược.
- Thay đổi API / event schema phải được review trước khi merge.
- Secret không hardcode: dùng biến môi trường / Secret Manager.
- Thao tác quan trọng (tạo đơn, callback thanh toán, đặt giá, reserve kho) phải idempotent.

## Phân quyền
JWT chứa claim `privileges` (danh sách như `PRODUCT.CREATE`, `AUCTION.BID`, khớp SRS mục 2.7).
Dùng `@PreAuthorize("hasAuthority('PRODUCT.CREATE')")` trên method.
Keycloak phát JWT RS256. Gateway và từng service xác minh chữ ký bằng JWKS, kiểm tra issuer;
`KeycloakAuthorities` chuyển realm composites thành authority. Không xác thực bằng header client tự gửi.
User Service chỉ giữ profile và gọi Admin API khi cần quản lý IAM.
Xem [bàn giao ERD V2](ERD_V2_ALIGNMENT.md) để biết migration và các API thay đổi.

## Thêm dependency khi cần
| Service | Thêm |
|---|---|
| auction, fulfillment | `spring-boot-starter-data-redis`, `spring-boot-starter-websocket` (auction) |
| commerce, notification, fulfillment | `spring-kafka` |
| notification | `spring-boot-starter-mail`, Firebase Admin SDK |
