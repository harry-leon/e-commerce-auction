# TT-S3-06/08 — Đối soát job với shipment đã xác nhận

Kế thừa worker MR !174/V13; merge !173 → !174 trước phần này. V14 thêm shipment_dispatch_job_reconciliations, không backfill/tự đóng job. Dataset giữ Commerce 22/Fulfillment 14. API không gọi Commerce/carrier, không requeue/reset guard, sửa trạng thái tiền/kho, phát lại event hoặc giải phóng claim.

## API Admin

- GET /api/v1/shipments/dispatch-jobs/{jobId}: trả id/orderId/parcelId/claimId/state/shipmentId/attempts/lastErrorType/updatedAt. Không trả lease token, recipient/address/phone hoặc payload.
- POST cùng đường dẫn với /reconcile, header Idempotency-Key UUID, body {"evidenceReference":"CASE-123","note":"Đã đối chiếu shipment và guard"}. Actor chỉ lấy JWT subject UUID.

Cần role Admin; 401 thiếu JWT/subject UUID, 403 sai role, 400 key/body thiếu hoặc note/evidence rỗng/quá dài, 404 job lạ, 409 chưa đủ bằng chứng/key khác nội dung. Mới trả 201, replay cùng actor/key/job/nội dung trim trả 200 và audit cũ. New key cho job đã đóng bị 409, không tạo audit thành công giả. Evidence tối đa 200, note 500 ký tự; không nhập secret/token/PII. Chỉ trả các UUID/thời điểm audit, không trả ghi chú hoặc bằng chứng nội bộ. Gateway đã bao phủ /api/v1/shipments/**.

## Điều kiện và transaction

Job phải REQUIRES_REVIEW, có claimId. Khóa actor/key → job → lifecycle Order → guard/shipment. Chờ lifecycle lock giúp không bỏ qua transaction dispatch/recovery đang chạy; tuổi job/lease timeout không chứng minh request đã dừng.

Guard phải COMMITTED, tracking không trống và khớp shipment cùng Order. Snapshot guard phải khớp kiện (warehouse/carrier/service/weight/district/ward), recipient và giá trị đơn đã lưu trên shipment; COD=0, insurance dương. Không sửa hoặc điền thông tin thiếu bằng giá/địa chỉ hiện tại.

Shipment phải có key dispatch-job:{jobId}, hoặc có audit shipment_dispatch_resolutions đúng attempt/shipment/tracking. Với guard UNKNOWN hoặc snapshot/key/provenance không khớp, phải đối soát bằng workflow có bằng chứng trước, không ép status để vượt kiểm tra.

Audit và chuyển job COMMITTED cùng transaction; rollback không giữ audit/key giả. ClaimId giữ nguyên, không xóa/gia hạn hold. Không đổi shipment/guard payload/status hiện tại hoặc tạo thêm ShipmentCreated. COMMITTED chỉ xác nhận local job đã liên kết shipment có bằng chứng, không phải giao hàng thành công hay tài chính hiện tại hết review.

## Phạm vi recovery

Sau manual-resolution phục hồi guard/shipment, dùng API này để đóng job nếu tất cả dữ liệu khớp. Cũng hỗ trợ ACK/shipment đã commit bằng key job nhưng bước ghi job cuối cùng lỗi. Không hỗ trợ khẳng định NOT_SENT, giải phóng claim, requeue/gửi carrier mới, job chưa có claim hoặc job đang DISPATCHING. Không xóa audit, sửa key/row hoặc reset guard để thử lại.

API xác minh dữ liệu Fulfillment và audit nội bộ, không xác minh lại tiền/claim từ Commerce hoặc hỏi provider. Bằng chứng carrier ngoài hệ thống do operator đối chiếu; test fixture không phải nghiệm thu provider thật. Ingress cũ/binary đang chạy, release claim, cancel/return/refund và full E2E vẫn cần quy trình riêng trước bật worker. Đây không phải kho chứng từ chống sửa DB bằng chữ ký số.

## Kiểm chứng

Fulfillment clean verify checkstyle:check đạt 234 test, 0 fail/error/skip; JaCoCo LINE 3207/3932 (81,56%). Mới 8 ca PostgreSQL: manual recovery thật rồi đóng job/replay/outbox không trùng, hai kiểu concurrency, rollback, guard/snapshot/recipient lệch, key/audit nguồn, state/claim/input và chờ lifecycle lock. 3 MVC bảo vệ Admin/JWT actor/key, 201/200/GET metadata và 409 đúng envelope.

19 test Node demo đạt; migration/seed replay/reset replay/reseed cả 7 service qua trên PostgreSQL tạm, không dùng DB nhóm/.env/provider. Cluster tạm đã dừng. Pipeline GitLab/sandbox/UAT chưa xác nhận.
