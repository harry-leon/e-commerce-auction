# Auction Service DTO Contract

> **Gateway paths đã dành:** `/api/v1/auctions/**`, `/api/v1/bids/**`, `/ws/auctions/**`
>
> **Trạng thái:** `CURRENT` — Đã triển khai đầy đủ Quản lý phiên đấu giá (HG-S2-2) và Quyết toán phiên đấu giá (HG-S2-1)

---

## 1. Quản lý Phiên đấu giá (Auction Session Management — HG-S2-2)

### 1.1. Tạo phiên đấu giá (Create Auction)
- **Method & Path:** `POST /api/v1/auctions`
- **Quyền truy cập:** `ROLE_SELLER` (Token JWT)
- **Request Body (`CreateAuctionRequest`):**
```json
{
  "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "skuId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "startPrice": 5000000.00,
  "bidIncrement": 100000.00,
  "startTime": "2026-11-06T08:00:00Z",
  "endTime": "2026-11-07T20:00:00Z",
  "visibility": "PUBLIC"
}
```
- **Ràng buộc nghiệp vụ:**
  - `startPrice > 0`, `bidIncrement >= 1000 VND` (theo `app.auction.bidding.min-increment`).
  - `startTime` phải ở tương lai; `endTime > startTime`; thời lượng từ 1 giờ đến 7 ngày.
  - Người bán không vượt quá hạn mức tối đa 20 phiên active (`SCHEDULED` hoặc `ACTIVE`).
  - Không được trùng phiên active cho cùng một `productId` hoặc `skuId`.
- **Response (`ApiResponse<AuctionResponse>` — HTTP 201 Created):**
```json
{
  "success": true,
  "data": {
    "id": "c1f7b9c2-5717-4562-b3fc-2c963f66afa6",
    "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "skuId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sellerId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "startPrice": 5000000.00,
    "bidIncrement": 100000.00,
    "currentPrice": 5000000.00,
    "highestBidderId": null,
    "visibility": "PUBLIC",
    "status": "SCHEDULED",
    "startTime": "2026-11-06T08:00:00Z",
    "endTime": "2026-11-07T20:00:00Z",
    "extensionCount": 0,
    "version": 1,
    "createdAt": "2026-11-05T05:00:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req-xxx",
    "timestamp": "2026-11-05T05:00:00Z"
  }
}
```

---

### 1.2. Danh sách phiên đấu giá (List Auctions)
- **Method & Path:** `GET /api/v1/auctions`
- **Quyền truy cập:** Public
- **Query Parameters:**
  - `status`: Enum (`DRAFT`, `SCHEDULED`, `ACTIVE`, `ENDED`, `SETTLED`, `CANCELLED`)
  - `sellerId`: UUID
  - `visibility`: Enum (`PUBLIC`, `PRIVATE`)
  - `page`: int (mặc định 1)
  - `limit`: int (mặc định 20, tối đa 100)
  - `sortBy`: string (mặc định `startTime`)
  - `sortDir`: string (`ASC` / `DESC`, mặc định `DESC`)
- **Response (`ApiResponse<List<AuctionResponse>>` — HTTP 200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "id": "c1f7b9c2-5717-4562-b3fc-2c963f66afa6",
      "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "sellerId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "startPrice": 5000000.00,
      "bidIncrement": 100000.00,
      "currentPrice": 5000000.00,
      "status": "SCHEDULED",
      "startTime": "2026-11-06T08:00:00Z",
      "endTime": "2026-11-07T20:00:00Z"
    }
  ],
  "error": null,
  "meta": {
    "requestId": "req-xxx",
    "timestamp": "2026-11-05T05:00:00Z",
    "pagination": {
      "page": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "hasNext": false,
      "hasPrevious": false
    }
  }
}
```

---

### 1.3. Chi tiết phiên đấu giá (Get Auction Detail)
- **Method & Path:** `GET /api/v1/auctions/{auctionId}`
- **Quyền truy cập:** Public
- **Response (`ApiResponse<AuctionResponse>` — HTTP 200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "c1f7b9c2-5717-4562-b3fc-2c963f66afa6",
    "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "skuId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sellerId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "startPrice": 5000000.00,
    "bidIncrement": 100000.00,
    "currentPrice": 5000000.00,
    "highestBidderId": null,
    "visibility": "PUBLIC",
    "status": "SCHEDULED",
    "startTime": "2026-11-06T08:00:00Z",
    "endTime": "2026-11-07T20:00:00Z",
    "extensionCount": 0,
    "version": 1,
    "createdAt": "2026-11-05T05:00:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req-xxx",
    "timestamp": "2026-11-05T05:00:00Z"
  }
}
```

---

### 1.4. Chỉnh sửa phiên trước giờ bắt đầu (Update Auction)
- **Method & Path:** `PUT /api/v1/auctions/{auctionId}`
- **Quyền truy cập:** `ROLE_SELLER` (chủ sở hữu phiên) hoặc `ROLE_ADMIN`
- **Request Body (`UpdateAuctionRequest`):** Các trường là tùy chọn
```json
{
  "startPrice": 5500000.00,
  "bidIncrement": 150000.00,
  "startTime": "2026-11-06T09:00:00Z",
  "endTime": "2026-11-07T21:00:00Z",
  "visibility": "PUBLIC"
}
```
- **Ràng buộc:** Chỉ được phép chỉnh sửa khi trạng thái là `SCHEDULED` (hoặc `DRAFT`) và `now < startTime`.

---

### 1.5. Hủy phiên đấu giá (Cancel Auction)
- **Method & Path:** `POST /api/v1/auctions/{auctionId}/cancel`
- **Quyền truy cập:** `ROLE_SELLER` (chủ sở hữu phiên) hoặc `ROLE_ADMIN`
- **Request Body (`CancelAuctionRequest`):**
```json
{
  "reason": "Sản phẩm bị lỗi hỏng trong kho không thể giao dịch"
}
```
- **Ràng buộc:** Không thể hủy nếu phiên đã ở trạng thái `ENDED` hoặc `SETTLED`. Nếu đã `CANCELLED` thì idempotent trả về kết quả hiện tại.

---

## 2. Quyết toán Phiên đấu giá (Auction Settlement — HG-S2-1)

| Method | Path | Quyền truy cập | Mục đích |
|---|---|---|---|
| `POST` | `/api/v1/auctions/{auctionId}/settle` | `ROLE_ADMIN`, `ROLE_SELLER` | Khóa phiên và xác định người thắng cuộc khi kết thúc |
| `GET` | `/api/v1/auctions/{auctionId}/settlement` | Authenticated | Tra cứu kết quả quyết toán chính thức |
