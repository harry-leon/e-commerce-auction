# TT-S3-06 — Snapshot Commerce cho Fulfillment

`GET /api/v1/internal/commerce/shipping-orders/{orderId}` trả `ApiResponse.data` gồm orderId, orderNo, status, recipientName, recipientPhone, shippingAddress, paymentMethod, priceFinalized, total, fulfillmentStatus. Đây là dữ liệu hiện tại đã lưu ở Commerce, không phải một lệnh giao hàng hoặc snapshot bất biến được ký. Không trả buyer email/ID, payment evidence, token hoặc danh sách refund.

Token phải đồng thời có service identity `azp=nexus-fulfillment-service` và privilege `COMMERCE.SHIPPING_READ` theo ServiceIdentity chung. Browser, Admin và Auction không được thay thế. Security filter, controller và service đều chặn sai quyền. Chưa xác thực trả 401, sai identity/quyền 403, UUID sai 400, order không tồn tại 404. GET chỉ đọc, không sửa trạng thái/DB hoặc gọi carrier. Các route Auction cũ giữ nguyên policy.

## IAM và triển khai

Chưa tạo/cấp client hay role trong Keycloak thật. IAM owner cần tạo confidential service account `nexus-fulfillment-service` nếu chưa có, tắt direct-access/standard flow, tạo realm privilege COMMERCE.SHIPPING_READ và cấp riêng cho account đó cùng scope mapping cần thiết. Lưu secret tại môi trường triển khai, không ghi vào repo. Xác minh token thật có đúng azp, subject service account và quyền; fake JWT trong test chỉ xác minh policy ứng dụng. Không cấp privilege cho role Buyer/Seller/Admin hoặc Auction. Endpoint vẫn fail closed trước khi IAM được cấu hình.

API chỉ gọi trực tiếp Commerce trên mạng nội bộ. Không thêm route public gateway cho namespace này. Các service không truy cập DB Commerce trực tiếp; deployment vẫn cần giới hạn network ingress, API không tự thay thế network policy.

## Điều kiện sử dụng trước khi tạo shipment

- Giá chưa chốt trả total=null, không dùng estimatedTotal làm số tiền chính thức. GET không kết luận payment đã thành công chỉ từ paymentMethod/total; producer phải dựa vào trạng thái/payment/kho được xác nhận trong workflow.
- Đơn cũ thiếu tên/điện thoại/địa chỉ giữ null/giá trị cũ. Fulfillment phải chặn tạo vận đơn khi thiếu dữ liệu cần thiết.
- Order hiện chưa lưu districtId, wardCode, weightGrams hoặc kho xuất. API không đoán các giá trị đó; cần snapshot đóng gói thực tế và đối chiếu contract hãng trước khi gọi carrier.
- Đây chưa phải integration end-to-end: chưa có client Fulfillment gọi API này, producer Commerce tự tạo shipment, durable dispatch attempt/UNKNOWN hoặc đối soát mất ACK. Không bật retry mutation từ API snapshot. MR !150 chỉ bỏ retry bên trong resilience manager, không đảm bảo retry request từ ngoài là an toàn.

## Kiểm tra

MVC test bảo vệ client/scope, Admin/browser/Auction bị từ chối và response không lộ email. Unit test giữ null ở đơn cũ, không trả tổng chưa chốt, giữ giá snapshot khi đã chốt và báo đơn không tồn tại. Không dùng merchant/carrier hoặc Keycloak thật trong test này. IAM owner và Khang cần review contract trước khi nối client/producer.
