# TT-S3-07 — Chứng từ điều chỉnh cho refund (credit note)

Bổ sung phần còn thiếu của nhóm "invoice/receipt": biên nhận thu gốc và biên nhận hoàn tiền đã có, nhưng chưa có
chứng từ **điều chỉnh** liên kết hai chứng từ đó khi refund hoàn tất.

## 1. Phạm vi

| Hạng mục | Trạng thái |
| --- | --- |
| Payment receipt (`GET /api/v1/payments/{id}/receipt`) | Đã có từ trước, không thay đổi |
| Refund receipt (`GET /api/v1/refunds/{id}/receipt`) | Đã có từ trước, không thay đổi |
| **Credit note (mới)** | Phát hành + tra cứu, tài liệu này |
| Hóa đơn điện tử / ký số / PDF | **Chưa có**, ngoài phạm vi MR này |

Đây là chứng từ **nội bộ** (`INTERNAL_REFUND_CREDIT_NOTE`), không phải hóa đơn thuế. Không thay thế chứng từ
điều chỉnh do nhà cung cấp hóa đơn phát hành và không chứa mã số thuế, chữ ký số hay số seri hóa đơn.

## 2. API

| Method | Đường dẫn | Quyền | Kết quả |
| --- | --- | --- | --- |
| POST | `/api/v1/refunds/{id}/credit-note` | `ROLE_ADMIN` | 201 mới / 200 replay |
| GET | `/api/v1/refunds/{id}/credit-note` | `ROLE_BUYER` chủ đơn, `ROLE_ADMIN` | 200 / 404 |

Body POST: `{ "note": "..." }` (bắt buộc, ≤ 500 ký tự, không ký tự điều khiển) và header `Idempotency-Key: <UUID>`.

Response:

```json
{
  "version": 1,
  "documentType": "INTERNAL_REFUND_CREDIT_NOTE",
  "documentNo": "CN-<refundId>",
  "creditNoteId": "...",
  "refundId": "...",
  "paymentId": "...",
  "orderId": "...",
  "originalReceiptId": "PAY-<paymentId>",
  "refundReceiptId": "REFUND-<refundId>",
  "amount": 50000,
  "currency": "VND",
  "evidenceSource": "PROVIDER | MANUAL",
  "issuedAt": "2026-11-15T10:00:00Z"
}
```

Không trả `note`, `idempotencyKey` hay actor. Số chứng từ sinh từ `refundId` nên tra cứu lại luôn cùng số.

## 3. Quy tắc nghiệp vụ

1. **Chỉ phát hành khi refund đã có biên nhận hợp lệ.** Service gọi lại `RefundReceiptService.download` thay vì tin
   cột `status='SUCCEEDED'`. Nhờ đó credit note tự động thừa hưởng toàn bộ kiểm tra: registry bằng chứng khớp
   source/amount/currency, không có `refund_payment_holds`, không có `refund_result_conflicts`, quyền chủ đơn.
2. **Không sửa chứng từ gốc.** Bảng `refund_credit_notes` chỉ đọc `payments`/`refund_requests`; không có UPDATE nào
   lên `payments`, `orders` hay `refund_provider_receipts`.
3. **Một refund một credit note.** `UNIQUE(refund_id)` chặn bản ghi thứ hai ở tầng DB kể cả khi Admin dùng key khác.
4. **Idempotency theo actor.** `UNIQUE(issued_by, idempotency_key)`. Replay cùng key + cùng `refundId` + cùng note trả
   200; đổi nội dung cùng key trả 409.
5. **Không mở đường hoàn tiền mới.** Credit note không đổi hạn mức, không giải phóng hold, không phát event.
6. **404 trước 409.** Refund không tồn tại hoặc Buyer không sở hữu vẫn nhận 404 để không dò được refund của người
   khác; chỉ khi biên nhận từ chối vì lý do trạng thái/bằng chứng mới quy về 409.

## 4. Migration

`V24__refund_credit_notes.sql`:

- Bảng mới `refund_credit_notes` với FK tới `refund_requests`, `payments`, `orders`.
- `CHECK (amount > 0 AND amount = trunc(amount))`, `CHECK (currency = 'VND')`, `CHECK (evidence_source IN ('PROVIDER','MANUAL'))`.
- `UNIQUE(refund_id)`, `UNIQUE(issued_by, idempotency_key)`, `UNIQUE(document_no)`.
- Index `idx_refund_credit_notes_payment(payment_id, issued_at)` cho đối soát theo giao dịch thu.
- **Không backfill** refund lịch sử: thiếu bằng chứng đối soát tại thời điểm nâng cấp nên chỉ phát hành khi có yêu cầu.

`BaselineMigrationPostgresTest` được cập nhật từ `22` lên `23` và thêm assert bảng mới rỗng sau nâng cấp.

## 5. Kiểm chứng

| Test | Nội dung |
| --- | --- |
| `RefundCreditNoteServiceTest` (7) | Sinh số chứng từ ổn định, liên kết hai biên nhận, replay, đổi nội dung cùng key, refund chưa sẵn sàng, refund không tồn tại, bản ghi thứ hai, validation đầu vào, `get` kiểm quyền qua biên nhận |
| `RefundCreditNoteControllerTest` (3) | 401 anonymous, 403 Buyer/Seller/service, 400 thiếu key, 422 thiếu note, 201 khi Admin tạo, `note` không lộ ra response, Buyer/Admin đọc được |
| `PaymentPostgresTest` (+1) | Trên PostgreSQL thật: chưa SUCCEEDED → 409 và không có dòng; sau `manual-resolution` phát hành được; replay cùng key; key mới → 409; Payment gốc không đổi |
| `BaselineMigrationPostgresTest` | Flyway nâng lên V24, bảng mới rỗng |

Kết quả local 15/11/2026 (PostgreSQL 16 trong container tạm, không dùng DB nhóm):

```
Tests run: 515, Failures: 0, Errors: 0, Skipped: 0
You have 0 Checkstyle violations.
BUILD SUCCESS
```

## 6. Sửa lỗi có sẵn trên `dev`

`dev` tại `e69354b` không test-compile được: commit `52b0cc8` (dispute lifecycle) gọi
`orderRepository.findForUpdate(...)` trong `OrderServiceTest` nhưng repository chỉ khai báo `findForPayment(...)`
(và `OrderServiceImpl` cũng gọi `findForPayment`). MR này sửa 6 chỗ trong test cho khớp API thật. Đây là fix
compile, không đổi hành vi nghiệp vụ. Nên merge phần fix này trước hoặc cùng MR để pipeline các nhánh khác chạy được.

## 7. Giới hạn còn lại

- Chưa có UI Admin phát hành/tải credit note.
- Chưa tích hợp nhà cung cấp hóa đơn điện tử, ký số, PDF và số seri chứng từ theo quy định.
- Chưa có credit note cho hoàn tiền từng phần (policy partial refund vẫn chờ BA chốt).
- Chưa xác minh pipeline GitLab trên MR này.
