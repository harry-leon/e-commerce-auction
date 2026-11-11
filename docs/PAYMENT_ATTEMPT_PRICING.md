# Order/Payment — chốt giá theo Payment Attempt

Phạm vi: phần Tiến Thương sau cuộc họp Product–Inventory–Order. Tài liệu mô tả implementation của nhánh này, chưa xác nhận đã merge/deploy hoặc các thành viên khác đã hoàn thành phần phụ thuộc.

## 1. Hành vi thay đổi

| Bước | Hành vi |
| --- | --- |
| Create Order | Lưu SKU, số lượng, địa chỉ và giá dự kiến; official price/total null, priceFinalized=false. Không tự tạo VNPAY attempt hoặc URL. |
| Bắt đầu thanh toán | BE đọc giá Catalog hiện tại cho đơn REGULAR; AUCTION giữ giá settlement. Tính tổng, lưu snapshot từng dòng và giữ kho trước khi trả URL. |
| Attempt đang xử lý | Giá/số tiền giữ nguyên; không tính lại theo Catalog khi GET hoặc nhận callback. |
| IPN thành công | Xác minh chữ ký/merchant/amount/transaction, chốt OrderItem và tổng Order từ snapshot, ghi Payment và outbox trong transaction Commerce. |
| Chờ kho | Payment API trả PROCESSING; official đã chốt vì tiền đã xác thực, Order vẫn PENDING_PAYMENT. |
| Kho xác nhận | Worker commit nguyên tử toàn bộ phiếu ở Fulfillment; sau đó Order PAID, Payment API SUCCESS. |
| IPN thất bại xác định | Payment FAILED, đơn vẫn chờ và chưa có giá chính thức; release đúng ref attempt thường. AUCTION giữ phiếu tới deadline để winner có thể retry. |
| Retry FAILED | Cùng order, key mới, attempt mới, giá mới; ref kho mới tách khỏi attempt cũ. |
| URL hết hạn/chưa rõ kết quả | GET trả EXPIRED; DB vẫn PENDING. Không mở attempt mới chỉ dựa trên thời gian client, cần đối soát provider. |
| Success đến sau FAILED/hủy hoặc đã có tiền ở attempt khác | Ghi tiền/trạng thái REVIEW và trả REQUIRES_REVIEW; không ghi đè giá đã chốt hoặc tự giao/hoàn tiền. |

Ví dụ: tạo đơn 2 áo dự kiến 150.000/áo → attempt 1 lấy giá mới 160.000/áo, amount 320.000 → thất bại → attempt 2 lấy 170.000/áo, amount 340.000 → Catalog tăng nữa nhưng callback vẫn chốt 170.000/áo và tổng 340.000.

## 2. Contract FE và Inventory cần tích hợp

### Tạo đơn

POST /api/v1/orders giữ request và Idempotency-Key tạo đơn. Phần giá trong response mới:

    {
      "status": "PENDING_PAYMENT",
      "subtotal": null,
      "shippingFee": null,
      "discount": null,
      "total": null,
      "paymentMethod": "VNPAY",
      "paymentUrl": null,
      "estimatedSubtotal": 300000,
      "estimatedShippingFee": 0,
      "estimatedDiscount": 0,
      "estimatedTotal": 300000,
      "priceFinalized": false
    }

GET Order list/detail và các dòng OrderItem cũng phân biệt official nullable với estimated. OrderItem có estimatedUnitPrice/estimatedSubtotal; Order summary có estimatedTotal/priceFinalized. estimated là dự kiến lúc tạo, không phải snapshot của attempt mới nhất.

**Hào:** sau tạo đơn, gọi POST /api/v1/payments với body chỉ orderId. FE hiển thị Payment.amount/priceSnapshot cho khách trước khi điều hướng paymentUrl. Không dùng amount do FE tính để ghi nhận thanh toán. **Đang:** adapter list/detail phải giữ ý nghĩa null/chưa chốt, không ép thành 0 hoặc PAID.

### Tạo/replay attempt

Header Idempotency-Key là UUID. Giữ nguyên key khi gửi lại cùng thao tác; key mới khi khách chủ động thử lại sau FAILED. Key có phạm vi order; buyer ownership luôn được kiểm tra trước replay.

- Lần đầu vẫn hỗ trợ caller cũ không gửi key; khuyến nghị FE luôn gửi key.
- Cùng key đã tạo: trả đúng attempt cũ, kể cả FAILED, không lấy giá/giữ kho lại.
- Đã có PENDING: cùng key replay; caller không key có thể lấy attempt hiện tại. Key khác trả PAYMENT_IN_PROGRESS để không gắn nhầm key vào giao dịch cũ.
- PENDING hết hạn: trả PAYMENT_EXPIRED, không tự kết luận chưa thu tiền.
- FAILED không có key: PAYMENT_RETRY_KEY_REQUIRED.
- Sai UUID: PAYMENT_KEY_INVALID.
- Có tiền cần đối soát: PAYMENT_REQUIRES_REVIEW, chặn attempt mới.
- REVIEW là hàng rào toàn đơn: GET, replay cùng key và helper URL cũ đều trả REQUIRES_REVIEW/paymentUrl=null, kể cả attempt đang xem còn PENDING trong DB. Không sửa snapshot/trạng thái tiền của attempt đó chỉ để hiển thị.

Payment response thêm snapshot và ref kho:

    {
      "amount": 340000,
      "currency": "VND",
      "priceSnapshot": {
        "subtotal": 340000,
        "shippingFee": 0,
        "discount": 0,
        "total": 340000,
        "items": [
          {
            "orderItemId": "UUID",
            "skuId": "UUID",
            "quantity": 2,
            "unitPrice": 170000,
            "itemTotal": 340000
          }
        ]
      },
      "inventoryRefId": "UUID"
    }

amount là tiền VND; chỉ khi ký/gửi VNPAY mới nhân 100. Payment.id là TxnRef. Amount, JSONB snapshot và URL đều xuất phát từ cùng phép tính BE.

**Khang:** REGULAR mới reserve với refType=ORDER, refId=paymentId, mỗi attempt độc lập. COMMIT/RELEASE dùng inventoryRefId; orderId thật vẫn lưu trong Payment và outbox Commerce. AUCTION và attempt legacy tiếp tục ref=orderId. Tra ledger dùng inventoryRefId để không nhầm Order UUID với attempt UUID. Không thay API/schema Fulfillment trong MR này.

## 3. COD và đấu giá

- COD vẫn reserve theo orderId lúc tạo; official để null. Admin/hệ thống xác nhận thu tiền qua command PAID thì chốt dự kiến, lưu fulfillmentStatus=PENDING và một COD_COMMIT, Order vẫn PENDING_PAYMENT. Worker commit toàn bộ kho thành công mới chuyển PAID/CONFIRMED. Đây là xác nhận thu tiền thủ công, chưa tích hợp chứng từ thu COD từ đơn vị vận chuyển.
- Nếu kho hết hạn/không còn phiếu, COD chuyển CANCELLED/REVIEW, giữ nguyên giá chính thức và dấu vết thu tiền để đối soát; task kết thúc, không retry lỗi vĩnh viễn hoặc tự hoàn tiền. Timeout/mất ACK giữ chờ và retry cùng ref. Lặp command không ghi nhận thu tiền lần hai; đơn đang chờ kho/REVIEW bị chặn giao hàng và hủy như đơn chưa trả tiền.
- AUCTION lưu giá thắng ở estimatedUnitPrice/estimatedTotal; không lấy giá bán thường hoặc phí ship cấu hình mới để ghi đè settlement. Hạn thanh toán giữ theo handover.
- API nội bộ AuctionOrderResponse.total tiếp tục là giá settlement để tương thích bridge; không dùng trường này làm bằng chứng đã thu tiền. Các API buyer dùng priceFinalized để phân biệt.
- Chưa có API provider query/cancel/refund; giao dịch mơ hồ hoặc cần hoàn tiền được đưa về đối soát thủ công, không mở thanh toán mới tùy tiện.

## 4. Migration và triển khai

V10__payment_attempt_price_snapshots.sql là migration mới; không sửa V1–V9.

1. Backfill estimated từ giá cũ; giữ nguyên giá/trạng thái lịch sử đã hoàn tất.
2. Kiểm tra attempt PENDING cũ: amount, tổng đơn và từng dòng phải khớp. Nếu lệch hoặc thiếu dòng, migration dừng và rollback để vận hành đối soát.
3. Backfill price_snapshot và inventory_ref_id cho payment cũ trước khi xóa official của đơn chưa thu tiền. Không đọc giá Catalog mới khi nâng cấp.
4. Cho phép official null, thêm price_finalized, bỏ unique một payment/order; chỉ một PENDING/SUCCESS VNPAY mỗi order. Provider reference SUCCESS/REVIEW không được trùng.
5. Không tạo lại notification hoặc task kho lịch sử. Worker tiếp tục xử lý task COMMIT cũ có targetId=orderId.

Triển khai phối hợp với FE đã đổi contract; không deploy backend này đơn lẻ rồi coi checkout FE cũ còn tương thích. Review migration trên bản sao DB và sao lưu trước nâng cấp; không rollback schema bằng cách tự điền giá mới cho các đơn đang chờ.

Commerce và Fulfillment có transaction riêng, không phải transaction phân tán. Remote reserve đã thành công nhưng Commerce rollback sẽ release đúng ref. Nếu process chết hoặc cleanup mất mạng, TTL kho là phương án cuối; chưa có outbox bù trừ bền vững cho crash trước commit attempt.

### V11 — đối soát kho COD

V11__cod_inventory_settlement.sql bổ sung fulfillment_status cho Order và action COD_COMMIT. API list/detail trả fulfillmentStatus: PENDING, CONFIRMED hoặc REVIEW; null với VNPAY/luồng lịch sử chưa có dữ liệu này.

COD V10 đã thu tiền nhưng còn task CONFIRM chưa xong được chuyển sang một task COD_COMMIT để xác nhận kho nguyên tử. Nếu đang PAID, migration đưa về PENDING_PAYMENT và thêm lịch sử, giữ nguyên giá/tiền. Task CONFIRM cũ được đánh dấu đã thay thế; COD đã hoàn tất không bị phát task lại. Đơn cũ đã đi xa hơn PAID được giữ trạng thái, worker đánh dấu REVIEW để vận hành xử lý, không tự kéo lùi việc giao hàng đã xảy ra.

Triển khai V11 phối hợp các instance Commerce/worker; tạm dừng thao tác xác nhận COD trong lúc nâng cấp. Không sửa checksum V10 để áp dụng sửa lỗi này.

## 5. Phụ thuộc chưa triển khai theo xác nhận cuộc họp

- **Đang/BA:** chưa chốt công thức giờ cao điểm, mua nhiều cùng nhà sản xuất, Promotion, thứ tự cộng dồn và làm tròn.
- **Hùng/Catalog:** Min Price và validation giá bán thuộc phần Catalog; contract hiện chưa có dữ liệu nhà sản xuất/danh mục đủ cho các rule mới.
- Phần tính giá BE hiện chỉ dùng giá SKU live và phí ship cấu hình; discount=0, voucher chưa hỗ trợ. Không tự đặt hệ số hoặc báo các chính sách trên đã xong.
- **Hào/Đang:** sửa Buyer checkout và adapter Order, tách luồng mô phỏng; không nằm trong MR backend này.
- Chưa có bằng chứng giao dịch merchant VNPAY thật hoặc kiểm thử toàn stack với Keycloak/Catalog/Fulfillment thật cho contract mới.

## 6. Review và bằng chứng

- **Hùng:** review schema/giá nullable, priceSnapshot, quyền và transaction.
- **Khang:** review ref kho theo attempt, retry/outbox và race với hết hạn.
- **Hào/Đang:** review thay đổi contract trước khi tích hợp FE.

Ảnh Swagger, transcript HTTP đã lọc và cách tái lập: [bằng chứng kiểm thử](test-evidence/payment-attempt-pricing/README.md).
