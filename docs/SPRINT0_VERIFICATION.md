# Sprint 0 verification — 2026-10-19

Historical verification for commit `9c11f2d`, before the convention compliance fixes. See [COMPLIANCE_VERIFICATION.md](COMPLIANCE_VERIFICATION.md) for the subsequent checks, expanded frontend coverage and CI changes. These results describe engineering checks; they are not stakeholder acceptance.

| Check | Result |
| --- | --- |
| Frontend ESLint | Passed |
| Frontend TypeScript (`tsc --noEmit`) | Passed; build now includes this check |
| Frontend Vite production build | Passed |
| Vitest | 9 tests passed |
| Playwright / Edge | 5 browser tests passed |
| User service | 70 tests passed; Maven verify and Checkstyle passed |
| Catalog service | 121 tests passed; Maven verify and Checkstyle passed |
| Commerce, auction, fulfillment, notification | 5 tests per service passed; Maven verify and Checkstyle passed |
| Gateway, Eureka server, Config Server | Maven verify passed; Docker images built |
| Docker Compose configuration | Valid |
| Complete Compose build | Passed after using a shared locked Maven cache for service builds |
| Runtime services | All service/infrastructure containers healthy; nginx web container running |
| Real PostgreSQL / SMTP smoke test | Passed through web nginx → Gateway → Eureka-resolved services |

Backend total: **211 tests**, no failures or errors. The scaffold tests in later-sprint services verify their existing infrastructure, not completed commerce/auction functionality.

Browser tests intercept API responses and verify product loading/detail/direct URLs/back navigation, login/session/profile writes, email reset token submission, displayed backend failure and admin category create/update/delete. They are separate from the live backend smoke test.

The live smoke test verified:

- Registration and denial of category writes for a buyer.
- Category parent/child persistence, maximum depth and cycle rejection.
- Product/SKU persistence, moderation, public keyword/category/price search and product detail.
- Rejection of deleting a category containing a product.
- Profile writes and persisted activity history.
- Actual SMTP email receipt in Mailpit, password reset, rejection of token reuse and JWT refresh.

The smoke helper deleted the product/category fixtures and soft-deleted its newly created account. It did not modify roles on existing accounts. The test email remains in local Mailpit.

Frontend coverage is V8 coverage of API/session/URL utility modules only (94.59% lines in this run). This is not whole-UI coverage. Backend JaCoCo checks retain the existing configuration and exclusions; a passing scaffold module is not evidence that its future business features are implemented.

Remaining evidence: review of the API contract and ERD, Figma comparison/component library, training completion, BRD/SRS approval and BA acceptance of the proposed Sprint 1 stories. See `SPRINT0_HANDOVER.md`.
