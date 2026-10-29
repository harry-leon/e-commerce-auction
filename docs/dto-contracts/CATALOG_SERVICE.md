# Catalog Service DTO Contract

> **Gateway paths:** `/api/v1/products/**`, `/api/v1/categories/**`
>
> **Success wrapper:** `ApiResponse<T>`; xem [COMMON.md](COMMON.md)
>
> **Phân trang:** `data` là array, `meta.pagination` chứa thông tin trang

## 0. DTO inventory

| DTO trong source | Vai trò | Có được controller expose |
|---|---|---|
| `ProductRequest`, `SkuRequest`, `ImageRequest`, `CategoryRequest` | Request body thao tác ghi | Có |
| `ProductFilterRequest` | Query object cho `/products/search` | Có |
| `ProductResponse`, `SkuResponse`, `ImageResponse`, `CategoryResponse` | Public response | Có |
| `SkuSnapshot` | Read model Catalog → Commerce | Có qua lookup endpoint |
| `PageResponse<T>` | Wrapper phân trang cục bộ | **Không**; controller dùng `ApiResponse.ok(Page<T>)` |

## 1. Endpoint matrix

### Product, SKU và image

| Method | Path | Request | `data` khi success | HTTP | Quyền |
|---|---|---|---|---|---|
| `POST` | `/products` | `ProductRequest` | `ProductResponse` | `201` | `PRODUCT.CREATE` hoặc role `SELLER`/`ADMIN` |
| `PUT` | `/products/{id}` | `ProductRequest` | `ProductResponse` | `200` | `PRODUCT.UPDATE` hoặc role `SELLER`/`ADMIN` |
| `DELETE` | `/products/{id}` | — | Không có body | `204` | `PRODUCT.DELETE` hoặc role `SELLER`/`ADMIN` |
| `GET` | `/products/{id}` | — | `ProductResponse` | `200` | Public |
| `GET` | `/products` | Query tìm kiếm | `ProductResponse[]` | `200` | Public nếu `status` rỗng/`ACTIVE`; trạng thái khác cần `PRODUCT.LIST` |
| `GET` | `/products/search` | `ProductFilterRequest` qua query | `ProductResponse[]` | `200` | Public |
| `GET` | `/products/seller/{sellerId}` | Query phân trang | `ProductResponse[]` | `200` | Public theo controller hiện tại |
| `POST` | `/products/{id}/status?target=...` | Query `target` | `ProductResponse` | `200` | Admin/Support hoặc quyền cập nhật được controller cho phép |
| `POST` | `/products/{id}/approve` | — | `ProductResponse` | `200` | Admin/Support hoặc quyền cập nhật được controller cho phép |
| `POST` | `/products/{productId}/skus` | `SkuRequest` | `SkuResponse` | `201` | Seller/Admin hoặc quyền create/update |
| `PUT` | `/products/skus/{skuId}` | `SkuRequest` | `SkuResponse` | `200` | Seller/Admin hoặc `PRODUCT.UPDATE` |
| `DELETE` | `/products/skus/{skuId}` | — | Không có body | `204` | Seller/Admin hoặc quyền delete/update |
| `GET` | `/products/skus/{id}` | — | `SkuSnapshot` | `200` | Public; chủ yếu dùng nội bộ bởi Commerce |
| `POST` | `/products/{productId}/images` | `ImageRequest` | `ImageResponse` | `201` | Seller/Admin hoặc quyền create/update |
| `PUT` | `/products/images/{imageId}` | `ImageRequest` | `ImageResponse` | `200` | Seller/Admin hoặc `PRODUCT.UPDATE` |
| `DELETE` | `/products/images/{imageId}` | — | Không có body | `204` | Seller/Admin hoặc quyền delete/update |

### Category

| Method | Path | Request | `data` khi success | HTTP | Quyền |
|---|---|---|---|---|---|
| `GET` | `/categories` | — | `CategoryResponse[]` dạng cây | `200` | Public |
| `GET` | `/categories/{id}` | — | `CategoryResponse` | `200` | Public |
| `POST` | `/categories` | `CategoryRequest` | `CategoryResponse` | `201` | `CATEGORY.CREATE` |
| `PUT` | `/categories/{id}` | `CategoryRequest` | `CategoryResponse` | `200` | `CATEGORY.UPDATE` |
| `DELETE` | `/categories/{id}` | — | Không có body | `204` | `CATEGORY.DELETE` |

## 2. Request DTO

Lookup `GET /api/v1/products/skus/{id}` bổ sung `sellerId` UUID từ Product vào `SkuSnapshot` để Commerce kiểm tra chủ shop khi checkout. Các field cũ giữ nguyên: `skuId`, `skuCode`, `productName`, `thumbnailUrl`, `price`, `stockQuantity`, `isActive`. `stockQuantity` vẫn null vì tồn kho thuộc Fulfillment. Triển khai Catalog mới trước Commerce checkout; Cart cũ vẫn đọc được snapshot có thêm field.

### `ProductRequest`

| Field | Kiểu | Bắt buộc | Validation/quy tắc |
|---|---|---|---|
| `name` | `string` | Có | Không blank, tối đa `255` ký tự |
| `description` | `string` | Không | Chưa có giới hạn trong DTO |
| `categoryId` | `string` UUID | Có | Danh mục phải tồn tại theo service |
| `auctionEnabled` | `boolean` | Không | Khi tạo, mặc định `false` |
| `skus` | `SkuRequest[]` | Có | Ít nhất `1` phần tử; từng phần tử không null và được validate |
| `images` | `ImageRequest[]` | Không | Tối đa `5` phần tử; từng phần tử không null và được validate |

Create luôn tạo product ở trạng thái `DRAFT`; client không gửi `sellerId`, `status`, `id` hoặc timestamps. Endpoint update hiện dùng cùng DTO nên vẫn yêu cầu `name`, `categoryId` và ít nhất một SKU.

Ví dụ body dùng chung cho create/update product:

```json
{
  "name": "Nexus T-Shirt",
  "description": "Áo thun cotton màu đen",
  "categoryId": "84a1470c-3c7a-4d2f-979c-caba67df506d",
  "auctionEnabled": false,
  "skus": [
    {
      "skuCode": "NEXUS-BLACK-M",
      "attributes": "{\"color\":\"BLACK\",\"size\":\"M\"}",
      "price": 250000.00,
      "originalPrice": 300000.00,
      "status": "ACTIVE"
    }
  ],
  "images": [
    {
      "url": "https://cdn.example.com/products/nexus-black-m.jpg",
      "sortOrder": 0
    }
  ]
}
```

`attributes` là một JSON string nên JSON bên trong phải escape như ví dụ; không gửi trực tiếp object cho field này.

### `SkuRequest`

| Field | Kiểu | Bắt buộc | Validation/quy tắc |
|---|---|---|---|
| `skuCode` | `string` | Có | Không blank, tối đa `100` ký tự |
| `attributes` | `string` | Không | Chuỗi thuộc tính; hiện chưa phải JSON object có schema |
| `price` | `number` | Có | `>= 0` |
| `originalPrice` | `number` | Không | `>= 0` |
| `status` | `"ACTIVE" \| "INACTIVE"` | Không | Khi tạo, mặc định `ACTIVE` |

Ví dụ body khi thêm/cập nhật riêng một SKU:

```json
{
  "skuCode": "NEXUS-BLACK-L",
  "attributes": "{\"color\":\"BLACK\",\"size\":\"L\"}",
  "price": 260000.00,
  "originalPrice": 310000.00,
  "status": "ACTIVE"
}
```

### `ImageRequest`

| Field | Kiểu | Bắt buộc | Validation/quy tắc |
|---|---|---|---|
| `url` | `string` | Có | Không blank, tối đa `500` ký tự |
| `sortOrder` | `number` nguyên | Không | `>= 0`, mặc định `0` khi tạo |

Ví dụ body khi thêm/cập nhật riêng một image:

```json
{
  "url": "https://cdn.example.com/products/nexus-black-l.jpg",
  "sortOrder": 1
}
```

### `CategoryRequest`

| Field | Kiểu | Bắt buộc | Validation/quy tắc |
|---|---|---|---|
| `name` | `string` | Có | Không blank, tối đa `255` ký tự |
| `nameVi` | `string` | Không | Tối đa `255` ký tự |
| `parentId` | `string` UUID | Không | `null` nghĩa là danh mục gốc; server tự tính level và chặn vòng lặp/quá 3 cấp |
| `status` | `"ACTIVE" \| "INACTIVE"` | Có | Phân biệt hoa thường |

```json
{
  "name": "Thời trang nam",
  "nameVi": "Thời trang nam",
  "parentId": null,
  "status": "ACTIVE"
}
```

### Query search và phân trang

`GET /products` nhận `q`, `categoryId`, `minPrice`, `maxPrice`, `auctionEnabled`, `status`, cùng `page`, `size`, `sort` của Spring Pageable.

`GET /products/search` nhận `keyword`, `categoryId`, `minPrice`, `maxPrice`, `status`; mặc định `size=20`, sort `createdAt,desc`. `minPrice` và `maxPrice` phải `>= 0`.

`ProductStatus`: `DRAFT`, `ACTIVE`, `INACTIVE`. Trang bắt đầu từ `0`.

## 3. Response DTO

### `ProductResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | Product ID |
| `sellerId` | `string` UUID | Không | Lấy từ principal khi tạo |
| `categoryId` | `string` UUID | Không | Category ID |
| `name` | `string` | Không | Tên sản phẩm |
| `description` | `string` | Có | Mô tả |
| `status` | `ProductStatus` | Không | `DRAFT`, `ACTIVE`, `INACTIVE` |
| `auctionEnabled` | `boolean` | Không theo entity hợp lệ | Có cho phép đấu giá |
| `createdAt` | `string` | Có | ISO-8601 Instant |
| `skus` | `SkuResponse[]` | Không theo mapper hợp lệ | Danh sách SKU |
| `images` | `ImageResponse[]` | Không theo mapper hợp lệ | Danh sách ảnh |

Ví dụ success của create/get/update product; các endpoint dùng cùng `ProductResponse`:

```json
{
  "success": true,
  "data": {
    "id": "681efbd3-4741-4e02-a2dd-67d8e1766805",
    "sellerId": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
    "categoryId": "84a1470c-3c7a-4d2f-979c-caba67df506d",
    "name": "Nexus T-Shirt",
    "description": "Áo thun cotton màu đen",
    "status": "DRAFT",
    "auctionEnabled": false,
    "createdAt": "2026-10-25T08:30:00Z",
    "skus": [
      {
        "id": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
        "skuCode": "NEXUS-BLACK-M",
        "attributes": "{\"color\":\"BLACK\",\"size\":\"M\"}",
        "price": 250000.00,
        "originalPrice": 300000.00,
        "status": "ACTIVE"
      }
    ],
    "images": [
      {
        "id": "cedbe915-a9d5-44da-bd72-edeea1ae9c25",
        "url": "https://cdn.example.com/products/nexus-black-m.jpg",
        "sortOrder": 0
      }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_8cb579f0",
    "timestamp": "2026-10-25T08:30:00.000Z"
  }
}
```

### `SkuResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | SKU ID |
| `skuCode` | `string` | Không | Mã SKU |
| `attributes` | `string` | Có | Chuỗi thuộc tính |
| `price` | `number` | Không | Giá hiện tại |
| `originalPrice` | `number` | Có | Giá gốc |
| `status` | `"ACTIVE" \| "INACTIVE"` | Không | Trạng thái SKU |

### `ImageResponse`

| Field | Kiểu JSON | Có thể null |
|---|---|---|
| `id` | `string` UUID | Không |
| `url` | `string` | Không |
| `sortOrder` | `number` nguyên | Không theo dữ liệu mới |

Response của endpoint thêm/sửa riêng SKU hoặc image vẫn dùng envelope chuẩn. `data` lần lượt là object `SkuResponse` hoặc `ImageResponse` đúng các field trong bảng; hai shape này cũng xuất hiện đầy đủ bên trong ví dụ `ProductResponse` phía trên.

### `CategoryResponse`

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `id` | `string` UUID | Không | Category ID |
| `name` | `string` | Không | Tên mặc định |
| `nameVi` | `string` | Có | Tên tiếng Việt |
| `parentId` | `string` UUID | Có | `null` ở node gốc |
| `level` | `number` nguyên | Không | Cấp do server tính |
| `status` | `"ACTIVE" \| "INACTIVE"` | Không | Trạng thái |
| `productCount` | `number` nguyên | Không | Số sản phẩm trực tiếp tại node, không khẳng định gồm toàn bộ cây con |
| `children` | `CategoryResponse[]` | Không | Nhánh con; node lá trả `[]` |

Ví dụ `GET /categories`:

```json
{
  "success": true,
  "data": [
    {
      "id": "84a1470c-3c7a-4d2f-979c-caba67df506d",
      "name": "Fashion",
      "nameVi": "Thời trang",
      "parentId": null,
      "level": 0,
      "status": "ACTIVE",
      "productCount": 12,
      "children": [
        {
          "id": "004a72f2-64f7-4349-ad46-69178ded6873",
          "name": "Men",
          "nameVi": "Nam",
          "parentId": "84a1470c-3c7a-4d2f-979c-caba67df506d",
          "level": 1,
          "status": "ACTIVE",
          "productCount": 5,
          "children": []
        }
      ]
    }
  ],
  "error": null,
  "meta": {
    "requestId": "req_f04953d1",
    "timestamp": "2026-10-25T08:35:00.000Z"
  }
}
```

### `SkuSnapshot` — contract nội bộ Catalog → Commerce

| Field | Kiểu JSON | Có thể null | Ghi chú |
|---|---|---|---|
| `skuId` | `string` UUID | Không | SKU ID |
| `skuCode` | `string` | Không | Mã SKU |
| `productName` | `string` | Không | Tên sản phẩm |
| `thumbnailUrl` | `string` | Có | Ảnh đầu tiên nếu có |
| `price` | `number` | Không | Giá hiện tại |
| `stockQuantity` | `number` | Có | Hiện luôn `null` vì tồn kho thuộc Fulfillment |
| `isActive` | `boolean` | Không | Chỉ true khi cả SKU và product đều active |

`stockQuantity: null` nghĩa là **chưa kiểm tra tồn kho**, không có nghĩa hết hàng hoặc không giới hạn.

Ví dụ `GET /products/skus/{id}`:

```json
{
  "success": true,
  "data": {
    "skuId": "ba844bf3-8ead-4a83-9c36-b418c07a5f13",
    "skuCode": "NEXUS-BLACK-M",
    "productName": "Nexus T-Shirt",
    "thumbnailUrl": "https://cdn.example.com/products/nexus-black-m.jpg",
    "price": 250000.00,
    "stockQuantity": null,
    "isActive": true
  },
  "error": null,
  "meta": {
    "requestId": "req_35275b84",
    "timestamp": "2026-10-25T08:36:00.000Z"
  }
}
```

### JSON phân trang product

`GET /products`, `/products/search` và `/products/seller/{sellerId}` trả array trong `data`; pagination không nằm cùng cấp với array mà nằm tại `meta.pagination`:

```json
{
  "success": true,
  "data": [
    {
      "id": "681efbd3-4741-4e02-a2dd-67d8e1766805",
      "sellerId": "26d7aa54-8284-45cd-b07b-5ca1581ba72f",
      "categoryId": "84a1470c-3c7a-4d2f-979c-caba67df506d",
      "name": "Nexus T-Shirt",
      "description": "Áo thun cotton màu đen",
      "status": "ACTIVE",
      "auctionEnabled": false,
      "createdAt": "2026-10-25T08:30:00Z",
      "skus": [],
      "images": []
    }
  ],
  "error": null,
  "meta": {
    "requestId": "req_13f64f42",
    "timestamp": "2026-10-25T08:40:00.000Z",
    "pagination": {
      "page": 0,
      "size": 20,
      "totalElements": 41,
      "totalPages": 3,
      "hasNext": true,
      "hasPrevious": false
    }
  }
}
```

Các endpoint delete Product/SKU/Image/Category thành công trả HTTP `204 No Content`: response không có JSON body, FE không gọi `response.json()`.

## 4. TypeScript tối thiểu phía FE

```ts
type ProductStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE'
type SkuStatus = 'ACTIVE' | 'INACTIVE'

interface ProductResponse {
  id: string
  sellerId: string
  categoryId: string
  name: string
  description: string | null
  status: ProductStatus
  auctionEnabled: boolean
  createdAt: string | null
  skus: SkuResponse[]
  images: ImageResponse[]
}
```

FE phải đọc danh sách phân trang từ `response.data` và `response.meta.pagination`; không dùng `PageResponse` cục bộ vì controller hiện không expose class đó.
