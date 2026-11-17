# Dispatch: tạo vận đơn, mất ACK và phục hồi trên JVM thật

## Mục đích và cách chạy

`scripts/check-shipping-flow.mjs --dispatch` mở rộng bộ transport/security đã có. Ba JVM Commerce, Fulfillment, Notification dùng PostgreSQL và Keycloak riêng; adapter GHN thật gọi một HTTP fixture trên loopback bằng token sinh tạm không rỗng. Không dùng fallback mô phỏng trong adapter và không gọi provider bên ngoài.

```powershell
node --test scripts/shipping-flow/fixtures.test.mjs scripts/shipping-flow/dispatch.test.mjs
node scripts/check-shipping-flow.mjs --pg-bin 'C:/Program Files/PostgreSQL/18/bin' --maven '<đường dẫn mvn.cmd>' --dispatch
```

Yêu cầu Java 21, Maven, PostgreSQL binary và Docker với image `quay.io/keycloak/keycloak:24.0.5` có sẵn. Không đọc `.env`. Mặc định build mới JAR; `--skip-build` chỉ dành cho chẩn đoán, report ghi rõ artifact có tái dùng hay không.

## Các tình huống

1. Admin đăng ký kiện qua API; Buyer bị chặn. Worker GET handoff, POST claim/preflight bằng client credentials thật. Proxy làm mất ACK sau khi Commerce commit claim; retry dùng cùng job UUID và chỉ tạo một claim, một vận đơn carrier. Kiểm tra cân nặng/người nhận/COD từ parcel và snapshot.
2. Tạo refund trước khi đăng ký kiện: worker đưa job sang REVIEW, không claim và không gọi carrier. Ngược lại, refund mới sau claim bị chặn.
3. Carrier fixture nhận create rồi trả 503: guard UNKNOWN và job REVIEW; restart không được gửi create lần hai.
4. Carrier nhận create nhưng giữ response: dừng JVM Fulfillment khi job DISPATCHING. Chỉ làm lease test hết hạn, JVM mới tự chuyển REVIEW và không gửi lại.
5. Admin dùng tracking do fixture đã nhận để manual-resolution, sau đó reconcile job. Buyer bị chặn; replay trả audit cũ, thay nội dung cùng key trả 409. Một shipment, một ShipmentCreated outbox và không có create carrier bổ sung; claim vẫn chặn refund.

Các kiểm tra transport/security cũ vẫn chạy trước dispatch. Polling carrier tắt để tách việc tạo/khôi phục shipment khỏi cập nhật trạng thái giao hàng.

Job CI `shipping-flow:fixtures` chạy test Node và syntax khi scripts/CI thay đổi. Job này không chạy integration ba JVM; không diễn giải pipeline xanh thành bằng chứng carrier/restart đã được thử trong CI.

## Fixture và giới hạn bằng chứng

SQL seed Order PROCESSING, Payment SUCCESS/inventory CONFIRMED và handoff WAITING_PARCEL. Đây là đầu vào giả định **đã xác nhận tiền/kho**, không phải bằng chứng checkout/IPN/reservation thực đã chạy. Merchant `FIXTURE_E2E` và provider reference chỉ tồn tại trong DB test cô lập. Refund worker/provider tắt, không có hoàn tiền thực.

Sau crash, harness chỉ cập nhật `lease_expires_at` về quá khứ để không phải chờ 120 giây; không sửa state, guard, shipment hoặc audit để ép kết quả. Manual resolution dựa trên bằng chứng giả lập từ HTTP fixture, không phải đối soát carrier sandbox.

`report.json` lưu các nhóm PASS và SHA-256 JAR; chỉ kết luận thành công khi `result=PASS`, `cleanup=true`. Cuối lượt dừng các JVM, container IAM và PG cluster do lượt đó tạo, giải phóng response bị giữ. Không dừng tài nguyên khác. Local Maven package bỏ qua JUnit; không suy ra pipeline GitLab xanh hoặc coverage backend từ kết quả này.

Phần còn cần nghiệm thu riêng: credentials/callback provider, sandbox carrier và payment/refund, polling giao hàng với provider, UAT cùng nhóm. Không bật worker ở môi trường dùng chung chỉ vì lượt fixture local pass.

## Kết quả local ngày 14/11/2026

23/23 test Node PASS; syntax harness/module, diff check và parse YAML CI PASS. Hai lượt integration đều 13/13 nhóm PASS, `cleanup=true`. Lượt đầu build mới cả ba JAR từ backend `3410e0b93ecbdce1ca371d8eb1dcf0b6700314f4`; lượt cuối dùng `--skip-build` để kiểm tra thêm quyền metadata và chặn reconcile guard UNKNOWN bằng cùng JAR, không có thay đổi backend giữa hai lượt.

SHA-256 JAR: Commerce `a128919e71e946fd13fbc906a504096378885636d7c2fac2489e92faca02564e`; Fulfillment `9fda6b19508bc5a31b17e9e5f171defda0d98c94b0a653109faf8623bcc536c7`; Notification `f0c793a1f58c416e8b4cf226a63b726f80fb7ac43523d72933376337a6b5458f`. Chưa xác minh pipeline GitLab hoặc coverage backend qua lượt này.
