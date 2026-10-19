# Convention compliance verification — 2026-10-19

Follow-up to `9c11f2d`, checked locally with Node 22, Java 21 and Docker Desktop against `CONTRIBUTING.md`. This report records engineering verification, not stakeholder acceptance or completion of training/Figma deliverables.

## Changes

- Frontend screens and auth state now live under `src/features/<feature>/`; HTTP calls use `src/api/client.ts`. Shared loading behavior lives in `src/hooks` and `src/components`.
- Tokens use sessionStorage only. Loading the client removes legacy localStorage tokens. “Remember email” persists only an email; it does not preserve authentication after the tab session ends.
- Prettier replaces oxfmt, respects `.editorconfig`, and runs in the frontend CI lint job. Both npm and pnpm lockfiles are generated from the updated dependency set. JSON configuration and lockfiles cannot contain inline comments; this document explains their purpose.
- Vitest measures all application TS/TSX source, including prototype screens. Only tests, test setup and declaration files are excluded. The enforced minimum is 70% line coverage, and the GitLab coverage extraction reads the same metric.
- Config Server and Eureka now have Docker image jobs alongside their verification jobs. Docker builds enable BuildKit for cache mounts.
- Catalog errors now use the shared top-level `ApiError` shape (`code`, `message`, `details`, `timestamp`). Successful responses retain their existing envelope. This changes the previous nested error contract and requires review by consuming teams under `CONTRIBUTING.md`; the web client accepts both shapes during transition.
- Changed functionality includes Vietnamese comments explaining responsibilities, data flow and limitations. Prototype tests verify their existing UI behavior and do not imply completed later-sprint backend functionality.

## Local results

| Check | Result |
| --- | --- |
| Frontend ESLint, TypeScript and production build | Passed |
| Prettier format check | Passed |
| Vitest | 136 tests passed across 8 files |
| Application line coverage | 70.64% (1,309 / 1,853 lines), minimum 70% |
| Coverage gate rejection check | Setting the threshold to 100% correctly fails the suite |
| Playwright / Edge | 5 browser tests passed, including email-only localStorage persistence |
| Catalog Maven clean verify and Checkstyle | Passed; 121 tests |
| CI configuration | YAML parsed; 27 jobs checked for stages, templates and script structure; infrastructure image paths and coverage regex checked |
| Compose web and catalog rebuild | Passed; containers started successfully |
| Live PostgreSQL / SMTP smoke test | Passed through the running stack |

Statement coverage is 69.6%, branch coverage 55.11% and function coverage 57.79%. The configured gate measures lines; these other metrics are reported without claiming they exceed 70%. The new tests cover both API-backed screens and legacy UI, including interactions, routing, authentication storage and error handling.

The live smoke test verifies registration, buyer permission denial using the top-level `ApiError`, category depth/cycles, product/SKU persistence and filtering, category deletion guards, profile/activity writes, SMTP password reset, token single use and JWT refresh. It removes its product/category fixtures and soft-deletes its newly created user without changing existing account roles.

Other backend service results remain the historical results in [SPRINT0_VERIFICATION.md](SPRINT0_VERIFICATION.md); those unchanged services were not rerun in this follow-up.

## Limits

GitLab pipeline status could not be read with the available authentication. Local YAML/structure checks do not replace GitLab CI lint or an actual runner execution. Do not describe the remote pipeline as green until its jobs have completed successfully. API contract review and team acceptance remain separate from these checks.
