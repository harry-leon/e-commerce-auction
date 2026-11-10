# TT-S2-3 — Bằng chứng review

Issue: #84. Ảnh được chụp từ React/Vite bằng Chrome headless, dùng HTTP fixture cho payment/profile.
Đây là bằng chứng hành vi UI với response cụ thể, **không phải** bằng chứng đã thanh toán tại merchant VNPAY.
Sơ đồ là hình minh họa implementation, không phải ảnh log thực thi.

| Ảnh | Điều reviewer cần kiểm tra |
| --- | --- |
| [01-processing.png](01-processing.png) | Tiền đã ghi nhận nhưng kho chưa xác nhận: chưa hiển thị thành công, yêu cầu không trả tiền lần hai |
| [02-requires-review.png](02-requires-review.png) | Đã thu tiền nhưng không xác nhận được đơn: liên hệ hỗ trợ, không giả đã hoàn tiền |
| [03-confirmed.png](03-confirmed.png) | API SUCCESS mới hiển thị xác nhận thanh toán |
| [04-payment-flow.png](04-payment-flow.png) | Phân biệt Payment SUCCESS và Order PAID; commit/sweep cùng khóa phiếu |

Tất cả URL UI đều có `vnp_ResponseCode=00`; PROCESSING và REQUIRES_REVIEW vẫn không hiển thị Payment confirmed.
ID, số tiền và profile trong ảnh là fixture công khai, không chứa cookie/token/secret/hash merchant.

## Tái lập ảnh

Chạy `npm run dev -- --host 127.0.0.1 --port 3013` trong `frontend-web`, rồi tại root:

```powershell
$env:BROWSER_EXECUTABLE_PATH = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
node scripts/capture-winner-payment-evidence.mjs
```

Nếu Playwright đã cài Chromium, bỏ biến browser path. Có thể đổi server bằng `EVIDENCE_BASE_URL`.
Script assert nội dung heading thật và không có heading thành công khi API chưa SUCCESS, sau đó mới chụp ảnh.

## Kiểm tra trước khi tạo MR ngày 10/11/2026

- Commerce: 342 test, `verify checkstyle:check` pass, line coverage 91,07%; DB test PostgreSQL riêng.
- Fulfillment: 30 test InventoryService và 2 test row-lock PostgreSQL pass; Checkstyle pass.
- Auction: 15 test client/handover/scheduler pass; Checkstyle pass.
- Gateway: PaymentIpnSecurityTest pass. Checkstyle mặc định báo 27 lỗi Java có sẵn; TT-S2-3 chỉ đổi logging YAML tại Gateway.
- Frontend: **280 test trong 19 file pass**, line coverage **73,30%**, ESLint/typecheck/production build pass.
- Prettier các file payment mới/sửa pass. `npm run format:check` toàn frontend báo **35 file**, đã đối chiếu cả 35 cũng fail trên `origin/dev`; không có file fail mới do TT-S2-3.

Không đánh dấu pipeline GitLab xanh chỉ dựa kết quả local. MR frontend cần xử lý baseline format trước khi merge.
Chi tiết contract/deployment và giới hạn refund: [TT-S2-3_WINNER_PAYMENT.md](../../TT-S2-3_WINNER_PAYMENT.md).
