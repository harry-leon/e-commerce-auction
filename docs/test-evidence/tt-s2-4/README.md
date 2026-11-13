# Bằng chứng Compose Sprint 2

`readiness.json` do `node scripts/check-compose-readiness.mjs` sinh từ HTTP/WebSocket thật trên Compose local ngày 13/11/2026. Chỉ lưu status và assertion, không chứa env, token, payload hoặc URL thanh toán đã ký.

Phạm vi: web, Gateway health, hai route public, bảo vệ orders và upgrade WebSocket rồi từ chối STOMP CONNECT ẩn danh. Không phải bằng chứng winner thanh toán VNPAY hoặc UAT đầy đủ. Xem [báo cáo tích hợp](../../TT-S2-4_INTEGRATION_REVIEW.md) về test local, skip và vướng mắc seed IAM.
