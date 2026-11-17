# TT-S3-05 — Event envelope chung và xử lý poison event

Nhóm TT-S3-05 yêu cầu "eventId/type/version/aggregateId/occurredAt/correlationId, inbox dedup, retry và xử lý poison event".
Trước MR này contract shipment chỉ có `eventId`, `eventType`, `occurredAt` cùng các trường nghiệp vụ; không có version,
aggregate hay correlation. Dedup/retry đã có từ trước (`shipping_event_inbox`, `fulfillment_event_outbox`), MR này bổ sung
phần envelope và phân loại poison.

## 1. Envelope

Producer: `FulfillmentEventOutboxService` phát thêm 4 trường cho mọi sự kiện shipment.

| Trường | Kiểu | Ý nghĩa |
| --- | --- | --- |
| `eventId` | UUID | Định danh duy nhất, đã dùng làm dedup key |
| `eventType` | string | `ShipmentCreated`…`ShipmentReturned` |
| `schemaVersion` | int | `1` cho contract hiện tại |
| `aggregateType` | string | `SHIPMENT` |
| `aggregateId` | UUID | Luôn bằng `shipmentId` |
| `correlationId` | UUID | Gốc chuỗi nhân quả; mặc định bằng `eventId` |
| `occurredAt` | ISO-8601 | Thời điểm nghiệp vụ |

Consumer: `ShippingEventInboxService.Event` nhận thêm 4 trường, đều **nullable** và có constructor cũ 8 tham số.
Nhờ vậy outbox cũ chưa nâng cấp vẫn gửi được — đây là lý do chọn nullable thay vì `@NotNull`.

## 2. Phân loại poison event

`requireEnvelope` chạy trước khi chạm DB:

| Điều kiện | Kết quả |
| --- | --- |
| `schemaVersion` vắng mặt | Chấp nhận (contract cũ) |
| `schemaVersion` = 1 | Chấp nhận |
| `schemaVersion` khác 1 | **422** `SHIPPING_EVENT_POISON` |
| `aggregateType` vắng mặt hoặc `SHIPMENT` | Chấp nhận |
| `aggregateType` khác | **422** |
| `aggregateId` vắng mặt hoặc trùng `shipmentId` | Chấp nhận |
| `aggregateId` khác `shipmentId` | **422** |

Vì sao 422 chứ không 409:

- `ShippingEventDeliveryWorker` coi 400/422 là **poison**: đặt `attempts=8` ngay, dừng retry, giữ dead letter cho vận hành.
- 409 là "trạng thái tạm thời, thử lại sau" (ví dụ Delivered đến trước PickedUp) nên vẫn retry hữu hạn.
- Nếu event sai contract mà retry, nó sẽ quay vô hạn tới hết budget rồi mới dead letter, làm nhiễu health
  `shippingEventTransport` trong nhiều tick.

Sự kiện poison **không** được ghi vào `shipping_event_inbox`, để lần replay/đối soát sau thấy rõ là chưa xử lý.

## 3. Không thay đổi

- Dedup: vẫn advisory lock `shipping-event:{eventId}` + `UNIQUE` inbox + so khớp payload.
- Retry/backoff: `attempts`, `next_attempt_at`, `last_error` giữ nguyên.
- Broker: vẫn HTTP outbox, chưa có Kafka/AMQP. `schemaVersion`/`correlationId` chỉ là hợp đồng dữ liệu, không
  phải bằng chứng có broker ngoài.
- Không đổi trạng thái tài chính, không gọi carrier.

## 4. Kiểm chứng

| Test | Nội dung |
| --- | --- |
| `FulfillmentEventOutboxServiceTest` (2, mới) | Payload có đủ 6 trường envelope; aggregate trỏ đúng shipment; shipment không có status vẫn sinh envelope hợp lệ |
| `ShippingEventDeliveryPostgresTest` (5) | Fixture cập nhật envelope đầy đủ; ACK/retry/poison/concurrent vẫn đúng |
| `PaymentPostgresTest` (+1) | PostgreSQL thật: producer cũ (không envelope) vẫn xử lý; envelope version 1 hợp lệ; version 2 và aggregate lệch trả 422 và không ghi inbox |
| `ShippingEventInternalControllerTest` (1) | Giữ nguyên luồng HTTP/security |

Kết quả local 15/11/2026 trên PostgreSQL 16 container tạm:

```
commerce-service:    Tests run: 505, Failures: 0, Errors: 0, Skipped: 0   Checkstyle 0 violation
fulfillment-service: Tests run: 236, Failures: 0, Errors: 0, Skipped: 0   Checkstyle 0 violation
```

## 5. Hai lỗi có sẵn trên `dev` phải sửa để chạy test

1. `OrderServiceTest` gọi `orderRepository.findForUpdate(...)` trong khi `OrderRepository` chỉ có `findForPayment(...)`
   → commerce-service không test-compile được từ commit `52b0cc8`. Fix giống MR !185.
2. `DispatchCommerceClientTest.bindsHandoffClaimAndPreflightWithStableKey` dùng `Instant.now()` (nano giây) rồi so sánh
   sau JSON round-trip (chỉ giữ micro giây) → fail ngẫu nhiên. Cắt về mili giây trước khi assert.

Cả hai là fix compile/flaky, không đổi hành vi production. Nếu !185 được merge trước, phần sửa `OrderServiceTest`
trong MR này sẽ tự động khớp và không tạo conflict nội dung.

## 6. Giới hạn còn lại

- Chưa rollout envelope sang các sự kiện khác (notification, auction, inventory); chỉ shipment được chuẩn hóa trong MR này.
- Chưa có proxy/broker đọc `correlationId` để dựng trace xuyên service; hiện chỉ lưu trong payload outbox.
- Chưa có alert riêng cho poison event; vận hành vẫn đọc qua health/dead letter hiện có.
- Chưa xác minh pipeline GitLab.
