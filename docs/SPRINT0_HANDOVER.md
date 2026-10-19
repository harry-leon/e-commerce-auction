# Sprint 0 implementation and acceptance

This document describes implemented behavior and remaining review evidence. It does not record stakeholder approval or completion of training/Figma work.

## Local environment

1. Copy `.env.example` to `.env` if `.env` does not already exist. Keep existing credentials; configure the shared JWT secret (at least 32 characters).
2. Run `docker compose up -d --build` from the repository root. PostgreSQL persists in the existing `pgdata` volume; do not remove it to fix migrations.
3. Open the web app at http://localhost:3000. The browser calls `/api/v1` through nginx and the API gateway.
4. Eureka: http://localhost:8761. Config Server shared configuration: http://localhost:8888/user-service/default.
5. Local password reset emails are delivered over SMTP to Mailpit: http://localhost:8025. They are not delivered to external inboxes. Configure `SMTP_HOST`, `SMTP_PORT`, authentication and STARTTLS for an external mail provider.

Compose uses `LOCAL_DB_USERNAME` / `LOCAL_DB_PASSWORD` (default `nexus` / `nexus`) for its own PostgreSQL and service connections. This prevents remote `DB_USERNAME` / `DB_PASSWORD` in an existing `.env` from being combined with a local database URL. For an existing local volume, set the local variables to that volume's original credentials; changing container environment variables does not change existing database roles/passwords.

Each service registers with Eureka in Compose; Gateway uses `lb://...` routes. Individual services can still run without discovery locally (`EUREKA_ENABLED=false` by default). Shared configuration resides in `backend/config-server/src/main/resources/configuration/application.yml`; service-specific configuration and secrets remain in service config/environment. Config import is optional for standalone development.

Frontend local development: `npm ci`, `npm run dev` inside `frontend-web`; Vite proxies `/api` to `http://localhost:8080`. Set `API_PROXY_TARGET` for another gateway. URL routes support direct opening and browser back/forward. Auth session state uses Zustand; “remember me” chooses localStorage instead of sessionStorage. The demo role-switch toolbar is removed from the application.

## API v1 contract for review

User service responses use the existing user DTOs. Catalog uses `{success,data,error,meta}`; list pagination metadata is `{page,pageSize,total}` and pages are zero-based.

| Endpoint | Behavior / authorization |
| --- | --- |
| `POST /api/v1/auth/register` | Register buyer; validated profile fields; returns JWT session |
| `POST /api/v1/auth/login` | Authenticate; existing server-side failed-attempt policy |
| `POST /api/v1/auth/refresh` | Verify signature, issuer, expiry, refresh token type and token version |
| `POST /api/v1/auth/forgot-password` | Generate expiring token and send SMTP reset link |
| `POST /api/v1/auth/reset-password` | Consume token and set new password |
| `POST /api/v1/auth/change-password` | Authenticated; `currentPassword`, `newPassword` |
| `GET/PUT /api/v1/users/me` | View/update own profile with privileges |
| `GET /api/v1/users/me/activity-history` | Paginated account activity |
| `/api/v1/roles`, `/api/v1/users/{id}/roles` | Existing role CRUD, privilege assignment and user role assignment |
| `GET /api/v1/categories` | Public category tree with direct product counts |
| `GET /api/v1/categories/{id}` | Public category detail |
| `POST/PUT/DELETE /api/v1/categories[/{id}]` | Requires `CATEGORY.CREATE/UPDATE/DELETE` |
| `GET /api/v1/products` | Public ACTIVE products; `q`, `categoryId`, `minPrice`, `maxPrice`, `auctionEnabled`, `page`, `size`, `sort` |
| `GET /api/v1/products/{id}` | Public ACTIVE product detail; other statuses require ownership/admin |
| Product/SKU/image write endpoints | Existing CRUD with privileges and ownership checks |

Category writes use `{name,nameVi,parentId,status}`. `parentId:null` means root; status is `ACTIVE` or `INACTIVE`. Maximum depth is three, including an entire moved subtree. Self-parenting/descendant cycles are rejected. Delete children first; categories containing any products cannot be deleted. Name translation is preserved in a new Flyway migration.

Product keyword search is case insensitive over name/description; `%` and `_` are literal. Category filters include descendants (up to the supported three levels). A price range must be nonnegative and ordered; matching requires an active SKU inside that range. Products are deduplicated before pagination. Non-ACTIVE status searches require `PRODUCT.LIST`.

Swagger remains available per service on `/swagger-ui.html`; OpenAPI JSON on `/v3/api-docs`. This document and annotations are review inputs, **not evidence of API approval**.

## Verification

Frontend: `npm run lint`, `npm run typecheck`, `npm run coverage`, `npm run build`. Coverage is real V8 coverage of API/session/URL utilities; it is not whole-application coverage. `npm run test:e2e` runs browser integration tests against intercepted API responses (Edge by default, or set `PLAYWRIGHT_CHANNEL`). Browser tests verify credential requests, profile saving, reset token submission, product URLs/back navigation and visible API failures.

Backend: run `mvn clean verify checkstyle:check` in each existing service. Catalog adds category business-rule tests and H2 PostgreSQL-mode search integration tests. H2 tests do not replace the PostgreSQL runtime check. IAM adds SMTP-message and forged/expired-token regression tests. New infrastructure services can be built with `mvn clean verify`.

After Compose is healthy, set `NEXUS_SMOKE_BOOTSTRAP_ADMIN=true` and run `node scripts/smoke-sprint0.mjs` from the repo root. This is a localhost-only verification helper: it registers a fresh fixture account, grants ADMIN to that account directly in local PostgreSQL, exercises category/product/profile/reset/refresh APIs through nginx and Gateway, and deletes its product/category fixtures and soft-deletes its account. It does not grant roles to existing accounts. Mailpit keeps the test reset email until cleared manually.

## Ownership and outstanding evidence

| Owner from Sprint 0 plan | Deliverable / review |
| --- | --- |
| Tiến Thương | IAM, SMTP, Gateway/discovery/config; approve User/Catalog API contract; review CI |
| Quốc Hùng | Product/SKU CRUD, category CRUD, search/filter, schema and ERD accuracy |
| Vinh Khang | Profile/activity history, DTO/validation; provide Figma component evidence |
| Quốc Hào | Auth/profile/catalog UI integration; review route/session behavior and original visual requirements |
| Linh Đang | Product detail/category admin integration; review business requirements and Sprint 1 story acceptance criteria |

The API-backed Sprint 0 screens are implemented in `frontend-web/src/pages/SprintPages.tsx`. Existing demonstration screens for later sprints (cart, checkout, orders, auctions, dashboards and other admin modules) remain prototypes. Their presence is not evidence of implemented backend features.

The source includes an SRS and database specifications. The file named `srs-nexus-ecommerce-auction-v1.docx` currently contains plain text/Markdown rather than a native Word archive; it should be converted if a Word deliverable is required. Physical ERD diagrams derived from migrations are in `docs/database/ERD.md`.

Still requiring team evidence: Figma links/screens and component library, training completion, signed/reviewed BRD/SRS, business review of ERD, formal API approval, and acceptance of Sprint 1 stories. Do not mark these approved solely because draft files exist.
