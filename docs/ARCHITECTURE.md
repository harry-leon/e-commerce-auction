# Cấu trúc code

```
api/             Controller, DTO, exception handler. Chỉ gọi application/.
application/     Use case, điều phối nghiệp vụ, transaction.
domain/          Entity, value object, domain event, quy tắc nghiệp vụ. Không phụ thuộc framework nếu có thể.
infrastructure/  Repository, Kafka producer/consumer, Redis, client gọi hệ thống ngoài (GHN/GHTK, PayOS...).
config/          Cấu hình Spring (security, openapi, kafka...).
```

Chiều phụ thuộc: `api -> application -> domain <- infrastructure`.

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
