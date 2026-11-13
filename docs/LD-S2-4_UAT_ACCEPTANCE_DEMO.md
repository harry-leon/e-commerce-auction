# [LD-S2-4] UAT, Báo cáo nghiệm thu và Kịch bản Demo Sprint 2

Ngày chốt hồ sơ: 13/11/2026
Trạng thái: **Chưa đủ điều kiện nghiệm thu UAT / chưa thể đóng task**

## 1. Kết luận nghiệm thu

Hồ sơ và kịch bản dưới đây đã sẵn sàng để chạy trên môi trường tích hợp. Chưa thể xác nhận DoD “UAT toàn luồng qua API Gateway với dữ liệu thật” hoặc “đóng 100% bug Sprint 2”: Docker Engine trong phiên hiện tại không truy cập được; Gateway local chỉ trả lời một phần smoke anonymous, không có actor/credential UAT; và chưa có kết nối GitLab để đọc danh sách issue / ghi kết quả retest.

Không được diễn giải test unit/service hoặc smoke ẩn danh thành UAT dữ liệu thật. Cần chạy các bước ở mục 3 trong môi trường tích hợp, ghi evidence, và đồng bộ trạng thái ticket GitLab trước khi ký nghiệm thu.

| Tiêu chí | Kết quả hiện có | Trạng thái |
| --- | --- | --- |
| API Gateway, web và public auction route | Smoke Compose 6/6 pass lúc `2026-11-13T03:17:17Z`; xem [readiness.json](test-evidence/tt-s2-4/readiness.json) | Pass smoke, không phải UAT |
| Kiểm tra anonymous trong phiên hiện tại | `GET /actuator/health` 200, `GET /api/v1/auctions` 200, `GET /api/v1/orders` 401, web 200; `GET /api/v1/products` không kết nối được | Smoke một phần, chưa xác nhận dependency/dữ liệu |
| Replay không tạo Bid/Order trùng | Có test service-level cho handover/order replay; chưa có kết quả chạy trong phiên UAT này và chưa có bằng chứng từ môi trường tích hợp | Chưa xác nhận UAT |
| Kho được giữ trong phiên và chuyển đúng khi handover | Có test Fulfillment/Auction service-level; không có quan sát xuyên service trong phiên này | Chưa xác nhận UAT |
| Toàn luồng Auction qua Gateway bằng dữ liệu thật | Không chạy được do môi trường không truy cập | Chưa đạt / blocked |
| Log và retest toàn bộ bug Sprint 2 trên GitLab | Không truy cập được GitLab issue list | Chưa xác nhận; không thể tuyên bố đóng 100% |
| Hướng dẫn sử dụng, nghiệm thu và demo | Tài liệu tham chiếu có sẵn; checklist và kịch bản demo được tập hợp tại hồ sơ này | Chuẩn bị xong, chờ evidence thực tế |

## 2. Hồ sơ tham chiếu và giới hạn

- Kịch bản gốc: [LD-S2-1 Auction E2E](LD-S2-1_AUCTION_E2E_AC_SRS_BACKLOG.md).
- Hướng dẫn khởi chạy/API: [RUN_AND_TEST_GUIDE.md](RUN_AND_TEST_GUIDE.md).
- Data demo và giới hạn fixture: [DEMO_DATA.md](DEMO_DATA.md). Dataset demo là dữ liệu giả lập, không phải dữ liệu khách hàng hay giao dịch thật.
- Kết quả tích hợp gần nhất: [TT-S2-4_INTEGRATION_REVIEW.md](TT-S2-4_INTEGRATION_REVIEW.md), [TT-S2-3_WINNER_PAYMENT.md](TT-S2-3_WINNER_PAYMENT.md).
- Báo cáo LD-S2-1 mô tả contract tại thời điểm cũ, rằng create/bid/winner handover chưa có. Source hiện đã có test handover và payment; do đó phải đối chiếu API/deployment hiện hành trước khi chọn request, không dùng mô tả cũ làm bằng chứng UAT.
- TT-S2-4 ghi rõ chưa chạy ma trận role, auction bid/anti-sniping, handover, payment, timeout/recovery, logs và fixture trên môi trường UAT. Smoke 6/6 chỉ xác nhận web, health Gateway, route public, bảo vệ orders với anonymous và từ chối STOMP anonymous.

## 3. Hướng dẫn sử dụng theo vai trò

### Seller

1. Đăng nhập bằng tài khoản Seller trên web, mở khu vực quản lý Auction.
2. Tạo phiên bằng SKU đang được phép bán, nhập giá mở đầu và khoảng thời gian hợp lệ; xác nhận thông tin trước khi lưu.
3. Kiểm tra phiên xuất hiện đúng trạng thái và kho đã giữ cho phiên. Chỉ sửa khi phiên chưa mở; hủy theo điều kiện nghiệp vụ và kiểm tra reservation được giải phóng đúng lúc.
4. Theo dõi bid và trạng thái phiên. Không tự đặt giá cho phiên của mình.

### Buyer / người tham gia đấu giá

1. Đăng nhập bằng Buyer, mở danh sách và chi tiết Auction, kiểm tra giá hiện tại, bước giá và thời gian còn lại.
2. Gửi bid hợp lệ; theo dõi cập nhật realtime và lịch sử bid. Nếu mất kết nối, reconnect và đối chiếu snapshot hiện tại trước khi thao tác tiếp.
3. Không gửi lại một bid như lệnh mới khi chưa biết kết quả request trước; replay phải dùng lại cùng idempotency key.
4. Sau khi phiên kết thúc, kiểm tra kết quả winner/loser và notification. Winner mở order do hệ thống tạo và làm theo hướng dẫn thanh toán trước deadline.

### Admin / người vận hành

1. Dùng tài khoản có quyền được cấp; kiểm tra action quản trị, trạng thái phiên và log theo correlation ID.
2. Xác minh settlement, order, payment và reservation qua dữ liệu server. Không xác nhận thanh toán chỉ dựa vào URL return trên trình duyệt.
3. Với lỗi downstream hoặc kết quả chưa rõ, tra trạng thái hiện tại trước khi replay command; không chạy seed/reset trên môi trường dùng chung.

Tên menu có thể khác theo build. Mọi bước cần được đối chiếu với giao diện và contract đang triển khai; hướng dẫn này không giả định một endpoint chưa được xác nhận.

### Đầu mối theo phân công Sprint 2

| Thành viên | Phạm vi đối chiếu khi UAT/review | Evidence cần phối hợp |
| --- | --- | --- |
| Tiến Thương — Leader/Backend | Gateway/WebSocket/JWT/service permission; order/payment, callback lặp, timeout; CI và tích hợp | Build/deployment, API/correlation IDs, payment và timeout evidence, known issues |
| Quốc Hùng — Backend | Seller auction API/validation/permission; bid history/concurrency/idempotency; scheduler/anti-sniping/settlement | Auction/bid/settlement state và lỗi backend tương ứng |
| Vinh Khang — Backend/Support UI | Reservation AUCTION, TTL/extend/release/transition; winner order retry; confirm/reconcile stock và notification | Reservation lifecycle, order handover, retry/recovery evidence |
| Quốc Hào — Frontend | List/detail, bid/WebSocket, conflict/reconnect/resync, countdown/extension, winner/loser và điều hướng order | Browser/network evidence cho hai buyer, reconnect và luồng UI |
| Linh Đang — Frontend/BA | AC/SRS/E2E, seller/admin UI, winner order/shipping/payment UX, UAT, bug log và demo | Ma trận UAT, ticket GitLab, báo cáo nghiệm thu và kịch bản review |

Phân công không thay thế trách nhiệm sửa lỗi của owner code. Lỗi phát hiện phải gán đúng service/UI owner, có người retest độc lập hoặc reviewer được nhóm chỉ định.

## 4. UAT checklist cần thực hiện trên môi trường tích hợp

### Chuẩn bị

1. Ghi commit SHA, URL Gateway, build/deployment ID, thời gian UTC, browser và tài khoản/role dùng thử. Không đưa password, JWT, cookie, VNPAY URL đã ký hoặc PII vào evidence.
2. Dùng seller/buyer/admin UAT được cấp quyền và SKU có tồn khả dụng. Không chạy `--reset` hoặc seed trên DB dùng chung; chỉ dùng dữ liệu được owner môi trường phê duyệt.
3. Mở DevTools Network và correlation/request ID. Chụp trạng thái nghiệp vụ từ API/UI và log đã lọc secret.
4. Ghi từng ca là `PASS`, `FAIL` hoặc `BLOCKED`, kèm timestamp, ID auction/order/reservation đã mask phù hợp và link evidence.

### Ca kiểm thử và tiêu chí pass

| ID | Thao tác qua Gateway | Tiêu chí pass / bằng chứng cần lưu | Trạng thái UAT |
| --- | --- | --- | --- |
| UAT-01 | Seller tạo phiên với SKU hợp lệ; thử SKU không hợp lệ/đã có phiên active | Phiên hợp lệ có owner, giá/thời gian/trạng thái đúng và đúng một reservation; invalid bị từ chối, không để lại phiên/giữ kho dở dang | Chưa chạy |
| UAT-02 | Seller sửa phiên trước giờ mở; seller khác/Buyer/anonymous thử sửa | Chỉ owner/role được phép sửa trước hạn; request bị cấm trả 401/403; trạng thái và reservation không đổi | Chưa chạy |
| UAT-03 | Seller hủy phiên đủ điều kiện; thử hủy sau khi không còn eligible | Transition đúng rule; bid/settlement không tiếp tục; reservation được release đúng một lần và actor được ghi nhận | Chưa chạy |
| UAT-04 | Buyer mở danh sách/chi tiết; thử truy cập phiên hạn chế bằng role khác | Nội dung, visibility, dữ liệu bidder và action khớp quyền; không lộ thông tin riêng hoặc cho thao tác trái quyền | Chưa chạy |
| UAT-05 | Hai Buyer kết nối WebSocket/STOMP và đặt bid hợp lệ/không hợp lệ | Giá, bid count và lịch sử khớp server; seller self-bid, dưới bước giá hoặc bid ngoài thời gian bị từ chối, không mutation | Chưa chạy |
| UAT-06 | Gửi lại cùng bid command/idempotency key sau ACK hoặc mất ACK | Replay trả cùng kết quả; chỉ một Bid được lưu, không tăng giá/count hai lần | Chưa chạy |
| UAT-07 | Hai Buyer gửi bid cạnh tranh đồng thời | Kết quả phù hợp thứ tự server; không mất/có hai winner cho cùng version; request thua nhận contract conflict/retry rõ ràng | Chưa chạy |
| UAT-08 | Bid trong cửa sổ anti-sniping và ngoài cửa sổ | Chỉ bid nằm trong policy gia hạn `endTime`; event extension đúng một lần; kiểm tra TTL reservation theo endTime mới | Chưa chạy |
| UAT-09 | Ngắt WebSocket giữa phiên, reconnect và đồng bộ lại | Client lấy snapshot mới nhất, không hiển thị bid/event trùng hoặc quay về giá cũ; không mất trạng thái sau reconnect | Chưa chạy |
| UAT-10 | Quan sát scheduler mở/đóng phiên; settlement với và không có bid | Transition đúng thời điểm; một settlement; winner/giá chốt đúng hoặc kết quả no-bid đúng; lệnh settle lặp không nhân bản | Chưa chạy |
| UAT-11 | Handover settlement thắng sang Commerce; replay sau ACK bị mất | Một order đúng winner/auction/giá; replay trả cùng order; không tạo order thứ hai | Chưa chạy |
| UAT-12 | Theo dõi reservation từ lúc mở phiên qua handover tới order | Không có khoảng hở reservation bị giải phóng giữa phiên; chuyển `AUCTION` → `ORDER` đúng một lần; payment thành công chỉ confirm kho theo contract | Chưa chạy |
| UAT-13 | Winner xem order, thông tin giao hàng và deadline; khởi tạo payment | Đúng order/winner/shipping data/deadline; giá lấy từ server; replay cùng key giữ payment attempt/TxnRef và deadline | Chưa chạy |
| UAT-14 | Phát callback/IPN lặp và kiểm tra timeout cạnh tranh trong sandbox | Callback trùng không ghi nhận tiền/order/history hai lần; timeout và callback có trạng thái nhất quán; tiền đã nhận nhưng kho lỗi phải vào trạng thái review theo contract | Chưa chạy |
| UAT-15 | Kiểm tra notification cho winner/loser/outbid và kết quả phiên | Người nhận, nội dung và trạng thái khớp sự kiện thực; replay không gửi notification trùng | Chưa chạy |
| UAT-16 | Tra log theo auction → settlement → order/payment/reservation | Correlation ID nối được các bước; log không lộ token, secret, PII hoặc URL thanh toán đã ký | Chưa chạy |

Chỉ đánh dấu pass khi quan sát trạng thái qua endpoint/Gateway hoặc giao diện quản trị có quyền; không dùng số liệu mock frontend làm bằng chứng. Với payment/IPN, chỉ dùng VNPAY sandbox và tài khoản được giao; không thanh toán thật. Không thực hiện callback/IPN giả trên môi trường production.

## 5. Log bug và retest GitLab

Không có issue list trong workspace và không có GitLab connector trong phiên này, vì vậy không tạo số ticket giả và không thể xác nhận tỷ lệ đóng. Khi có quyền GitLab, lấy toàn bộ issue gắn Sprint 2/LD-S2-4 vào bảng sau; giữ nguyên issue mở, không đóng chỉ dựa trên unit test.

| GitLab issue | Severity | Mô tả / bước tái hiện | Build + môi trường | Evidence lỗi | Fix commit/build | Retest | Trạng thái GitLab |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Chưa đồng bộ được | — | Cần import danh sách issue Sprint 2 từ GitLab | — | — | — | Chưa chạy | Chưa xác nhận |

Tỷ lệ đóng chỉ được tính sau khi tải danh sách ticket phạm vi Sprint 2: `đã retest pass và đóng / tổng ticket bug trong phạm vi`. Nếu tổng bằng 0, báo “không có ticket được xác nhận”, không báo 100% theo phép chia rỗng. Mỗi ticket chỉ đóng sau retest trên build triển khai, evidence đính kèm và xác nhận owner.

## 6. Kịch bản Demo Sprint 2 (8–10 phút)

### Mục tiêu và điều kiện

Trình bày một phiên Auction từ seller tới winner order/payment, nhấn mạnh chống replay và giữ kho. Demo phải dùng môi trường được xác nhận sẵn sàng; fixture local chỉ là dữ liệu demo giả lập. Trước buổi review, người demo cần hoàn thành UAT mục 3 và điền các ID đã mask bên dưới.

| Thời lượng | Người trình bày / thao tác | Nội dung cần nói hoặc chứng minh |
| --- | --- | --- |
| 0:00–1:00 | Giới thiệu scope, build, Gateway và actor | Xác nhận đây là build nào, service nào đã triển khai; nêu rõ sandbox/dữ liệu demo |
| 1:00–2:00 | Seller mở danh sách và tạo/chọn Auction | SKU, giá khởi điểm, thời gian, trạng thái; reservation ID đã mask |
| 2:00–4:00 | Hai buyer mở cùng phiên; đặt hai bid hợp lệ | Giá hiện tại và realtime update thống nhất; buyer không thể bid trái quyền/quy tắc |
| 4:00–5:00 | Replay cùng bid command/key một lần | Chỉ một Bid được lưu; hiển thị cùng kết quả, không tăng số bid lần hai |
| 5:00–6:00 | Chốt phiên và xem winner/settlement | Winner và giá chốt lấy từ backend; không nhập tay kết quả |
| 6:00–7:00 | Xem winner order và reservation handover; replay handover nếu an toàn trên fixture | Một order duy nhất; reservation không bị giải phóng giữa Auction → Order |
| 7:00–8:00 | Mở bước thanh toán sandbox hoặc trình bày evidence UAT đã chạy | Không dùng browser return/query làm bằng chứng đã trả tiền; trạng thái server là nguồn chuẩn |
| 8:00–10:00 | Mở log/evidence và tổng kết bug | Chỉ trình bày các ticket có GitLab ID và retest evidence; nêu issue còn mở và giới hạn |

**Điền trước buổi demo:** Build/commit: `________`; Gateway: `________`; Auction ID (mask): `________`; Order ID (mask): `________`; evidence UAT: `________`; GitLab bug pass/total/open: `________`.

**Fallback nếu tích hợp lỗi:** dừng thao tác ghi dữ liệu, chuyển sang ảnh/video/evidence đã lưu và nêu chính xác bước lỗi. Không tiếp tục retry POST nếu chưa rõ request trước đã commit hay chưa; tra cứu trạng thái bằng ID nghiệp vụ trước.

## 7. Phạm vi chuyển Sprint 3 và phần phải giữ trong nghiệm thu Sprint 2

Chỉ hoãn các hạng mục được nhóm thống nhất và ghi rõ trong tài liệu Sprint 2 sang Sprint 3–4; không dùng việc hoãn để bỏ khỏi SRS. Đối chiếu [Sprint 2 Detailed Plan](SPRINT2_DETAILED_PLAN.md), [Sprint 2 Auction Engine Stories](SPRINT2_AUCTION_ENGINE_STORIES.md) và [Project Plan Sprint 2–4](PROJECT_PLAN_SPRINT2_TO_4.md) để chốt hạng mục deferred, owner và capacity Sprint 3.

Các tiêu chí trong mục UAT-01..16 thuộc phạm vi mục tiêu Sprint 2 đã cung cấp: quản lý phiên, realtime bid, scheduler/anti-sniping, settlement/order, vòng đời kho, payment/callback/timeout, notification, quyền và recovery. Chỉ chuyển tiêu chí ra khỏi DoD khi có quyết định defer và owner được ghi nhận; cập nhật ticket/capacity Sprint 3 tương ứng.

## 8. Hành động còn thiếu để ký nghiệm thu

1. Cấp môi trường tích hợp đang chạy và quyền truy cập Gateway/actor test; sau đó chạy UAT-01..16, lưu evidence đã lọc dữ liệu nhạy cảm.
2. Cấp danh sách issue GitLab Sprint 2 và quyền cập nhật issue; retest từng ticket trên build tích hợp, ghi liên kết evidence, đóng ticket đạt.
3. Đối chiếu lại LD-S2-1 với API contract/deployment hiện hành; cập nhật baseline E2E riêng theo quy trình owner tài liệu.
4. Sau khi có dữ liệu thật, cập nhật bảng trạng thái, tính chính xác tỷ lệ bug đóng và ký báo cáo nghiệm thu. Trạng thái task chỉ chuyển Done khi DoD được chứng minh.

## 9. Kiểm tra trong phiên chuẩn bị

- `git status --short`: sạch trước khi thêm hồ sơ này.
- `docker compose ps`: không chạy được vì Docker Engine trả `permission denied` trên named pipe.
- Smoke read-only localhost trong phiên: Gateway health 200, auctions 200, orders 401, web 200; products không kết nối được. Không gọi API có ghi dữ liệu do không có actor/key UAT và không xác minh được deployment/dataset.
- `mvn -f backend/auction-service/pom.xml -Dtest=AuctionOrderHandoverServiceTest test`: không chạy test vì Maven Central bị chặn (`Permission denied: getsockopt`) và parent POM chưa có trong cache.
- Đã đọc smoke evidence, báo cáo TT-S2-4, TT-S2-3, bộ scenario LD-S2-1 và test liên quan trong source; việc đọc test source không thay cho kết quả test chạy hoặc UAT.
