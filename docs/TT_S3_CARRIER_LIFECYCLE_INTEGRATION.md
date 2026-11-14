# Polling carrier → Commerce → Notification

## Cách chạy và phạm vi

```powershell
node --test scripts/shipping-flow/fixtures.test.mjs scripts/shipping-flow/dispatch.test.mjs
node scripts/check-shipping-flow.mjs --pg-bin 'C:/Program Files/PostgreSQL/18/bin' --maven '<mvn.cmd>' --carrier-lifecycle
```

Yêu cầu Java 21, Maven, PostgreSQL binary, Docker và image Keycloak 24.0.5 có sẵn. Tùy chọn mới bao gồm `--dispatch` và `--dispatch-security`; không đổi hành vi mặc định của hai chế độ cũ. Harness chạy xong create/recovery mới restart Fulfillment để bật scheduler polling thật với chu kỳ 1000ms, đúng giới hạn bean validation.

Carrier fixture bổ sung GHN POST `/shiip/public-api/v2/shipping-order/detail`, chỉ truy cập loopback bằng token sinh tạm. Điều khiển `unavailable/status` ở fixture để tạo lỗi HTTP hoặc trạng thái carrier. Không cập nhật trực tiếp Shipment/Order hoặc chèn event/outbox cho các tình huống lifecycle.

## Bằng chứng cần kiểm tra

- Carrier detail trả 503: đã có ít nhất hai lần đọc HTTP, Shipment vẫn PENDING, Order PROCESSING, chưa có thông báo cho đơn.
- Carrier trả `picked`: scheduler → adapter → Shipment PICKED_UP → outbox → Commerce SHIPPED → Notification. Ít nhất ba lần poll lặp không tạo thêm ShipmentPickedUp hoặc thông báo.
- Carrier trả `delivered`: Order và Shipment DELIVERED, hai thông báo IN_APP cho đơn. Buyer đánh dấu thông báo giao thành công là READ.
- Restart Fulfillment: đợi scheduler đọc shipment recovery còn active để chứng minh poller đã chạy lại. Shipment/Order vẫn DELIVERED, thông báo vẫn READ, đúng một ShipmentDelivered và một create carrier.

Job CI fixture đã có ở MR !177 chạy test HTTP fixture/syntax; test nhiều JVM vẫn chạy riêng. `report.json` phải có `result=PASS`, `cleanup=true`; checksum JAR và việc tái dùng artifact nằm trong report. Không kết luận pipeline xanh từ local.

## Dependency và giới hạn

Nhánh kế thừa MR !177 (dispatch/carrier recovery). Merge !177 trước MR lifecycle để diff riêng phần polling; target `dev`, không tự merge. Nhờ @fsa_KhangLV và @fsa_HungHTQ review luồng liên service.

Payment/inventory/handoff vẫn là đầu vào seed như runbook `TT_S3_DISPATCH_CARRIER_INTEGRATION.md`. Carrier là HTTP fixture, không phải sandbox; không có checkout/IPN/reservation, provider callback, refund thực hoặc UAT. Không thử luồng trả hàng bằng cách làm vận đơn đã DELIVERED quay lại trạng thái trước đó. Không thay đổi hoặc bật scheduler/worker trong cấu hình triển khai dùng chung.

## Kết quả local ngày 14/11/2026

23/23 test Node và 16/16 nhóm integration PASS, `cleanup=true`; syntax harness/module và diff check PASS. Lượt integration dùng `--skip-build`, tái sử dụng đúng ba JAR đã build từ backend dev `3410e0b` trong lượt !177; không có thay đổi Java/backend ở MR này. SHA-256 được ghi trong report và trùng runbook dispatch/carrier. Chưa xác minh pipeline GitLab hoặc coverage backend từ lượt test này.
