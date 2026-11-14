# TT-S3-06 — Worker dispatch từ kiện đã khai báo

Phụ thuộc API Commerce MR !173: GET handoff theo Order và POST preflight claim. Merge/deploy Commerce trước worker. Fulfillment V13 thêm shipment_dispatch_jobs; không seed/call carrier trong migration. Dataset giữ Commerce 22/Fulfillment 13. Worker mặc định tắt, thay đổi này chưa bật cấu hình hoặc gọi IAM/carrier thật.

## Luồng và quyền

1. Khi bật worker và có cấu hình OAuth, mỗi tick seed tối đa 10 kiện chưa có job (gồm cả kiện đã đăng ký trước khi bật). Job UUID được commit trước HTTP và làm Idempotency-Key cố định cho claim; mỗi Order/parcel chỉ một job.
2. SKIP LOCKED lấy job WAITING đến hạn, chuyển CLAIMING với token/lease 120 giây. Không giữ transaction DB trong HTTP.
3. Kiểm tra kho/carrier active, chưa có shipment/dispatch attempt; GET handoff. 404 chỉ chờ 60 giây, không tiêu ngân sách claim. Không tự tạo handoff cho đơn chưa xác nhận tiền/kho.
4. POST claim cùng job UUID/parcel qua client_credentials; lưu claimId dưới fencing token. POST preflight kiểm tra lại tài chính và lấy snapshot người nhận, không dùng replay claim cũ thay quyền mới.
5. Commit CAS DISPATCHING với lease còn hạn và kho/carrier còn active. Chỉ sau đó gọi ShippingService.createShipment với key dispatch-job:{jobId}, kiện thật, snapshot Commerce và COD=0.
6. ShippingService production vẫn dùng durable guard trước HTTP carrier. Job chỉ COMMITTED khi shipment đúng Order/key/tracking đã tồn tại trong DB; không tự nhận shipment của ingress khác. COMMITTED không phải đã giao hàng hoặc guard chắc chắn không còn UNKNOWN.

OAuth dùng app.shipping.commerce.url/token-url/client-id/client-secret hiện có; giữ secret trong môi trường. Service account nexus-fulfillment-service cần COMMERCE.SHIPPING_DISPATCH và COMMERCE.SHIPPING_READ. API Commerce xác minh caller từ JWT, không dùng Admin/Buyer thay thế. HTTP không redirect/retry ngầm; timeout 5 giây, 401 xóa cache cho lần gọi sau. Response tối đa 64 KiB kiểm tra sau nhận byte[], chưa có streaming allocation cap; lỗi không nối body/cause/PII.

## Cổng và vận hành

- SHIPPING_DISPATCH_WORKER_ENABLED=false mặc định, tương ứng app.shipping.dispatch-worker-enabled. Khi tắt/thiếu OAuth, không seed/recover/call mạng; cấu hình có mặt không chứng minh credential hợp lệ.
- SHIPPING_DISPATCH_WORKER_DELAY_MS mặc định 5000; scheduler riêng không chiếm tick shipping event/polling.
- Commerce cần SHIPPING_HANDOFF_ENABLED và SHIPPING_DISPATCH_CLAIM_ENABLED được review trước bật; producer không backfill đơn đã CONFIRMED cũ.
- Muốn truyền ShipmentCreated/callback sang Commerce/Notification vẫn cần các transport/consumer và IAM đã triển khai; worker này không tự bật event/notification/email.

Trước mở worker, phải rà các kiện cũ sẽ được seed, provider sandbox/credential/tuyến thật, mọi ingress dispatch cũ/manual và quy trình cancel/return/release claim. Thay đổi này không thay policy refund/hủy hoặc giới hạn API legacy. Preflight là snapshot theo thời điểm; kết quả tài chính muộn vẫn có thể đặt hold sau response. Chưa có release claim/requeue job có audit nên chưa coi là nghiệm thu vận hành đầy đủ.

## Timeout, crash và recovery

CLAIMING hết lease chỉ retry API claim idempotent cùng key. Fencing token cũ không được chuyển DISPATCHING. Tối đa 8 lỗi trước dispatch (backoff 15–120 giây); 4xx nghiệp vụ trừ 401/429 vào REQUIRES_REVIEW ngay. Lỗi 503/timeout có thể retry cùng key; preflight 409 giữ claimId để đối soát và không gọi carrier.

DISPATCHING không bao giờ trở lại WAITING tự động. Exception sau dấu này vào REVIEW; crash/lease hết hạn cũng chỉ REVIEW, kể cả có thể chưa gửi carrier. Binary cũ còn chạy có thể vẫn đang hoàn tất request: REVIEW không chứng minh carrier chưa nhận hoặc request đã dừng. Không xóa/reset job/guard/claim hoặc tạo job khác để ép retry.

Đọc metadata nội bộ để đối soát, không xuất PII lên ticket:

```sql
SELECT id,order_id,parcel_id,claim_id,state,attempts,last_error_type,created_at,updated_at
FROM shipment_dispatch_jobs WHERE state<>'COMMITTED' ORDER BY created_at;
```

Đối chiếu theo orderId với shipment_dispatch_attempts/shipments và bằng chứng carrier. API manual-resolution hiện có chỉ phục hồi shipment/guard, chưa tự đóng/requeue job. Chưa có API cập nhật trạng thái job, release claim, metric/alert riêng hoặc SLA/retention; health event outbox không thay giám sát bảng này. Không kết luận cần gửi lại chỉ vì worker/claim timeout hoặc job đã REVIEW.

## Kiểm chứng

Fulfillment clean verify checkstyle:check đạt 223 test, 0 fail/error/skip; LINE 3137/3862 (81,23%). Mới 4 HTTP/OAuth wire tests và 8 PostgreSQL schema cô lập: mất ACK claim giữ key, hai worker, takeover lease/token cũ, marker commit qua kết nối observer riêng, crash không resend, preflight review, retry budget, default-off.

Commerce/ShippingService trong ca worker được mock tại boundary; callback mock lưu shipment fixture để kiểm tra commit/key, không gọi carrier. Guard/ShippingService có bộ test hiện hữu nhưng chưa chứng minh E2E mới qua JVM/IAM/provider thật. 19 test Node demo PASS; cả 7 service qua migration/seed replay/reset replay/reseed trên PostgreSQL tạm, các cluster đã dừng. Pipeline GitLab/sandbox/UAT chưa xác nhận.
