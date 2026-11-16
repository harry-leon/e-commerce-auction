# TT-S3-06 — Lưu khai báo kiện trước dispatch

Fulfillment bổ sung kho dữ liệu kiện thực, độc lập API preview. Chỉ Admin khai báo/đọc; không gọi Commerce, carrier, reserve/commit kho hoặc tạo vận đơn. Đây là khai báo của nhân viên, chưa phải xác nhận đã cân đúng, carrier hỗ trợ tuyến hoặc quyền dispatch.

## Contract

`POST /api/v1/shipments/preparation/{orderId}/parcels`, header `Idempotency-Key` UUID bắt buộc, body cùng cấu trúc Parcel của preview:

```json
{"warehouseId":"11111111-1111-4111-8111-111111111111","carrier":"GHN","serviceType":"STANDARD","weightGrams":725,"toDistrictId":12,"toWardCode":"WARD"}
```

Ví dụ chỉ minh họa, không phải kho/tuyến thật để gửi carrier. Mã carrier GHN/GHTK và serviceType được trim/uppercase; wardCode trim. Weight/district dương; serviceType tối đa 30 ký tự mã chữ/số/gạch dưới, wardCode tối đa 100. Không tự thêm kho/cân nặng mặc định. Kho/carrier phải tồn tại và active tại thời điểm đăng ký; chưa kiểm tra route/service thực tế hoặc kho đã được phân bổ cho Order.

POST mới trả 201, replay cùng actor/key/order/nội dung chuẩn hóa trả 200. Khác nội dung/order cùng key, hoặc key/actor khác cho Order đã có kiện trả 409. Một Order chỉ một khai báo, chưa có API sửa/xóa/ghi đè. Replay giữ nguyên id, registeredBy, registeredAt kể cả kho sau đó tắt; đây là trả lịch sử, không có nghĩa kiện hiện đủ điều kiện gửi.

`GET` cùng đường dẫn trả khai báo/audit hiện có; 404 nếu chưa có. Cả hai API yêu cầu role Admin và JWT subject UUID; actor không nhận từ body. 400 key/body sai, 401 thiếu JWT/subject hợp lệ, 403 sai role; 409 PARCEL_CONFLICT hoặc PARCEL_RESOURCE_UNAVAILABLE. Dùng ApiResponse/ApiError hiện có. Gateway đã bao phủ `/api/v1/shipments/**`.

## Lưu trữ và concurrency

V12 thêm shipment_parcels, không backfill. FK chỉ tới kho/carrier Fulfillment; orderId thuộc Commerce nên không FK xuyên service và endpoint này chưa xác minh Order tồn tại. Chỉ lưu thông số kiện, actor/key/thời điểm; không ghép tên/điện thoại/địa chỉ người nhận. Không nhập secret/PII vào trường mã.

Khóa transaction PostgreSQL actor/key → Order, rồi ghi kiện/audit cùng row. Unique Order và actor/key bảo vệ nhiều instance/restart; rollback không giữ key hoặc row giả. API không UPDATE dữ liệu cũ. Khi đã thấy shipment/dispatch attempt, từ chối khai báo mới; kiểm tra này không tuần tự hóa với các API dispatch cũ. Không dùng nó làm hàng rào chống dispatch đồng thời hoặc thay lifecycle guard.

## Phụ thuộc và bước tiếp

Kế thừa Fulfillment !160/!162; merge dependency V11 trước V12. Không cần merge Commerce producer !168 để dùng API khai báo. Khi tích hợp hai nhánh, giữ cả metadata Commerce 21 và Fulfillment 12, không hạ version của service kia.

Chưa có consumer ghép kiện với WAITING_PARCEL, quyền service đọc kiện, claim Commerce tuần tự với refund/hủy, cancellation/revision của kiện hoặc worker gọi carrier. Consumer tương lai phải xác minh lại Order/Payment/kho/refund/hold và kiện hiện hành, lấy snapshot người nhận qua API đúng service identity; chỉ gửi mutation qua durable dispatch guard sau khi có quyền bền vững. Không chuyển bản ghi này thẳng vào API createShipment để coi như đã chống race tài chính.

## Kiểm chứng

JUnit báo cáo 210 test Fulfillment, 0 fail/error/skip; gồm 5 ca PostgreSQL mới (replay, hai kiểu concurrency, rollback, validation/dispatch đã tồn tại) và 3 ca MVC (quyền, JWT actor, 201/200/GET và lỗi metadata/409). JaCoCo LINE 3025/3743 (80,82%). Test dùng PostgreSQL tạm và fixture, không phải provider/IAM triển khai hoặc UAT.

Chạy đầy đủ: `mvn -f backend/fulfillment-service/pom.xml clean verify checkstyle:check` với `FULFILLMENT_TEST_DB_URL` chỉ trỏ PostgreSQL tạm. Xác minh cuối chạy lại 8 ca mới bằng `-Dtest=ShipmentParcelPostgresTest,ShipmentParcelControllerTest verify checkstyle:check` sau biên dịch; không tính cộng thêm vào 210 ca khác nhau. 19 test demo Node và migration/seed replay/reset replay/reseed cả 7 service đạt. Cluster tạm đã dừng. Không dùng DB nhóm/.env hoặc gọi carrier thật; pipeline GitLab chưa xác nhận.
