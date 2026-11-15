# TT-S3-05 — Preferences thông báo và lọc lịch sử inbox

Bổ sung hai phần còn thiếu của nhóm "notification transport/push/health": người dùng tự cấu hình kênh nhận tin và tra cứu lịch sử inbox theo bộ lọc. Phần này **không** thay đổi transport, không thêm broker ngoài và không đổi nội dung thông báo nghiệp vụ.

## 1. Lọc lịch sử inbox — `GET /api/v1/notifications`

| Tham số | Kiểu | Mặc định | Ý nghĩa |
| --- | --- | --- | --- |
| `page`, `size` | int | 0, 20 | Giữ nguyên giới hạn cũ: `page` 0–10000, `size` 1–100 |
| `type` | string | — | Một giá trị trong `CommerceNotificationController.Type` |
| `unreadOnly` | boolean | false | Chỉ trả tin `SENT` (chưa đọc) |
| `from`, `to` | ISO-8601 instant | — | Lọc `created_at` trong khoảng, bao gồm hai đầu |

Quy tắc:

- `user_id` luôn bind từ JWT subject và đứng đầu mệnh đề `WHERE`; không nhận `userId` từ query nên không có đường đọc chéo hộp thư.
- `type` ngoài danh sách enum trả `400 INVALID_FILTER`. Giá trị tự do không được đẩy xuống DB để tránh quét bảng và tránh tạo API tra cứu tùy ý.
- `from`/`to` sai định dạng trả `400 INVALID_FILTER`; `from > to` cũng trả `400`.
- `unreadOnly=true` chỉ thu hẹp danh sách trả về. `totalElements` tính trên bộ lọc `type`/`from`/`to` đang áp dụng; `unreadCount` tính trên toàn bộ tin chưa đọc cùng bộ lọc, nên badge không "biến mất" khi người dùng đang mở tab chưa đọc.

## 2. Cấu hình nhận tin — `/api/v1/notifications/preferences`

| Method | Đường dẫn | Quyền | Kết quả |
| --- | --- | --- | --- |
| GET | `/api/v1/notifications/preferences` | JWT hợp lệ | Danh sách `{type, channel, enabled}` của chính subject |
| PUT | `/api/v1/notifications/preferences` | JWT hợp lệ | Upsert một cặp `{type, channel, enabled}` |

Quy tắc:

- Chỉ cấu hình được kênh `PUSH` và `EMAIL`. `IN_APP` cố định bật vì là nguồn resync sau khi reconnect; gửi `IN_APP` trả `400 INVALID_PREFERENCE`.
- `type` phải thuộc enum sự kiện Commerce đang phát (`ORDER_*`, `PAYMENT_*`, `AUCTION_*`, `REFUND_*`). Loại lạ trả `400` để không sinh cấu hình chết.
- Ghi bằng `INSERT ... ON CONFLICT (user_id,notification_type,channel) DO UPDATE`, dựa trên unique constraint có sẵn từ `V2__init_schema.sql`. Gọi lặp chỉ giữ một dòng, không sinh bản ghi trùng.
- Thiếu field bắt buộc đi qua bean validation nên trả `422 VALIDATION_ERROR` theo envelope chung, không phải `400`.
- Không cần migration mới: bảng `notification_preferences` đã tồn tại từ V2 và trước đây chưa có API nào dùng.

## 3. Ảnh hưởng tới luồng gửi thông báo

`CommerceNotificationController.receive` gọi `pushAllowed(userId, type)` trước khi ghi bản ghi `PUSH` và phát WebSocket:

- Không có dòng cấu hình → mặc định bật, giữ đúng hành vi trước thay đổi này.
- Có dòng `enabled=false` → bỏ qua bản ghi `PUSH` và WebSocket.
- `IN_APP` **luôn** được ghi và luôn có `notification_attempts` tương ứng, nên inbox REST vẫn đầy đủ sau reconnect.
- Lỗi đọc bảng cấu hình không làm mất thông báo: catch `RuntimeException` và trả `true`. Đây là chủ ý — cấu hình là tối ưu trải nghiệm, không phải điều kiện đúng đắn của nghiệp vụ.

Transport vẫn là HTTP outbox + simple STOMP broker trong process. Preferences không chứng minh broker ngoài, không thay thế retry/dead-letter và không thay thế bằng chứng push tới thiết bị thật.

## 4. Kiểm chứng

- `NotificationControllerTest`: thêm ca lọc theo `type`/`unreadOnly`/`from`/`to` và các đầu vào bị từ chối (`type` lạ, `from` sai định dạng, `from > to`).
- `NotificationPreferenceControllerTest` (mới): anonymous `401`, subject không phải UUID `401`, đọc danh sách theo đúng subject, PUT hợp lệ, từ chối `IN_APP`/loại lạ, thiếu field `422`.
- `CommerceNotificationControllerPushTest`: thêm ca tắt `PUSH` — không phát WebSocket, chỉ ghi `IN_APP` + attempt.
- `NotificationPostgresTest.filtersHistoryAndPersistsPreferencesOnRealDatabase`: chạy PostgreSQL thật, xác nhận lọc theo loại không lẫn tin người khác, `unreadOnly` + `unreadCount` nhất quán, upsert hai lần chỉ một dòng và không đụng cấu hình user khác.

Kết quả local ngày 15/11/2026 (PostgreSQL 16 trong container tạm, không dùng DB nhóm):

```
Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
You have 0 Checkstyle violations.
BUILD SUCCESS
```

## 5. Giới hạn còn lại

- Chưa có UI Buyer cho preferences; API sẵn sàng nhưng frontend chưa gọi.
- Chưa phân biệt ưu tiên theo kênh khi broker ngoài được thêm; simple broker hiện không hỗ trợ routing theo preference ở tầng transport.
- Chưa có bằng chứng push tới thiết bị thật (FCM/APNs); health `notificationTransport` chỉ phản ánh broker trong process.
