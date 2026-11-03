# DTO Contract giữa Frontend và Backend

> **Phiên bản:** 1.0
>
> **Baseline Order/Payment được đối chiếu:** 03/11/2026; các service ngoài phạm vi giữ thời điểm tài liệu riêng
>
> **Phạm vi:** API đi qua `api-gateway`, các DTO request/response đã tồn tại trong source
>
> **Trạng thái:** `CURRENT` — mô tả hành vi đang được triển khai, không phải thiết kế dự kiến

## 1. Mục đích

Thư mục này là điểm tham chiếu chung để Frontend và Backend thống nhất tên field, kiểu dữ liệu, validation, enum và cách bọc response. Khi tài liệu khác với source đang chạy, source là hành vi thực tế và pull request thay đổi DTO phải cập nhật lại tài liệu này trong cùng commit.

Tài liệu chỉ đánh dấu một contract là `CURRENT` khi controller/DTO tương ứng đã có trong source. Route mới chỉ xuất hiện trong SRS, ERD hoặc tài liệu Sprint phải được ghi là `PLANNED`; Frontend không tích hợp route `PLANNED` như API thật.

## 2. Danh mục contract

| Thành phần | Contract | Tình trạng nghiệp vụ |
|---|---|---|
| Dùng chung | [COMMON.md](COMMON.md) | Envelope lỗi và envelope success chuẩn đã có trong `nexus-common` |
| API Gateway | [API_GATEWAY.md](API_GATEWAY.md) | Login, refresh, register đã triển khai |
| User Service | [USER_SERVICE.md](USER_SERVICE.md) | User, role, password đã triển khai |
| Catalog Service | [CATALOG_SERVICE.md](CATALOG_SERVICE.md) | Product, SKU, image, category, search đã triển khai |
| Commerce Service | [COMMERCE_SERVICE.md](COMMERCE_SERVICE.md) | Cart, checkout COD/VNPAY, đọc/hủy/cập nhật đơn, payment/IPN và outbox đã triển khai |
| Auction Service | [AUCTION_SERVICE.md](AUCTION_SERVICE.md) | Chưa có DTO nghiệp vụ |
| Fulfillment Service | [FULFILLMENT_SERVICE.md](FULFILLMENT_SERVICE.md) | Chưa có DTO nghiệp vụ |
| Notification Service | [NOTIFICATION_SERVICE.md](NOTIFICATION_SERVICE.md) | Inbox IN_APP, ingestion Commerce, transactional email và idempotency đã triển khai |
| Reputation Service | [REPUTATION_SERVICE.md](REPUTATION_SERVICE.md) | Chưa có controller/DTO nghiệp vụ |

Register tích hợp Sprint 2, nguồn nhánh, migration và bằng chứng kiểm tra: [TT-S2-1_ORDER_PAYMENT_BASELINE.md](../TT-S2-1_ORDER_PAYMENT_BASELINE.md).

## 3. Quy ước đọc bảng DTO

- `Bắt buộc = Có`: field phải có và không được `null`, căn cứ annotation validation hoặc kiểu xử lý trong controller.
- `Bắt buộc = Không`: field có thể bỏ khỏi JSON. Nếu source không định nghĩa default, giá trị phía server có thể là `null`.
- UUID truyền dưới dạng chuỗi chuẩn, ví dụ `"6f1f50df-8f2f-4db5-a49f-44bc63d13d2a"`.
- `LocalDate` dùng `YYYY-MM-DD`; `Instant` dùng ISO-8601 UTC; `LocalDateTime` hiện không bắt buộc có timezone.
- `BigDecimal` được serialize thành JSON `number`. FE không nên dùng phép toán số thực để chốt tiền; giá trị từ server là nguồn hiển thị/chốt đơn.
- Enum phân biệt hoa thường và dùng giá trị `UPPER_SNAKE_CASE` đúng như bảng.
- Field response có thể `null` được ghi rõ. FE không tự suy ra field chưa có từ mock data.
- Mỗi service `CURRENT` có JSON request/response mẫu cho từng shape khác nhau. Các endpoint dùng chung DTO sẽ tham chiếu cùng một mẫu thay vì lặp lại payload.
- Giá trị UUID, token, thời gian, URL và dữ liệu cá nhân trong ví dụ chỉ là dữ liệu minh họa; tên field và cấu trúc JSON mới là contract.

## 4. Quy ước tích hợp FE/BE

1. FE định nghĩa type theo JSON contract, không import hoặc mô phỏng class Java.
2. BE không đổi tên, kiểu, nullability, enum hoặc wrapper của field đã công bố nếu chưa cập nhật tài liệu và thông báo FE.
3. Field mới trong response nên tương thích ngược. Xóa/đổi tên field, đổi wrapper hoặc đổi ý nghĩa là breaking change và cần API version mới hoặc giai đoạn chuyển tiếp.
4. Request không gửi field do server quản lý như `id`, `sellerId`, `status` mặc định, thời gian tạo/cập nhật hoặc quyền lấy từ JWT, trừ khi contract endpoint ghi rõ.
5. FE luôn xử lý HTTP status trước, sau đó mới đọc payload. Với service dùng envelope, kiểm tra thêm `success`.
6. Danh sách rỗng dùng `[]`; không dùng `null` nếu DTO hiện tại trả collection.
7. Phân trang dùng chỉ số trang bắt đầu từ `0`.

## 5. Chênh lệch hiện tại cần biết

| Mã | Hiện trạng | Cách FE xử lý tạm thời | Hướng đồng bộ đề xuất |
|---|---|---|---|
| `CONTRACT-001` | Catalog và Commerce bọc success bằng `ApiResponse<T>`; User success trả DTO/`Page`/`Map` trực tiếp | Type riêng theo từng endpoint; không ép toàn bộ response về một wrapper | Chuyển User success sang envelope trong một thay đổi có phối hợp |
| `CONTRACT-002` (đã xử lý) | Auth Gateway dùng ApiResponse; token chỉ trong cookie HttpOnly | AuthPayload JSON chỉ có user/expiresIn/tokenType; cookie tự gửi, có header chống CSRF | Theo contract hiện tại trong API_GATEWAY.md; không khôi phục token vào storage |
| `CONTRACT-003` | `frontend-web/src/types.ts` chứa nhiều model mock rộng hơn DTO Backend thật | Dùng mapper từ API DTO sang UI model; field chưa có phải có default hiển thị rõ | Không dùng UI model làm API contract |
| `CONTRACT-004` | User list/activity trả cấu trúc `Page` của Spring Data, không phải `meta.pagination` | Parse cấu trúc Page riêng | Chuẩn hóa sang `ApiResponse<List<T>>` kèm `meta.pagination` |
| `CONTRACT-005` | `PageResponse` cục bộ tồn tại trong vài service nhưng controller hiện không dùng | Không xây FE type dựa vào class chưa được expose | Xóa class thừa hoặc dùng thống nhất trong lần refactor sau |
| `CONTRACT-006` | `MetaInfo.sort` ở Backend là một `string`, trong `frontend-web/src/api/types.ts` hiện khai báo `string[]` | Chưa đọc `sort` nếu chưa normalize | Chọn một kiểu và sửa BE/FE/test trong cùng pull request |
| `CONTRACT-007` | Type `Envelope<T>` cục bộ của FE dùng `meta.total/page/pageSize`, khác `meta.pagination` thực tế | Dùng `ApiResponse<T>`/`PaginationInfo` cho code mới | Loại bỏ type tương thích cũ sau khi migrate nơi sử dụng |

## 6. Checklist khi thay đổi DTO

- Cập nhật DTO/controller, validation, enum và test Backend.
- Cập nhật file contract của service trong thư mục này.
- Cập nhật type/mapper và test Frontend nếu contract đã được FE sử dụng.
- Ghi rõ breaking/non-breaking trong mô tả pull request.
- Chạy kiểm thử của service bị ảnh hưởng và `npm test -- --run` nếu thay đổi contract FE đang dùng.
- Kiểm tra Swagger/OpenAPI của service sau khi chạy local; Swagger hỗ trợ khám phá API nhưng tài liệu này vẫn là thỏa thuận phối hợp FE/BE.
