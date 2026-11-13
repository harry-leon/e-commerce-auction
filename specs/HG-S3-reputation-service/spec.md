# Đặc tả tính năng HG-S3: Hoàn thiện hệ thống Uy tín, Đánh giá, Kiểm tra điều kiện Đấu giá và Kiểm duyệt Admin

**Trạng thái:** Ready for Implementation  
**Nhánh làm việc:** `feature/hg-s3-reputation-service` (tách từ `dev`)  
**Phạm vi:** `reputation-service`, `auction-service`, `api-gateway`, tài liệu hợp đồng & hướng dẫn kiểm thử (`docs/`).  
**Các issue liên quan:** `HG-S3-1`, `HG-S3-2`, `HG-S3-3`.

---

## 1. Tổng quan & Kiến trúc

Hệ thống hiện mới có nền tảng `reputations` và `penalties` (chỉ có luồng nội bộ `POST /api/v1/internal/reputations/penalties`). Chưa có API đánh giá sau giao dịch (`ratings`), chưa cập nhật điểm uy tín & cấp độ (`TrustLevel`) theo đánh giá, chưa có API/tích hợp kiểm tra điều kiện uy tín khi tạo phiên hoặc đặt giá đấu giá, và chưa có luồng khiếu nại/kiểm duyệt Admin.

### Nguyên tắc kiến trúc bắt buộc
1. **Khóa schema theo ERD V2:** Sử dụng nguyên bảng `reputations`, `ratings`, `penalties` trong `V2__init_schema.sql` + `V3__align_with_final_erd.sql` của `reputation-service`. Không thêm bảng/cột trái phép vào database.
2. **Database-per-Service:** `reputation-service` xác thực trạng thái đơn hàng và các bên tham gia qua `OrderVerificationClient` gọi `GET {COMMERCE_SERVICE_URL}/api/v1/orders/{orderId}` (chuyển tiếp JWT hiện tại của người dùng), tuyệt đối không query trực tiếp DB của `commerce-service`.
3. **Bảo mật & Chống IDOR:** Danh tính `raterId` / `actorId` lấy từ JWT (`sub`), không tin tưởng ID giả mạo từ client; phân quyền rõ ràng cho Buyer/Seller, Admin/Support, và luồng nội bộ service-to-service qua `ServiceIdentity`.

---

## 2. Yêu cầu chức năng (Functional Requirements)

### HG-S3-1: Đánh giá sau giao dịch (`ratings`)
- **Entity & Repository:** Tạo `Rating` ánh xạ bảng `ratings` (`id`, `rater_id`, `ratee_id`, `order_id`, `score` `1..5`, `comment` max `1000`, `is_aggregated`, `is_disputed`, `created_at`) và `RatingRepository` hỗ trợ kiểm tra trùng `(order_id, rater_id)`, truy vấn đánh giá hợp lệ (`is_disputed = false`), tính trung bình & tổng số đánh giá hợp lệ.
- **API Endpoint (`RatingController` + route `api-gateway`):**
  - `POST /api/v1/ratings`: Người mua hoặc Người bán đánh giá đối tác sau giao dịch.
  - `GET /api/v1/ratings/user/{userId}`: Danh sách phân trang đánh giá hợp lệ (`is_disputed = false`) của một người dùng (public).
  - `GET /api/v1/ratings/order/{orderId}`: Danh sách đánh giá gắn với một đơn hàng (yêu cầu đăng nhập).
- **Quy tắc nghiệp vụ:**
  - `raterId` lấy từ JWT (`sub`); cấm tự đánh giá (`raterId == rateeId` -> `400`).
  - Điểm đánh giá bắt buộc trong `[1, 5]`; comment tối đa 1000 ký tự.
  - Khi `app.reputation.order-verification-enabled=true`: gọi `commerce-service` xác nhận đơn hàng đang ở trạng thái `DELIVERED` hoặc `COMPLETED` (`409 REVIEW_ORDER_NOT_DELIVERED`), người đánh giá là `buyerId` hoặc `sellerId` (`403 REVIEW_NOT_OWNER`), và `rateeId` là đối tác còn lại (`400 INVALID_PARAM_VALUE`).
  - Tối đa 1 đánh giá cho mỗi `(orderId, raterId)` (`409 REVIEW_ALREADY_EXISTS`).

### HG-S3-2: Tính điểm uy tín, Trust Level, Penalty Summary & Điều kiện Đấu giá
- **Cập nhật điểm từ đánh giá:** Khi tạo rating hợp lệ (`isAggregated = false`), khóa bi quan bản ghi `Reputation` của `rateeId` (`lockOrCreateReputation`), áp dụng delta điểm (`5 -> +3`, `4 -> +1`, `3 -> 0`, `2 -> -3`, `1 -> -5`, giữ `score >= 0`), đánh dấu `isAggregated = true`, tính lại `TrustLevel` (`<60 LOW`, `60..109 STANDARD`, `110..149 HIGH`, `>=150 VIP`).
- **Tóm tắt giao dịch & Sự kiện:** Bổ sung `avgRating` và `totalRatings` vào `ReputationSummaryResponse`; cung cấp API ghi nhận thống kê giao dịch (`POST /api/v1/internal/reputations/transactions`, đảm bảo `totalTx >= successTx + failedTx`); phát sự kiện sau commit (`UserRatedEvent`, `ReputationScoreUpdatedEvent`, `TrustLevelChangedEvent`, `ReputationPenaltyAppliedEvent`).
- **API Kiểm tra điều kiện Đấu giá:**
  - `GET /api/v1/reputations/{userId}/eligibility?action=BID|CREATE_AUCTION`: Trả về `eligible`, `score`, `minRequiredScore`, `trustLevel`, `reason`. Ngưỡng chuẩn SRS: `BID >= 40`, `CREATE_AUCTION >= 50`.
  - `GET /api/v1/internal/reputations/{userId}/eligibility?action=...`: Endpoint nội bộ cho `nexus-auction-service` (yêu cầu privilege `AUCTION.READ` hoặc `REPUTATION.PENALTY` hoặc token dịch vụ hợp lệ) và `POST /api/v1/internal/reputations/transactions`.
- **Tích hợp `auction-service`:** Kiểm tra điều kiện uy tín qua `ReputationEligibilityClient` trong `AuctionServiceImpl.createAuction` (`CREATE_AUCTION`, min `50`) và `BiddingServiceImpl.placeBid` (`BID`, min `40`) khi `app.auction.reputation-enforcement-enabled=true`; từ chối bằng `REPUTATION_ACTION_FORBIDDEN` (`403`) nếu không đạt.

### HG-S3-3: Admin Kiểm duyệt, Gắn cờ Khiếu nại & Audit
- **Gắn cờ khiếu nại đánh giá (`POST /api/v1/ratings/{ratingId}/dispute`):** Chỉ `rateeId` hoặc `ROLE_ADMIN` / `ROLE_SUPPORT` được phép gắn cờ `isDisputed = true` (người khác -> `403 REVIEW_NOT_OWNER`).
- **Admin Tra cứu & Kiểm duyệt:**
  - `GET /api/v1/reputations/admin/users`: Danh sách phân trang hồ sơ uy tín, hỗ trợ lọc theo `trustLevel` (`ADMIN`, `SUPPORT`).
  - `GET /api/v1/reputations/admin/ratings`: Danh sách phân trang đánh giá, hỗ trợ lọc `disputedOnly` và `rateeId` (`ADMIN`, `SUPPORT`).
  - `GET /api/v1/reputations/admin/penalties`: Lịch sử penalty/điều chỉnh phân trang, hỗ trợ lọc `userId` và `refType` (`ADMIN`, `SUPPORT`).
  - `POST /api/v1/reputations/admin/adjustments`: Điều chỉnh điểm thủ công (`refType = MANUAL`, delta khác `0`, lý do bắt buộc, idempotent theo `refId`), chỉ `ROLE_ADMIN`.
  - `POST /api/v1/reputations/admin/ratings/{ratingId}/dispute/resolve`: Xử lý khiếu nại (`DISMISS` -> hạ cờ `isDisputed = false`; `INVALIDATE` -> giữ `isDisputed = true`, hoàn ngược ảnh hưởng điểm của đánh giá bằng bản ghi `Penalty` `refType = RATING`, `refId = ratingId` chống trùng lặp), chỉ `ROLE_ADMIN`.

---

## 3. Tiêu chí nghiệm thu & Kiểm thử
- Checkstyle (`checkstyle:check`) 0 lỗi, `mvn verify` xanh, JaCoCo Line Coverage >= `70%` cho `reputation-service` và `auction-service`.
- Kiểm thử sống Swagger UI trên trình duyệt (Playwright) với đầy đủ ca thành công, lỗi validation (`400`/`422`), lỗi nghiệp vụ (`403`/`409`) và RBAC (`401`/`403`).
- Cập nhật tài liệu hợp đồng `docs/dto-contracts/REPUTATION_SERVICE.md` và hướng dẫn chạy/kiểm thử `docs/HG-S3_REPUTATION_GUIDE.md`.
