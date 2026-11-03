# KẾ HOẠCH HOÀN THÀNH PROJECT NEXUS — SPRINT 2–4

**Mốc kế hoạch đã chốt:** Cuối Sprint 3 chạy được các luồng nghiệp vụ chính; Sprint 4 hoàn thiện, kiểm thử và bàn giao dự án.

**Thành viên:** Tiến Thương, Quốc Hùng, Vinh Khang, Quốc Hào, Linh Đang.

**Căn cứ:** [SRS dự án](srs-nexus-ecommerce-auction-v1.docx), source và các nhánh Git đã rà soát ngày 30/10/2026. Mốc bàn giao là mục tiêu kế hoạch, không phải xác nhận toàn bộ yêu cầu đã được triển khai hoặc chắc chắn đạt. Chưa đối chiếu được issue/approval/pipeline GitLab do API trả 401.

## 1. Lộ trình chung

| Sprint | Thời gian | Mục tiêu | Mốc demo/nghiệm thu |
| --- | --- | --- | --- |
| **Sprint 2** | **02/11 – 15/11/2026** | Hoàn thiện Auction Engine và tích hợp đơn hàng/thanh toán từ Sprint 1. | **13/11:** Seller tạo phiên → giữ kho → Buyer bid realtime → chốt phiên → tạo đơn → thanh toán. |
| **Sprint 3** | **16/11 – 29/11/2026** | Giao vận, đánh giá/uy tín, hoàn tiền và thông báo; nối đủ các luồng nghiệp vụ chính. | **27/11:** Mua thường và đấu giá → thanh toán → giao hàng → hoàn tất/đánh giá; có ca hủy/hoàn tiền và hết hạn đấu giá. |
| **Sprint 4** | **30/11 – 13/12/2026** | Hoàn thiện yêu cầu còn thiếu, xử lý lỗi, kiểm thử phi chức năng, triển khai và bàn giao. | **04/12:** Đóng chức năng; **11/12:** UAT, demo và bàn giao. |

Ngày làm việc là thứ Hai–thứ Sáu. Không mặc định làm cuối tuần; ngày kết thúc sprint là Chủ nhật, review diễn ra vào thứ Sáu trước đó.

**Nguyên tắc phạm vi:** Bám SRS; không tự chuyển các yêu cầu như restricted auction, push notification, reputation, refund hoặc nhiều carrier thành “ngoài phạm vi”. Yêu cầu chưa làm phải được ghi trong bảng theo dõi SRS; chỉ thay đổi phạm vi khi có xác nhận của người phụ trách nghiệm thu. Auto-bid và escrow/rút tiền xuất hiện trong giao diện hoặc tài liệu nháp không tự trở thành yêu cầu bắt buộc nếu chưa đối chiếu SRS/AC được duyệt.

## 2. Sprint 2 — Auction Engine

**Mục tiêu: Hoàn thiện luồng đấu giá từ tạo phiên, trả giá trực tiếp đến chốt phiên và thanh toán đơn hàng đấu giá.**

- **Tích hợp Sprint 1:** Hợp nhất checkout, Order management, payment và notification cần thiết; giữ nguyên các API đang hoạt động.
- **Quản lý phiên:** Kiểm tra seller/SKU, tạo/sửa trước giờ mở, hủy phiên và quản lý quyền truy cập.
- **Bid realtime:** Kiểm tra bước giá, idempotency, xử lý đồng thời, lịch sử bid, WebSocket và reconnect.
- **Chốt phiên:** Tự mở/đóng, anti-sniping, một settlement và một order cho mỗi phiên thắng.
- **Kho/thanh toán:** Giữ hàng đủ vòng đời phiên và deadline; không reserve kép; callback và timeout có kết quả nhất quán.

| Thành viên | Vai trò | Nhiệm vụ Sprint 2 | Thời gian |
| --- | --- | --- | --- |
| **Tiến Thương** | Leader / Backend | Chốt contract và tích hợp phần đơn hàng/thanh toán còn lại Sprint 1. | 02/11 – 03/11 |
| | | Cấu hình Gateway/WebSocket, JWT, quyền service và cấu hình đấu giá. | 04/11 – 05/11 |
| | | Nối thanh toán đơn đấu giá, webhook, callback trùng và xử lý hết hạn. | 06/11 – 10/11 |
| | | Review tích hợp, CI, sửa lỗi và chuẩn bị demo. | 11/11 – 13/11 |
| **Quốc Hùng** | Backend | Entity/API tạo, danh sách, chi tiết, sửa và hủy phiên; validation/quyền. | 02/11 – 04/11 |
| | | Bid service, lịch sử, concurrency, idempotency và event sau commit. | 05/11 – 06/11 |
| | | Scheduler mở/đóng, anti-sniping và bàn giao kết quả settlement. | 09/11 – 10/11 |
| | | Test cạnh tranh, bid sát giờ đóng, sửa lỗi và demo. | 11/11 – 13/11 |
| **Vinh Khang** | Backend / Support UI | Reserve AUCTION, TTL, gia hạn, release và chuyển giữ kho sang order. | 02/11 – 04/11 |
| | | Settlement, tích hợp Commerce tạo đơn từ kết quả tin cậy, retry chống trùng. | 05/11 – 10/11 |
| | | Xác nhận/hoàn tồn, tích hợp thông báo winner và kiểm thử recovery/timeout. | 11/11 – 13/11 |
| **Quốc Hào** | Frontend | Auction list/detail và API adapter; loading/empty/error. | 02/11 – 05/11 |
| | | Bid/WebSocket, xử lý conflict, reconnect và resync dữ liệu. | 06/11 – 09/11 |
| | | Countdown, extension, winner/loser và điều hướng order. | 10/11 – 11/11 |
| | | Test hai trình duyệt, quyền, lỗi mạng và sửa UI. | 12/11 – 13/11 |
| **Linh Đang** | Frontend / BA | Chốt AC, rule SRS/source và kịch bản E2E; chuẩn bị backlog SRS còn lại. | 02/11 – 03/11 |
| | | Seller tạo/sửa/hủy phiên, trạng thái và thao tác Admin theo contract. | 04/11 – 06/11 |
| | | Winner Order, shipping information, deadline và payment sandbox. | 09/11 – 11/11 |
| | | UAT, tổng hợp lỗi, tài liệu và demo. | 12/11 – 13/11 |

**Điều kiện chuyển Sprint 3:** Luồng auction thực thi bằng dữ liệu thật qua Gateway; replay không trùng bid/order; kho không bị giải phóng giữa phiên; kết quả payment do backend xác nhận. Việc chưa đạt phải có owner và được trừ vào capacity Sprint 3.

Chi tiết task, estimate và test: [phân công Sprint 2](SPRINT2_ASSIGNMENT.md), [rà soát và kế hoạch kỹ thuật Sprint 2](SPRINT2_DETAILED_PLAN.md). Các phần hoãn trong tài liệu Sprint 2 chỉ có nghĩa là chuyển tiếp sang Sprint 3–4, không phải loại khỏi SRS toàn dự án.

## 3. Sprint 3 — Hoàn thiện luồng nghiệp vụ toàn hệ thống

**Mục tiêu: Chạy được luồng mua thường và đấu giá đến giao hàng, đánh giá; có xử lý hủy/hoàn tiền, uy tín và thông báo theo SRS.**

- **Shipping:** Phí vận chuyển, tạo vận đơn, tracking, mapping trạng thái, callback/polling và retry.
- **Fulfillment:** Kho, chọn/phân bổ kho, pick/pack và ledger; liên kết trạng thái giao hàng với order.
- **Reputation:** Buyer/Seller đánh giá sau giao dịch, tính điểm/trust level, penalty và kiểm tra eligibility khi tạo phiên/bid.
- **Commerce:** Hủy/hoàn tiền theo state machine, đối soát, invoice/receipt.
- **Notification:** Event-driven email/push, preferences, history, retry và event logs.

| Thành viên | Vai trò | Nhiệm vụ Sprint 3 | Thời gian |
| --- | --- | --- | --- |
| **Tiến Thương** | Leader / Backend | Chốt contract shipping/payment/refund/events; chuẩn bị credentials và endpoint callback với các provider. | 16/11 – 17/11 |
| | | Triển khai hủy/hoàn tiền, chống refund trùng và đối soát; chuẩn hóa notification transport, push và broker health. | 18/11 – 23/11 |
| | | Nối Commerce–Shipping–Notification, invoice/receipt; kiểm tra quyền và recovery, hỗ trợ test toàn luồng. | 24/11 – 27/11 |
| **Quốc Hùng** | Backend | Chốt quy tắc rating/reputation và quyền; tạo entity/API đánh giá Buyer/Seller sau giao dịch hợp lệ. | 16/11 – 18/11 |
| | | Điểm/trust level, transaction summary, penalty, audit và eligibility cho Auction. | 19/11 – 24/11 |
| | | Admin điều chỉnh/tra cứu, dispute flag theo quy tắc; test quyền, một rating mỗi bên/giao dịch, chống tự đánh giá. | 25/11 – 27/11 |
| **Vinh Khang** | Backend / Support UI | Shipment model, carrier adapter, quote/create/tracking; kiểm tra sandbox và lập test contract cho carrier. | 16/11 – 20/11 |
| | | Mapping status, callback/polling idempotent, retry/circuit breaker; nối pick/pack/ship, allocation và ledger. | 23/11 – 25/11 |
| | | Test carrier timeout/lặp callback, warehouse allocation và vòng đời kho; sửa lỗi tích hợp. | 26/11 – 27/11 |
| **Quốc Hào** | Frontend | Shipping options/phí ship, tracking và trạng thái giao hàng Buyer/Seller. | 16/11 – 20/11 |
| | | Giao diện đánh giá, trust level, notification inbox/push permission và preferences; tích hợp API. | 23/11 – 25/11 |
| | | Test quyền, lỗi provider, reconnect và sửa UI toàn luồng. | 26/11 – 27/11 |
| **Linh Đang** | Frontend / BA | Chốt AC shipping/refund/reputation/notification; đối chiếu toàn bộ SRS, ghi rõ phần chưa đáp ứng. | 16/11 – 17/11 |
| | | Seller fulfillment, Admin reputation/penalty và hủy/hoàn tiền; history, event logs và invoice/receipt UI theo API. | 18/11 – 24/11 |
| | | E2E hai luồng mua hàng/đấu giá, UAT và backlog Sprint 4 với owner/AC rõ ràng. | 25/11 – 27/11 |

**Mốc kiểm tra:**

- **17/11:** Contract, rule và quyền đã thống nhất; credentials/provider blockers có owner.
- **20/11:** Có API rating và một đường quote/create/tracking carrier chạy được; các đường refund/notification được demo sớm, không đợi cuối sprint mới nối.
- **25/11:** Chạy được luồng tích hợp payment → shipment → delivery → completed → rating; có ca lỗi/refund/timeout.
- **27/11:** Demo luồng chính, khóa danh sách chức năng còn thiếu; không đưa nguyên một module lớn chưa bắt đầu sang tuần cuối Sprint 4.

**Điều kiện chuyển Sprint 4:** Các luồng chính có test và dữ liệu DB thật; adapter mô phỏng nếu có phải ghi là chưa nghiệm thu tích hợp provider thật. Email, push và inbox là các kênh khác nhau; không dùng toast để kết luận đã có push theo SRS.

## 4. Sprint 4 — Hoàn thiện và bàn giao

**Mục tiêu: Hoàn thành phần SRS còn thiếu đã được xác định, đạt tiêu chí nghiệm thu và bàn giao hệ thống có thể triển khai, vận hành và tái hiện demo.**

| Thành viên | Vai trò | Nhiệm vụ Sprint 4 | Thời gian |
| --- | --- | --- | --- |
| **Tiến Thương** | Leader / Backend | Hoàn thiện HTTPS/secrets, CI/CD, môi trường bàn giao; broker/retry và các tích hợp còn thiếu. | 30/11 – 04/12 |
| | | Load test, logs/metrics/tracing/alerts; kiểm tra triển khai nhiều instance và tính đúng dữ liệu khi restart. | 07/12 – 08/12 |
| | | Backup/restore, rollback/triển khai, release checklist, sửa lỗi cuối và bàn giao. | 09/12 – 11/12 |
| **Quốc Hùng** | Backend | Hoàn thiện quyền restricted auction và eligibility; đóng thiếu sót Catalog/Order/Reputation theo bảng SRS. | 30/11 – 04/12 |
| | | Test race, idempotency, quyền truy cập, hiệu năng bid/search và audit. | 07/12 – 08/12 |
| | | Sửa lỗi UAT, cập nhật OpenAPI/contract và hỗ trợ demo backend. | 09/12 – 11/12 |
| **Vinh Khang** | Backend / Support UI | Hoàn thiện multi-carrier strategy, allocation/warehouse, carrier metrics và recovery còn thiếu. | 30/11 – 04/12 |
| | | Test invariant kho, shipment retry/fallback, carrier outage, settlement recovery. | 07/12 – 08/12 |
| | | Sửa lỗi, chốt dữ liệu/demo fulfillment và tài liệu tích hợp carrier. | 09/12 – 11/12 |
| **Quốc Hào** | Frontend | Hoàn thiện UI restricted auction, notification/preferences và các màn còn thiếu theo API; kiểm tra responsive/loading/error. | 30/11 – 04/12 |
| | | Browser E2E, session/quyền, reconnect, regression hai luồng và kiểm tra không fallback thành công giả. | 07/12 – 08/12 |
| | | Sửa lỗi UAT, đóng bản frontend và chuẩn bị demo. | 09/12 – 11/12 |
| **Linh Đang** | Frontend / BA | Đối chiếu yêu cầu–API–UI–test; đóng UI/Admin và các AC còn thiếu, chuẩn bị hướng dẫn sử dụng. | 30/11 – 04/12 |
| | | Điều phối UAT, lập biên bản pass/fail/blocked; kiểm tra invoice/refund/dispute và phân quyền. | 07/12 – 09/12 |
| | | Tổng hợp nghiệm thu, tài liệu, kịch bản demo và danh sách giới hạn còn lại để bàn giao. | 10/12 – 11/12 |

**04/12:** Đóng chức năng và tạo release candidate. Sau mốc này ưu tiên fix lỗi và kiểm chứng; thêm chức năng phải đánh giá ảnh hưởng ngày bàn giao.

**11/12:** Demo/nghiệm thu và bàn giao. Sprint kết thúc theo lịch ngày **13/12/2026**.

## 5. Bảng kiểm phạm vi SRS và trách nhiệm

Đây là các nhóm yêu cầu để theo dõi, không phải tuyên bố đã có full traceability cho từng điều khoản. Linh Đang tách thành từng requirement ID/AC trong Sprint 2 và cập nhật bằng chứng xuyên suốt Sprint 3–4.

| Nhóm SRS | Owner / phối hợp | Sprint thực hiện/kiểm chứng | Bằng chứng cần có |
| --- | --- | --- | --- |
| User/IAM, roles/privileges, profile | Thương; Hào/Đang UI | Kế thừa, rà S2–S4 | API/UI và test quyền, refresh/logout/reset; kiểm tra phần admin quản lý còn thiếu. |
| Product/Category/Search | Hùng; Hào UI | Kế thừa, rà S3–S4 | CRUD/ownership/filter/page và load test, không chỉ snapshot/demo UI. |
| Cart/Checkout/Order/Payment | Thương + Hùng; Hào/Đang UI | S2; hoàn thiện S3 | Merge baseline, callback/idempotency, lịch sử/trạng thái, tax/discount/shipping, guest cart và validation theo AC. |
| Cancellation/Refund/Invoice | Thương; Đang UI/AC | S3, đóng S4 | State machine, refund replay/đối soát, invoice/receipt và delivery qua notification. |
| Auction lifecycle/bid/settlement | Hùng + Khang; Thương/Hào/Đang | S2, bổ sung S3–04/12 | Đồng thời/restart, anti-sniping, visibility/eligibility, một winner/order, deadline và penalty. |
| Inventory/Warehouse | Khang; Hùng review | S2–S3, đóng S4 | Reserve/commit/release/TTL, allocation, pick/pack/ship, ledger bất biến và test concurrent. |
| Shipping/Logistics | Khang; Thương/Hào/Đang | S3, đóng S4 | Carrier adapters, quote/create/track, callback, retry/circuit breaker, selection/fallback và metrics. Không tự bỏ multi-carrier. |
| Rating/Reputation/Penalty/Dispute | Hùng; Đang/Hào UI | S3, đóng S4 | Hai bên đánh giá hợp lệ, trust/summary, eligibility, penalties, admin adjustment, dispute flag và audit. |
| Notification/Event logs/Broker health | Thương + Khang; Hào/Đang UI | S2 nền, S3–S4 hoàn thiện | Email/push, preferences, history/attempts, mandatory transactional messages, logs/filter/detail, broker outage/recovery. |
| Performance/Scaling/Reliability | Thương, cả nhóm | Thiết kế S2–S3, đo S4 | Báo cáo môi trường/tải/p95/p99, restart không mất bid, nhiều instance không sai winner/order. |
| Security/Observability/Operations | Thương; Hùng/Khang review | Xuyên suốt, kiểm chứng S4 | TLS, RBAC, bảo vệ dữ liệu, logs/traces/alerts, pipeline/deploy/rollback, backup/restore và retention policy. |

Không coi một module là “Done” chỉ vì đã có giao diện hoặc schema. Mỗi yêu cầu có một trong bốn trạng thái: **đạt**, **chưa đạt**, **chưa kiểm chứng**, **đã được duyệt thay đổi phạm vi**.

## 6. Capacity, phụ thuộc và kiểm soát tiến độ

- Giữ giả định từ kế hoạch Sprint 2: **40 giờ tập trung/người/sprint**, trong đó khoảng **30 giờ giao task + 10 giờ review/họp/sửa lỗi/dự phòng**. Tổng 5 người: **200 giờ/sprint**, khoảng **150 giờ giao task**. Đây là giả định planning, chưa phải velocity đo được.
- Sprint 3–4 ở trên là các gói công việc mục tiêu, **chưa được estimate từng issue**. Cả nhóm phải tách/ước lượng tại planning; nếu vượt capacity phải điều chỉnh phạm vi hoặc thời hạn minh bạch. Việc đặt tên task vào một sprint không chứng minh có đủ thời gian thực hiện full SRS.
- Cuối Sprint 2 đo giờ thực tế, carry-over và số AC đã nghiệm thu để lập forecast lại. Carry-over lấy capacity của sprint sau, không cộng thêm vào cùng một người như việc miễn phí.
- Backend chốt contract sớm; FE có thể dựng theo fixture nhưng phải nối API thật trước nghiệm thu. Không để dồn tích hợp vào hai ngày cuối sprint.
- Thương điều phối Commerce/common/Gateway; Hùng sở hữu Auction/Reputation; Khang sở hữu Fulfillment/Shipping và settlement bridge. Thay đổi chồng file/entity phải hẹn cửa sổ tích hợp và review chéo.
- Reviewer chéo: Hùng ↔ Thương cho BE, Hùng/Thương review Khang; Hào ↔ Đang cho FE. Tác giả không tự nghiệm thu toàn bộ task của mình.
- Nếu gate 06/11 hoặc 25/11 chưa đạt, tổ chức rà tiến độ ngay; nếu 04/12 còn module chính chưa chạy, báo rủi ro ngày bàn giao. Không ghi “hoàn thành SRS” chỉ để giữ mốc lịch.

## 7. Điều kiện hoàn thành dự án

1. Hai luồng **mua thường** và **đấu giá** chạy xuyên suốt bằng dữ liệu thật; quyền Buyer/Seller/Admin đúng và có các ca lỗi/retry/concurrency.
2. Các yêu cầu SRS có test/UAT hoặc quyết định thay đổi phạm vi được xác nhận; không có yêu cầu bắt buộc bị bỏ mà không ghi nhận.
3. Không còn lỗi nghiêm trọng liên quan tiền, tồn kho, quyền, mất/trùng bid/order/refund. Lỗi còn lại có owner, mức độ và xác nhận chấp nhận khi bàn giao.
4. Build/lint/test và coverage gate liên quan đạt; API/DTO/schema/config và comment theo `AGENTS.md` khớp hành vi hiện tại.
5. Báo cáo hiệu năng đối chiếu SRS: login ≤500ms, search ≤800ms, cart ≤500ms, order ≤1,5s, bid ≤300ms, notification ≤1s; throughput Catalog 1.000 request/s, Commerce 500 request/s, Auction 3.000 bid/phút, Notification 10.000 message/phút. Ghi môi trường/phương pháp; không thay bằng chỉ tiêu stories <200ms khi chưa có quyết định thống nhất.
6. Có bằng chứng deploy, logging/metrics/tracing, backup/restore và đối chiếu RTO ≤4h/RPO ≤15 phút. Daily backup đơn lẻ không chứng minh đạt RPO 15 phút. Availability/retention dài hạn được bàn giao bằng thiết kế, cấu hình và cách theo dõi; không khẳng định đã đo đủ nhiều năm hoặc đạt SLA dài hạn trong hai tuần.
7. Bàn giao source/commit release, migration, OpenAPI, tài liệu cài đặt/vận hành, hướng dẫn sử dụng, test/UAT report, demo fixtures và known limitations. Secret cung cấp qua cơ chế quản lý môi trường, không đưa vào tài liệu.

**Kết luận kế hoạch:** Mục tiêu bàn giao **11/12/2026, cuối Sprint 4**; Sprint 3 phải có bản tích hợp toàn luồng. Đây là lịch mục tiêu để nhóm thực hiện và theo dõi, việc nghiệm thu đầy đủ căn cứ SRS và bằng chứng thực tế.
