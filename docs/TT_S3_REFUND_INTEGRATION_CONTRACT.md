# TT-S3-01/02 — Contract hủy, refund và tích hợp

Trạng thái: bản thiết kế để triển khai/review, chưa phải contract đã được BA và provider duyệt. Đã có RefundStatus/RefundPolicy, migration V13–V15, API tạo/đọc/duyệt và đối soát thủ công. Worker/codec/HTTP mặc định tắt, chỉ test fixture; chưa có refund sandbox thật, callback refund hoặc chứng từ. Chi tiết [worker/recovery](TT_S3_REFUND_WORKER.md) và [manual resolution](TT_S3_REFUND_MANUAL_RESOLUTION.md).

## 1. Ranh giới dữ liệu

Commerce sở hữu Order, Payment, Refund, hạn mức và chứng từ. Fulfillment sở hữu Shipment, carrier callback, allocation/return/ledger. Notification sở hữu inbox, delivery attempt, preference và transport. Service gọi API/event, không đọc DB của nhau.

Payment SUCCESS là bằng chứng tiền đã nhận, không đồng nghĩa Order PAID: worker còn phải xác nhận kho. Refund SUCCEEDED là kết quả hoàn tiền riêng, không sửa Payment SUCCESS thành FAILED. Hủy shipment, hoàn tiền và nhập hàng trả là ba tác vụ riêng; không tự restock từ callback refund.

## 2. Hủy đơn — giữ hành vi hiện tại cho đến khi luồng mới đủ an toàn

| Điều kiện | Xử lý |
| --- | --- |
| PENDING_PAYMENT, chưa có giao dịch có thể thu tiền | Buyer sở hữu đơn/Admin hủy bằng API hiện có; history và RELEASE outbox cùng transaction |
| Payment PENDING/SUCCESS/REVIEW hoặc inventory REVIEW | Không hủy như đơn chưa trả; tra cứu/đối soát trước |
| PAID hoặc COD đã ghi nhận thu tiền | API hiện tại tiếp tục từ chối; chỉ mở sau khi có workflow hủy/refund đầy đủ |
| Shipment đã giao carrier hoặc hàng đã giao | Không hủy đồng bộ; chuyển yêu cầu return/dispute và xác nhận nghiệp vụ riêng |
| CANCELLED/REFUNDED lặp | Trả trạng thái hiện tại sau kiểm tra quyền, không tạo history/hoàn kho lần hai |

Policy đề xuất cho bản đầu: refund VNPAY toàn phần, do Admin khởi tạo sau khi xác minh điều kiện hủy/return. Buyer/Seller chưa được tự gửi lệnh hoàn tiền. Refund COD, hoàn từng phần và phí carrier cần BA/provider quyết định riêng, không giả lập thành công. RefundPolicy kiểm tra hạn mức chung, không tự cấp quyền hoặc bật hoàn từng phần.

## 3. Refund state machine

| Trạng thái | Ý nghĩa | Chuyển tiếp hợp lệ |
| --- | --- | --- |
| REQUESTED | Yêu cầu được lưu và giữ hạn mức, chưa gửi provider | PROCESSING hoặc REQUIRES_REVIEW |
| PROCESSING | Worker đã nhận lệnh và có thể đã gửi provider | SUCCEEDED, FAILED, UNKNOWN hoặc REQUIRES_REVIEW |
| UNKNOWN | Không biết kết quả sau timeout/mất ACK | SUCCEEDED/FAILED sau query đã xác minh, hoặc REQUIRES_REVIEW |
| REQUIRES_REVIEW | Xung đột kết quả/cần vận hành đối soát | SUCCEEDED hoặc FAILED với bằng chứng và audit |
| FAILED | Provider xác nhận không hoàn tiền, không chỉ là HTTP lỗi | REQUIRES_REVIEW nếu nhận bằng chứng trái ngược muộn |
| SUCCEEDED | Provider xác nhận đã hoàn tiền | Không lùi trạng thái |

Replay cùng trạng thái hợp lệ nhưng caller không được phát lại side effect. UNKNOWN không quay lại PROCESSING để gửi mù lần nữa. FAILED nhận thông tin thành công muộn phải vào REVIEW, chưa tự cập nhật Order. REVIEW chỉ được kết thúc bằng kết quả provider/audit đã xác minh.

Hạn mức: `requested > 0` và `succeeded + reserved + requested <= captured`, tất cả VND nguyên, không làm tròn. Reserved bao gồm REQUESTED/PROCESSING/UNKNOWN và REVIEW còn có thể hoàn tiền. Nếu REVIEW phát sinh sau FAILED, phải khóa payment, tính lại tổng và đóng băng yêu cầu mới; không tự reserve vượt captured. Số tổng lấy từ DB dưới khóa payment, không nhận từ client. Giữ unresolved refund trong hạn mức cho đến khi đối soát xong.

## 4. API tiếp nhận và phần đề xuất tiếp theo

Mọi response sử dụng envelope/error hiện có của common. API mới cần cập nhật OpenAPI và Gateway trước khi mở cho frontend.

| Endpoint | Caller | Đầu vào/kết quả |
| --- | --- | --- |
| POST /api/v1/refunds | ROLE_ADMIN, hiện thực tại controller và service | Body paymentId, reason; Idempotency-Key UUID bắt buộc; amount toàn phần do server tính; lần đầu 201, replay 200; chỉ lưu REQUESTED, chưa thực hiện hoàn tiền |
| POST /api/v1/refunds/{id}/manual-resolution | ROLE_ADMIN ở controller/service | Chỉ UNKNOWN/REVIEW, bắt buộc key, snapshot tiền/ref và bằng chứng; audit/receipt/status cùng transaction, 201/200 replay; không gửi tiền |
| GET /api/v1/refunds/{id} | Buyer chủ đơn hoặc Admin | Trạng thái, amount/currency, timestamp; không trả lý do nội bộ, token, chữ ký hoặc raw provider payload |
| POST /api/v1/internal/refunds/{id}/reconcile (chưa triển khai) | Service identity được allowlist + quyền reconcile riêng | Xếp lịch query bằng refundId đã lưu, không nhận status/amount tùy ý từ caller |

Unique key gồm principal khởi tạo và idempotency UUID; replay so paymentId và reason đã trim. Amount do server chốt ở lần đầu. Cùng key khác nội dung trả 409. Transaction khóa key trước đọc replay, sau đó khóa Order → Payment để kiểm tra hạn mức. Không giữ transaction DB khi gọi provider trong worker tương lai.

API dùng ApiException/envelope hiện có: REFUND_REQUEST_INVALID (400), REFUND_NOT_FOUND (404), REFUND_KEY_CONFLICT, REFUND_PAYMENT_NOT_SETTLED, REFUND_REQUIRES_REVIEW và REFUND_AMOUNT_EXCEEDED (409). Sai role bị Security trả 403. REFUND_PROVIDER_NOT_CONFIGURED (503) dành cho dispatch tương lai, chưa dùng. READ ngoài ownership trả 404. Payment DEMO_ONLY, chưa thu tiền hoặc có REVIEW không được tiếp nhận tự động.

Kiểm tra local: 36 test trong RefundPolicyTest, RefundControllerTest và PaymentPostgresTest pass, không skip; PostgreSQL 18 tạm riêng, migration V13 chạy thật. Checkstyle pass. Provider/kho/notification ở biên test là mock, không phải giao dịch sandbox refund thật. Fixture test payment cũ được bổ sung tồn kho mock vì Checkout hiện kiểm tra tồn trước reserve.

Kiểm tra trước MR: Commerce `verify checkstyle:check` đạt 396 test, không skip, line coverage 90,63%. Baseline migration được cập nhật kỳ vọng V13 và kiểm tra không sinh refund từ dữ liệu cũ. Gateway test và 19 test fixture đạt; seed/reset/reseed cả 7 service được kiểm tra trên cluster tạm riêng. Pipeline GitLab và provider refund vẫn chưa được xác minh.

## 5. Persistence, worker và recovery đề xuất

V13 tạo refund_requests; V14 thêm approval/attempt/query audit, requestId ổn định và lease recovery. Không sửa/backfill migration cũ. REQUESTED chỉ dispatch khi được Admin duyệt riêng và hai feature flag đã bật; không tự hủy đơn, release kho hoặc phát event thành công. Chưa có inbox refund callback vì chưa chốt contract callback riêng với provider.

Luồng: transaction tạo REQUESTED + giữ hạn mức + task → worker claim bằng lease/khóa ngắn → commit PROCESSING → gửi provider ngoài transaction → transaction nhận kết quả + history + notification outbox. Nếu worker chết sau claim hoặc sau gửi, lease hết hạn chuyển vào đối soát, không coi task chưa done là quyền gửi lại lệnh.

Callback/query phải kiểm tra nguồn/chữ ký, merchant, request reference, payment reference, amount và currency trước side effect. Result trùng được ACK mà không phát event lần hai. Cùng provider reference gắn hai refund phải vào REVIEW. Lỗi DB rollback không được ACK thành công. Nguyên tắc retry thực tế phụ thuộc bảo đảm idempotency/query của provider đã được cấp.

Order REFUNDED chỉ cập nhật khi refund toàn phần SUCCEEDED và điều kiện kết thúc nghiệp vụ hủy/return đã được xác nhận. Không hồi sinh đơn CANCELLED/COMPLETED bằng callback. Nếu workflow yêu cầu đổi terminal state, phải sửa state machine và test bằng migration/API riêng, không gán enum trực tiếp để vượt validation hiện có.

## 6. Shipping và event contract

`POST /api/v1/shipments/events/order-paid` bắt buộc ServiceIdentity với azp `nexus-commerce-service` và privilege `SHIPPING.ORDER_PAID`. Token Buyer/Admin, service khác hoặc chỉ có INVENTORY.ORDER đều bị chặn. Đây là lệnh nội bộ qua HTTP, không phải callback public của carrier. Realm import đã khai báo role/scope; IAM đang chạy cần người vận hành cập nhật mapping và lấy token mới, không xóa realm. Chưa có producer Commerce gửi lệnh này tự động.

### Ma trận quyền kỹ thuật đang áp dụng

| Thao tác | Người dùng | Service | Giới hạn |
| --- | --- | --- | --- |
| Hủy đơn hiện hành | Buyer sở hữu đơn/Admin | Không mở quyền service mới | Không bypass kiểm tra payment đang chờ/đã thu |
| Tạo refund REQUESTED | Admin | Không cấp cho Commerce service account | Không thực hiện hoàn tiền |
| Đọc refund | Chủ đơn/Admin | Không cấp quyền đọc đại trà | Ngoài phạm vi trả 404 |
| Tạo shipment từ OrderPaid | Không nhận Buyer/Seller/Admin | azp Commerce + SHIPPING.ORDER_PAID | Tạo idempotent bởi ShippingService, chưa có transport event bền vững |
| Mutation kho đơn | Không nhận JWT người dùng | azp Commerce + INVENTORY.ORDER | Không dùng quyền kho thay quyền shipping |
| Notification HTTP hiện tại | Không mở thêm quyền người dùng | NOTIFICATION_WRITER theo endpoint hiện hành | Chưa chuẩn hóa thành service identity + event envelope mới |

OrderStatusStateMachine thuần còn có nhánh PAID → REFUNDED cho các role lịch sử, nhưng OrderServiceImpl đang từ chối cập nhật REFUNDED vì chưa có provider. Không được gọi state machine thuần như bằng chứng rằng Buyer/Seller đã có API hoàn tiền. Worker tương lai phải có transition riêng với bằng chứng provider, audit và điều kiện return; chưa mở transition terminal state trong thay đổi này.

Không phát sự kiện có thể dẫn tới xuất kho chỉ dựa vào Payment SUCCESS. Với VNPAY, phát sau Order PAID và inventory CONFIRMED; với COD cần chốt mốc riêng với Khang/BA vì trình tự tạo shipment và ghi nhận thu tiền có thể khác.

Envelope v1 đề xuất: eventId UUID, eventType, schemaVersion=1, aggregateType, aggregateId, aggregateVersion, occurredAt UTC, correlationId, causationId và payload. EventId cố định khi retry; consumer unique theo consumer/eventId, so khớp nội dung khi replay. Không dùng timestamp để bảo đảm thứ tự; callback cũ không được làm lùi trạng thái. Schema mới cần adapter, không đổi trực tiếp body notification HTTP hiện tại.

Danh sách sự kiện cần chốt producer/consumer: OrderReadyForShipment (Commerce → Fulfillment), ShipmentStatusChanged (Fulfillment → Commerce/Notification), RefundRequested/RefundSucceeded/RefundRequiresReview (Commerce → Notification), ReceiptIssued (Commerce → Notification). Đây là tên đề xuất, chưa có producer/consumer chạy thật cho các event mới này.

## 7. Notification, push và health

Giữ HTTP outbox đang có cho đến khi broker mới được kiểm chứng. Notification hiện dùng simple broker trong RAM, không coi là broadcast bền vững nhiều instance. Inbox phải lưu trước push; client reconnect lấy lại inbox, push không thay dữ liệu đã lưu.

Nếu chọn broker ngoài: cần outbox producer, consumer inbox, ACK sau commit, retry/backoff hữu hạn và dead letter/replay có audit. Readiness phản ánh dependency bắt buộc; liveness không fail chỉ vì provider/broker gián đoạn. Health không trả secret hoặc URI chứa credential; trạng thái degraded phải hiện rõ backlog/attempt, không giả UP khi adapter bắt buộc chưa cấu hình.

## 8. Checklist provider/callback — TT-S3-02

| Mục | Trạng thái / đầu ra cần có |
| --- | --- |
| Merchant VNPAY payment | Có adapter payment; chưa chứng minh merchant có quyền refund/query |
| Refund/query API | Cần tài liệu chính thức đúng phiên bản và quyền merchant, quy tắc retry/idempotency, mã kết quả/settlement |
| Carrier GHN/GHTK | Có adapter; Khang xác nhận sandbox/credential/cancel/query thực và mapping trạng thái |
| Callback ingress | Chốt public HTTPS URL, route/method, xác minh nguồn, body limit, replay policy và log đã lọc |
| Callback refund | Chỉ tạo endpoint nếu provider thực sự hỗ trợ hợp đồng đó; không dùng IPN payment như refund callback |
| Secrets | Tên biến, owner và môi trường được ghi trong tài liệu; giá trị chỉ ở env/secret store, không ở fixture/MR |
| Receipt | BA chốt receipt nội bộ hay hóa đơn điện tử; không mặc định là hóa đơn thuế |
| Sandbox evidence | Một giao dịch đã thu, hoàn hợp lệ, query kết quả, replay/timeout; mock phải có nhãn riêng |

Không ghi tên biến refund provider cụ thể hoặc cấu hình endpoint mặc định trước khi có tài liệu được xác minh. Không cần gửi secret trong chat.

## 9. Acceptance cho các MR tiếp theo

- Hai lệnh refund đồng thời không cùng chiếm một hạn mức; cùng key/body replay một kết quả, key khác body trả conflict.
- Sai quyền/ownership, chữ ký/merchant/amount/reference sai không được thay đổi dữ liệu.
- Timeout sau provider nhận lệnh và worker restart phải query/đối soát, không gửi lặp mù.
- Callback trùng/đảo thứ tự không nhân đôi refund, receipt hoặc notification.
- Refund thành công không tự release/restock hàng đã CONFIRMED/SHIPPED.
- Broker/SMTP/carrier outage giữ công việc bền vững và phục hồi đúng một kết quả nghiệp vụ.
- Receipt truy cập đúng người, lấy snapshot đã chốt và có liên kết điều chỉnh sau refund.

Đã có unit test RefundPolicy, MVC test phân quyền/response và PostgreSQL test replay, xung đột key, ownership, hai yêu cầu đồng thời cho cùng payment. Payment/Order không được ghi REFUNDED bởi API tiếp nhận. Chưa có test refund provider hoặc recovery worker vì các phần này chưa triển khai.
