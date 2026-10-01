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
Lưu ý: user-service sẽ phát hành JWT; các service còn lại chỉ xác thực bằng cùng secret/khóa.
Khi triển khai thật, cân nhắc chuyển sang RS256 (khóa công khai) để không phải chia sẻ secret giữa các service.

## Thêm dependency khi cần
| Service | Thêm |
|---|---|
| auction, fulfillment | `spring-boot-starter-data-redis`, `spring-boot-starter-websocket` (auction) |
| commerce, notification, fulfillment | `spring-kafka` |
| notification | `spring-boot-starter-mail`, Firebase Admin SDK |
