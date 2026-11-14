# Thứ tự triển khai Sprint 3 — Tiến Thương

Phạm vi theo phân công 16–27/11/2026 trong PROJECT_PLAN_SPRINT2_TO_4.md. Đây là kế hoạch dựa trên source đã đọc, chưa phải kết quả nghiệm thu provider. Mã TT-S3 dưới đây là mã đề xuất, chưa phải issue GitLab đã tạo.

## 1. Hiện trạng cần giữ lại

| Phần | Bằng chứng source | Kết luận |
| --- | --- | --- |
| Payment VNPAY | PaymentService, VnpayGateway, PaymentInventoryWorker | Có tạo URL, xác minh IPN, idempotency và xử lý kết quả kho; không xây lại |
| Hủy/hoàn tiền | OrderServiceImpl từ chối REFUNDED và hủy PAID khi chưa có refund provider | Cần thêm luồng refund thực; không bỏ hàng rào hiện có để báo hoàn tiền giả |
| Notification | NotificationOutbox, NotificationWorker, CommerceNotificationController | Có outbox HTTP, retry, inbox/email và push STOMP; cần mở rộng contract/transport, không thay bằng broker ngay lập tức |
| Shipping | ShippingController, ShipmentController, ShippingCarrierAdapter, GHN/GHTK adapters | Có nền quote/create/tracking/webhook; phối hợp Khang về contract và trạng thái, chưa xác minh carrier sandbox trong lần rà này |
| Event shipping | ShippingEventListener dùng @EventListener | Là event trong process Spring; riêng listener này không chứng minh đã có truyền event bền vững giữa các service |
| Invoice/receipt | Chưa tìm thấy implementation tương ứng trong Commerce khi rà source | Cần chốt receipt nghiệp vụ hay hóa đơn điện tử; không gọi receipt là hóa đơn thuế |
| Đối soát payment/refund và broker health | Chưa tìm thấy luồng hoàn chỉnh trong phạm vi đã rà | Cần triển khai và có bằng chứng riêng; không dùng inventory reconcile thay cho đối soát tiền |

## 2. Thứ tự làm

| Thứ tự / mã đề xuất | Thời gian | Công việc của Thương | Phụ thuộc | Điều kiện hoàn thành |
| --- | --- | --- | --- | --- |
| 1 — TT-S3-01 | 16/11 | Chốt state machine hủy/refund và API/event contract với Đang, Khang | Hiện trạng Order/Payment/Shipment | Có bảng trạng thái, actor, lỗi, amount, idempotency, owner dữ liệu và mapping shipping; xác định rõ hủy trước/sau giao carrier |
| 2 — TT-S3-02 | 16–17/11 | Chuẩn bị cấu hình provider, endpoint callback và bộ fixture contract | 01 | Có danh sách biến môi trường không chứa giá trị secret, URL callback/return đúng vai trò, timeout, cách xác minh callback; smoke sandbox hoặc ghi blocker và owner |
| 3 — TT-S3-03 | 18–19/11 | Refund domain, migration mới và API tạo/tra cứu yêu cầu refund | 01; adapter contract từ 02 | Có quyền/ownership, giới hạn số tiền, khóa transaction và idempotency; replay cùng yêu cầu trả bản ghi cũ, không phát thêm lần hoàn |
| 4 — TT-S3-04 | 20–23/11 | Refund provider worker, đối soát và recovery; mở hủy đơn đã trả theo policy đã chốt | 02, 03; contract hủy shipment | Timeout sau khi gửi giữ trạng thái chưa rõ và query/đối soát trước retry; chỉ REFUNDED khi provider xác nhận; callback lặp/đảo thứ tự không hoàn hai lần |
| 5 — TT-S3-05 | 18–23/11 | Chuẩn hóa event envelope, notification transport và health | 01; quyết định broker trước khi code adapter | Có eventId/type/version/aggregateId/occurredAt/correlationId, inbox dedup, retry và xử lý poison event; health phản ánh đúng transport đang dùng; giữ HTTP outbox cho đến khi broker được kiểm chứng |
| 6 — TT-S3-06 | 24–25/11 | Nối Commerce–Shipping–Notification và mapping trạng thái | 04, 05; API shipping của Khang | Payment thành công tạo shipment một lần; callback shipment cập nhật Order đúng chiều, phát thông báo một lần; hủy/refund không tự restock khi hàng chưa được xác nhận trả |
| 7 — TT-S3-07 | 24–26/11 | Receipt/invoice theo phạm vi BA chốt, API tải và phân quyền | 01, 04; snapshot giá đã có | Receipt lấy snapshot đã chốt, truy cập đúng chủ đơn/quyền quản trị; replay không sinh chứng từ trùng; refund có liên kết điều chỉnh, không sửa chứng từ gốc âm thầm |
| 8 — TT-S3-08 | 26–27/11 | Regression, recovery, UAT và bàn giao | 06, 07 | Có pass/fail/blocked cho mua thường và đấu giá; test callback trùng, mất ACK, restart, provider outage, sai quyền; pipeline và bằng chứng sandbox tách biệt với mock |

Không tính 21–22/11 là ngày làm việc mặc định. Ngày 23/11 là phần kết thúc gói 18–23/11, không phải yêu cầu làm cuối tuần. Các mốc là kế hoạch cần điều chỉnh theo capacity và blocker provider, chưa phải cam kết estimate đã được nhóm duyệt.

## 3. Chốt contract trước khi triển khai

TT-S3-01 cần trả lời đủ các quyết định sau:

- Hủy đơn: ai được yêu cầu, trạng thái nào cho phép, xử lý COD đã thu tiền, shipment đã tạo/đã bàn giao và phí vận chuyển.
- Refund: toàn phần hay một phần; số tiền đã thu/đã hoàn/đang hoàn; trạng thái đề xuất REQUESTED → PROCESSING → SUCCEEDED/FAILED, cùng UNKNOWN/REQUIRES_REVIEW cho kết quả chưa rõ. Chốt tên trạng thái với BA trước migration.
- Idempotency: key gắn với caller/order/payment và nội dung yêu cầu; cùng key nhưng amount khác trả conflict. Không giữ transaction DB trong lúc gọi provider.
- Kết quả tài chính: ai xác nhận, callback hay query provider, trạng thái terminal nào được chuyển tiếp; không coi URL return hoặc HTTP timeout là kết quả hoàn tiền.
- Event: producer/consumer, schema version, dedup key, ordering theo aggregate, retry/backoff, dead letter/replay và log không chứa secret.
- Shipping: điểm bắt đầu shipment cho VNPAY và COD, callback đã xác minh, trạng thái được phép cập nhật Order, cơ chế cancel và xác nhận trả hàng với kho.
- Receipt/invoice: receipt nội bộ hay tích hợp nhà cung cấp hóa đơn; bên nào sở hữu số chứng từ, lưu snapshot và điều chỉnh khi refund.

Không đoán thông số refund từ API thanh toán VNPAY hiện có. Khi triển khai TT-S3-02/04 phải lấy tài liệu chính thức đúng merchant/API được cấp và xác minh quyền refund/query trên sandbox; secret chỉ đặt trong môi trường hoặc kho secret.

## 4. Tách việc và review

- Thương: Commerce payment/refund/reconciliation, transport/event contract, cấu hình và bằng chứng tích hợp.
- Khang: carrier adapter, shipment callback/mapping, warehouse/return; phối hợp thống nhất API, không làm lại module song song.
- Hùng: quyền service và tác động reputation/penalty của hủy/refund theo quy tắc BA chốt.
- Đang: AC hủy/refund/chứng từ, UI Admin và UAT; Hào: trạng thái Buyer, inbox/push/reconnect theo API đã chốt.

Tách MR theo thành phần và ghi thứ tự phụ thuộc: contract/docs → Commerce refund schema/API → worker/provider → Notification transport → tích hợp shipping → receipt → regression. MR Auction/Payment cần hai approval theo CONTRIBUTING.md; không push trực tiếp dev/main và không merge khi chỉ test mock pass.

## 5. Việc bắt đầu ngay

Đã chuẩn bị [contract TT-S3-01/02](TT_S3_REFUND_INTEGRATION_CONTRACT.md), RefundStatus/RefundPolicy, migration V13 và API tiếp nhận/tra cứu yêu cầu. Thiết kế provider vẫn chờ review; chưa có worker gọi provider và chưa thay đổi luồng hủy đơn hiện hành.

Làm TT-S3-01 trước: tạo bảng contract hủy/refund/events với request/response, quyền, state transition và AC lỗi. Có thể chuẩn bị fixture/provider checklist của TT-S3-02 đồng thời; chưa gọi refund thật hoặc đổi trạng thái PAID/REFUNDED trước khi có contract và adapter đã kiểm chứng.

Phần nền V13/API tiếp nhận đã có MR !138. Phần làm tiếp ở local gồm quyền Shipping, codec/HTTP Merchant API mặc định tắt, V14/worker/approval/recovery và V15/đối soát thủ công/audit/receipt registry/hold; có test MVC/PostgreSQL/mock. Chưa nghiệm thu provider sandbox, chưa bật dispatch lên môi trường đang dùng, chưa nối workflow hủy đơn đã thu tiền/notification/invoice. Các bước 5–8 vẫn cần triển khai và UAT.

Cập nhật phần đã tách review: !142 quyền Shipping, !143 Merchant HTTP, !144 refund worker/đối soát thủ công, !145 Notification refund consumer/health. Producer Commerce V16 ghi outbox refund và giao inbox/push đang ở nhánh riêng; xem [contract, giới hạn và thứ tự triển khai](TT_S3_REFUND_NOTIFICATION_OUTBOX.md). Có MR/test local không đồng nghĩa đã merge, pipeline xanh hoặc nghiệm thu sandbox. Bước 5 còn replay vận hành/email/envelope chung; bước 6–8 và chốt policy với BA vẫn chưa hoàn tất.

## 6. Phạm vi tiếp tục — 13/11/2026

Giữ đúng phân công Tiến Thương: contract shipping/payment/refund/events; chuẩn bị provider/callback; hủy/refund/chống trùng/đối soát; notification transport/push/health; nối Commerce–Shipping–Notification; invoice/receipt, quyền/recovery và hỗ trợ test toàn luồng. Không coi mỗi thay đổi kỹ thuật hoặc mỗi MR là một task phân công mới.

- MR !164 bổ sung kiểm chứng Shipping bằng Keycloak/PostgreSQL/ba JVM cô lập, mất ACK/replay/restart; dependency !156–!163 vẫn cần review/merge. Không thay thế carrier sandbox hoặc UAT.
- Tiếp TT-S3-07 bằng [API tải biên nhận thu tiền nội bộ](TT_S3_PAYMENT_RECEIPT.md) từ snapshot Payment SUCCESS; không tạo lại module Payment. Đây là phần đầu receipt, chưa hoàn tất hóa đơn/registry phát hành/điều chỉnh refund hoặc policy BA.
- Các mục chưa được nghiệm thu vẫn phải giữ trạng thái riêng: merchant/carrier sandbox và callback ingress thật; workflow hủy đã thu tiền theo policy BA; tự động tạo shipment sau xác nhận tiền/kho; vận hành IAM/alert/push reconnect; chứng từ điều chỉnh và UAT/pipeline. Chỉ đánh dấu hoàn tất khi có bằng chứng tương ứng, không suy ra từ unit/mock hoặc MR đã mở.
