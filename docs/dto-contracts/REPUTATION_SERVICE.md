# Reputation Service DTO Contract

> **Gateway paths:** `/api/v1/reputations/**`, `/api/v1/ratings/**`
>
> **Internal paths (Service-to-Service):** `/api/v1/internal/reputations/**`
>
> **Trạng thái:** `CURRENT` — Hoàn thiện Sprint 3 (`HG-S3-1`, `HG-S3-2`, `HG-S3-3`)
>
> **Swagger UI (Local):** `http://localhost:8087/swagger-ui.html` (`/v3/api-docs`)

---

## 1. Envelope chuẩn

Mọi response REST của Reputation Service đều bọc trong `ApiResponse<T>` (`backend/common`) với 4 khóa cố định:

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": {
    "requestId": "...",
    "timestamp": "...",
    "pagination": {
      "page": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1
    }
  }
}
```

- Với response đơn lẻ hoặc danh sách không phân trang (`ApiResponse.ok(data)`): `meta.pagination` là `null`.
- Với response phân trang (`ApiResponse.ok(Page<T>)`): `data` là mảng JSON và `meta.pagination` chứa thông tin phân trang (`page`, `size`, `totalElements`, `totalPages`).

---

## 2. Enums

### `TrustLevel`
- `LOW` (`score < 60`)
- `STANDARD` (`60 <= score < 110`, điểm khởi tạo mặc định `100`)
- `HIGH` (`110 <= score < 150`)
- `VIP` (`score >= 150`)

### `EligibilityAction`
- `BID`: Ngưỡng mặc định `minReputationToBid = 40`
- `CREATE_AUCTION`: Ngưỡng mặc định `minReputationToSell = 50`

### `PenaltyRefType`
- `AUCTION`, `ORDER`, `RATING`, `MANUAL`

### `DisputeResolution` (`ResolveDisputeRequest.DisputeResolution`)
- `DISMISS`: Bác bỏ khiếu nại, giữ nguyên điểm uy tín và gỡ cờ `isDisputed`
- `INVALIDATE`: Hủy hiệu lực đánh giá, hoàn tác delta điểm uy tín (idempotent theo `PenaltyRefType.RATING` + `ratingId`)

---

## 3. Public & Authenticated Endpoints (`/api/v1/reputations/**`, `/api/v1/ratings/**`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| `GET` | `/api/v1/reputations/me` | Authenticated | Xem hồ sơ uy tín của chính chủ JWT (`sub`) |
| `GET` | `/api/v1/reputations/{userId}` | Public | Xem tóm tắt uy tín, thống kê giao dịch và `avgRating` công khai |
| `GET` | `/api/v1/reputations/{userId}/eligibility?action=BID\|CREATE_AUCTION` | Public | Kiểm tra người dùng có đủ điểm tối thiểu tham gia/mở đấu giá hay không |
| `GET` | `/api/v1/reputations/{userId}/penalties` | Owner hoặc `ROLE_ADMIN`/`ROLE_SUPPORT` | Xem lịch sử biên bản phạt & điều chỉnh điểm |
| `POST` | `/api/v1/ratings` | Authenticated | Gửi đánh giá 1-5 sao cho đối tác của đơn hàng `DELIVERED`/`COMPLETED` |
| `GET` | `/api/v1/ratings/user/{userId}` | Public | Xem danh sách phân trang các đánh giá hợp lệ (không bị khiếu nại) của user |
| `GET` | `/api/v1/ratings/order/{orderId}` | Authenticated | Xem các đánh giá gắn với một đơn hàng |
| `POST` | `/api/v1/ratings/{ratingId}/dispute` | `rateeId` hoặc `ROLE_ADMIN`/`ROLE_SUPPORT` | Gắn cờ khiếu nại một đánh giá |

### Admin / Support Moderation Endpoints (`/api/v1/reputations/admin/**`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| `GET` | `/api/v1/reputations/admin/users?trustLevel=...` | `ROLE_ADMIN` / `ROLE_SUPPORT` | Tra cứu danh sách hồ sơ uy tín phân trang, lọc theo `trustLevel` |
| `GET` | `/api/v1/reputations/admin/ratings?disputedOnly=...&rateeId=...` | `ROLE_ADMIN` / `ROLE_SUPPORT` | Tra cứu tất cả đánh giá hoặc lọc các đánh giá đang bị khiếu nại |
| `GET` | `/api/v1/reputations/admin/penalties?userId=...&refType=...` | `ROLE_ADMIN` / `ROLE_SUPPORT` | Tra cứu nhật ký xử phạt và điều chỉnh thủ công (`MANUAL`, `RATING`, `AUCTION`, `ORDER`) |
| `POST` | `/api/v1/reputations/admin/adjustments` | `ROLE_ADMIN` | Điều chỉnh điểm uy tín thủ công (`[-200..200]`) kèm lý do kiểm toán bắt buộc |
| `POST` | `/api/v1/reputations/admin/ratings/{ratingId}/dispute/resolve` | `ROLE_ADMIN` | Phán quyết khiếu nại (`DISMISS` hoặc `INVALIDATE`) |

---

## 4. Internal Service-to-Service Endpoints (`/api/v1/internal/reputations/**`)

Bảo vệ bởi `ServiceIdentity` (JWT claim `azp` + Keycloak privilege authority):

| Method | Path | Caller được phép | Mô tả |
|---|---|---|---|
| `POST` | `/api/v1/internal/reputations/penalties` | `nexus-commerce-service` + `REPUTATION.PENALTY` | Trừ điểm vi phạm (lũy nghiệm theo `userId + refType + refId`) |
| `GET` | `/api/v1/internal/reputations/{userId}/eligibility?action=...` | `nexus-auction-service` (`COMMERCE.AUCTION`) hoặc `nexus-commerce-service` (`REPUTATION.PENALTY`) | `auction-service` gọi kiểm tra `BID` / `CREATE_AUCTION` |
| `POST` | `/api/v1/internal/reputations/transactions` | `nexus-commerce-service` (`REPUTATION.PENALTY`) hoặc `nexus-auction-service` (`COMMERCE.AUCTION`) | Cập nhật thống kê `totalTx`, `successTx`, `failedTx`, `auctionWon`, `auctionFailed` |

---

## 5. DTO Payloads

### `ReputationSummaryResponse`
```json
{
  "userId": "uuid",
  "score": 103,
  "trustLevel": "STANDARD",
  "totalTx": 4,
  "successTx": 4,
  "failedTx": 0,
  "auctionWon": 1,
  "auctionFailed": 0,
  "avgRating": 4.75,
  "totalRatings": 4
}
```

### `CreateRatingRequest` -> `RatingResponse`
Request:
```json
{
  "orderId": "uuid",
  "rateeId": "uuid",
  "score": 5,
  "comment": "Giao hàng nhanh, đóng gói cẩn thận"
}
```
- Validation: `orderId != null`, `rateeId != null`, `1 <= score <= 5`, `comment.length <= 1000`.
- Quy tắc cộng/trừ điểm vào `Reputation`: `5 -> +3`, `4 -> +1`, `3 -> 0`, `2 -> -3`, `1 -> -5` (kẹp sàn `>= 0`).

Response (`201 Created`):
```json
{
  "id": "uuid",
  "orderId": "uuid",
  "raterId": "uuid",
  "rateeId": "uuid",
  "score": 5,
  "comment": "Giao hàng nhanh, đóng gói cẩn thận",
  "isAggregated": true,
  "isDisputed": false,
  "createdAt": "2026-11-12T10:00:00Z"
}
```

### `ReputationEligibilityResponse`
```json
{
  "userId": "uuid",
  "action": "BID",
  "eligible": true,
  "score": 100,
  "minRequiredScore": 40,
  "trustLevel": "STANDARD",
  "reason": null
}
```

### `AdminAdjustReputationRequest` -> `PenaltyResponse`
Request:
```json
{
  "userId": "uuid",
  "scoreDelta": -15,
  "reason": "Vi phạm quy định giao dịch",
  "refId": "uuid (tùy chọn, dùng làm khóa idempotency cho MANUAL)"
}
```
- Validation: `userId != null`, `-200 <= scoreDelta <= 200`, `scoreDelta != 0`, `reason` not blank (`<= 500`).

### `DisputeRatingRequest` & `ResolveDisputeRequest`
- `DisputeRatingRequest`: `{ "reason": "string (tùy chọn, <= 500)" }`
- `ResolveDisputeRequest`: `{ "resolution": "DISMISS" | "INVALIDATE", "reason": "string (bắt buộc, <= 500)" }`

### `PenaltyResponse`
```json
{
  "penaltyId": "uuid",
  "userId": "uuid",
  "refType": "MANUAL",
  "refId": "uuid",
  "scoreDelta": -15,
  "previousScore": 100,
  "newScore": 85,
  "trustLevel": "STANDARD",
  "reason": "[ADMIN <adminId>] Vi phạm quy định giao dịch",
  "idempotentReplay": false,
  "createdAt": "2026-11-12T10:15:00Z"
}
```

### `RecordTransactionRequest`
```json
{
  "userId": "uuid",
  "outcome": "SUCCESS",
  "auction": true
}
```

---

## 6. Mã lỗi nghiệp vụ chính

| HTTP | ErrorCode | Ngữ cảnh |
|---|---|---|
| `400` | `INVALID_PARAM_VALUE` | Tự đánh giá chính mình, `scoreDelta == 0`, `rateeId` không khớp đối tác đơn hàng, giải quyết rating không ở trạng thái disputed |
| `409` | `REVIEW_ORDER_NOT_DELIVERED` | Đơn hàng chưa ở trạng thái `DELIVERED` hoặc `COMPLETED` |
| `401` | `Unauthorized` | Thiếu hoặc JWT Keycloak không hợp lệ |
| `403` | `AUTH_FORBIDDEN` / `REVIEW_NOT_OWNER` / `AccessDenied` | Người gọi không phải chủ đơn hàng, không phải `rateeId`/`ADMIN`/`SUPPORT`, hoặc không đủ role `ADMIN`/`SUPPORT` |
| `404` | `REVIEW_NOT_FOUND` / `ORDER_NOT_FOUND` | Không tìm thấy bản ghi rating hoặc đơn hàng tại `commerce-service` |
| `409` | `REVIEW_ALREADY_EXISTS` | Mỗi bên đã gửi 1 đánh giá cho đơn hàng này |
| `422` | `VALIDATION_ERROR` | Vi phạm `@Valid` trên DTO request (`score` ngoài `1..5`, thiếu `reason`, `scoreDelta` ngoài `-200..200`...) |
| `403` | `REPUTATION_ACTION_FORBIDDEN` | Khi `auction-service` chặn tạo phiên hoặc đặt giá vì điểm uy tín thấp hơn ngưỡng `40`/`50` |
