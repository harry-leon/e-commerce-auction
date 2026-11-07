# TT-S2-2 — Gateway, STOMP và service identity

## Hợp đồng kết nối

- Browser kết nối WebSocket thuần `/ws/auctions` qua nginx/Vite → Gateway → Auction. Không dùng SockJS, token query string hoặc truy cập service trực tiếp.
- Gateway route HTTP Auction giữ nguyên; route WS riêng dùng `AUCTION_WS_URL` (`ws://localhost:8084` khi chạy riêng, `lb:ws://auction-service` trong Compose).
- Cookie HttpOnly `nexus_access` được Gateway đổi thành Bearer và xác minh; Auction xác minh lại HTTP JWT. Browser gửi STOMP `CONNECT` không cần đọc cookie bằng JavaScript.
- Client có token riêng gửi native header `Authorization: Bearer <JWT>` trong `CONNECT`; token này được Auction xác minh bằng JWKS, issuer và thời hạn. Không dùng `login`, `passcode`, userId hay `X-User-*` để cấp quyền.
- Thiếu/sai/hết hạn JWT: từ chối CONNECT hoặc HTTP upgrade nếu cookie/bearer HTTP đã sai; STOMP lỗi gửi ERROR rồi đóng socket. Socket đã kết nối bị đóng mã 1008 khi JWT hết hạn, kiểm tra mỗi giây. Client refresh qua HTTP rồi tạo kết nối mới; không refresh trên socket cũ.
- `SUBSCRIBE /topic/auctions/{uuid}`: yêu cầu JWT hợp lệ và phiên tồn tại có visibility PUBLIC. Chưa hỗ trợ membership PRIVATE nên từ chối PRIVATE, wildcard và destination khác.
- `SEND /app/auctions/{uuid}/bids`: thêm yêu cầu privilege `AUCTION.BID` và không phải chủ phiên. Không được SEND trực tiếp `/topic/**` để giả event server.
- Đây là lớp transport/security. **Chưa có handler ghi bid**, chưa có event schema nghiệp vụ hay publisher sau transaction. Bid engine HG-S2-02 cần triển khai handler cùng validation, idempotency/concurrency, thời gian phiên và publish sau commit. SEND qua kiểm tra quyền hiện chưa tạo bid hay trả xác nhận thành công nghiệp vụ.
- Simple broker chỉ dùng một Auction instance; heartbeat 10 giây, payload tối đa 16 KiB, buffer gửi 64 KiB, chờ CONNECT tối đa 10 giây. Nhiều instance cần broker relay dùng chung.

## Origin và HTTP CORS

`WEB_ALLOWED_ORIGINS` là danh sách origin chính xác, ngăn bằng dấu phẩy, dùng chung Gateway/Auction. Mặc định localhost cổng 3000, 5173 và 8080. Production đặt origin HTTPS thực tế. Không dùng `*` với cookie. HTTP mutation dùng cookie vẫn yêu cầu `X-Nexus-Request: 1`; mở CORS không bỏ kiểm tra này. WebSocket kiểm tra Origin tại endpoint Auction.

Vite proxy `/ws/auctions` với `ws: true`; nginx proxy HTTP/1.1 với Upgrade/Connection. Cookie và Origin giữ nguyên. Khi phát triển bằng domain preview khác phải thêm domain đó vào allowlist cả hai service.

Nếu máy dùng `docker-compose.override.yml` đổi cổng web (ví dụ 3001), thêm `http://localhost:3001` vào `WEB_ALLOWED_ORIGINS` trong môi trường máy đó. Không thay đổi file override riêng của thành viên.

## Service account

File `deploy/keycloak/nexus-realm.json` khai báo clients confidential chỉ có client_credentials, tắt login/password grant và `fullScopeAllowed`. Service account role và client scope được giới hạn:

| Client (`azp`) | Privilege | API nhận |
|---|---|---|
| `nexus-auction-service` | `INVENTORY.AUCTION` | `/api/v1/inventory/auctions/**` |
| `nexus-auction-service` | `COMMERCE.AUCTION` | Namespace `/api/v1/internal/commerce/**` dành cho contract tương lai |
| `nexus-commerce-service` | `INVENTORY.ORDER` | Reserve/release đơn, confirm/release reservation, order inventory và events order |

Resource Server xác minh JWT rồi kiểm tra **đồng thời azp và privilege**. Token buyer/seller/admin hoặc token service khác bị chặn, kể cả có privilege nhưng sai client. Scheduler trong cùng service gọi nghiệp vụ trực tiếp, không cần giả JWT.

Auction cung cấp bean `auctionInventoryClient`, `auctionCommerceClient` và token cache chung `auctionServiceToken`. Các client gắn bearer, timeout 5 giây, xóa cache khi downstream 401 và không tự retry mutation. **Chưa có endpoint tạo order từ settlement trong Commerce**; namespace security không tự tạo endpoint. Contract và nghiệp vụ tạo order thuộc nhiệm vụ tích hợp settlement.

Commerce `HttpInventoryClient` luôn lấy service token cho mutation, kể cả caller cũ truyền JWT buyer. Checkout COD cũng phải cấu hình account kho; thiếu account trả 503, không fallback JWT buyer. Token chỉ cache RAM, refresh trước expiry, không lưu DB/log.

## Cấu hình và realm hiện có

1. Tạo `AUCTION_CLIENT_SECRET` và `COMMERCE_CLIENT_SECRET` khác nhau trong môi trường riêng; `.env.example` chỉ chứa tên biến. Compose truyền đúng secret cho Keycloak và service. Không dùng secret của `nexus-admin-cli`.
2. Realm mới: Keycloak import clients, service-account users, realm roles và `scopeMappings` từ JSON. Realm đã tồn tại **không tự cập nhật** bởi `start-dev --import-realm`. Dùng Keycloak Admin Console/API để tạo hai clients, gán đúng roles/service-account và client role scope như bảng; không xóa realm/database đang dùng để import lại.
3. Client service phải bật Service accounts, tắt Standard flow/Direct access grants; chọn client scope `roles` để JWT có `realm_access.roles`, tắt Full scope allowed và chỉ thêm roles trong bảng.
4. Kiểm tra token thực tế có `azp` và roles đúng, không có quyền admin/BUYER/SELLER. Secrets và token chỉ kiểm tra trong môi trường riêng, không đưa vào repository hay log.
5. Chạy riêng: Auction nhận `AUCTION_TOKEN_URL`, `AUCTION_CLIENT_ID` (mặc định `nexus-auction-service`), `AUCTION_CLIENT_SECRET`. Commerce nhận `PAYMENT_INVENTORY_TOKEN_URL`, `PAYMENT_INVENTORY_CLIENT_ID` (mặc định `nexus-commerce-service`), `PAYMENT_INVENTORY_CLIENT_SECRET` (fallback `COMMERCE_CLIENT_SECRET`). URL token mặc định theo `KEYCLOAK_URL` localhost 8180. Cấp đúng `SERVER_PORT` từng service khi chạy ngoài Compose.

Không thay đổi realm/secret trong hạ tầng đang chạy từ các test repository này.

## Kiểm thử

- Gateway: runtime Netty proxy upgrade/frame thật, xóa header giả và CORS allowlist; các test HTTP/filter hiện có vẫn bảo vệ auth cookie và IPN.
- Auction: runtime Tomcat/STOMP, JWT RS256 ký thật qua JWKS HTTP fixture, từ chối token sai chữ ký/issuer/expiry, Origin lạ, nhận broadcast, chặn SEND giả và đóng socket nhàn rỗi khi hết hạn. Repository Auction là mock; không cần DB/Keycloak ngoài.
- Common/Commerce: service identity đúng/sai azp/privilege, OAuth form/cache/invalidate và HTTP kho nhận service token thay JWT buyer.
- Fulfillment: controller test từ chối user token/sai service trước khi gọi nghiệp vụ kho, và giữ các tình huống reserve/extend/release/confirm thành công bằng service identity.

Ngoài các test từng service, [bằng chứng browser](test-evidence/tt-s2-2/README.md) chạy trực tiếp browser → Gateway → Auction bằng JWKS/repository fixture, kiểm tra cookie, bearer, CORS/Origin và frame thật. Chưa thay thế smoke test browser → nginx → Gateway → Auction với realm thực tế sau khi vận hành cấu hình service account. Không coi security test là bằng chứng bid engine/order settlement đã hoàn tất.

Tài liệu tham chiếu: [Spring STOMP token authentication](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html), [Spring Cloud Gateway routing](https://docs.spring.io/spring-cloud-gateway/docs/current/reference/html/).
