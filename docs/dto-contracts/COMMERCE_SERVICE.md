# Commerce Service DTO Contract

> **Gateway paths:** `/api/v1/cart/**`, `/api/v1/checkout/**`, `/api/v1/orders/**`, `/api/v1/payments/**`
>
> **CURRENT:** chỉ có Cart
>
> **Success wrapper:** `ApiResponse<T>`; xem [COMMON.md](COMMON.md)

## 0. DTO inventory

| DTO trong source | Vai trò | Có được controller expose |
|---|---|---|
| `AddToCartRequest`, `UpdateCartItemRequest`, `MergeCartRequest` | Request body Cart | Có |
| `CartResponse`, `CartItemResponse` | Public response Cart | Có |
| `SkuInfo` | DTO nội bộ Commerce nhận từ Catalog | Không trực tiếp; ánh xạ từ `SkuSnapshot` |
| `PageResponse<T>` | Wrapper phân trang dành cho nghiệp vụ sau này | **Không**; chưa có controller dùng |

## 1. Endpoint matrix hiện có

| Method | Path | Request | `data` khi success | HTTP | Xác thực |
|---|---|---|---|---|---|
| `GET` | `/cart` | — | `CartResponse` | `200` | JWT hợp lệ |
| `POST` | `/cart/items` | `AddToCartRequest` | `CartResponse` | `200` | JWT hợp lệ |
| `PUT` | `/cart/items/{itemId}` | `UpdateCartItemRequest` | `CartResponse` | `200` | JWT hợp lệ |
| `DELETE` | `/cart/items/{itemId}` | — | `CartResponse` | `200` | JWT hợp lệ |
| `DELETE` | `/cart/items` | — | `null` | `200` | JWT hợp lệ |
| `POST` | `/cart/merge` | `MergeCartRequest` | `CartResponse` | `200` | JWT hợp lệ |

Chủ giỏ được lấy từ `sub` của JWT đã xác minh. Header `X-User-Id` có thể được Gateway gửi kèm nhưng controller không dùng header đó để quyết định quyền sở hữu.

## 2. Request DTO

### `AddToCartRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `skuId` | `string` UUID | Có | Không null; Catalog phải tìm thấy SKU hợp lệ |
| `quantity` | `number` nguyên | Có | Tối thiểu `1` |

```json
{
  "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
  "quantity": 2
}
```

### `UpdateCartItemRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `quantity` | `number` nguyên | Có | Tối thiểu `1` |

Gửi `0` không có nghĩa xóa; dùng `DELETE /cart/items/{itemId}`.

```json
{
  "quantity": 3
}
```

### `MergeCartRequest`

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `items` | `AddToCartRequest[]` | Có | Không được rỗng; mỗi phần tử được validate theo `AddToCartRequest` |

Nếu SKU đã có trong giỏ active, service cộng dồn quantity theo quy tắc hiện tại.

```json
{
  "items": [
    {
      "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
      "quantity": 2
    },
    {
      "skuId": "2f78db6a-e71d-4ca7-8a41-43e9870bf55f",
      "quantity": 1
    }
  ]
}
```

## 3. Response DTO

### `CartResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `cartId` | `string` UUID | Không | ID giỏ |
| `userId` | `string` UUID | Không | Chủ giỏ |
| `status` | `CartStatus` | Không | `ACTIVE`, `MERGED`, `EXPIRED`, `CONVERTED` |
| `items` | `CartItemResponse[]` | Không | Danh sách dòng hàng; rỗng dùng `[]` |
| `totalItems` | `number` nguyên | Không | Số dòng SKU khác nhau |
| `totalQuantity` | `number` nguyên | Không | Tổng quantity của mọi dòng |
| `subtotal` | `number` | Không | Tổng `unitPrice * quantity`, scale 2 |
| `discountAmount` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `shippingFee` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `taxAmount` | `number` | Không | Giai đoạn cart hiện trả `0.00` |
| `totalAmount` | `number` | Không | Hiện bằng subtotal sau các khoản ước tính, scale 2 |
| `expiresAt` | `string` | Có | ISO-8601 Instant |

### `CartItemResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | Cart item ID |
| `skuId` | `string` UUID | Không | SKU ID |
| `skuCode` | `string` | Không | Nếu không lấy được Catalog, mapper dùng `N/A` |
| `productName` | `string` | Không | Nếu không lấy được Catalog, mapper dùng `Unknown Product` |
| `thumbnailUrl` | `string` | Có | URL ảnh đầu tiên |
| `unitPrice` | `number` | Không | Ưu tiên giá live từ Catalog, fallback giá lưu trong cart |
| `quantity` | `number` nguyên | Không | Số lượng |
| `stockAvailable` | `number` nguyên | Có | Hiện thường `null` vì Catalog không sở hữu tồn kho |
| `isAvailable` | `boolean` | Không | SKU/product phải active; stock `null` không tự làm item unavailable |
| `subtotal` | `number` | Không | `unitPrice * quantity`, scale 2 |

Không hiển thị `stockAvailable: null` thành `0`. Đây là trạng thái “chưa xác minh tồn kho từ Fulfillment”.

Ví dụ:

```json
{
  "success": true,
  "data": {
    "cartId": "548fb87a-3182-4c8d-8539-b8248cf8e4bc",
    "userId": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
    "status": "ACTIVE",
    "items": [
      {
        "id": "8500a48b-b5de-429b-89bb-67d50157e95b",
        "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
        "skuCode": "NEXUS-BLACK-M",
        "productName": "Nexus T-Shirt",
        "thumbnailUrl": null,
        "unitPrice": 250000.00,
        "quantity": 2,
        "stockAvailable": null,
        "isAvailable": true,
        "subtotal": 500000.00
      }
    ],
    "totalItems": 1,
    "totalQuantity": 2,
    "subtotal": 500000.00,
    "discountAmount": 0.00,
    "shippingFee": 0.00,
    "taxAmount": 0.00,
    "totalAmount": 500000.00,
    "expiresAt": "2026-11-01T08:30:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-25T08:30:00.000Z"
  }
}
```

`DELETE /cart/items` là trường hợp success có `data: null`:

```json
{
  "success": true,
  "data": null,
  "error": null,
  "meta": {
    "requestId": "req_763e0ef4",
    "timestamp": "2026-10-25T08:50:00.000Z"
  }
}
```

Mọi endpoint thêm, sửa, xóa một item và merge đều trả lại toàn bộ `CartResponse` trong `data`, cùng shape với ví dụ trên. Lỗi validation và lỗi nghiệp vụ dùng JSON envelope trong [COMMON.md](COMMON.md#2-errordetail-và-validationerrordetail).

## 4. Contract chưa triển khai

Các route `/checkout/**`, `/orders/**`, `/payments/**` đã được Gateway dành sẵn và `docs/API_SPEC_CART_ORDER.md` có thiết kế draft, nhưng source hiện chưa có controller/DTO tương ứng. Trạng thái của chúng là `PLANNED`, không phải contract callable.

FE có thể chuẩn bị UI model nội bộ nhưng không được coi payload draft là API ổn định cho đến khi DTO/controller/test Backend được thêm và file này được cập nhật.
