# TT-S3-06 — Fulfillment chuẩn bị dữ liệu kiện

Nhánh này kế thừa !150 (retry mutation và quyền Shipping từ !142); khi chạy cần API Commerce snapshot !152 và IAM đã cấp `nexus-fulfillment-service` + `COMMERCE.SHIPPING_READ`. Chưa đổi Keycloak hoặc gọi carrier thật.

## Cấu hình

Fulfillment đọc các property `app.shipping.commerce.url` (mặc định http://localhost:8083), `app.shipping.commerce.token-url`, `app.shipping.commerce.client-id` (mặc định nexus-fulfillment-service), `app.shipping.commerce.client-secret`. Dùng biến môi trường tương ứng `APP_SHIPPING_COMMERCE_URL`, `APP_SHIPPING_COMMERCE_TOKEN_URL`, `APP_SHIPPING_COMMERCE_CLIENT_ID`, `APP_SHIPPING_COMMERCE_CLIENT_SECRET`; secret chỉ lưu ở môi trường/kho secret. Thiếu OAuth phải báo lỗi, không lấy JWT Admin/Buyer thay thế.

Client dùng cache client_credentials chung của common, HTTP connect/read 5 giây, không theo redirect hoặc retry tự động. Response phải có success=true, data và orderId khớp request; 401 xóa cache để lần request sau lấy token mới. Lỗi upstream/parse không nối response/cause chứa PII. Chưa có circuit breaker riêng hoặc GET retry trong client này.

## API preview

`POST /api/v1/shipments/preparation/{orderId}` chỉ JWT Admin. Ví dụ body:

```json
{"warehouseId":"11111111-1111-4111-8111-111111111111","carrier":"GHN","serviceType":"STANDARD","weightGrams":725,"toDistrictId":12,"toWardCode":"WARD_CODE_FROM_PROVIDER"}
```

UUID/tuyến trong ví dụ chỉ minh họa, không phải fixture để gửi carrier. Dữ liệu kho/cân nặng/quận/phường do người chuẩn bị kiện cung cấp thực tế. Tên/số điện thoại/địa chỉ/giá lấy từ Commerce, body không được dùng để thay snapshot đó. Response 200 `ApiResponse.data` là CreateShipmentRequest, chưa lưu DB, tạo tracking, reserve/commit kho hoặc gửi carrier. Gateway đã route `/api/v1/shipments/**`.

Phạm vi hiện tại chỉ VNPAY với status PAID/PROCESSING, priceFinalized=true, total không âm, người nhận/điện thoại/địa chỉ đầy đủ và fulfillmentStatus không phải REVIEW. COD cần workflow riêng nên bị chặn. Parcel phải có warehouseId, carrier/serviceType, weightGrams>0, districtId>0, wardCode không rỗng; không tự đặt 500g. Thiếu điều kiện trả 409 SHIPMENT_NOT_READY. Thiếu JWT 401, sai role 403, body JSON/UUID sai 400. Lỗi IAM/upstream hiện đi qua lỗi server chung, chưa phân loại lại 404/502 của upstream trong contract này.

Preview không chứng minh kho tồn tại/được phân bổ, carrier hỗ trợ tuyến, kiện đã cân đúng hoặc trạng thái Commerce sẽ giữ nguyên đến dispatch. Dispatch phải xác minh lại và lưu command/attempt trước HTTP. Không chuyển response preview vào API tạo shipment rồi retry khi timeout để coi như đã chống trùng.

## Kiểm tra và phần còn lại

Test dùng HTTP localhost cho OAuth/Commerce, không dùng secret thật: bearer service account, cache, refresh 401, sai order, redirect/503. Unit test ghép snapshot với kiện, không thu COD lần nữa, từ chối đơn sai trạng thái/COD/giá tạm/thiếu người nhận. MVC test bảo vệ Admin, chặn Buyer/khách trước khi gọi service.

Chưa triển khai durable dispatch attempt/UNKNOWN/query-by-reference, producer sau thanh toán, callback Commerce hoặc E2E IAM/carrier. Các phần này vẫn cần làm trước khi bật tự động tạo vận đơn. Không có migration trong phần chuẩn bị dữ liệu này.
