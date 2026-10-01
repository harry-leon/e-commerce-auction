# Quy ước làm việc (Project Nexus)

Áp dụng cho repo monorepo `nexus` (frontend + backend).

## 1. Chiến lược nhánh (Gitflow)

| Nhánh | Mục đích | Tách từ | Merge vào |
|---|---|---|---|
| `main` | Code production. Mỗi lần release gắn tag `vX.Y.Z` | | |
| `develop` | Nhánh tích hợp, **default branch** | | |
| `feature/<ticket>-<mô-tả>` | Tính năng mới. Vd `feature/NEX-12-place-bid` | `develop` | `develop` |
| `bugfix/<ticket>-<mô-tả>` | Sửa lỗi trên develop | `develop` | `develop` |
| `release/X.Y.0` | Chuẩn bị phát hành, chỉ sửa bug nhỏ, cập nhật version | `develop` | `main` và `develop` |
| `hotfix/<ticket>-<mô-tả>` | Sửa lỗi gấp trên production | `main` | `main` và `develop` |

Quy tắc:
- **Không push trực tiếp** vào `main` và `develop`. Mọi thay đổi đi qua Merge Request.
- Nhánh feature sống ngắn (vài ngày). Thường xuyên rebase/merge `develop` vào nhánh của mình.
- Tên nhánh viết thường, nối bằng dấu `-`.
- Tag phiên bản theo [SemVer](https://semver.org): `vMAJOR.MINOR.PATCH`.

## 2. Conventional Commits

Dạng: `<type>(<scope>): <mô tả ngắn, thể hiện mệnh lệnh, tiếng Anh, không chấm cuối>`

| type | Dùng khi |
|---|---|
| `feat` | Thêm tính năng |
| `fix` | Sửa lỗi |
| `docs` | Chỉ sửa tài liệu |
| `refactor` | Đổi cấu trúc code, không đổi hành vi |
| `test` | Thêm / sửa test |
| `perf` | Cải thiện hiệu năng |
| `build` | Đổi build, dependency |
| `ci` | Đổi cấu hình CI |
| `chore` | Việc vặt khác |
| `revert` | Hoàn tác commit |

`scope` là service hoặc thành phần bị ảnh hưởng: `user`, `catalog`, `commerce`, `auction`, `fulfillment`, `notification`, `gateway`, `web`, `deploy`, `ci`, `docs`.
Thay đổi chạm nhiều thành phần thì chọn scope chính, hoặc bỏ scope.

Ví dụ:

```
feat(auction): add anti-sniping extension
fix(commerce): reject zero quantity
test(fulfillment): cover concurrent reservation
ci: add jacoco coverage gate
feat(commerce)!: change order status enum
```

- Dấu `!` hoặc footer `BREAKING CHANGE: ...` đánh dấu thay đổi phá vỡ tương thích (API, event schema).
- Có thể thêm body giải thích "vì sao" và footer tham chiếu ticket: `Refs: NEX-12`.

## 3. Merge Request

1. Tạo nhánh từ `develop`, commit nhỏ và rõ ràng.
2. Push và mở MR vào `develop`. Dùng MR template có sẵn, điền đủ.
3. **Tiêu đề MR phải theo Conventional Commits** (CI kiểm tra). Dự án dùng **squash merge**, tiêu đề MR trở thành commit trên `develop`.
4. Điều kiện để merge:
   - Pipeline xanh. CI chỉ chạy job của thành phần có file thay đổi (xem `.ci/jobs.yml`); coverage >= 70%.
   - Tối thiểu **1 approve** từ người khác (service quan trọng như payment, auction: 2).
   - Mọi thread thảo luận đã resolved.
5. Người tạo MR tự merge sau khi đủ điều kiện. Nhánh nguồn tự xóa sau khi merge.
6. MR nên nhỏ (dưới ~400 dòng thay đổi). Reviewer phản hồi trong vòng 1 ngày làm việc.

Thay đổi API hoặc event schema (Kafka) **phải** được tag các team liên quan review (SRS mục 4.2).

## 4. Quy ước code

**Chung**
- Không commit secret, `.env`, token. Dùng biến môi trường / Secret Manager.
- Mỗi MR nên chỉ động tới **một** thành phần (một service hoặc FE). Việc cần sửa nhiều service thì tách MR, ghi rõ thứ tự merge.
- Service **không** truy cập DB của service khác; giao tiếp qua API hoặc event (SRS 4.1).

**Backend (`backend/*`)**
- Java 21, Checkstyle (`mvn checkstyle:check`), unit test cho nghiệp vụ, coverage >= 70%.
- DB chỉ đổi qua Flyway (`V<số>__<mô_tả>.sql`). Không sửa migration đã merge.
- API có version `/api/v1`, mô tả bằng OpenAPI, lỗi trả về theo `ApiError`.
- Đổi API hoặc event schema (Kafka): tag team liên quan review (SRS 4.2) và cập nhật `api-gateway` nếu thêm đường dẫn mới.

**Frontend (`frontend-web`)**
- TypeScript strict, ESLint + Prettier. Cấu trúc theo feature: `src/features/<tên>/`.
- Test bằng Vitest, coverage >= 70%. Gọi API qua `src/api/client.ts`.
- Biến `VITE_*` lộ ra trình duyệt: không đặt secret. Không lưu token trong localStorage.

## 5. Quy trình release

1. Tạo `release/X.Y.0` từ `develop`, chỉ sửa bug và tăng version.
2. MR vào `main`, merge xong gắn tag `vX.Y.0`. CI build và push Docker image theo tag.
3. Merge ngược `main` về `develop`.
4. Hotfix: tách `hotfix/*` từ `main`, MR vào `main`, gắn tag patch, merge ngược về `develop`.
