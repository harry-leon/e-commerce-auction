# Thứ tự gộp sau xử lý conflict với dev

Đã đồng bộ với dev 47beec3 và giữ chức năng ở cả hai phía, không dùng checkout ours/theirs để bỏ test hoặc hạ schema. Đây là thứ tự tích hợp/review, không phải xác nhận pipeline GitLab xanh hoặc quyền bật dispatch/provider.

| Thứ tự | MR | Ghi chú |
| --- | --- | --- |
| 1 | !165 | Receipt thu tiền, giữ context test Shipping từ dev |
| 2 | !166 | Refund receipt, đã nhận bản sửa !165 |
| 3 | !168 | Handoff producer, đã nhận receipt !166 để tránh conflict context test lần nữa |
| 4 | !172 | Dispatch claim, đã nhận !168 và toàn bộ test receipt/handoff |
| 5 | !171 | Kiện Fulfillment, đã nhận !172 và giữ Commerce 22/Fulfillment 12 |
| Độc lập | !170 | Giữ session/menu Admin và tìm kiếm/lọc tồn kho Discovery của dev |

Merge MR trước rồi refresh MR tiếp theo; không squash/rebase lại nhánh nguồn một cách tùy tiện khi chưa kiểm tra dependency. Nếu dev có commit mới sau mốc trên thì cần kiểm tra lại mergeability. !169 đã nằm trong dev; không tạo commit giả để gộp lại.

## Kiểm chứng local

- Receipt/refund sau đồng bộ dev: 474 test PASS, không skip.
- Commerce trên bản ghép cuối receipt/handoff/claim: 488 test PASS, không skip; clean verify và Checkstyle exit 0.
- Fulfillment parcel sau đồng bộ dev: 211 test PASS, không skip; clean verify và Checkstyle exit 0.
- Dataset tổng hợp Commerce 22/Fulfillment 12: cả 7 service qua migration, seed replay, reset replay, reseed; 19 test Node demo PASS. PostgreSQL fixture đã dừng.
- !170: typecheck, lint, production build và Prettier riêng Layout PASS; 5 KeycloakClientTest và Checkstyle PASS.

Frontend !170 còn 33 test fail trong auctions API, orders, seller/admin auctions và shipping-options-fee. Chạy lại đúng bốn file trên dev 47beec3 cho 33 ca fail có tên trùng hoàn toàn; không sửa API hoặc bỏ assert để che lỗi nền. Suite !170 có 385 pass/33 fail. Format toàn frontend còn 32 file; dev gốc cũng chưa sạch format. Các lỗi này phải được xử lý/đánh giá riêng trước khi khẳng định đủ điều kiện pipeline/merge.

Chưa merge dev, bật flag dispatch, đổi IAM hoặc gọi provider thật trong quá trình xử lý conflict. Giới hạn claim/parcel và yêu cầu review vẫn theo runbook từng MR.
