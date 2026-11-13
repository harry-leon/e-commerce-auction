# Dữ liệu demo Nexus

## Phạm vi và ý nghĩa

`scripts/seed-demo.mjs` thêm bộ `nexus-demo-v1` vào đúng các DB trong `docker compose config` hiện tại. Công cụ quản trị seed kết nối riêng từng service; code service không được cấp thêm quyền đọc DB khác.

Đây là dữ liệu **giả lập có quan hệ nhất quán**, không phải dữ liệu khách hàng, giá thị trường hoặc giao dịch tài chính thật. Tên người/cửa hàng, CCCD, số điện thoại, địa chỉ, đánh giá và lịch sử đơn đều phục vụ demo. Domain email `.example` không dùng để nhận thư. Không xác minh OTP/eKYC, không gửi email và không gọi VNPAY.

| Nhóm                                      |                                 Số lượng |
| ----------------------------------------- | ---------------------------------------: |
| Tài khoản IAM và hồ sơ                    | 8: 1 admin, 1 support, 3 seller, 3 buyer |
| Danh mục                                  |                       12: 6 gốc và 6 con |
| Sản phẩm / SKU / ảnh minh họa             |                             18 / 36 / 18 |
| Sản phẩm ACTIVE / DRAFT                   |                                   17 / 1 |
| Kho mới / bản ghi tồn kho                 |    2 / 42; còn dùng kho `WH_001` hiện có |
| Biến động kho / phiếu giữ hàng            |                                  80 / 21 |
| Đơn hàng / dòng hàng / payment fixture    |                               9 / 18 / 9 |
| Đơn COMPLETED / DELIVERED / CANCELLED     |                                3 / 3 / 3 |
| Giỏ ACTIVE / dòng giỏ                     |                                    3 / 6 |
| Shipment / lịch sử shipment               |                                   6 / 36 |
| Phiên đấu giá / bid / settlement          |                                6 / 8 / 2 |
| Product review / peer rating / reputation |                                3 / 6 / 8 |
| Thông báo IN_APP / attempt minh họa       |                                  18 / 18 |

Giá là VND nguyên. SKU có hai phiên bản, ảnh SVG minh họa tự chứa trong DB, không phụ thuộc CDN. Có SKU hết hàng để demo lỗi tồn kho và sản phẩm DRAFT để demo phê duyệt. Mỗi giỏ/đơn chỉ chứa hàng của một seller. Buyer không đồng thời là seller của chính đơn đó.

Payment lịch sử mang `merchant_code=DEMO_ONLY`, `provider_ref=NXDEMO-NOT-A-REAL-TRANSACTION-*`, không có URL thanh toán và không thêm outbox/tác vụ payment. Trạng thái SUCCESS/FAILED của các dòng này chỉ là fixture. Không dùng làm bằng chứng đã thu tiền hoặc gọi IPN để ép thanh toán. Không seed voucher/refund/invoice vì các luồng tương ứng chưa đầy đủ hoặc chưa có tài liệu hóa đơn thật.

Các màn FE còn mock hoặc hard-code (ví dụ đấu giá/reputation, một số trường rating/tồn kho) không tự chuyển sang DB khi seed. Bộ seed kiểm tra DB và các API đã có; không khẳng định toàn bộ màn FE đã tích hợp.

## Chạy từ thư mục gốc repository

Yêu cầu Node có `fetch`, Docker đang chạy, container `postgres`, Gateway, Keycloak và các backend đã sẵn sàng. Credential DB/IAM lấy từ cấu hình Compose trong bộ nhớ; không in password/token hoặc ghi vào SQL.

```powershell
# Chỉ đọc schema/IAM và xuất plan/SQL dưới Git directory; chưa ghi dữ liệu nghiệp vụ.
node scripts/seed-demo.mjs --plan

# Chạy migration thật và seed hai lần trong DB local tạm; không ghi DB nghiệp vụ/IAM.
node scripts/seed-demo.mjs --check

# Thay fingerprint bằng Target được in từ --plan; đích khác làm công cụ dừng.
node scripts/seed-demo.mjs --apply --target <fingerprint>

# Đọc lại đúng các PK của dataset và kiểm tra API/login, không tự reset dữ liệu.
node scripts/seed-demo.mjs --verify

node --test scripts/demo/dataset.test.mjs scripts/demo/runtime.test.mjs
```

`--apply` tự chạy lại kiểm thử DB tạm trước lần ghi đầu tiên. Phiên bản hiện hỗ trợ: User V6, Catalog V7, Fulfillment V6, Commerce V12, Auction V3, Reputation V3 và Notification V5. Version/cột/kho mặc định không khớp thì dừng. SKU chỉ lưu `original_price` trong Catalog; mã kho thuộc `seller_skus` của Fulfillment. Payment lịch sử có snapshot giá và `inventory_ref_id` theo Commerce V10, vẫn mang marker DEMO_ONLY.

## Reset fixture để chạy lại demo

Chỉ dùng trên môi trường demo riêng. Đọc fingerprint từ `--plan`, dừng toàn bộ backend/Gateway/web của môi trường đó, giữ PostgreSQL và Keycloak rồi chạy:

```powershell
docker compose stop user-service catalog-service commerce-service auction-service fulfillment-service notification-service reputation-service api-gateway web
node scripts/seed-demo.mjs --reset --target <fingerprint>
docker compose up -d
node scripts/seed-demo.mjs --apply --target <fingerprint>
```

Reset chỉ xóa đúng PK của bộ fixture trong manifest local, giữ User/IAM, schema, migration và volume. Trước khi xóa, mỗi DB khóa bảng và kiểm tra FK: có row ngoài fixture tham chiếu vào fixture thì dừng, kể cả FK CASCADE. Không tự xóa đơn/payment/tác vụ phát sinh từ buổi demo để vượt hàng rào này; cần đối soát hoặc dùng môi trường demo riêng mới. Các tham chiếu nghiệp vụ không có FK giữa DB không thể được phát hiện đầy đủ: không dùng reset trên DB dùng chung hoặc production.

Mỗi service là một transaction, không có rollback xuyên DB. Nếu lỗi sau vài service, checkpoint `resetPhases` cho phép chạy lại cùng lệnh để hoàn tất; không chạy seed khi reset đang dở. Reset thành công làm mới mốc thời gian nghiệp vụ cho phiên/giữ kho, giữ mốc User và password cũ. Công cụ không cập nhật realm hoặc reset mật khẩu.

Kiểm chứng migration, seed hai lần, reset hai lần, reseed và chặn FK CASCADE ngoài fixture trên cluster PostgreSQL tạm độc lập:

```powershell
node scripts/check-demo-local.mjs 'C:/Program Files/PostgreSQL/18/bin'
```

Script không đọc `.env`; cluster chỉ nghe loopback và luôn được dừng trong `finally`. Thư mục tạm được giữ để kiểm tra. CI chạy cùng script bằng PostgreSQL 16 và user postgres trong container riêng.

## Tài khoản và credential local

Email: `demo.admin@nexus.example`, `demo.support@nexus.example`, ba seller `demo.seller-minhan@nexus.example`, `demo.seller-thanhlam@nexus.example`, `demo.seller-annhien@nexus.example` và ba buyer `demo.buyer-mai@nexus.example`, `demo.buyer-nam@nexus.example`, `demo.buyer-linh@nexus.example`.

Password demo ngẫu nhiên được tạo lúc apply và lưu tại đường dẫn `credentials.json` mà công cụ in ra, dưới `<git-dir>/nexus-demo/<fingerprint>/`. File này chỉ ở local và không được commit. Dùng chung password ban đầu cho tám tài khoản demo; chạy lại không reset password đã có.

Keycloak cấp UUID thật; UUID đó được dùng làm `users.id`, `keycloak_id` và mọi tham chiếu seller/buyer/bidder. Khi tạo user, công cụ gửi marker `nexus_demo_dataset=nexus-demo-v1` và chỉ gán các role đã tồn tại. Keycloak User Profile có thể lọc custom attributes; khi đó resume chỉ nhận đúng UUID đã checkpoint sau khi tạo tài khoản thành công, kèm email/username khớp. Không nhận theo email đơn thuần. Nếu mất manifest mà IAM không lưu marker, công cụ dừng thay vì tự nhận tài khoản. Không nâng quyền hoặc sửa tài khoản của nhóm.

## Chống trùng và phục hồi

- UUID cho dữ liệu nghiệp vụ và mã `NXDEMO-*` ổn định. Timestamp được cố định trong `state.json` từ lần đầu.
- `--apply` chỉ INSERT, không UPDATE/DELETE/TRUNCATE bảng nghiệp vụ. PK trùng có nội dung khác hoặc natural-key conflict làm rollback transaction của service đó. `--reset` là chế độ riêng có kiểm tra fingerprint, manifest và tham chiếu FK như mô tả ở trên.
- Mỗi DB có transaction và advisory lock riêng. Không có distributed transaction; lỗi ở service sau không rollback dữ liệu đã commit của service trước. `state.json` lưu checkpoint để tiếp tục khi chạy lại.
- Chỉ lỗi DNS PostgreSQL trước kết nối được retry ngắn; không retry lỗi chưa rõ kết quả COMMIT. HTTP GET gặp 502/503/504 có tối đa hai lần thử lại và được ghi vào báo cáo; POST không tự retry.
- Lock file ngăn hai lượt apply trên cùng máy. Nếu tiến trình bị kill cứng, kiểm tra không còn tiến trình seed trước khi xử lý `apply.lock` còn lại; không xóa dữ liệu DB để thử lại.
- Lần apply đã hoàn tất chỉ verify, không tự refill giỏ, nhập thêm kho hoặc tạo lại đơn. Dữ liệu đã thay đổi qua buổi demo có thể khiến kiểm tra baseline báo chênh lệch; công cụ không ghi đè để che chênh lệch đó.
- Tất cả SQL xuất ra chỉ để review. Dùng CLI để giữ kiểm tra fingerprint, schema, IAM và checkpoint; không chạy SQL preview riêng lẻ trước khi UUID IAM đã được resolve.

## Kiểm tra nhất quán

Kiểm tra thuần dữ liệu trước seed: định dạng phone/CCCD, UUID duy nhất, seller tồn tại, SKU/giá VND, subtotal/tổng tiền/payment, lịch sử trạng thái, ledger vật lý và reserved, winner/giá chốt, review/rating thuộc đơn hoàn tất, shipment đúng trạng thái và thông báo đúng buyer.

Kiểm thử PostgreSQL dùng migration của chính repository, chạy seed hai lần và kiểm tra số PK. Chỉ các DB có tên ngẫu nhiên do lượt chạy tạo được dọn trong `finally`. Sau apply, công cụ đọc lại các PK của dataset ở từng DB, chạy kiểm tra chéo rồi kiểm tra login/role/profile, sản phẩm ACTIVE, tồn kho, giỏ, đơn và hộp thư qua HTTP. Kết quả lưu trong `verification.json`; lỗi phải được xử lý, không bỏ qua để báo thành công.

Phiên ACTIVE/SCHEDULED và phiếu giữ kho là dữ liệu có thời hạn tính từ mốc `asOf`. Scheduler thật có thể chốt phiên/nhả kho sau khi đến hạn. Đây là thay đổi nghiệp vụ bình thường; seed không tự lùi thời gian hoặc sửa lịch sử đã được ứng dụng xử lý.

## Lỗi BE phát hiện khi kiểm chứng dữ liệu

API danh sách đơn có thể trả 500 `LazyInitializationException` khi đã có đơn/items thật: overload `LocalDateTime` mặc định trong `OrderService` gọi nội bộ bản `Instant`, nên annotation transaction trên implementation không bao phủ điểm vào overload đó. Bản sửa đi kèm khai báo `@Transactional(readOnly=true)` ngay tại các default overload buyer/seller/admin và chi tiết dạng boolean. Không cần đổi trạng thái đơn hoặc bật Open Session in View để tránh lỗi.

`OrderReadTransactionTest` dùng proxy Spring thật và transaction manager fixture để tái hiện mất transaction trước sửa, rồi kiểm tra cả bốn điểm vào sau sửa. Khi review/publish, nên tách phần sửa Commerce này thành MR riêng với bộ seed theo quy ước repository. Cần deploy image Commerce chứa bản sửa để bước kiểm chứng HTTP của seed đi qua đầy đủ.

## Kết quả kiểm chứng local ngày 04/11/2026

- Bộ fixture đã được thêm vào 7 DB Compose hiện đang dùng; UUID user khớp IAM thực. Không reset/xóa dữ liệu đang có.
- 17 test seed qua. Seed chạy hai lần trong từng DB tạm với migration thật, không trùng PK và không vi phạm FK/CHECK.
- Sau khi đọc lại dữ liệu, các phép đối chiếu tổng tiền, ledger/tồn kho, sở hữu đơn, winner, rating và người nhận thông báo đều qua.
- HTTP: 8 tài khoản login/role/profile đúng; mỗi buyer có 3 đơn, mỗi đơn 2 dòng, giỏ 2 dòng, hộp thư 6 tin/3 chưa đọc; 17 sản phẩm ACTIVE xuất hiện ở API public và stock đầu tiên khớp DB.
- Chrome: login buyer 200, giỏ và chi tiết sản phẩm hiện đúng hàng demo, ảnh SVG tải được, không có page error ở các trang đã kiểm tra.
- Commerce: 293 test qua, 16 test DB bị skip vì không cấp `NEXUS_TEST_DB_URL`; Checkstyle 0 lỗi và coverage gate qua. Đây không phải kết quả chạy lại toàn bộ Postgres integration suite; kiểm chứng DB seed ở trên là bộ kiểm tra riêng.
- Báo cáo chi tiết và credential local nằm dưới `<git-dir>/nexus-demo/8dfaeb32d506aaec/`. Không commit `credentials.json` hoặc chia sẻ password demo công khai.
