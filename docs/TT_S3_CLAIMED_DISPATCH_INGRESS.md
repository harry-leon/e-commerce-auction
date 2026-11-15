# Đóng ingress tạo vận đơn legacy khi dùng claimed dispatch

## Vấn đề và thay đổi

Worker đã lấy snapshot/kiện, claim/preflight và giữ dấu gửi carrier, nhưng `POST /api/v1/shipments` và OrderPaid vẫn có thể tạo vận đơn mà không đi qua Commerce claim. Đúng role/service identity không phải bằng chứng tài chính. Đường này cần đóng trước khi coi việc “không có claim” là điều kiện chốt Order đã refund.

`ClaimedDispatchIngressPolicy` chặn legacy nếu **worker bật HOẶC required bật**:

| `app.shipping.dispatch-worker-enabled` | `app.shipping.claimed-dispatch-required` | Legacy create/OrderPaid |
| --- | --- | --- |
| false | false | Giữ hành vi cũ |
| true | false hoặc true | 409 CLAIMED_DISPATCH_REQUIRED |
| false | true | 409, dùng cho toàn fleet/instance không chạy worker |

Cấu hình required tương ứng `SHIPPING_CLAIMED_DISPATCH_REQUIRED`, mặc định false, đã map Compose. Không thể đặt required=false để mở lại legacy khi worker vẫn bật. Không bật flag nào trên môi trường nhóm trong thay đổi này.

HTTP create bị chặn trước ShippingService, kể cả Admin/Seller/Support/SHIPPING.CREATE_SHIPMENT hoặc client tự gửi key `dispatch-job:{uuid}`. HTTP OrderPaid vẫn yêu cầu đúng Commerce identity/SHIPPING.ORDER_PAID nhưng cũng bị chặn trong controlled mode. `createShipmentForPaidOrder` kiểm tra lại policy trước đọc repository, bảo vệ cả Spring event listener nội bộ và replay OrderPaid cũ.

Worker vẫn gọi primitive `createShipment` có guard để tạo shipment đã claim, không đi qua helper legacy. GET, quote/tracking, đăng ký kiện, manual resolution, job reconciliation và Pick/Pack/Ship không bị thay đổi bởi gate này. Gate không tự tạo kiện, claim, trả hàng, hoàn tiền hoặc xóa dấu dispatch. Khi cần đọc vận đơn cũ, dùng GET thay vì POST legacy.

## Rollout cùng workflow refund

1. Giữ Commerce refund-finalization và Fulfillment dispatch worker tắt trong lúc chuẩn bị; review các MR và SHA binary triển khai.
2. Triển khai binary có gate trên **mọi instance Fulfillment**; đặt `SHIPPING_CLAIMED_DISPATCH_REQUIRED=true` toàn fleet, kể cả instance không chạy worker. Dừng/drain binary cũ và các request đang gửi trước khi coi ingress đã đóng. Instance cũ không hiểu flag nên chỉ thêm biến môi trường không đủ.
3. Kiểm tra POST legacy bằng caller có quyền phải trả 409, không phải 200/201 hoặc 401 do token sai; GET/prepare/recovery vẫn hoạt động đúng quyền. Dừng producer OrderPaid cũ; listener nội bộ bị chặn sẽ ghi lỗi, không tự chuyển event này thành durable parcel/handoff.
4. Đối soát các attempt UNKNOWN/shipment legacy và lệnh carrier ngoài hệ thống đã có **trước rollout**. Gate không xóa các lệnh đó hoặc chứng minh chúng chưa được carrier nhận; không mở claim/refund dựa trên tuổi request.
5. Chỉ sau kiểm tra trên, IAM/config provider và review policy, bật worker cho instance được chọn. Nếu dùng API chốt Order refund của !181, chỉ bật sau khi đã kiểm soát ingress và các lệnh cũ như trên; test gate không thay thế provider sandbox/UAT.

Rollback worker về false vẫn giữ required=true để tránh vô tình mở đường legacy khi còn claim/UNKNOWN. Mở lại legacy là quyết định rollout riêng, không là hành động cleanup tự động. Không thay event schema, DB/migration hoặc role OAuth trong MR này.

## Kiểm chứng

MVC dùng property worker=true và policy thật để chặn các role legacy, key giả và Commerce đúng identity; không tương tác ShippingService/kho. Suite legacy mặc định false giữ các ca create/identity cũ. Unit bảo vệ OR của hai cờ, listener gọi service dừng trước repository và primitive worker vẫn hoạt động. PostgreSQL guard/worker/recovery hiện có chạy trong bộ Fulfillment verify.

Local ngày 15/11/2026: Fulfillment `clean verify checkstyle:check` exit 0, **239 test**, 0 fail/error/skip; JaCoCo LINE **81,66% (3228/3953)**, Checkstyle 0 violations. Cluster PostgreSQL tạm đã dừng. Compose config với credentials giả và diff check PASS. Chưa xác minh pipeline, fleet rollout hoặc carrier sandbox; không chạy lại bộ ba JVM của !178 trong lượt này.
