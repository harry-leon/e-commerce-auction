# [LD-S2-1] Auction Engine — AC chốt, E2E scenarios và backlog SRS

## 1. Mục tiêu và phạm vi

Tài liệu này là baseline kiểm thử end-to-end cho luồng **Tạo phiên → Live Bid →
Anti-sniping → Settlement → Winner Order**, dựa trên SRS §3.5 và source hiện tại.
Nó chốt điều kiện pass/fail, dữ liệu cần quan sát và các dependency cần có trước
khi tự động hóa. Không xem UI/mock model là API contract.

### Trạng thái contract tại thời điểm rà soát

| Hạng mục | Trạng thái | Bằng chứng trong source | Hệ quả kiểm thử |
| --- | --- | --- | --- |
| Settlement | Có | `POST /api/v1/auctions/{auctionId}/settle`, `GET .../settlement` | Có thể kiểm thử API với auction seed sẵn. |
| Tạo/cập nhật/hủy/list/chi tiết auction | Chưa có public contract | `docs/dto-contracts/AUCTION_SERVICE.md` ghi `PLANNED` | Không thể dựng dữ liệu E2E qua Gateway. |
| Place bid và bid history | Chưa có controller/DTO | Chỉ có entity/repository `Bid` | Không thể kiểm thử bid qua API. |
| Live WebSocket/STOMP | Chưa có cấu hình/controller/message contract | Gateway mới route `/ws/auctions/**` | Không thể assertion realtime hoặc anti-sniping. |
| Winner order và payment timeout | Chưa tích hợp | `AuctionSettlement.orderId` không được gán; `CheckoutService` từ chối AUCTION | Không thể xác nhận order, thanh toán hay penalty E2E. |

**Kết luận:** đây là bộ kịch bản E2E chuẩn bị sẵn cho Sprint 3–4; hiện chỉ
`AUC-E2E-04` và một phần kiểm tra quyền của nó có thể chạy API-level với dữ liệu
được seed trực tiếp. Không tạo Postman collection giả định payload chưa được chốt.

## 2. Acceptance Criteria đã chốt

| ID | Acceptance criterion | Điều kiện đạt |
| --- | --- | --- |
| AC-01 | Tạo phiên hợp lệ | Seller đủ quyền tạo được auction `SCHEDULED`; SKU hợp lệ được reserve; trả về `201` và `auctionId`. |
| AC-02 | Kiểm soát tạo phiên | Từ chối product/SKU không hợp lệ, trùng active auction, giới hạn seller, giá/bước giá/thời gian sai; không tạo auction hoặc reservation dở dang. |
| AC-03 | Live bid nguyên tử | Chỉ Buyer đủ điều kiện bid trong `ACTIVE` và `[startTime,endTime)`; bid tối thiểu đúng; seller không bid phiên mình; idempotency key không tạo bản ghi thứ hai; cạnh tranh trả `409` hoặc kết quả nhất quán. |
| AC-04 | Realtime | Bid hợp lệ phát một event có `auctionId`, giá mới, bidder đã mask, số lượt bid, `endTime` và server time; subscriber nhận một lần theo thứ tự server chấp nhận. |
| AC-05 | Anti-sniping | Bid hợp lệ trong cửa sổ cấu hình gia hạn `endTime`, tăng `extensionCount`, phát event; ngoài cửa sổ hoặc quá giới hạn thì không đổi `endTime`. |
| AC-06 | Settlement | Tại/qua `endTime`, auction khóa bid; chọn bid cao nhất (hòa giá: bid sớm hơn); ghi đúng một settlement; success có winner/final price/deadline, không bid là `FAILED`. |
| AC-07 | Winner order | Settlement `SUCCESS` gửi một yêu cầu/event idempotent sang Commerce; tạo đúng một order `sourceType=AUCTION`, `auctionId`, buyer=winner, price=final price. Auction Service không tự tạo order bằng DB của Commerce. |
| AC-08 | Payment timeout | Quá deadline mà order chưa paid: đánh dấu settlement `DEFAULTED`, phát timeout event, release/điều chỉnh reservation và áp penalty theo contract; thao tác lặp lại không phát sinh duplicate. |
| AC-09 | Cancel/error | Cancel hợp lệ chặn bid/settlement, release reservation và audit actor/time/reason; admin cancel có quyền riêng; lỗi downstream phải retry/idempotent hoặc có outbox/DLQ quan sát được. |

## 3. Business rules map từ SRS §3.5

| Rule | Quy tắc chốt | Hiện có | Cần chốt/triển khai |
| --- | --- | --- | --- |
| BR-AUC-01 | Giá khởi điểm > 0; bước giá ≥ cấu hình (hiện cấu hình `1,000 VND`). | Config `app.auction.bidding.min-increment`. | Validation request và error contract. |
| BR-AUC-02 | Duration trong 60 phút–168 giờ; `endTime > startTime`. | SRS. | Validation request. |
| BR-AUC-03 | Mỗi seller tối đa số active auction theo config (source hiện là 20). | Config. | Query/count và định nghĩa chính xác active (`SCHEDULED`, `ACTIVE`). |
| BR-AUC-04 | Một product/SKU không có hơn một auction active; inventory reserve khi publish/create. | SRS/entity có SKU. | Contract với Catalog/Fulfillment và transaction/outbox. |
| BR-AUC-05 | Chỉ `ACTIVE` nhận bid, biên thời gian là `startTime ≤ bidTime < endTime`. | `canAcceptBid`. | Place-bid API phải gọi rule này trong transaction. |
| BR-AUC-06 | Seller không bid auction của mình; highest bidder không tự raise tiếp khi chưa bị vượt. | SRS. | Authorization/business validation. |
| BR-AUC-07 | Minimum bid là `startPrice` nếu chưa bid, hoặc `currentPrice + bidIncrement`; bid amount dùng decimal. | SRS/entity `BigDecimal`. | Validation và response lỗi 422. |
| BR-AUC-08 | Idempotency key là duy nhất; cạnh tranh không làm mất bid/ghi hai winner. | `Bid.idempotencyKey` unique; `Auction.version`. | Endpoint semantics, optimistic-lock retry/409. |
| BR-AUC-09 | Bid trong 120 giây cuối gia hạn theo cấu hình; phải có giới hạn số lần gia hạn. | Cửa sổ 120 giây có config. | Chưa có `maxExtensionCount` config/schema/logic — cần chốt. |
| BR-AUC-10 | End processing chỉ một lần; winner là valid bid cao nhất, hòa giá chọn `placedAt` sớm hơn. | Settlement chọn `amount DESC, placedAt ASC`; unique settlement. | Event/order integration. |
| BR-AUC-11 | Không bid: `FAILED`; bid hợp lệ: `SUCCESS`, deadline = settledAt + 24h. | Settlement service. | Deadline config theo SRS và timeout worker. |
| BR-AUC-12 | Settlement/order/payment timeout/cancel phải idempotent, audit được và không cho bid sau end/cancel. | Settlement idempotent và lock DB. | Cancel, payment timeout, audit, outbox/retry. |

## 4. Dữ liệu và quan sát chung

- Actor: `sellerA`, `buyerB`, `buyerC`, `admin`; product/SKU ACTIVE do `sellerA` sở hữu.
- Giá chuẩn: start `5,000,000`, increment `100,000` VND. Mọi timestamp dùng UTC và đồng hồ server.
- Mỗi case ghi lại request/correlation-id, auction row, bid rows, settlement row, reservation,
  domain event/outbox, Commerce order/payment và notification. Không dùng ID người dùng từ client
  làm bằng chứng ownership; server lấy principal.
- Môi trường cần Clock/scheduler có thể điều khiển hoặc time-travel test fixture; không dùng `sleep`
  nhiều phút để chờ hết hạn.

## 5. Kịch bản E2E

| ID | Luồng | Tiền điều kiện và bước chính | Expected result / assertion |
| --- | --- | --- | --- |
| AUC-E2E-01 | Happy path — create, live bid, winner order | 1) `sellerA` tạo/publish auction. 2) scheduler kích hoạt. 3) `buyerB` và `buyerC` subscribe. 4) B bid 5.0m, C bid 5.2m. 5) advance đến end. 6) settle/event handler tạo order. 7) `buyerC` thanh toán trước deadline. | Một reservation; trạng thái `SCHEDULED→ACTIVE→SETTLED`; hai bid hợp lệ và realtime event; winner=`buyerC`, final=5.2m; đúng một `AUCTION` order liên kết auction; payment paid; settlement/order event không duplicate. |
| AUC-E2E-02 | Anti-sniping | Auction ACTIVE, còn 75 giây, `extensionCount=0`. `buyerC` bid hợp lệ 6.0m; subscriber đang kết nối. Lặp lại ở ngoài cửa sổ và tại giới hạn extension. | End time tăng đúng policy, count tăng một, một `AUCTION_EXTENDED` event chứa end time mới. Ngoài cửa sổ/đạt limit: bid vẫn xử lý nếu hợp lệ nhưng end time/count không đổi. |
| AUC-E2E-03 | Bid invalid/concurrency | Thử seller self-bid, amount dưới min, bid khi scheduled/end/cancelled, gửi lại cùng idempotency key, rồi hai buyer submit đồng thời cùng amount. | Không có mutation với bid invalid; duplicate trả kết quả ban đầu và chỉ một bid; race có một winner DB, price/version nhất quán, request còn lại nhận conflict/retry contract. |
| AUC-E2E-04 | Settlement hiện có — success, failed và idempotency | Seed `ACTIVE` auction hết hạn có 2 bid khác giá; gọi settle hai lần. Lặp lại với auction hết hạn không bid. | Success chọn bid cao nhất, `paymentDeadline=settledAt+24h`, auction `SETTLED`, một row settlement. Lần hai trả settlement cũ. No-bid trả `FAILED`, winner/finalPrice/deadline null và auction `ENDED`. |
| AUC-E2E-05 | Timeout thanh toán | Sau E2E-01, advance qua deadline khi order chưa paid; chạy timeout worker/event consumer hai lần. | Chỉ một default transition/event; settlement `DEFAULTED`; reservation release/điều chỉnh đúng contract, penalty/audit/notification xuất hiện; không có charge/refund/order duplicate. |
| AUC-E2E-06 | Hủy và lỗi liên dịch vụ | Seller hủy khi eligible, admin force-cancel khi ACTIVE; thử bid/settle sau hủy. Mô phỏng Catalog/Fulfillment/Commerce timeout và gửi lại event. | Authorization đúng; `CANCELLED` chặn bid/settle; audit actor/time/reason và release reservation. Failure có trạng thái retryable/outbox-DLQ rõ ràng, retry không nhân đôi settlement/order. |

### API assertion khả dụng ngay cho AUC-E2E-04

| Request | Principal | Assertion |
| --- | --- | --- |
| `POST /api/v1/auctions/{auctionId}/settle` | `ROLE_ADMIN` hoặc `ROLE_SELLER` | `200`, envelope `success=true`; repeat trả cùng `settlement.id`. `ROLE_BUYER`/anonymous bị từ chối. |
| `GET /api/v1/auctions/{auctionId}/settlement` | authenticated user | `200`, đúng result/winner/final price/deadline; auction chưa settle trả `404`. |

`POST .../settle` chỉ hợp lệ khi auction đã `ENDED` hoặc `ACTIVE` có `now >= endTime`;
không dùng endpoint này để bypass lifecycle trong test happy-path.

## 6. Backlog SRS cho Sprint 3–4 (Definition of Ready)

| Priority | Backlog item | Mô tả DoR / dependency phải chốt trước khi implement |
| --- | --- | --- |
| P0 | AUC-API-01 Auction lifecycle API | OpenAPI cho create/update/publish/cancel/list/detail; request/response, pagination/filter, role/ownership, validation/error code và transition matrix. |
| P0 | AUC-BID-02 Atomic place bid | REST/STOMP command contract, idempotency key behavior, locking/retry/409, bid history visibility và error payload. |
| P0 | AUC-RT-03 Live event protocol | STOMP authentication/authorization, destinations, snapshot + event schema/versioning, ordering/reconnect/resync và backpressure. |
| P0 | AUC-ANTI-04 Anti-sniping policy | Chốt extension amount vs window, inclusive boundary, `maxExtensionCount`, schema/config, event payload và UI/server clock rule. |
| P0 | AUC-ORD-05 Settlement-to-Commerce | Chốt event/API ownership, idempotency/correlation key, snapshot payload, order `AUCTION` creation, inventory commit/release và outbox/retry/DLQ. |
| P0 | AUC-PAY-06 Winner payment deadline | Payment deadline config, source of paid signal, default state transition, penalty owner/rule, notification and inventory handling, retry/audit. |
| P1 | AUC-CAN-07 Cancel and audit | Seller/admin eligibility matrix, reason code, refund/reservation effects, immutable audit schema, event and idempotency. |
| P1 | AUC-SEC-08 Eligibility/visibility | Reputation lookup/failure policy, restricted auction membership, seller/bidder constraints, IDOR tests and rate limiting. |
| P1 | AUC-OPS-09 Observability | Metrics/traces (bid latency, conflicts, extension, settlement lag, outbox failures), alert thresholds, reconciliation job/runbook. |
| P2 | AUC-NOT-10 Notifications | Template/key contract for started/outbid/extended/won/lost/payment due/default/cancel; delivery preference and failure handling. |

### DoR chung cho từng backlog item

1. Owner service, sequence diagram và authoritative data owner được chỉ định.
2. Versioned API/event contract được Tiến Thương và Quốc Hùng phê duyệt; có example success/failure.
3. State transition, authorization, idempotency/correlation và timeout/retry đã xác định.
4. Flyway migration mới (nếu cần), rollback/reconciliation impact và test data strategy được review.
5. AC Gherkin và E2E scenario tương ứng ở mục 5 có thể tự động hóa không cần seed DB xuyên service.

## 7. Ma trận truy vết

| SRS §3.5 | AC | E2E |
| --- | --- | --- |
| Create/configure/lifecycle/eligibility | AC-01, AC-02 | AUC-E2E-01, 03 |
| Live bidding/bid history | AC-03, AC-04 | AUC-E2E-01, 03 |
| Anti-sniping | AC-05 | AUC-E2E-02 |
| End/winner/settlement | AC-06 | AUC-E2E-04 |
| Commerce handoff/winner order | AC-07 | AUC-E2E-01 |
| Payment deadline | AC-08 | AUC-E2E-05 |
| Cancel/consistency/idempotency/events | AC-09 | AUC-E2E-03, 06 |
