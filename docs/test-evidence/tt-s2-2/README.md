# Bằng chứng kiểm thử TT-S2-2

Ảnh được chụp bằng Playwright/Edge từ các request thực tế. Không ghép ảnh hoặc điền thủ công trạng thái PASS.

| Ảnh | Phạm vi |
|---|---|
| [01-cors-origin.png](01-cors-origin.png) | Browser thực thi CORS từ hai origin khác nhau; preflight 200/403, credential đúng origin và WebSocket bị chặn với Origin lạ |
| [02-websocket-stomp-auth.png](02-websocket-stomp-auth.png) | Browser → Gateway Netty → Auction Tomcat/STOMP: bearer/cookie, token sai/hết hạn, PUBLIC broadcast, chặn SEND/PRIVATE và đóng socket nhàn rỗi |
| [03-service-security.png](03-service-security.png) | Kết quả JUnit của identity azp + privilege, HTTP client Commerce và quyền API Inventory; đây là bằng chứng tự động ở tầng service, không phải browser gọi realm thật |
| [04-backend-test-summary.png](04-backend-test-summary.png) | Tổng hợp JUnit XML của năm module, thể hiện cả số test pass và skip |

[browser-results.json](browser-results.json) chứa kết quả assertion, thời điểm UTC và timezone Asia/Ho_Chi_Minh. Không chứa bearer, secret hoặc private key. Ngày giờ trên ảnh hiển thị theo Asia/Ho_Chi_Minh.

[validation-summary.json](validation-summary.json) được script sinh từ JUnit XML, không sửa thủ công. Checkstyle đã chạy ở Auction/Commerce/Fulfillment; TypeScript và Prettier riêng `vite.config.ts` đạt. `npm run format:check` toàn frontend vẫn báo 33 file có sẵn ngoài phạm vi sửa; không tự format lại các file này. Không khẳng định pipeline remote đã chạy khi chưa có kết quả CI.

## Ranh giới kiểm thử

- Gateway, Auction transport/security, native browser WebSocket và CORS chạy thật trên cổng loopback riêng.
- IAM là public JWKS và JWT RS256 sinh trong RAM, AuctionRepository là mock có hai phiên PUBLIC/PRIVATE. Không dùng `.env`, DB hay tài khoản Keycloak của nhóm.
- Broadcast có type `SECURITY_TEST_FIXTURE`; đây không phải event của bid engine. Chưa chứng minh ghi bid DB, settlement tạo order, IAM production hoặc nginx production.
- Script dừng JVM/browser/HTTP fixture sau khi chạy. Log JVM và Java argfile nằm trong `backend/*/target`, không commit.

## Chạy lại

Chạy từ root bằng Java 21, Maven và Node; Playwright/Edge phải có sẵn qua dependencies của `frontend-web`:

```powershell
mvn -q -f backend/common/pom.xml install
mvn -q -f backend/api-gateway/pom.xml package
mvn -q -f backend/auction-service/pom.xml verify checkstyle:check dependency:build-classpath '-Dmdep.outputFile=target/evidence-classpath.txt' '-Dmdep.includeScope=test'
mvn -q -f backend/commerce-service/pom.xml verify checkstyle:check
mvn -q -f backend/fulfillment-service/pom.xml verify checkstyle:check
node scripts/smoke-tt-s2-2-browser.mjs
```

Script dùng các cổng `18460`, `18461`, `18462`, `18464` và thất bại nếu cổng đã có tiến trình khác; không dừng hay chiếm cổng của hệ thống đang chạy. `PLAYWRIGHT_CHANNEL` có thể thay `msedge` nếu máy dùng browser khác đã cài.

Nếu không có `NEXUS_TEST_DB_URL`, các test PostgreSQL tùy chọn của Commerce được skip theo cấu hình hiện có. Test service security được đưa vào ảnh phải có `skipped=0`; script từ chối báo đạt nếu JUnit có lỗi/skip.

## Nhánh và review

Nhánh tích hợp thực tế trên remote là `dev`; repository không có `develop` tại thời điểm thực hiện. Nhánh feature được tạo từ `origin/dev`, không đẩy vào `dev`/`main` và không tự merge.

Thay đổi được chia commit theo Common → Gateway → Auction → Commerce → Fulfillment → Web proxy → Deploy/tài liệu/bằng chứng. Khi mở MR, theo CONTRIBUTING.md cần tách review theo thành phần và ghi thứ tự phụ thuộc; riêng Auction/payment cần đủ hai approval trước merge.
