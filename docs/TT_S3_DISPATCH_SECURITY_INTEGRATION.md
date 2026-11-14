# Dispatch: kiểm tra JWT và cờ tính năng trên JVM thật

Mở rộng `scripts/check-shipping-flow.mjs` bằng `--dispatch-security`. Mặc định vẫn chạy bộ transport cũ; tùy chọn mới chạy thêm hai nhóm kiểm tra sau các tình huống ACK/replay/restart.

## Cách chạy

Yêu cầu Java 21, Maven, PostgreSQL binary và Docker daemon hoạt động. Image `quay.io/keycloak/keycloak:24.0.5` phải có sẵn. Chạy tại checkout chứa các API claim/preflight:

```powershell
node scripts/check-shipping-flow.mjs --pg-bin 'C:/Program Files/PostgreSQL/18/bin' --maven '<đường dẫn mvn.cmd>' --dispatch-security
node --test scripts/shipping-flow/fixtures.test.mjs
```

Harness build ba JAR, tạo PostgreSQL/realm Keycloak riêng và dùng JWT được ký thật. Không đọc `.env`; finally dừng đúng tài nguyên do lượt test tạo. Đường dẫn artifact được in cuối lượt; `report.json` phải có `result=PASS` và `cleanup=true`. Không dùng `--skip-build` khi cần chứng minh source hiện tại.

## Phạm vi đã kiểm tra

- Handoff GET, claim POST và preflight POST: không token nhận 401; Buyer, Admin và service sai nhận 403. Service sai có cả DISPATCH/READ để kiểm tra identity độc lập với privilege.
- Service Fulfillment đúng nhận 503 khi dispatch tắt mặc định.
- Restart JVM Commerce sau khi bật cờ: service đúng nhận 404 cho handoff/claim chưa tồn tại, service sai vẫn nhận 403. Không tạo Payment hoặc dispatch claim qua các request này.
- Giữ nguyên các kiểm tra transport: mất ACK, replay có audit, quyền xem notification, restart JVM và health Admin.

## Giới hạn và thứ tự tích hợp

Lượt local ngày 14/11/2026: 9/9 nhóm integration PASS, `cleanup=true`; 22/22 test Node PASS, syntax/diff check PASS. Ba JAR được build mới tại backend commit `ed00e008e977e496b850cbddd56089cb664f8adb` (không tái dùng artifact). SHA-256 Commerce `670f4173e4de92c5c0c1910e8b0a682a4edb4cdbd2ad942ba8f85e128963185a`; Fulfillment `c3e1b0b43d8df34a48e0b5b6bc3eb6d10d95df72d480d7e6dc867201a57b1e6f`; Notification `a2b050c191ae5af5f81334644e6dbdf37df8e36a1e265cac1238b76d7245e9e8`. Maven package ở harness bỏ qua JUnit; lượt này không xác minh coverage backend hoặc pipeline GitLab.

Không kiểm tra happy path claim/preflight có dữ liệu, worker gọi carrier, crash giữa carrier ACK và commit, hoặc API reconcile job. Không chứng minh checkout, xác nhận tồn kho, credentials sandbox, đối soát nhà vận chuyển hoặc UAT đã hoàn thành. Worker dispatch không được bật bởi tùy chọn này; không có HTTP tới provider.

Nhánh test hiện kế thừa MR !173 (preflight), !174 (worker) và !175 (reconcile). Merge các dependency theo thứ tự đó trước khi merge MR test để diff chỉ còn harness/tài liệu. Nhờ @fsa_KhangLV và @fsa_HungHTQ review quyền service; không tự merge.
