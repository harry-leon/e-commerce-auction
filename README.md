# Project Nexus

Nền tảng thương mại điện tử kết hợp đấu giá (xem SRS trong `docs/srs-nexus-ecommerce-auction-v1.docx`).
Repo dạng **monorepo**: frontend, backend (7 service nghiệp vụ + gateway + discovery/config) và hạ tầng nằm chung một repo.

```
nexus/
├── frontend-web/            React + TypeScript + Vite
├── backend/
│   ├── api-gateway/         Spring Cloud Gateway (điểm vào duy nhất)
│   ├── user-service/        User Profile (IAM: Keycloak)          (DB: nexus_user)
│   ├── catalog-service/     Product, Category, Search           (DB: nexus_catalog)
│   ├── commerce-service/    Cart, Checkout, Payment, Order      (DB: nexus_commerce)
│   ├── auction-service/     Auction, Bidding, Settlement        (DB: nexus_auction)
│   ├── fulfillment-service/ Inventory, Shipping                 (DB: nexus_fulfillment)
│   ├── notification-service/ Event log, Notification            (DB: nexus_notification)
│   ├── reputation-service/   Rating, Penalty, Trust Score        (DB: nexus_reputation)
│   ├── discovery-server/     Eureka registry
│   ├── config-server/        Centralized configuration
│   └── common/               Shared response/security library
├── deploy/                  init DB (docker), Kubernetes manifests
├── docs/                    SRS, kiến trúc, hướng dẫn GitLab
├── scripts/                 script cấu hình GitLab
├── .ci/ + .gitlab-ci.yml    CI/CD
└── docker-compose.yml       chạy toàn bộ hệ thống
```

> Database: **không có thư mục Database chung**. Mỗi service sở hữu một DB riêng (SRS 4.1),
> schema nằm trong `backend/<service>/src/main/resources/db/migration` (Flyway).
> Các DB được tạo trong cùng một container PostgreSQL khi chạy local (`deploy/postgres/init-databases.sh`).

## Chạy toàn bộ hệ thống

Cần Docker. Lần đầu, tạo `package-lock.json` cho FE:

```bash
(cd frontend-web && npm install)      # rồi commit frontend-web/package-lock.json
cp .env.example .env
docker compose up --build
```

- Web: http://localhost:3000 · Gateway: http://localhost:8080 · Swagger từng service (dev): http://localhost:8081/swagger-ui.html ... 8086
- Thêm Redis + Kafka: `docker compose --profile full up --build`

Dev hằng ngày thường chỉ chạy phần mình làm: `npm run dev` trong `frontend-web/`, hoặc `mvn spring-boot:run` trong một service.

## Mở backend trong IntelliJ IDEA

`backend/pom.xml` tổng hợp 11 module Maven (10 ứng dụng và thư viện `common`).
Các service giữ Spring Boot parent riêng, có thể chạy/debug độc lập sau khi import.

1. Mở thư mục `nexus` trong IntelliJ.
2. Chuột phải `backend/pom.xml` → **Add as Maven Project** (hoặc **Link Maven Project**).
3. Chọn JDK 21 cho Project SDK và Maven importer/runner; đợi Maven đồng bộ dependencies.

Nếu không thấy Maven hay lệnh import: mở **Settings → Plugins → Installed**,
kiểm tra plugin **Maven** đã bật, rồi khởi động lại IDE nếu vừa bật plugin.
Bạn cũng có thể dùng **File → Open**, chọn `backend/pom.xml` và mở như project
để import toàn bộ backend; cách này mở backend thay vì toàn bộ monorepo.
POM tổng không tự cài hoặc bật plugin Maven trong IDE.

Build từ thư mục gốc repo bằng Maven đã cài trên máy (hoặc Maven tích hợp trong IntelliJ):

```bash
mvn -f backend/pom.xml clean verify
mvn -f backend/pom.xml -pl auction-service -am clean package -DskipTests
```

Lệnh thứ hai build `auction-service` cùng các dependency nội bộ cần thiết;
`-DskipTests` bỏ qua chạy test. Build riêng một service ngoài Reactor vẫn cần
cài dependency nội bộ vào Maven local repository trước, ví dụ `mvn -f backend/common/pom.xml install`.

## Quy ước
- Git: [CONTRIBUTING.md](CONTRIBUTING.md) (Gitflow, Conventional Commits, Merge Request)
- Cài đặt GitLab: [docs/GITLAB_SETUP.md](docs/GITLAB_SETUP.md)
- Kiến trúc & nguyên tắc: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- DTO contract FE/BE theo từng service: [docs/dto-contracts/README.md](docs/dto-contracts/README.md)
