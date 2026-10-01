# Project Nexus

Nền tảng thương mại điện tử kết hợp đấu giá (xem SRS trong `docs/srs-nexus-ecommerce-auction-v1.docx`).
Repo dạng **monorepo**: frontend, backend (6 service + gateway) và hạ tầng nằm chung một repo.

```
nexus/
├── frontend-web/            React + TypeScript + Vite
├── backend/
│   ├── api-gateway/         Spring Cloud Gateway (điểm vào duy nhất)
│   ├── user-service/        Identity, Role, Reputation          (DB: nexus_user)
│   ├── catalog-service/     Product, Category, Search           (DB: nexus_catalog)
│   ├── commerce-service/    Cart, Checkout, Payment, Order      (DB: nexus_commerce)
│   ├── auction-service/     Auction, Bidding, Settlement        (DB: nexus_auction)
│   ├── fulfillment-service/ Inventory, Shipping                 (DB: nexus_fulfillment)
│   └── notification-service/ Event log, Notification            (DB: nexus_notification)
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

## Quy ước
- Git: [CONTRIBUTING.md](CONTRIBUTING.md) (Gitflow, Conventional Commits, Merge Request)
- Cài đặt GitLab: [docs/GITLAB_SETUP.md](docs/GITLAB_SETUP.md)
- Kiến trúc & nguyên tắc: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
