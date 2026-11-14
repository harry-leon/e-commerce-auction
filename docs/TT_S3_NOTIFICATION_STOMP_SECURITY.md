# TT-S3-05/08 — Kiểm chứng transport push và reconnect

Phần độc lập của Tiến Thương: kiểm tra quyền/recovery và hỗ trợ test notification transport/push/broker health. Nhánh tách từ `dev`, không phụ thuộc receipt !165–!166 hoặc bộ Shipping !156–!164. Chỉ bổ sung test và tài liệu, không đổi API, schema hoặc cấu hình triển khai.

## Phạm vi chạy thật

`NotificationStompSecurityIntegrationTest` khởi động HTTP server/WebSocket/STOMP/simple broker thật. Dùng SecurityConfig, NotificationStompInterceptor, ExpiringNotificationWebSocketSessions, NotificationTransportHealth và JwtDecoder production. RSA private key sinh riêng cho lần chạy; JWKS fixture chỉ công bố public key trên loopback. Token ký RSA được decoder tải JWKS và kiểm tra chữ ký/issuer/expiry thật, không mock JwtDecoder.

Spring context chỉ import transport/security, không quét service nghiệp vụ. Cổng HTTP/JWKS đều loopback ngẫu nhiên; `spring.config.name` riêng, Config Server/Eureka tắt, không dùng `.env`, DB nhóm hoặc gửi email/provider. Socket/context/HttpClient/JWKS server được dọn sau test kể cả assertion thất bại. Mọi chờ trạng thái/frame có deadline; chờ registry và subscription broker thật trước broadcast, không retry tin để che race subscribe.

## Các ca bảo vệ

| Nhóm | Kiểm tra |
| --- | --- |
| CONNECT và Origin | Thiếu bearer, token sai định dạng/chữ ký/issuer, hết hạn hoặc thiếu exp không nhận CONNECTED; Origin khác bị HTTP 403 khi upgrade |
| Quyền và chống giả tin | Hai JWT có kênh nhận riêng; subscribe topic người khác và client SEND bị ERROR/đóng; kênh hợp lệ vẫn nhận sentinel sau lỗi |
| Reconnect | JWT mới cùng user subscribe lại và nhận push mới; tin phát khi offline không tự replay từ simple broker |
| Broker health | Gọi stop/start trên SimpleBrokerMessageHandler thật: UP → OUT_OF_SERVICE → UP; kết nối sau restart vẫn nhận tin |

Ca expiry socket im lặng đã có ở NotificationWebSocketExpiryTest, giữ nguyên. Các test nghiệp vụ hiện có tiếp tục kiểm tra inbox PostgreSQL, replay/dedup/READ và push sau commit; test mới không thay thế chúng bằng dữ liệu giả rồi gọi là toàn luồng.

## Chạy và giới hạn

Chạy riêng: `mvn -f backend/notification-service/pom.xml -Dtest=NotificationStompSecurityIntegrationTest test checkstyle:check`. Chạy hồi quy: `mvn -f backend/notification-service/pom.xml clean verify checkstyle:check`, đặt `NEXUS_TEST_DB_URL` chỉ tới PostgreSQL tạm để các ca DB không skip. Không dùng database triển khai trong biến test.

JWKS/issuer là fixture HTTP cục bộ, **không phải Keycloak triển khai**. Test kết nối trực tiếp Notification, chưa chứng minh Gateway/HTTPS ingress, browser UI, refresh token/reconnect của frontend hoặc resync inbox sau offline. Reconnect ở đây là client test đóng socket rồi kết nối lại; không tự thêm cơ chế reconnect vào frontend. Broker restart là lifecycle trong process, chưa phải restart JVM hoặc failover nhiều instance. Không kiểm tra broker ngoài/Kafka/Redis; health UP không chứng minh browser đã nhận mọi tin. Simple broker vẫn không bền vững: frontend cần đọc inbox REST để bù tin khi offline.

Kiểm chứng local 13/11/2026: Notification `clean verify checkstyle:check` exit 0; 65 test, 0 fail/error/skip, gồm 4 ca socket mới. PostgreSQL 18 tạm chạy các ca DB hiện có, đã dừng sau kiểm thử. JaCoCo LINE 730/820 (89,02%). Chưa xác nhận pipeline GitLab hoặc nghiệm thu triển khai.
