# Báo Cáo Kiểm Thử Giao Diện Luồng 3 (Buyer) & Hướng Dẫn Thực Hiện

**Mã Task/Issue:** `HG-S3-FLOW3`  
**Tên task:** Kiểm thử giao diện Luồng 3 (Buyer): Kết thúc đấu giá → Thông báo → Thanh toán trúng thầu → Đơn hàng → Đánh giá uy tín  
**Môi trường:** Local (`http://localhost:3000` / Frontend Vite React TypeScript & Backend Spring Boot Microservices via Docker Compose)  
**Thời gian thực hiện:** 13/11/2026 (Asia/Ho_Chi_Minh)  
**Tình trạng:** **HOÀN THÀNH (100% PASS - 15/15 Bằng chứng)**

---

## 1. Môi trường & Hướng dẫn Khởi chạy (Run Guide)

### 1.1 Khởi chạy Backend Services
Hệ thống Nexus bao gồm các microservices chạy trên Docker Compose:
- `nexus-postgres-1`: Database PostgreSQL cổng 5433 (nội bộ 5432), chứa các schema riêng biệt (`nexus_user`, `nexus_catalog`, `nexus_commerce`, `nexus_auction`, `nexus_fulfillment`, `nexus_notification`, `nexus_reputation`).
- `nexus-keycloak-1`: IAM Auth Server (cổng 8180) quản lý phiên đăng nhập SSO, OAuth2/OIDC.
- `nexus-api-gateway-1`: API Gateway (cổng 8080) điều hướng request và xác thực JWT Bearer/Cookie.
- `nexus-auction-service-1`: Quản lý phiên đấu giá, đặt giá realtime WebSocket STOMP và chốt phiên (cổng 8084).
- `nexus-commerce-service-1`: Quản lý giỏ hàng, đơn hàng, thanh toán VNPAY sandbox (cổng 8083).
- `nexus-notification-service-1`: Thông báo in-app và email/push (cổng 8086).
- `nexus-reputation-service-1`: Đánh giá sao, huy hiệu tín nhiệm (cổng 8087).

Lệnh khởi chạy:
```bash
docker compose up -d
# Kiểm tra sẵn sàng của toàn bộ dịch vụ:
node scripts/check-compose-readiness.mjs
```

### 1.2 Khởi chạy Frontend Web
Frontend React TypeScript chạy trên Vite:
```bash
cd frontend-web
npm install
npm run dev
# Mở trình duyệt tại: http://localhost:3000
```

### 1.3 Tài khoản Demo Sử dụng Trong Kiểm Thử
| Vai trò | Email / Username | Mật khẩu | User ID | Tên hiển thị UI |
|---|---|---|---|---|
| **Buyer (Người mua)** | `buyer@nexus.vn` | `Nexus123@` | `4928292a-14de-4d37-87f4-5fc8917cf240` | Nguyễn Văn Mua / Minh Tran (`demo=buyer`) |
| **Seller (Người bán)** | `seller@nexus.vn` | `Nexus123@` | `6433cfef-1ee8-439f-9cec-1dcf32f841e0` | Trần Thị Bán / Anh Nguyen (`demo=seller`) |

---

## 2. Danh Sách Lỗi Đã Tìm Thấy & Đã Khắc Phục (Bug Fixes)

Trong quá trình thực hiện kiểm thử tự động và rà soát giao diện UI/UX sạch, các vấn đề sau đã được phát hiện và khắc phục triệt để:

| STT | Vấn đề / Lỗi phát hiện | Nguyên nhân kỹ thuật | Giải pháp khắc phục | Trạng thái |
|---|---|---|---|---|
| 1 | **UI lộ enum thô và thiếu bộ đếm** trong Hộp thư thông báo | `NotificationInbox.tsx` hiển thị trực tiếp chuỗi enum backend (`AUCTION_WON`, `AUCTION_OUTBID`, `ORDER_PLACED`...) và thiếu badge "X chưa đọc · Y thông báo". | Bổ sung hàm `formatNotificationType` dịch toàn bộ enum sang tiếng Việt thân thiện; thêm badge bộ đếm `X chưa đọc · Y thông báo` nổi bật tại Header hộp thư. | **ĐÃ SỬA & VERIFIED** |
| 2 | **Lộ raw UUID nội bộ** tại màn hình thanh toán đấu giá (`AuctionPaymentPage`) | `AuctionPages.tsx` hiển thị trực tiếp chuỗi UUID 36 ký tự của `settlement.auctionId` và `settlement.orderId`. | Định dạng lại mã hiển thị ngắn gọn dạng `#XXXXXX` (lấy 8 ký tự đầu viết hoa) tránh lộ cấu trúc DB nội bộ. | **ĐÃ SỬA & VERIFIED** |
| 3 | **Thiếu nút điều hướng "Xem chi tiết đơn hàng"** sau khi thanh toán thành công | Màn hình `VerifiedOrderResult` và `VnpayPaymentResult` chỉ hiển thị thông báo thành công mà thiếu nút bấm trực tiếp dẫn người mua tới chi tiết đơn vừa thanh toán. | Bổ sung nút `Xem chi tiết đơn hàng` điều hướng người dùng tới `/order-detail?id={orderId}`. | **ĐÃ SỬA & VERIFIED** |
| 4 | **Lộ sellerId dạng UUID thô** trên trang chi tiết đơn hàng | `OrderDetailPage` hiển thị chuỗi `ID: {order.sellerId}` raw UUID gây rối mắt người dùng. | Thay thế bằng link thân thiện "Ghé thăm gian hàng" trỏ trực tiếp tới `/seller-shop?sellerId={order.sellerId}`. | **ĐÃ SỬA & VERIFIED** |
| 5 | **Nút "+ Theo dõi" shop thiếu tương tác** | Trang `SellerShopPage` có nút theo dõi tĩnh, chưa lưu trạng thái follow và chưa có phản hồi người dùng. | Triển khai state lưu `localStorage` (`nexus_followed_sellers`), chuyển nút thành "✓ Đang theo dõi" khi bấm, cập nhật số lượng follower và bắn toast thông báo thành công tiếng Việt. | **ĐÃ SỬA & VERIFIED** |
| 6 | **Buyer bị chặn "Access denied" khi vào trang gian hàng Seller (`/seller-shop`)** | Trong `AppRouter.tsx`, điều kiện bảo vệ route kiểm tra `page.startsWith('seller-')` đã vô tình chặn luôn cả route `seller-shop` của Buyer. | Bổ sung ngoại lệ `page !== 'seller-shop'` và thêm `seller-shop`, `public-reputation` vào danh sách `publicPages` cho phép Buyer xem gian hàng tự do. | **ĐÃ SỬA & VERIFIED** |

---

## 3. Bảng Kết Quả Kiểm Thử Toàn Diện (Test Execution Matrix)

| Bước | Trang / URL | Phần tử kiểm thử | Tình huống kiểm thử | Kết quả UI quan sát | Đối chiếu Database (PostgreSQL) | Kết luận | Ảnh bằng chứng |
|:---:|---|---|---|---|---|:---:|---|
| **1** | `/home?demo=buyer` | Header thanh điều hướng | Đăng nhập Buyer demo | Hiển thị đầy đủ Avatar, Tên người dùng, Icon thông báo có badge, Giỏ hàng, Menu điều hướng | Cookie phiên làm việc hợp lệ; profile map đúng Buyer | **PASS** | `home_demo-buyer_header.png` |
| **2a** | `/notifications` | Header & Bộ đếm thông báo | Xem danh sách thông báo | Tiêu đề "Hộp thư Thông báo", badge đếm rõ ràng `X chưa đọc · Y thông báo`, tabs "Tất cả", "Chưa đọc" | Query `nexus_notification.notifications`: đếm đúng số dòng trạng thái `SENT` và `READ` | **PASS** | `notifications_counter_list.png` |
| **2b** | `/notifications` | Nút "Đánh dấu đã đọc" trên item | Đánh dấu 1 thông báo đã đọc | Thông báo chuyển trạng thái đã đọc (mất chấm xanh), badge đếm giảm đi 1 | Record tương ứng chuyển cờ `status='READ'` | **PASS** | `notifications_mark-read_success.png` |
| **2c** | `/notifications` | Nút "Đánh dấu tất cả đã đọc" | Đánh dấu tất cả thông báo | Toàn bộ thông báo chuyển trạng thái đã đọc, bộ đếm chưa đọc về 0, toast báo thành công | Tất cả bản ghi của user chuyển sang `status='READ'` | **PASS** | `notifications_mark-all-read_success.png` |
| **2d** | Database Verification | Schema `nexus_notification` | Đối chiếu trạng thái lưu DB | Hiển thị bảng kết quả psql trước/sau đối chiếu | Khớp 100% cột `status` (`READ`), thời gian `sent_at`, `created_at` | **PASS** | `notifications_is-read_db.png` |
| **2e** | Test 2 trình duyệt đồng thời (Outbid) | 2 Context độc lập (50% trái, 50% phải) | Buyer B đặt giá cao hơn Buyer A trong phiên đấu giá | Cửa sổ Trái (Buyer A): Nhận thông báo "Bị trả giá cao hơn" kèm nút "Đấu giá lại". Cửa sổ Phải (Buyer B): Hiển thị lượt đặt giá mới nhất dẫn đầu | Bảng `bids` ghi nhận lượt đặt giá mới của Buyer B; bảng `auctions` cập nhật `current_price` và `highest_bidder_id` | **PASS** | `outbid-notification_two-browsers_buyer-a-buyer-b.png` |
| **3a** | `/auction-payment` | Form thông tin nhận hàng | Bỏ trống Số điện thoại / Địa chỉ nhận hàng | Báo lỗi validation tiếng Việt rõ ràng ngay bên dưới form; chặn submit đi tiếp | Không có transaction hay bản ghi rác nào được tạo | **PASS** | `auction-payment_validation-error.png` |
| **3b** | `/auction-payment` | Form nhận hàng & Nút "Tiến hành thanh toán" | Điền đầy đủ thông tin hợp lệ (SĐT, Địa chỉ) | Nút submit chuyển trạng thái loading xử lý và chuyển hướng thành công sang cổng sandbox | Khởi tạo handover liên kết settlement và order | **PASS** | `auction-payment_submit_success.png` |
| **3c** | `/auction-payment` | Kiểm tra đơn hết hạn thanh toán | Truy cập phiên đã `DEFAULTED` / `CANCELLED` | Hiển thị banner cảnh báo đỏ: "Phiên đấu giá này đã bị hủy thanh toán...", nút thanh toán bị vô hiệu hoá (`disabled`) | Khớp bản ghi `auction_settlements` (`result='DEFAULTED'`) và `orders` (`status='CANCELLED'`) | **PASS** | `auction-payment_expired-deadline_blocked.png` |
| **4a** | `/sandbox-payment` | Tabs phương thức thanh toán | Chuyển đổi VNPAY QR, ATM, Thẻ quốc tế | Hiển thị đầy đủ form nhập thẻ, danh sách ngân hàng NCB/Vietcombank, đồng hồ đếm ngược 15 phút | Chưa ghi nhận thanh toán; chờ người dùng thao tác | **PASS** | `sandbox-payment_methods_select.png` |
| **4b** | `/sandbox-payment` → `/payment-result` | Nút "Mô phỏng Thanh toán Thành công" | Click xác nhận thanh toán giả lập | Chuyển sang màn hình kết quả thành công, hiển thị mã đơn, số tiền đã trả và nút "Xem chi tiết đơn hàng" | Cập nhật bảng `orders` sang `status='PAID'` | **PASS** | `payment-result_success.png` |
| **4c** | Database Verification | Schema `nexus_commerce` | Đối chiếu trạng thái đơn hàng | Kết quả query terminal cho thấy order chuyển sang PAID | Bảng `orders`: `status='PAID'`, `payment_deadline` khớp dữ liệu | **PASS** | `order-payment_paid_db.png` |
| **5a** | `/order-history` | Tab "Đã thanh toán" & Danh sách đơn | Kiểm tra đơn hàng đấu giá trúng thầu | Đơn hàng hiển thị badge tím nổi bật "Đấu giá" (`Auction`) cạnh mã đơn `AUC-...`, trạng thái "Đã thanh toán" | Khớp trường `source_type='AUCTION'` và `status='PAID'` | **PASS** | `order-history_auction-badge_paid-tab.png` |
| **5b** | `/order-detail` | Chi tiết đơn hàng & Người bán | Mở trang chi tiết đơn hàng đấu giá | Hiển thị địa chỉ giao hàng đã nhập, thông tin đấu giá, và link thân thiện "Ghé thăm gian hàng" | Khớp dữ liệu `shipping_address`, `recipient_phone`, `seller_id` | **PASS** | `order-detail_auction-info_address.png` |
| **6** | `/seller-shop` | Thông tin uy tín & Nút "Theo dõi" | Click nút "+ Theo dõi" shop của người bán | Nút chuyển thành "✓ Đang theo dõi", số followers tăng lên 1, hiển thị toast thông báo thành công tiếng Việt | Lưu cờ follow vào `localStorage` của buyer; dữ liệu rating khớp `nexus_reputation` | **PASS** | `seller-shop_reputation-follow_success.png` |

---

## 4. Các Câu Truy Vấn Database Đã Dùng Trong Kiểm Thử (SQL Verification)

Tất cả các câu truy vấn được thực thi trên PostgreSQL container thông qua Docker Compose:

### 4.1 Kiểm tra thông báo (Notification Service)
```sql
SET search_path TO nexus_notification;
SELECT id, user_id, channel, event_type, title, status, auction_id, created_at
FROM notifications
WHERE user_id = '4928292a-14de-4d37-87f4-5fc8917cf240'
ORDER BY created_at DESC LIMIT 6;
```

### 4.2 Kiểm tra phiên đấu giá & Settlement (Auction Service)
```sql
SET search_path TO nexus_auction;
SELECT id, status, highest_bidder_id, current_price, end_time
FROM auctions
WHERE id IN ('99999999-9999-9999-9999-999999999999', 'b73c36d6-555f-480c-b69f-587ecf74e345');

SELECT id, auction_id, winner_id, final_price, result, order_id, payment_deadline
FROM auction_settlements
WHERE auction_id IN ('99999999-9999-9999-9999-999999999999', 'b73c36d6-555f-480c-b69f-587ecf74e345');
```

### 4.3 Kiểm tra đơn hàng & Thanh toán (Commerce Service)
```sql
SET search_path TO nexus_commerce;
SELECT id, order_no, buyer_id, seller_id, source_type, status, total, payment_deadline
FROM orders
WHERE buyer_id = '4928292a-14de-4d37-87f4-5fc8917cf240' OR id = 'd3132031-1a5a-468f-8bee-90e0a7ec9c55'
ORDER BY created_at DESC LIMIT 5;
```

### 4.4 Kiểm tra uy tín người bán (Reputation Service)
```sql
SET search_path TO nexus_reputation;
SELECT r.user_id, r.score, r.trust_level,
       (SELECT ROUND(COALESCE(AVG(score),0),2) FROM ratings WHERE ratee_id=r.user_id AND is_disputed=false) AS avg_rating,
       (SELECT COUNT(*) FROM ratings WHERE ratee_id=r.user_id AND is_disputed=false) AS total_ratings
FROM reputations r
WHERE r.user_id = '6433cfef-1ee8-439f-9cec-1dcf32f841e0';
```

---

## 5. Danh Mục Ảnh Bằng Chứng (Screenshot Evidence)

Tất cả ảnh bằng chứng được lưu tại thư mục: `docs/screenshots/HG-S3-FLOW3/`

1. `home_demo-buyer_header.png` — Màn hình Trang chủ với Header hiển thị tên và avatar của Buyer demo.
2. `notifications_counter_list.png` — Hộp thư thông báo với tiêu đề, bộ đếm "X chưa đọc · Y thông báo", nút Tải lại.
3. `notifications_mark-read_success.png` — Đánh dấu đã đọc một thông báo thành công.
4. `notifications_mark-all-read_success.png` — Đánh dấu tất cả thông báo là đã đọc thành công, bộ đếm về 0.
5. `notifications_is-read_db.png` — Ảnh chụp terminal bảng `notifications` đối chiếu cờ status/is_read trong database.
6. `outbid-notification_two-browsers_buyer-a-buyer-b.png` — Ảnh ghép 2 trình duyệt đồng thời 50/50: Buyer A nhận thông báo Outbid và Buyer B đặt giá thành công.
7. `auction-payment_validation-error.png` — Màn hình thanh toán đấu giá với thông báo lỗi validation khi bỏ trống SĐT/địa chỉ.
8. `auction-payment_submit_success.png` — Điền thông tin giao hàng hợp lệ và gửi thành công.
9. `auction-payment_expired-deadline_blocked.png` — Màn hình thanh toán với đơn đã hết hạn, hiển thị cảnh báo và nút bị vô hiệu hoá.
10. `sandbox-payment_methods_select.png` — Cổng thanh toán sandbox với các phương thức VNPAY, ATM, Thẻ quốc tế.
11. `payment-result_success.png` — Màn hình kết quả thanh toán thành công kèm nút "Xem chi tiết đơn hàng".
12. `order-payment_paid_db.png` — Ảnh chụp terminal bảng `orders` đối chiếu trạng thái PAID trong database.
13. `order-history_auction-badge_paid-tab.png` — Lịch sử đơn hàng với badge "Đấu giá" màu tím tại tab Đã thanh toán.
14. `order-detail_auction-info_address.png` — Chi tiết đơn hàng đấu giá, địa chỉ giao hàng và link "Ghé thăm gian hàng".
15. `seller-shop_reputation-follow_success.png` — Trang gian hàng người bán, hiển thị huy hiệu tín nhiệm, điểm sao và trạng thái "+ Theo dõi" chuyển thành "✓ Đang theo dõi".

---

## 6. Hướng Dẫn Chạy Kiểm Thử Tự Động (Automation Execution)
Để chạy lại toàn bộ kịch bản kiểm thử E2E tự động và tự động cập nhật bộ ảnh bằng chứng:
```bash
node scripts/test-flow3-e2e.mjs
```
Script sẽ tự động khởi động trình duyệt Chromium, thực thi toàn bộ 6 bước, kiểm tra dữ liệu database PostgreSQL và lưu toàn bộ ảnh bằng chứng vào thư mục `docs/screenshots/HG-S3-FLOW3/`.
