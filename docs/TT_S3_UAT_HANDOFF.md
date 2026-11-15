# Sprint 3 Tiến Thương — bàn giao và phần còn phải nghiệm thu

Đối chiếu ngày 14/11/2026 theo tám nhóm việc trong [kế hoạch](TT_SPRINT3_WORK_ORDER.md). Mã TT-S3 là mã phân công, không phải issue GitLab đã xác minh. Không coi số MR là số task mới hoặc đánh dấu cả sprint hoàn tất từ kết quả local.

## Trạng thái theo phạm vi phân công

| Nhóm | Implementation/bằng chứng đang có | Phần còn phải chốt hoặc chạy |
| --- | --- | --- |
| 01 Contract, quyền, state machine | [Contract refund](TT_S3_REFUND_INTEGRATION_CONTRACT.md), policy/khóa Order–Payment; claim tuần tự với tạo refund; namespace service chỉ cho đúng identity/privilege | BA và các owner xác nhận policy hủy sau thanh toán/bàn giao carrier, COD/partial refund/phí vận chuyển; source không thay cho quyết định nghiệp vụ |
| 02 Credentials, callback, sandbox | Runbook Merchant API, flags mặc định tắt, IAM và các adapter đã có code/test | Chưa có bằng chứng merchant refund/query, carrier sandbox, callback ingress/egress thực. Thương phối hợp Khang và người giữ cấu hình; secret đưa vào môi trường/kho secret, không đưa vào MR hoặc biên bản |
| 03 Refund entity/API/chống trùng | RefundRequestService, RefundController, V13; role Admin tạo, Buyer chủ đơn đọc; key replay/conflict và hạn mức trong transaction | Không cần tạo lại entity/API. Buyer/Seller không có quyền tự hoàn; acceptance trên môi trường nhóm vẫn chưa xác minh |
| 04 Provider, đối soát, recovery | RefundDispatchWorker, V14/V15, approval, UNKNOWN/REVIEW, query audit, manual-resolution và registry/hold; [runbook](TT_S3_REFUND_WORKER.md) | Query PAY không xác nhận refund; UNKNOWN không gửi mutation lại. Cần đối soát provider thật và policy cancellation/return trước duyệt refund; không tự biến Order thành REFUNDED hoặc restock |
| 05 Notification/push/health | Refund/shipping inbox/outbox, retry/replay, health backlog Admin; test socket STOMP/JWKS/expiry/reconnect ở [runbook](TT_S3_NOTIFICATION_STOMP_SECURITY.md) | HTTP outbox và simple broker hiện có; chưa có bằng chứng broker relay nhiều instance, alert receiver hoặc push đến thiết bị trên môi trường triển khai. Health UP không chứng minh kết nối provider/browser |
| 06 Commerce–Shipping–Notification | Snapshot/parcel, handoff V21, claim V22, preflight; Fulfillment guard V9, recovery V10, worker V13 và reconciliation V14; core đã vào dev qua !173–!176 | Test !177 và !178 đang mở tại lần fetch này. Worker vẫn cần review cấu hình/rollout; không bật live từ local PASS. Không backfill handoff hoặc giải phóng claim UNKNOWN tự động |
| 07 Receipt/invoice/quyền | [Payment receipt](TT_S3_PAYMENT_RECEIPT.md), [refund receipt](TT_S3_REFUND_RECEIPT.md): JSON tải từ snapshot/registry, Buyer sở hữu hoặc Admin, chặn trạng thái chưa xác minh | Đây là biên nhận nội bộ, không phải hóa đơn thuế/PDF/ký số. BA chốt phạm vi chứng từ và điều chỉnh; không xây hóa đơn điện tử khi chưa có yêu cầu/provider |
| 08 Regression/recovery/UAT | 23 test Node và 16 nhóm integration local PASS; HTTP/Keycloak/PostgreSQL/JVM thật, carrier giả lập; [report lưu kèm](evidence/tt-s3-carrier-lifecycle-local.json) | Pipeline GitLab, provider sandbox và UAT thường/đấu giá chưa có bằng chứng nghiệm thu. Cần người thực hiện và ký nhận kết quả riêng |

## Bằng chứng local và giới hạn

Report lưu kèm là bản sao JSON từ lượt `--carrier-lifecycle --skip-build`, `result=PASS`, `cleanup=true`, 16 nhóm test. File được sinh bởi harness, không chèn comment hoặc tự sửa PASS. Ba SHA-256 JAR đã đối chiếu với artifact đã chạy. `checkoutHead=6d8159a` là checkout lúc chạy; JAR được tái dùng từ backend dev `3410e0b` trong lượt !177, không phải build mới của !178. Các thay đổi giữa hai commit là test/CI/tài liệu, không đổi backend.

Source harness của lượt cuối được commit trong !178 (`ab73739`); report không tự xác thực source harness bằng chữ ký hoặc chứng minh môi trường triển khai. Không dùng report này để xác nhận SHA production, coverage hoặc pipeline GitLab.

Các tình huống local đã bảo vệ: JWT identity/role, mất ACK event và claim, retry/replay có audit, refund trước/sau claim, carrier nhận create nhưng mất phản hồi, dừng JVM trước ACK, manual recovery/reconcile, polling lỗi mạng, lấy hàng/giao thành công, không nhân đôi notification/event và giữ READ sau restart. Carrier là HTTP loopback; Payment SUCCESS/inventory CONFIRMED/handoff được seed. Không có thu/hoàn tiền, reservation hoặc callback provider thực trong lượt này.

## Thứ tự tích hợp và chạy nghiệm thu

1. Review/merge **!177 → !178 → MR bàn giao này** vào `dev`; kiểm tra pipeline thực và ghi URL/job/SHA. Chưa xác minh xanh tại lần bàn giao này. Không push trực tiếp `dev`, không tự merge các MR.
2. Chọn SHA triển khai và môi trường test riêng; ghi người giữ cấu hình, IAM client/role, callback URL và egress IP theo runbook hiện có. Không ghi token, secret, chữ ký hoặc raw signed request vào bằng chứng.
3. Thương/Khang xác minh sandbox merchant/carrier bằng dữ liệu test và lưu reference đã che thông tin nhạy cảm. Không thử mất ACK bằng cách tự hoàn tiền lần nữa; theo dõi requestId/UNKNOWN và đối soát theo runbook.
4. Đang/BA xác nhận policy hủy, return, phí và receipt; Hùng review quyền, Khang review carrier/kho, Thương thực hiện backend; phối hợp Hào kiểm tra Buyer/inbox/push. Đây là đề xuất người phối hợp theo kế hoạch, chưa phải xác nhận họ đã duyệt hoặc nhận việc.
5. Chạy các ca UAT dưới đây trên SHA đã chọn, ghi PASS/FAIL/BLOCKED và evidence. Có lỗi thì mở defect cụ thể; không đánh dấu PASS bằng cách sửa DB, bỏ guard hoặc bật flag ngoài kế hoạch rollout.

## Phiếu UAT còn phải điền trên môi trường nhóm

Tất cả ca dưới đây hiện là **CHƯA CHẠY trên môi trường nhóm**. Điền ngày, người thực hiện, SHA, môi trường, dữ liệu test đã che, kết quả và liên kết bằng chứng. Không dùng các ca local để tự điền PASS.

| Ca | Thao tác/dữ liệu | Kết quả cần quan sát |
| --- | --- | --- |
| U01 — mua thường | Buyer tạo đơn/attempt từ giá live, thanh toán sandbox, nhận IPN | Browser return không tự đánh dấu PAID; IPN đã xác minh, kho confirm rồi mới có handoff; một Payment/side effect theo key |
| U02 — winner auction | Winner hợp lệ mua đơn AUCTION, người khác thử truy cập | Chỉ đúng winner/chủ đơn có quyền, amount từ snapshot; kết quả payment/callback và thời hạn đúng contract đã chốt |
| U03 — callback trùng/muộn | Provider gửi trùng hoặc kết quả mâu thuẫn theo công cụ sandbox đã được chấp thuận | Không thu/hoàn/restock hai lần; kết quả mâu thuẫn cần review, không dùng status client để ghi thành công |
| U04 — refund đã duyệt | Admin tạo, replay cùng key và đổi body cùng key; sau đó duyệt request đủ điều kiện | 201 mới/200 replay/409 conflict; worker chỉ gửi sau approval và cấu hình đã xác minh; trạng thái theo bằng chứng provider |
| U05 — refund mất phản hồi | Mô phỏng lỗi mạng theo kế hoạch sandbox, đối soát requestId | UNKNOWN/REVIEW giữ hạn mức; không gửi mutation thứ hai; query PAY không giả refund SUCCEEDED |
| U06 — refund/dispatch cạnh tranh | Cùng đơn thử refund trước claim và claim trước refund | Một phía thắng theo khóa; không vừa tự hoàn vừa gửi hàng; claim chưa rõ không tự hết hạn |
| U07 — carrier lifecycle | Kiện đúng khối lượng/địa bàn, carrier xác nhận lấy/giao | Shipment → Commerce → inbox/push đúng chiều, cùng event không thông báo hai lần; không tự restock từ FAILED/RETURNED |
| U08 — recovery carrier | Mất ACK/restart trong môi trường test được kiểm soát; Admin đối soát bằng tracking provider | Guard UNKNOWN, không auto resend; manual-resolution và job reconcile có audit, replay ổn định; Buyer không thực hiện được |
| U09 — notification/quyền | Hai Buyer, Admin; token hết hạn, refresh/reconnect và đọc inbox | Không đọc/gửi đến user khác; socket hết hạn đóng, reconnect đúng danh tính, READ giữ nguyên khi replay |
| U10 — receipt | Chủ đơn/Admin tải payment/refund receipt; Buyer khác và trạng thái chưa xác minh thử tải | Đúng snapshot/registry và quyền, chặn chưa hoàn tất/hold/conflict; biên nhận thu gốc không bị sửa khi refund; không coi JSON là hóa đơn thuế |
| U11 — outage/health | Tạm dừng dependency theo kế hoạch, theo dõi backlog/dead-letter và replay | Không mất việc hoặc lộ payload/secret; Admin thấy health phù hợp, public không thấy chi tiết; receiver cảnh báo nếu đã triển khai phải có bằng chứng riêng |
| U12 — restart và bàn giao | Restart service có việc pending, đối chiếu audit/DB/provider, chạy lại pipeline trên SHA chọn | Không nhân đôi tác dụng phụ; đủ report/log đã che và người nghiệm thu. Không coi chỉ build local là pipeline xanh |

Sprint chỉ có thể chốt sau khi BA/nhóm thống nhất phạm vi, các ca bắt buộc có kết quả thật và các blocker còn lại được ghi nhận rõ; tài liệu này không thay chữ ký nghiệm thu.
