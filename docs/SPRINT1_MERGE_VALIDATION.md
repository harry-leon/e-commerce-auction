# Kiểm chứng bản gộp Sprint 1 vào main

Ngày chạy: 03/11/2026, múi giờ Asia/Saigon. Nhánh: `integration/sprint1-to-main`, MR #63.

## Các lỗi đã sửa

- `.gitattributes`: cố định LF cho văn bản trong `frontend-web` theo Prettier, tránh 41 lỗi xuống dòng khi checkout trên Windows có `core.autocrlf=true`. Asset nhị phân được giữ nguyên.
- User Service: bỏ import `Page` không dùng; bổ sung comment về JWT, validation và `ApiResponse` tại controller.
- Common: bỏ import thừa, thêm dấu ngoặc cho nhánh validation; giữ nguyên hành vi và bổ sung comment tại các nhánh đã sửa.
- Playwright Sprint 0: thay kịch bản reset token đã nghỉ bằng yêu cầu email Keycloak; danh mục chỉ gửi `name`; fixture catalog chặn cả URL có/không có query.
- Playwright Sprint 1: thêm kiểm chứng callback không tin query thành công, kết quả FAILED, lỗi API thanh toán, lỗi ghi READ và thử lại hộp thư. Test và fixture có comment mô tả nghiệp vụ, phạm vi mock.
- Smoke ERD: xác thực rồi mở `ApiResponse.data` cho Nexus, giữ JSON gốc của Keycloak; lịch sử hoạt động dùng mảng theo controller hiện tại.

## Kết quả kiểm tra

| Bộ kiểm tra backend | Test | Fail/Error/Skip |
| --- | ---: | --- |
| Common | 14 | 0/0/0 |
| Commerce | 307 | 0/0/0 |
| Notification | 45 | 0/0/0 |
| Catalog | 129 | 0/0/0 |
| Fulfillment | 49 | 0/0/0 |
| User | 53 | 0/0/0 |
| API Gateway | 13 | 0/0/0 |
| Auction | 7 | 0/0/0 |
| **Tổng** | **617** | **0/0/0** |

- Maven `clean verify` cho toàn bộ 10 module service/hạ tầng; Common `clean install`. Reputation, Config Server và Discovery Server build đạt nhưng chưa có test tự động, không tính vào 617 test.
- Các ngưỡng JaCoCo được cấu hình trong POM đều đạt. Commerce, Notification và Catalog chạy test PostgreSQL/Flyway thật với `NEXUS_TEST_DB_URL`, không bỏ qua test DB.
- Checkstyle: đạt cho các module có cấu hình riêng. Common, Reputation, Config Server và Discovery Server được kiểm tra bằng rules của `backend/commerce-service/config/checkstyle.xml`; chúng chưa khai báo rules riêng trong POM nên gọi plugin mặc định sẽ chọn bộ Sun khác quy ước dự án.
- Frontend: `npm run coverage` đạt **206 test**, line coverage **73,55%**, vượt ngưỡng 70%; `npm run test:e2e` đạt **9 test** trên Edge headless.
- `npm run format:check`, `npm run lint`, `npm run build`: đạt. Vite vẫn có cảnh báo bundle lớn hơn 500 kB; chưa thay cấu trúc tải trang trong lần sửa này.
- `node --test scripts/generate-erd.test.mjs`: **3 test đạt**; `node --check scripts/smoke-erd-v2.mjs` và `git diff --check`: đạt.
- `node scripts/smoke-order-notifications.mjs`: đạt. Gateway, Commerce, Notification và PostgreSQL thật; bảo vệ checkout/IPN thành công và thất bại, idempotency, mất ACK, retry, chống trùng, phân quyền hộp thư và worker tồn kho.
- `node scripts/smoke-erd-v2.mjs`: đạt. PostgreSQL và Keycloak thật; kiểm chứng nâng cấp V4→V5, Flyway/Hibernate, đăng ký, đăng nhập, refresh, cập nhật hồ sơ, chống giả header/nâng quyền, Role CRUD, giá giỏ hàng, đổi mật khẩu, endpoint reset cũ HTTP 410 và audit tại Notification.

## Môi trường và giới hạn

- Java 21, Maven 3.9.16, PostgreSQL 16 trong container tạm chỉ bind loopback; mỗi module test có database riêng. Không dùng dữ liệu của nhóm.
- Catalog được build trong bản sao thư mục tạm ngoài workspace IDE. Lần build tại workspace bị class `ProductMapperImpl` do compiler IDE ghi đè với lỗi `Unresolved compilation problems`; bản sao sạch chạy đủ 129 test và đóng gói thành công, không sửa code sinh tự động.
- Smoke tự tạo và dọn container/process của nó. Test trình duyệt dùng API fixture; smoke notification dùng fixture Catalog/Fulfillment/OAuth/VNPAY. Chưa thực hiện giao dịch với cổng VNPAY sandbox bên ngoài hoặc gửi email qua SMTP bên ngoài.
- `docs/SPRINT1_DEMO_GUIDE.md` là file chưa được theo dõi có sẵn trước tác vụ, không đưa vào commit này.

## Chạy lại

```powershell
# Cài Common trước khi verify từng module backend; chạy PostgreSQL tạm và tạo DB riêng từng module.
mvn -f backend/common/pom.xml clean install
$env:NEXUS_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:<port>/<database_rieng>'
mvn -f backend/commerce-service/pom.xml clean verify checkstyle:check
# Lặp verify cho notification/catalog/fulfillment/user/gateway/auction và các module hạ tầng.

# Module chưa khai báo Checkstyle riêng: dùng cùng rules hiện có của dự án.
mvn -f backend/common/pom.xml org.apache.maven.plugins:maven-checkstyle-plugin:3.5.0:check "-Dcheckstyle.config.location=<duong_dan_tuyet_doi>/backend/commerce-service/config/checkstyle.xml"

# Từ frontend-web:
npm run format:check
npm run lint
npm run coverage
npm run build
npm run test:e2e

# Từ repository root, sau khi có các JAR và Docker đang chạy:
node --test scripts/generate-erd.test.mjs
node scripts/smoke-order-notifications.mjs
node scripts/smoke-erd-v2.mjs
```
