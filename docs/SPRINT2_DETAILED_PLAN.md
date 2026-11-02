# KẾ HOẠCH SPRINT 2 — PROJECT NEXUS

> **Cập nhật lộ trình:** Mục tiêu toàn dự án đã chuyển sang hoàn thiện và bàn giao cuối Sprint 4; xem [kế hoạch Sprint 2–4](PROJECT_PLAN_SPRINT2_TO_4.md). Tài liệu này giữ bằng chứng rà soát và chi tiết kỹ thuật Sprint 2; các phần ngoài cam kết Sprint 2 phải tiếp tục theo dõi trong SRS ở Sprint 3–4.

> **Thời gian:** 02/11/2026–15/11/2026, theo xác nhận của người yêu cầu.
>
> **Ngày làm việc:** 02–06/11 và 09–13/11; review và retrospective ngày 13/11. Không mặc định làm cuối tuần.
>
> **Ngày rà soát:** 30/10/2026, múi giờ Việt Nam.
>
> **Trạng thái:** Kế hoạch đề xuất để nhóm chốt tại Sprint Planning; chưa phải cam kết đã được từng thành viên xác nhận.
>
> **Sprint Goal:** Có một luồng đấu giá công khai chạy bằng dữ liệu thật: Seller tạo phiên → giữ kho → tự mở phiên → Buyer trả giá và nhận cập nhật realtime → chống sniping → chốt một người thắng → Commerce tạo một đơn đấu giá → Winner thanh toán hoặc hết hạn và hoàn kho.

> **Cập nhật tích hợp ngày 02/11/2026:** Baseline dev tại e76015b đã có checkout, đọc/cập nhật/hủy đơn hợp lệ, VNPAY/IPN và email/inbox qua [MR !57](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7/-/merge_requests/57) và [MR !58](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7/-/merge_requests/58). Xem [báo cáo tích hợp](COMMERCE_REVIEW_INTEGRATION.md) để biết hành vi và giới hạn. Các bảng source/carry-over dưới đây giữ snapshot ngày 30/10 phục vụ truy vết, không phải trạng thái triển khai hiện tại. Chưa kiểm chứng merchant VNPAY sandbox thật hoặc nghiệm thu đầy đủ Sprint 1. Phần dọn source đang được review riêng tại [MR !59](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7/-/merge_requests/59) và [MR !60](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7/-/merge_requests/60).

## 1. Căn cứ và giới hạn kiểm chứng

### 1.1. Snapshot dùng để lập kế hoạch

| Nguồn | Kết quả kiểm tra |
| --- | --- |
| GitLab repository | `https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7` |
| Remote đã cập nhật | Đã chạy `git fetch origin --prune` thành công ngày 30/10/2026. |
| Baseline triển khai chính | `origin/dev`: `2b2841fb1f548cdce83af557237d36754e5b573b` — merge catalog seller snapshot. |
| Working tree đang mở | Nhánh `dev`, HEAD `94150e914391155fbd4e9d9821e54e9627f6d018`, chậm hơn `origin/dev` 13 commit tại thời điểm kiểm tra. |
| Nhánh `main` | `origin/main`: `dc3bfe9073bbcdbbb2d377baaec13a03dc4a913b`; khác lịch sử tích hợp với `dev`. Không dùng `main` thay cho baseline Sprint 2 mà chưa đối chiếu. |
| GitLab board/API | Các request đọc project, members, milestones, issues, merge requests và pipelines đều nhận HTTP 401. Không xác minh được assignee, issue đã đóng, approval, pipeline xanh hoặc lịch milestone. Fetch Git thành công không đồng nghĩa có quyền đọc các API này. |
| Source đã đọc | Cấu trúc service, controller/DTO/service liên quan checkout–order–inventory, AuctionProperties, migrations, Gateway route/security, giao diện auction/order/payment, config và CI; đối chiếu thêm các nhánh tính năng bên dưới. Đây là rà soát có trọng tâm, không phải kiểm toán từng dòng toàn repo. |
| Tài liệu nghiệp vụ | SRS `srs-nexus-ecommerce-auction-v1.docx` hiện là văn bản đọc được; stories Sprint 1/Sprint 2, DTO contracts, API spec, kiến trúc và bàn giao Sprint 0 trong Git. |

**Quy ước bằng chứng:** “Có trong dev” chỉ xác nhận code đã có ở snapshot trên; “có trên nhánh” không đồng nghĩa MR đã duyệt; “chưa kiểm chứng runtime” không được đổi thành “đã nghiệm thu”.

Tại thời điểm lập snapshot, working tree có 6 file tài liệu bị xóa: `CODE_GUIDE.md`, `COMPLIANCE_VERIFICATION.md`, `HOTFIX_USER_SERVICE_KEYCLOAK_REPORT.md`, `SPRINT0_HANDOVER.md`, `SPRINT0_VERIFICATION.md`, `STANDARDIZED_API_RESPONSE_V2_CHANGELOG.md`. MR tài liệu này ghi nhận việc xóa các báo cáo cũ; thông tin lịch sử vẫn xem được tại commit trước đó trong Git. Quy tắc code hiện hành nằm ở AGENTS.md và CONTRIBUTING.md.

### 1.2. Danh sách thành viên làm cơ sở phân việc

| Thành viên | Căn cứ nhận diện và kinh nghiệm hiện có | Vai trò đề xuất Sprint 2 |
| --- | --- | --- |
| Nguyễn Tiến Thương | Tài liệu bàn giao, alias `Thuong404-lab`; IAM, Gateway, common, checkout, VNPAY và notification branches. | Lead tích hợp, nền tảng bảo mật, Gateway/WebSocket và CI. |
| Hà Thúc Quốc Hùng | Alias `Ha Thuc Quoc Hung`; Catalog, Cart, Order foundation/management và response envelope. | Backend Auction: entity/API, bid, concurrency, lifecycle. |
| Vĩnh Khang | Alias `khanglyvinh`; inventory/reservation, commit/release, transactional email. Tài liệu cũ có cách ghi Vinh/Vĩnh Khang; cần chốt tên hiển thị khi tạo issue. | Backend giữ kho đấu giá, settlement–Commerce, thông báo tối thiểu. |
| Trần Quốc Hào | Alias `hao`; cart, catalog UI, chuyển hướng sandbox và payment result. | Frontend Buyer: list/detail, bid realtime, kết quả phiên. |
| Trần Ngọc Linh Đang | Alias `dangtnl`; order UI/API và stories Auction Engine. | BA, frontend Seller/Admin và Winner Order, UAT. |

Phân công dựa trên module và loại việc đã làm, không dùng số lượng commit để chấm năng lực. Chưa đối chiếu được danh sách thành viên chính thức trên GitLab. **Phạm Gia Văn** có commit khởi tạo `303c142`, nhưng chưa có bằng chứng phụ trách module trong tài liệu phân công đã đọc; xem mục 8, không tự tính là một developer bổ sung vào capacity.

## 2. Kết quả rà soát cuối Sprint 1

### 2.1. Phần có thể kế thừa và phần chưa được tính là hoàn thành

| Hạng mục | Bằng chứng source/Git | Kết luận để lập Sprint 2 |
| --- | --- | --- |
| Auth, JWT, API envelope | `common`, Gateway, User; commit `3c35668`, các bản sửa Keycloak; service kiểm tra JWT. | Kế thừa, nhưng quyền Auction và xác thực STOMP phải bổ sung/test. |
| Catalog/SKU snapshot | `46cb34d` đã vào `origin/dev`, snapshot có seller ownership. | Dùng để xác minh SKU/seller/giá; không để Auction đọc trực tiếp DB Catalog. |
| Cart và guest cart UI | Cart backend có trong dev; `b911524`, `0bc06a5` và các thay đổi FE nằm trong baseline remote. | Hồi quy trước khi tích hợp payment/order. Guest cart localStorage không chứng minh merge phía server đã đúng toàn bộ AC. |
| Checkout tạo đơn | `70e9aa8` đã merge; `OrderController`, `CheckoutService`, migration V4. | Có preview và tạo đơn REGULAR/COD idempotent. `validateOptions()` từ chối AUCTION, online payment/voucher ở baseline này. |
| Đọc/hủy/cập nhật đơn | Có `origin/feat/hg-s1-4-order-management` tại `88b42e0`; `f5b87f5` chứa API/state machine/tests. `origin/dev` chỉ có POST preview/create trong OrderController. | Carry-over tích hợp, không viết lại từ đầu và không ghi là API đã có trong dev. |
| Kho, reserve/commit/release | `1856abf`, `b30e872` đã vào dev; có AUCTION refType và timeout override. | Có nền tảng, chưa có vòng đời giữ kho đấu giá từ lịch mở đến hạn thanh toán và bàn giao sang order. |
| Event inventory | `InventoryEventListener` dùng Spring `@EventListener`; có REST bridge trong InventoryController. | Event trong JVM không tự truyền từ Commerce sang Fulfillment; cần xác minh đường HTTP/event thực tế và retry. |
| VNPAY thật | Các nhánh `feature/tt-s1-3-sandbox-payment`, `...payment-gateway`, `...payment-web`; SHA `17abc14`, `da5c3b2`, `186ff6c`. | Có code để review/tích hợp, chưa được tính đã nghiệm thu trên dev hoặc đã callback thành công bằng sandbox thật. |
| Thông báo | `origin/dev` Notification chủ yếu có user audit/ping. Nhánh `feature/transactional-email-e2e` tại `89eeaac`; nhánh inbox/outbox `feature/tt-s1-4-notifications` tại `ff2cf6e` và các nhánh thành phần. | Hai hướng triển khai cùng chạm notification/fulfillment, cần hợp nhất trước khi dùng cho Auction. |
| Order UI | Có API adapter và UI, nhưng `frontend-web/src/api/orders.ts` còn nhiều fallback/local mock; remote có trường hợp fallback 401 khi chưa có session. | Không dùng màn hình hiện dữ liệu làm bằng chứng order backend thành công. UAT phải bật chế độ API thật, lỗi auth không thành công giả. |
| Auction backend | `auction-service` có config, migration, security, ping; chưa có entity/controller/service/repository nghiệp vụ. POM chưa khai báo dependency WebSocket thực thi. | Phần mới chính của Sprint 2. Schema và route không thay thế engine đã chạy. |
| Auction frontend | `AuctionPages.tsx` dùng `AUCTIONS`, `createdAuctions`; placeBid chờ `setTimeout` và cập nhật UI. | Tận dụng layout; thay thao tác demo bằng API/STOMP và dữ liệu server. |
| Reputation | `origin/dev` chỉ có application/config ở Java, chưa có API nghiệp vụ reputation. | Không hứa trừ điểm/cấm đấu giá tự động trong MVP nếu chưa bổ sung service và quy tắc được duyệt. |

### 2.2. Các xung đột phải xử lý trước khi nối Auction

1. **OrderController và Order entity:** nhánh Hùng có GET/list/cancel/status và model mở rộng; dev có POST checkout/create, khóa checkout và snapshot V4. Merge phải giữ cả hai nhóm API, không thay nguyên file làm mất chức năng của bên còn lại.
2. **Payment và notification branches:** có thay đổi chồng lên CheckoutService, Order, Gateway security, BuyerPages và orders API. Tích hợp nhánh nhỏ theo dependency; không merge mù nhánh tổng hợp có nhiều merge base.
3. **Notification email và inbox/outbox:** thống nhất schema, cách lưu attempts, idempotency và một đường phát sự kiện; tránh gửi hai email cho cùng order/event. Khang review nhánh email, Thương điều phối nhánh outbox.
4. **Migration:** dev có Commerce V4; nhánh payment có V5, notification có V6. Auction/kho bổ sung migration mới theo trạng thái thực sau tích hợp, không sửa migration đã chạy hoặc dùng trùng version.
5. **Sai khác DTO FE/BE:** FE có cả adapter cũ và V2; phải đối chiếu response createOrder, envelope, enum trạng thái, phân trang và thông tin payment. Không coi khớp tên hàm là khớp contract.

## 3. Phạm vi Sprint 2 và các quyết định bắt buộc

### 3.1. Cam kết theo mức ưu tiên

**P0 — bắt buộc để đạt Sprint Goal:**

- Tích hợp nền Order/payment cần thiết từ Sprint 1, giữ cart/checkout hoạt động.
- Auction PUBLIC cho một SKU cụ thể, số lượng 1; tạo/sửa trước giờ mở, kiểm tra seller/product, chặn cùng product có nhiều phiên SCHEDULED/ACTIVE.
- Danh sách/chi tiết, start/end tự động, đặt bid nguyên tử và idempotent; chặn seller tự bid, trạng thái sai, bid thấp và bid sau giờ kết thúc.
- Realtime giá, bid history được che thông tin, đồng hồ theo giờ server, reconnect lấy snapshot lại.
- Anti-sniping cùng transaction với bid, giới hạn gia hạn theo cấu hình đã chốt.
- Một settlement cho một auction, một order cho một kết quả thắng; có retry khi Commerce tạm lỗi.
- Giữ kho suốt vòng đời phiên và hạn thanh toán, không reserve lần hai khi tạo order; trả kho đúng một lần khi hủy/thất bại/hết hạn.
- Winner thấy order thật, nhập địa chỉ theo contract được chốt, thanh toán qua backend; trình duyệt không tự đánh dấu PAID.
- Có kiểm thử tích hợp PostgreSQL và UAT thông qua Gateway, gồm các lỗi cạnh tranh và request gửi lại.

**P1 — lấy khi P0 ổn định và còn capacity:** inbox/email cho winner/outbid, Admin duyệt phiên nếu BA yêu cầu, màn hình lịch sử mở rộng và đo tải realtime có báo cáo. Thông báo lỗi/tình trạng trong UI của P0 vẫn bắt buộc; email và Web push là các kênh riêng, không được gọi UI toast là Web push.

**Ngoài cam kết mặc định:** PRIVATE auction/invite, auto-bid/proxy bidding, full Reputation/penalty/ban, refund thật/escrow/rút tiền, shipping provider thật, Web push, multi-region/multi-node STOMP broker và triển khai production. Nếu stakeholder yêu cầu mục này trong sprint, phải đổi scope/capacity ngay D1; ghi rõ phần SRS được hoãn.

### 3.2. Danh sách quyết định cần chốt D1

Linh Đang tổng hợp, Thương xác nhận kỹ thuật, Hùng/Khang xác nhận khả năng triển khai. Các giá trị dưới đây là điểm khác biệt đã tìm thấy, **không phải quyết định đã được duyệt**.

| Quyết định | SRS/source hiện tại | Việc cần chốt và tác động |
| --- | --- | --- |
| Giới hạn phiên/seller | SRS: 5; AuctionProperties/application.yml: 20. | Một cấu hình thống nhất, phạm vi trạng thái được tính, test tạo đồng thời ở ngưỡng giới hạn. |
| Bước giá, currency | SRS DEFAULT_BID_INCREMENT: 10; source minIncrement: 1000; stories dùng VND. | Phân biệt default increment với minimum increment; chốt đơn vị tiền, decimal/rounding. |
| Anti-sniping | SRS extension: 5 phút; source antiSnipingSeconds: 120; stories giới hạn 10 lần nhưng source chưa có cấu hình max extension. | Tách trigger window, extension duration, enabled và maxExtensions; quy tắc cộng vào endTime hay tính từ now. |
| Bid tại ranh giới thời gian | Stories có đoạn dùng khoảng đóng nhưng settlement đóng khi now >= endTime. | Đề xuất `[startTime, endTime)` theo server Clock; khóa/version dùng chung cho bid và close. |
| Moderation và trạng thái | Stories diagram có DRAFT/duyệt, scenario create lại ra SCHEDULED; UI có pending_approval/rejected ngoài DB enum. | Chọn direct SCHEDULED hoặc thêm quy trình duyệt có migration/transition. Không chỉ thêm enum ở FE. |
| Tồn kho thuộc service nào | Stories gán AUCTION_RESERVED/hoàn SKU cho Catalog; kiến trúc đặt inventory ở Fulfillment. | Fulfillment giữ/release kho; Catalog xác minh product/SKU/owner. Không bịa thêm trạng thái Catalog đã có. |
| PUBLIC/PRIVATE, eligibility | SRS có restricted auction và reputation eligibility; backend reputation chưa có. | MVP PUBLIC; quyền seller/bidder từ JWT. Điều kiện reputation cần defer được ghi nhận hoặc bổ sung việc riêng. |
| Trạng thái kết thúc không bid | Diagram chuyển CANCELLED, scenario mô tả ENDED + FAILED. | Chốt một state machine và test không bid/hủy riêng; không gộp thất bại với hủy hành chính. |
| Settlement → Order | Stories nói POST /orders AUCTION đã sẵn sàng; CheckoutService thực tế từ chối. | Thiết kế integration nội bộ có xác thực; Commerce kiểm chứng kết quả, giá và winner từ nguồn tin cậy, không nhận giá client. |
| Địa chỉ khi tự tạo order | Checkout hiện bắt recipient/address; settlement chưa có địa chỉ. | Chọn order chờ bổ sung shipping hoặc Winner cung cấp trước; quy định dữ liệu chưa đủ và thời điểm được tạo payment URL. Không dùng địa chỉ giả. |
| Hạn thanh toán và kho | SRS winner deadline 24h, checkout 15 phút, reservation mặc định 30 phút. | TTL riêng cho AUCTION: lịch chờ + phiên + gia hạn tối đa + thời hạn trả tiền; transfer ref sang ORDER phải nguyên tử/idempotent. TimeoutMinutes override hiện có chỉ là primitive, chưa đủ luồng. |
| Hết hạn vs callback muộn | Stories DEFAULTED/-50 điểm/ban 30 ngày; SRS yêu cầu penalty theo rule, chưa có service thực thi. | Chốt ưu tiên timestamp provider/server, trạng thái cuối và ngoại lệ đối soát. P0 phát/lưu timeout, cancel/release an toàn; penalty tự động là phạm vi bổ sung cần duyệt. |
| Provider và deadline | SRS default STRIPE, branches triển khai VNPAY. | Ghi nhận VNPAY sandbox là lựa chọn demo đã/đang chờ stakeholder xác nhận; không coi thay đổi provider được mặc nhiên duyệt. |
| SLA và bảo mật realtime | Stories yêu cầu <200ms; Gateway chỉ có route HTTP dùng `/ws/auctions/**`, chưa có STOMP config. | Chốt GET public, handshake/CONNECT auth, SUBSCRIBE/SEND quyền gì, WebSocket upstream, origin. Đo p95 trong môi trường được ghi rõ; chưa có cơ sở hứa SLA production. |

**Điều kiện DoR mới:** BDD + contract + quyết định trên có người xác nhận; migration/enum khớp; endpoint/event có ví dụ và lỗi; dependencies sẵn sàng. Bảng “ĐẠT” trong `SPRINT2_AUCTION_ENGINE_STORIES.md` phải được rà lại, đặc biệt cấu hình tập trung, WebSocket và tạo order AUCTION. Shared Config Server hiện chỉ có logging/tracing trong file configuration chung đã kiểm tra; giá trị trong local application.yml hoặc changelog không chứng minh runtime refresh tập trung đã hoạt động.

## 4. Capacity, trách nhiệm và nguyên tắc giao việc

Giả định **4 giờ tập trung/người/ngày × 10 ngày = 40 giờ/người**. Đề xuất giao 30 giờ/người, giữ 10 giờ/người cho review, họp, xử lý lỗi và bất định tích hợp. Tổng 5 người: 200 giờ khả dụng, 150 giờ task ước lượng, 50 giờ dự phòng. Đây là giờ đề xuất để planning, không phải timesheet hoặc velocity đã đo.

Giờ trong mỗi task gồm triển khai, test của chính task và cập nhật contract/comment; thời gian review chéo lấy từ dự phòng. Việc phát hiện vượt estimate phải được báo ngay, không lấy cuối tuần làm nguồn bù mặc định. Task carry-over chỉ tính công review/tích hợp/sửa, không tính lại toàn bộ code đã có.

| Thành viên | Việc chính | Giờ giao | Reviewer chính | Ranh giới sửa file |
| --- | --- | ---: | --- | --- |
| Tiến Thương | Tích hợp nền Sprint 1, contract hệ thống, Gateway/CI | 30 | Hùng cho Commerce; Khang cho inventory/notification | Điều phối `common`, Gateway, cấu hình; khóa cửa sổ sửa Commerce khi hợp nhất. |
| Quốc Hùng | Auction CRUD, bid, lifecycle | 30 | Thương | Sở hữu Auction entity/repository/bid/lifecycle; thống nhất interface cho Khang. |
| Vĩnh Khang | Auction reserve, settlement/order bridge, notification | 30 | Hùng review Auction; Thương review Commerce/auth | Sở hữu Fulfillment và Auction settlement; chỉ sửa Commerce integration sau baseline thống nhất. |
| Quốc Hào | Buyer auction/API/realtime | 30 | Linh Đang | `AuctionPages`, auction client/hook; sửa BuyerPages theo lịch tích hợp. |
| Linh Đang | BA, Seller/Admin, Winner Order, UAT | 30 | Hào review FE; Thương/Hùng review AC | SellerPages/AdminPages/order adapter; tránh sửa chung AuctionPages với Hào. |

## 5. Backlog chi tiết theo từng thành viên

Ngày đích là thời điểm bàn giao/review, không phải yêu cầu làm toàn bộ task trong đúng một ngày. Các task BE/FE có thể chạy song song sau khi contract D1 được chốt; mock dùng trong phát triển phải tách rõ khỏi đường nghiệm thu thật.

### 5.1. Nguyễn Tiến Thương — Lead, tích hợp và nền tảng (30 giờ)

| ID | Ưu tiên / giờ / hạn | Công việc và sản phẩm bàn giao | Phụ thuộc | Tiêu chí nghiệm thu |
| --- | --- | --- | --- | --- |
| TT-S2-01 | P0 / 12h / 03/11 | Hợp nhất Order management với checkout, VNPAY BE/Gateway/FE và phần notification cần thiết; cập nhật API contract/migration register. | Nhánh carry-over mục 2; Hùng/Khang/Hào hỗ trợ review trong giờ dự phòng. | POST preview/create và GET/list/detail/cancel/status cùng tồn tại theo quyền đã chốt; migration V4/V5 và các migration liên quan chạy từ DB mới/nâng cấp; payment IPN xác minh chữ ký, amount, replay. Không mất test hoặc khóa checkout. |
| TT-S2-02 | P0 / 6h / 04/11 | Gateway REST/WS routing, handshake/STOMP auth, CORS/origin và service identity cho settlement. Chốt giới hạn một Auction instance nếu dùng simple broker. | LD-S2-01, HG-S2-01; phối hợp HH-S2-02. | Browser qua Gateway upgrade kết nối thật; JWT sai/hết hạn và SEND giả bị chặn; GET PUBLIC đúng quyết định BA; service token không dùng quyền người dùng giả. Có test routing/auth. |
| TT-S2-03 | P0 / 8h / 10/11 | Nối winner payment với nền payment đã merge; bảo đảm retry timeout/inventory và lỗi upstream có trạng thái quan sát được; review luồng settlement. | TT-S2-01, KH-S2-02. | Một callback không đánh dấu paid hai lần; redirect không tự xác nhận tiền; callback muộn cạnh tranh timeout không vừa PAID vừa release kho; thông tin nhạy cảm không vào log. |
| TT-S2-04 | P0 / 4h / 12/11 | Cập nhật Compose/CI, cấu hình auction và hướng dẫn chạy demo; thu pipeline và integration evidence nếu có quyền. | Các MR P0. | Build/test service đổi và FE; cấu hình secret qua env; log có auctionId/settlementId/orderId/eventId; hướng dẫn reset fixture an toàn, không xóa volume để chữa migration. |

**Điểm kiểm soát:** nếu TT-S2-01 chưa hoàn tất sau 12h, báo scope review ngày 03/11; không âm thầm dành cả tuần cho tích hợp. Có thể hoãn notification P1 để giữ capacity cho Order/payment.

### 5.2. Hà Thúc Quốc Hùng — Backend Auction Engine (30 giờ)

| ID | Ưu tiên / giờ / hạn | Công việc và sản phẩm bàn giao | Phụ thuộc | Tiêu chí nghiệm thu |
| --- | --- | --- | --- | --- |
| HG-S2-01 | P0 / 10h / 04/11 | Entity/repository/DTO/controller Auction; create/list/detail/update-before-start/cancel; migration mới nếu cần; contract và OpenAPI. | LD-S2-01; Catalog snapshot; KH-S2-01 cho reserve thật. | Seller lấy từ JWT; SKU thuộc seller, ACTIVE và auction-enabled; hạn mức/duration/tiền hợp lệ; request đồng thời không tạo hai phiên hoạt động cho cùng product; cancel có actor/time/reason và release được retry. |
| HG-S2-02 | P0 / 12h / 06/11 | Bid service nguyên tử, `@Version` hoặc khóa phù hợp, idempotency, history; publish realtime sau commit. Thêm dependency và STOMP endpoint theo contract. | HG-S2-01, TT-S2-02, LD-S2-01. | Bid thấp/seller tự bid/không đủ quyền/sau endTime bị chặn; same key+same payload replay, khác payload conflict; cạnh tranh không mất giá cao nhất hoặc sinh bid rollback; loser nhận conflict rõ ràng. Không tự retry vô hạn. |
| HG-S2-03 | P0 / 4h / 09/11 | Scheduler start/end, anti-sniping cùng transaction với bid; interface kết thúc để Khang làm settlement. | HG-S2-02; cấu hình đã chốt. | Clock server và `[start,end)` nhất quán; scheduler catch-up sau restart; hai worker close không tạo hai winner; anti-sniping tôn trọng cap và rollback cùng bid. |
| HG-S2-04 | P0 / 4h / 11/11 | Kiểm thử cạnh tranh/ranh giới bổ sung và review settlement. | HG-S2-03, KH-S2-02. | PostgreSQL test bid vs bid, bid vs close, close vs cancel và scheduler trùng; kiểm tra DB không chỉ HTTP status. Nếu đo tải 100 request đồng thời, có cấu hình và kết quả, không ghi “đạt <200ms” khi chưa đo. |

**Lưu ý:** Hùng review carry-over Order ở TT-S2-01 nhưng không đồng thời làm lại OrderController ở nhánh Auction; ưu tiên đóng contract giao tiếp hơn mở rộng lại module cũ.

### 5.3. Vĩnh Khang — Inventory, Settlement và tích hợp Commerce (30 giờ)

| ID | Ưu tiên / giờ / hạn | Công việc và sản phẩm bàn giao | Phụ thuộc | Tiêu chí nghiệm thu |
| --- | --- | --- | --- | --- |
| KH-S2-01 | P0 / 10h / 04/11 | Reserve AUCTION số lượng 1, TTL theo vòng đời và gia hạn, release/transfer sang ORDER, quyền service và recovery. Cập nhật Fulfillment contract. | LD-S2-01, HG-S2-01; reserve primitives hiện có. | Reserve idempotent theo ref, hai người mua/phiên không cùng giữ một đơn vị kho; phiên dài không bị sweep sau 30 phút; transfer không tạo thời điểm available trở lại hoặc trừ kho lần hai; retry release không tăng kho thừa. |
| KH-S2-02 | P0 / 12h / 10/11 | Settlement persisted, outbox/worker hoặc cơ chế giao nhận bền vững đã chọn; Commerce tạo order từ kết quả tin cậy, lưu liên kết/order deadline; timeout recovery. | HG-S2-03; TT-S2-01; KH-S2-01. | UNIQUE auction settlement và unique AUCTION order/reference; worker restart/replay vẫn một order. Khi Commerce commit rồi response mất, retry nhận lại order cũ. Không truy cập DB chéo service, không nhận finalPrice từ browser. No-bid/cancel/hết hạn release đúng một lần. |
| KH-S2-03 | P1 / 4h / 11/11 | Hợp nhất cách gửi email/inbox từ carry-over; winner/outbid notification tối thiểu, có dedupe/retry. | TT-S2-01, KH-S2-02; chọn implementation thống nhất. | Một event không gửi hai thông báo do hai nhánh cùng xử lý; email lỗi không rollback bid/order; trạng thái gửi/attempt có thể tra cứu. Nếu hoãn phải ghi rõ chỉ còn UI thông báo P0. |
| KH-S2-04 | P0 / 4h / 12/11 | Test end-to-end tồn kho/settlement timeout và phối hợp UAT. | KH-S2-02, TT-S2-03. | Stock invariant không âm, reservation không mồ côi; mất kết nối/restart không mất settlement; timeout và callback đồng thời có đúng một kết quả cuối và trace để đối soát. |

**Thiết kế tối thiểu cần review trước code:** Auction lưu kết quả trong DB của mình; Commerce xác thực request/event nội bộ rồi tự tạo order trong DB Commerce; Fulfillment quản lý reservation trong DB Fulfillment. Transaction từng service kết hợp idempotency/retry, không gọi chuỗi HTTP rồi coi đó là transaction phân tán nguyên tử.

### 5.4. Trần Quốc Hào — Frontend Buyer Auction & Realtime (30 giờ)

| ID | Ưu tiên / giờ / hạn | Công việc và sản phẩm bàn giao | Phụ thuộc | Tiêu chí nghiệm thu |
| --- | --- | --- | --- | --- |
| HH-S2-01 | P0 / 10h / 05/11 | Auction API client/DTO adapter, list/detail/filter/page; thay nguồn `AUCTIONS` cho đường chạy thật. | LD-S2-01, HG-S2-01. | Data lấy từ API; 404 không mở AUCTIONS[0]; có loading/empty/error; quyền PUBLIC theo contract; tiền và trạng thái thống nhất với backend. |
| HH-S2-02 | P0 / 10h / 09/11 | STOMP connection hook, snapshot + event/version, bid form/idempotency, reconnect và cleanup. | HG-S2-02, TT-S2-02; contract từ D1 để làm UI trước. | Hai browser thấy bid sau commit; reconnect resync, không giảm giá do event cũ; pending gửi lại cùng key; conflict refresh giá mới; unsubscribe/close khi đổi phiên, không gửi trùng do mount. |
| HH-S2-03 | P0 / 6h / 10/11 | Countdown theo server/endTime, update khi extended/closed, kết quả winner/loser và điều hướng sang order. | HG-S2-03, KH-S2-02, LD-S2-03. | Đồng hồ máy khách lệch không mở bid quá hạn; nhận extension cập nhật deadline; order chưa tạo hiển thị đang xử lý/retry theo backend, không tự tạo order ở localStorage. |
| HH-S2-04 | P0 / 4h / 12/11 | Test Buyer/reconnect/auth/error và hỗ trợ UAT hai tài khoản. | HH-S2-01..03. | Test event trùng/lệch thứ tự, mất kết nối và 401/403/409; cleanup không còn subscription cũ; đường nghiệm thu không fallback thành công giả khi API lỗi. |

### 5.5. Trần Ngọc Linh Đang — BA, Seller/Admin và Winner Order (30 giờ)

| ID | Ưu tiên / giờ / hạn | Công việc và sản phẩm bàn giao | Phụ thuộc | Tiêu chí nghiệm thu |
| --- | --- | --- | --- | --- |
| LD-S2-01 | P0 / 6h / 02/11 | Chốt bảng quyết định mục 3.2, chỉnh stories/DoR/AC theo source và SRS; mô tả request/response/error/event mẫu với Hùng/Khang/Thương. | Planning có đủ 5 người. | Có người xác nhận từng decision; phân biệt rule đã duyệt và proposal; rõ time/currency/state/source_type, auth, stock ownership, deadline/địa chỉ. Không ghi config/API “đã có” khi chỉ là draft. |
| LD-S2-02 | P0 / 12h / 06/11 | Seller tạo/sửa trước giờ mở/hủy phiên, chọn SKU thật, xem trạng thái/lỗi. Admin screen tối thiểu theo policy đã chốt. | LD-S2-01, HG-S2-01, KH-S2-01. | Không tự cập nhật trạng thái như đã lưu trước server; 403 hiển thị đúng; invalid duration/increment/stock hiển thị lỗi field; refresh vẫn thấy dữ liệu; không tạo enum pending_approval ngoài contract. |
| LD-S2-03 | P0 / 8h / 11/11 | Winner Order thật: list/detail, badge AUCTION, deadline, bổ sung shipping và chuyển sang payment đã có; thống nhất adapter orders với Hào. | TT-S2-01, KH-S2-02, TT-S2-03. | Chỉ winner/staff hợp lệ xem order; giá từ settlement, không từ mock; countdown hết hạn dùng server state; thanh toán xong đợi trạng thái backend xác nhận; không tự ghi PAID/refunded vào localStorage. |
| LD-S2-04 | P0 / 4h / 13/11 | UAT checklist, test với cả nhóm, lỗi có bước tái hiện và bằng chứng; chuẩn bị demo/review/retro. | Các P0. | Có kết quả pass/fail/blocked từng ca mục 7, commit/environment/tài khoản role đã ẩn dữ liệu nhạy cảm; chưa chạy phải ghi chưa chạy. Ghi rõ phần không đạt Sprint Goal và scope chuyển tiếp. |

## 6. Lịch thực hiện và điểm kiểm soát

| Ngày | Mốc chung | Thương | Hùng | Khang | Hào | Linh Đang |
| --- | --- | --- | --- | --- | --- | --- |
| D1 — T2 02/11 | Planning, chốt scope/contract | Bắt đầu tích hợp carry-over | Entity/API + review Order | Thiết kế giữ kho/transfer | Map DTO, dựng API client | Decision log/AC/DoR |
| D2 — T3 03/11 | Gate A: nền S1 và rule đã chốt | Baseline Order/payment callable | Auction CRUD | Reserve AUCTION | List/detail UI | Form Seller theo contract |
| D3 — T4 04/11 | Gate B: tạo phiên giữ kho thật | Gateway/WS auth | CRUD có test | Reserve/TTL/release | List/detail API thật | Tạo/hủy phiên qua UI |
| D4 — T5 05/11 | Demo sớm seller → buyer view | WS upgrade/CI | Bid + concurrency | Settlement skeleton, order bridge | Detail + subscription | Validation/quyền Seller |
| D5 — T6 06/11 | Gate C: hai browser bid thật | Review bid/auth | Bid committed + event | Settlement/retry | Bid UI + xử lý conflict | Seller flow hoàn chỉnh |
| D6 — T2 09/11 | Đóng phiên và nhận kết quả | Payment integration | Scheduler/anti-sniping | Winner → order, transfer kho | Countdown/reconnect | Order/deadline UI |
| D7 — T3 10/11 | Gate D: một auction → một order | Payment/callback/timeout | Test race + fix | Settlement E2E, retry | Winner/loser route | Order/payment thật |
| D8 — T4 11/11 | Đóng feature P0 | Review lỗi tích hợp | Race regression | Notification P1 nếu còn giờ | Test realtime/auth | Test order/quyền |
| D9 — T5 12/11 | Regression, release candidate | CI/runbook/evidence | Fix backend | Test kho/deadline | UAT 2 browser | UAT toàn luồng |
| D10 — T6 13/11 | Review + retrospective | Demo môi trường chung | Demo concurrency | Demo settlement/recovery | Demo Buyer | Báo cáo AC, tồn đọng |

**Chuỗi phụ thuộc quan trọng:** LD-S2-01 → HG-S2-01 + KH-S2-01 → HG-S2-02 → HG-S2-03 → KH-S2-02 → TT-S2-03 + LD-S2-03 → UAT. TT-S2-01 chạy song song D1–D2 nhưng phải xong trước khi nối Commerce. FE triển khai theo contract từ D1, nghiệm thu chỉ trên API thật.

### Xử lý nếu trễ gate

- **Gate A chưa đạt ngày 03/11:** Thương và cả nhóm chốt lại estimate; hoãn KH-S2-03 và Admin moderation P1. Nếu nền Order/payment vẫn chưa callable thì công bố rủi ro Sprint Goal, không ghi cam kết E2E chắc chắn.
- **Gate B chưa đạt ngày 04/11:** tập trung backend/API tối thiểu, dừng mở rộng UI và filter; ưu tiên tạo–giữ kho–bid đúng.
- **Gate C chưa đạt ngày 06/11:** họp scope review trước tuần hai; cân nhắc nghiệm thu engine độc lập và chuyển settlement/payment sang sprint sau. Đây là thay đổi Sprint Goal cần nhóm/stakeholder đồng ý, không tự gọi MVP bị cắt là đạt mục tiêu ban đầu.
- **Gate D chưa đạt ngày 10/11:** giữ 12–13/11 cho fix/regression; không thêm P1. Ghi carry-over kèm owner và nguyên nhân.

## 7. Tiêu chí nghiệm thu và kế hoạch kiểm thử

### 7.1. Bộ ca bắt buộc

| Mã | Tình huống / kết quả cần bảo vệ | Người thực hiện chính |
| --- | --- | --- |
| AT-01 | Seller hợp lệ tạo phiên và giữ một SKU; seller khác/sku inactive/time sai/giá sai bị từ chối. | Hùng + Linh Đang |
| AT-02 | Hai request tạo cùng product hoặc vượt quota không vượt giới hạn; request thất bại không để reservation mồ côi. | Hùng + Khang |
| AT-03 | Phiên mở/đóng theo server time; restart scheduler catch-up; chạy hai worker không lặp settlement. | Hùng + Khang |
| AT-04 | Bid đầu, bid tăng giá đúng; seller tự bid, highest bidder bid tiếp nếu policy cấm, bid thấp hoặc hết giờ bị chặn. | Hùng + Hào |
| AT-05 | Cùng key/cùng payload trả bid cũ; đổi amount/auction/user với key cũ không thành bid mới trái quyền. | Hùng |
| AT-06 | Bid đồng thời: highest price khớp bid hợp lệ đã commit, không mất history; bid thua không được broadcast như đã thắng. | Hùng |
| AT-07 | Bid vs close/cancel; ngay trước, đúng và sau endTime; anti-sniping ở ranh giới trigger và cap. Dùng Clock kiểm soát. | Hùng + Khang |
| AT-08 | Hai browser nhận update; event trùng/cũ/reconnect không làm giá lùi; đồng hồ lệch client vẫn đúng. | Hào |
| AT-09 | JWT/CONNECT/SUBSCRIBE/SEND sai quyền; viewer không thấy dữ liệu bidder nhạy cảm; không đọc order người khác. | Thương + Hào + Linh Đang |
| AT-10 | Settlement có winner tạo một order giá snapshot; Commerce commit rồi response mất, retry/restart vẫn cùng order. | Khang + Thương |
| AT-11 | No-bid/cancel/timeout release một lần; TTL chịu đủ phiên dài và mọi extension; transfer AUCTION→ORDER không reserve kép. | Khang |
| AT-12 | Callback VNPAY đúng/sai chữ ký, sai amount, trùng, fail; browser redirect giả không tạo PAID. | Thương |
| AT-13 | Callback muộn đồng thời deadline worker không tạo PAID với kho đã hoàn; trạng thái cần đối soát được lưu/log. | Thương + Khang |
| AT-14 | Winner bổ sung shipping, xem deadline và payment thật; order pending integration có trạng thái rõ, không rơi sang mock. | Linh Đang + Hào |
| AT-15 | Hồi quy register/login/cart/checkout/order thường sau merge; kiểm tra migration trên DB mới và DB nâng cấp. | Cả nhóm, Thương điều phối |
| AT-16 | Nếu nhận P1 notification: gửi lặp chỉ một logical notification, SMTP lỗi có retry/attempt và không rollback order. | Khang |

Tối thiểu dùng PostgreSQL cho khóa/unique/race test. H2 và test mock HTTP không thay thế kiểm thử nhiều service qua Gateway. Với đo realtime, ghi số client, request, p50/p95, vị trí đo và môi trường; ngưỡng 200ms của stories chỉ kết luận sau khi đo, không dùng độ trễ setTimeout làm benchmark.

### 7.2. Definition of Done

Một task chỉ chuyển Done khi:

1. Có MR đúng scope và reviewer khác tác giả; code đã nằm trong baseline tích hợp `dev` được nhóm thống nhất.
2. AC tương ứng có bằng chứng test/UAT, gồm lỗi chính; build/lint/test liên quan và coverage gate hiện có chạy đạt. Không bỏ test/giảm threshold để làm xanh pipeline.
3. API/DTO/event/state/config/migration có tài liệu khớp source; comment tiếng Việt tại đoạn code mới/sửa giải thích trách nhiệm, luồng, validation, transaction/retry/cleanup theo `AGENTS.md`.
4. Test integration không sử dụng fallback/mock thay cho chức năng được nghiệm thu; demo mock nếu còn phải chỉ rõ trên tài liệu/môi trường.
5. Không lưu secret, token vào code/log/ảnh; phân quyền xác minh bằng JWT/service identity.
6. Có log/trạng thái tra được lỗi liên dịch vụ; retry có giới hạn/backoff và lưu tiến độ, không chỉ log rồi bỏ event.
7. Không còn lỗi nghiêm trọng làm mất tiền/kho/quyền/idempotency; lỗi còn lại ghi rõ owner và phạm vi ảnh hưởng.

**Sprint đạt mục tiêu** khi toàn bộ P0 được demo qua Gateway bằng dữ liệu DB thật, gồm ít nhất một ca thành công và một ca retry/timeout. Không dùng tổng số issue đóng hoặc % coverage làm tiêu chí duy nhất.

## 8. Cách đưa kế hoạch lên GitLab và phân công bổ sung

Chưa tạo/sửa issue, milestone, MR hoặc gán người trên GitLab trong lần lập kế hoạch này.

Khi có quyền board, Thương đối chiếu issue Sprint 1 và tránh tạo trùng task còn mở. Đề xuất milestone `Sprint 2 — Auction Engine`, start `2026-11-02`, due `2026-11-15`; ngày review vẫn là 13/11. Mỗi dòng task mục 5 tương ứng một issue hoặc link tới issue hiện có, tiêu đề `[TT/HG/KH/HH/LD-S2-xx] ...`; các mã này là mã kế hoạch, không phải GitLab issue ID.

Mỗi issue cần: owner, reviewer, priority, estimate, ngày đích, dependency, file/module, AC và link bằng chứng. Labels đề xuất `sprint::2`, `priority::P0/P1`, `area::auction/commerce/inventory/frontend/infra/ba`, `status::ready/doing/review/blocked/done`; nếu instance chưa có labels thì dùng bộ hiện có tương đương. Trạng thái blocked phải chỉ ra người cần hỗ trợ và điều kiện mở chặn.

Thứ tự tích hợp đề xuất:

1. Chốt baseline `origin/dev`, đối chiếu Order management với checkout cùng Hùng; giữ cả POST và GET/update/cancel, model/snapshot/audit.
2. VNPAY backend → Gateway IPN → FE payment result, kiểm tra callable trước khi thêm Auction Order.
3. Hợp nhất email/inbox/outbox theo một contract; có thể lùi phần P1, không lùi cơ chế giao nhận bền vững settlement P0.
4. Auction contract/schema → create/reserve → bid/realtime → lifecycle/settlement → Winner Order/payment.
5. MR nhỏ được review/test trên baseline hiện tại; dependency chưa merge thì ghi rõ target và thứ tự, tránh nhánh tổng hợp che mất xung đột.

**Phạm Gia Văn — phân công có điều kiện:** nếu xác nhận vẫn là thành viên Sprint 2, đề xuất nhận gói QA hỗ trợ 8 giờ: chuẩn bị ma trận tài khoản/quyền + dữ liệu demo (2h, 05/11), chạy AT-01/08/09/14/15 với bằng chứng (4h, 12/11), tổng hợp lỗi và xác nhận retest (2h, 13/11). Reviewer Linh Đang, hỗ trợ kỹ thuật Thương. Gói này chưa nằm trong 150 giờ đã giao và không thay thế trách nhiệm test của 5 owner; nếu không tham gia, owner tại mục 7 vẫn thực hiện.

Daily tối đa 15 phút: việc đã có bằng chứng, việc tiếp theo, dependency đang chặn. Sau mỗi gate, cập nhật Done/Remaining và giờ còn lại, không chỉ báo “đang làm”. Mỗi người nên có tối đa một task triển khai chính đang Doing.

## 9. Rủi ro và phương án xử lý

| Rủi ro | Dấu hiệu / ảnh hưởng | Owner | Cách xử lý |
| --- | --- | --- | --- |
| Nền Sprint 1 chưa tích hợp | OrderController/model/FE xung đột, baseline không callable. | Thương + Hùng | Gate A, timebox 12h, test hồi quy và đổi scope nếu vượt. |
| Rule chưa thống nhất | 5/20 phiên, 5 phút/120 giây, quota/default/min, moderation khác enum. | Linh Đang | Decision log D1, trạng thái chưa chốt phải visible, không hardcode mỗi service một kiểu. |
| Kho hết hạn giữa phiên | Sweep 30 phút làm SKU bán lại trong auction dài. | Khang | TTL riêng/extension/transfer có test Clock; không chỉ tắt scheduler toàn hệ thống. |
| Giá thầu/settlement race | Worker và bid ghi cùng auction; broadcast trước commit. | Hùng | Khóa/version, transaction chung, unique settlement, publish sau commit, test PostgreSQL. |
| Settlement mất/trùng order | HTTP timeout sau Commerce commit hoặc restart worker. | Khang | Outbox/progress persisted, consumer idempotent, unique auction order, retry và đối soát. |
| WS chạy direct service nhưng lỗi qua Gateway | Route HTTP chưa đủ upgrade/auth; đơn giản hóa broker quá mức. | Thương + Hào | Gate C phải qua Gateway; chốt single-instance MVP, ghi giới hạn scale. |
| Mock che lỗi thật | UI vẫn báo thành công khi 401/404/5xx hoặc dùng order local. | Hào + Linh Đang | Chế độ nghiệm thu không mock; test outage/auth, kiểm DB và Network khi UAT. |
| Sandbox/SMTP thiếu cấu hình | Không nhận IPN/email từ provider thật. | Thương + Khang | Chuẩn bị endpoint reachable/secret từ D1; đánh dấu blocked và phân biệt test chữ ký nội bộ với provider sandbox thực. |
| Scope quá lớn cho 2 tuần | Chưa qua gate nhưng vẫn mở thêm P1/reputation/admin. | Cả nhóm, Thương điều phối | Giữ 25% dự phòng, bỏ P1 trước, chỉ đổi Sprint Goal qua scope review rõ ràng. |

## 10. Kiểm tra đã thực hiện khi lập file này

- Đã fetch GitLab remote, đối chiếu `HEAD`, `origin/dev`, `origin/main`, lịch sử tác giả/commit và các nhánh carry-over có liên quan; không checkout/pull/merge làm thay đổi source đang mở.
- Đã đọc SRS, stories, contract và source có liên quan; xác nhận các sai khác rule, nền Auction chưa có nghiệp vụ, checkout từ chối AUCTION và các API chưa tích hợp.
- Đã chạy `npm run typecheck` trong `frontend-web` của working tree tại HEAD `94150e9`: **đạt, exit code 0**. Kết quả này không chứng minh `origin/dev` hoặc các nhánh chưa merge build/test đạt.
- Không chạy backend Maven verify, Docker/PostgreSQL E2E, browser UAT hoặc sandbox payment trong lần lập tài liệu; chưa có bằng chứng runtime cho Sprint Goal. Maven không có trên PATH của phiên kiểm tra. Không dùng kết quả test từ tài liệu cũ làm kết quả vừa chạy.
- GitLab API trả 401 nên board/milestone/membership/MR approval/pipeline vẫn cần đối chiếu khi có quyền. Không suy ra issue đã Done từ commit message hoặc tên branch.
- Chỉ thêm file kế hoạch này; không sửa code, comment source hay các tài liệu đang bị xóa trong working tree. Khi triển khai task, người sửa vẫn phải đọc và tuân thủ `AGENTS.md`.

## 11. Liên kết đối chiếu

- [SRS Nexus](srs-nexus-ecommerce-auction-v1.docx), phần configuration và Auction Services.
- [Sprint 1 cart/order stories](SPRINT1_CART_ORDER_STORIES.md).
- [Sprint 2 Auction Engine stories/DoR cần rà lại](SPRINT2_AUCTION_ENGINE_STORIES.md).
- [Auction contract — hiện PLANNED](dto-contracts/AUCTION_SERVICE.md).
- [Commerce contract](dto-contracts/COMMERCE_SERVICE.md), [Fulfillment contract](dto-contracts/FULFILLMENT_SERVICE.md), [Notification contract](dto-contracts/NOTIFICATION_SERVICE.md).
- [API spec Cart/Order](API_SPEC_CART_ORDER.md), [kiến trúc](ARCHITECTURE.md), [ERD alignment](ERD_V2_ALIGNMENT.md).
- [Code baseline GitLab dùng để rà soát](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_7/-/tree/2b2841fb1f548cdce83af557237d36754e5b573b).

Các liên kết file mở working tree hiện tại. Bảng rà soát và kết quả kiểm tra ngày 30/10 là bằng chứng lịch sử; trạng thái tích hợp mới hơn được nêu trong ghi chú cập nhật ngày 02/11 ở đầu tài liệu.
