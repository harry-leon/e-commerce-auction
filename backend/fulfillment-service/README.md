# fulfillment-service

Service **fulfillment** của Project Nexus.

- Package: `com.nexus.fulfillment` · DB riêng: `nexus_fulfillment` (Flyway: `src/main/resources/db/migration`)
- Chạy cả hệ thống từ thư mục gốc: `docker compose up --build`
- Chạy riêng: `mvn spring-boot:run` (cần PostgreSQL local, xem biến môi trường trong `application.yml`)
- Test: `mvn verify` (coverage >= 70%), style: `mvn checkstyle:check`
- Swagger (dev): http://localhost:8080/swagger-ui.html

Phạm vi nghiệp vụ: xem SRS (`docs/`). Cấu trúc code: xem `docs/ARCHITECTURE.md` ở gốc repo.
