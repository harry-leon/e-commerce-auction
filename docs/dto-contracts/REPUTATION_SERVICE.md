# Reputation Service DTO Contract

> **Gateway path đã dành:** `/api/v1/reputations/**`
>
> **Trạng thái:** `PLANNED` — service hiện chưa có controller hoặc DTO nghiệp vụ

## Contract hiện tại

Không có endpoint callable hoặc request/response DTO reputation được công bố cho FE. Các model `ReputationEntry`, `Penalty`, `RepHistory` và field trust/reputation trong `frontend-web/src/types.ts` là UI/mock model, không phải payload Backend.

Khác ba service khung còn lại, Reputation Service hiện cũng chưa có `PingController` trong source.

## Điều kiện chuyển sang `CURRENT`

Phải chốt DTO điểm uy tín công khai/cá nhân, lịch sử thay đổi điểm, đánh giá giao dịch và penalty; xác định rõ field nào công khai, quyền xem/cập nhật, quy tắc idempotency theo order; sau đó thêm controller/test và cập nhật file này.
