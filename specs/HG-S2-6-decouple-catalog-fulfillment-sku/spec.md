# Đặc tả tính năng HG-S2-6: Tách ranh giới Catalog SKU ID và Fulfillment SKU Code

**Trạng thái:** Ready for Implementation  
**Nhánh làm việc:** `feature/HG-S2-6-decouple-catalog-fulfillment-sku` (tách từ `dev`)  
**Phạm vi:** `catalog-service`, `fulfillment-service`, `api-gateway`, `commerce-service`, `frontend-web`, Supabase migrations, ERD & tài liệu hợp đồng DTO.

---

## 1. Bối cảnh và Bài toán nghiệp vụ

Trước đây bảng `nexus_catalog.skus` vừa giữ định danh biến thể (`id`) vừa tự yêu cầu và lưu `sku_code` (`NOT NULL UNIQUE`). Điều này vi phạm ranh giới Bounded Context:
- Người bán chưa nhập kho đã bị buộc nhập/sinh `sku_code` trong Catalog, dẫn tới mã SKU kho nội bộ bị trộn lẫn với dữ liệu trưng bày sản phẩm.
- `ProductServiceImpl.update()` từng xóa toàn bộ danh sách `skus` (`clear()`) và tạo lại bản ghi mới khiến `skuId` thay đổi, làm đứt tham chiếu từ `inventories`, `carts`, `auctions`.
- `nexus_fulfillment.inventories` chỉ tham chiếu `sku_id` thô mà không có bảng quản lý mã SKU kho của người bán (`seller_skus`), không xác thực quyền sở hữu SKU từ Catalog khi nhập kho (`inboundStock`), và không cung cấp API tra cứu danh sách/chi tiết SKU kho cho người bán.

### Nguyên tắc kiến trúc bắt buộc
1. **Database-per-Service:** `fulfillment-service` **tuyệt đối không** đọc/ghi trực tiếp database của `catalog-service` từ tầng ứng dụng; giao tiếp qua HTTP REST API (`GET /api/v1/skus/{skuId}`) hoặc Outbox Domain Event.
2. **Catalog Service** chỉ sở hữu `skuId` (`UUID`), `product_id`, `attributes`, `original_price` (minPrice), `status` và rule tính giá động (`pricing_rules`); **không** lưu, **không** sinh và **không** nhận `sku_code`.
3. **Fulfillment Service** là nơi duy nhất sinh và quản lý `skuCode` (định dạng `FSKU-00000001` từ PostgreSQL sequence `fulfillment_sku_code_seq`) khi người bán nhập kho lần đầu trong bảng `seller_skus`.
4. **Migration an toàn hai pha:** Bảng `inventories` bổ sung khóa ngoại `seller_sku_id -> seller_skus(id)` và backfill toàn bộ dữ liệu hiện có trên Supabase; **giữ nguyên `inventories.sku_id`** (đồng bộ cả hai cột) trong giai đoạn chuyển tiếp để không làm gãy consumer hiện hành.

---

## 2. Yêu cầu chức năng (Functional Requirements)

### FR-1: Catalog Service loại bỏ `sku_code` và giữ ổn định `skuId`
- **FR-1.1:** Đồng bộ nền tảng giá động (`V6`: bỏ `skus.price`, bắt buộc `original_price`, bảng `pricing_rules`) khớp schema Supabase hiện tại, sau đó áp dụng migration **`V7__remove_sku_code_from_skus.sql`** xóa cột `sku_code` (cùng ràng buộc unique cũ) khỏi bảng `skus` và tạo bảng `catalog_event_outbox`.
- **FR-1.2:** `Sku`, `SkuRequest`, `SkuResponse`, `ProductMapper`, `SkuLookupService.SkuSnapshot` loại bỏ `skuCode`. `SkuRequest` nhận thêm trường tùy chọn `UUID id` để xác định biến thể cần cập nhật khi sửa sản phẩm.
- **FR-1.3:** `ProductServiceImpl.update(UUID id, ProductRequest)` giữ nguyên `UUID id` của các SKU đang tồn tại; chỉ tạo mới UUID cho SKU chưa có `id` và từ chối nếu client gửi `sku.id` không thuộc sản phẩm đang sửa.
- **FR-1.4:** Expose endpoint **`GET /api/v1/skus/{id}`** (giữ `/api/v1/products/skus/{id}` làm alias tương thích) trả về thông tin SKU tối thiểu gồm `skuId`, `productId`, `sellerId`, `productName`, `thumbnailUrl`, `price`, `originalPrice`, `isActive` (không chứa `skuCode`), kèm route `/api/v1/skus/**` trên `api-gateway`.
- **FR-1.5:** Mỗi khi tạo SKU mới (`create`, `addSku`, hoặc thêm biến thể mới trong `update`), ghi sự kiện **`ProductSkuCreated`** vào `catalog_event_outbox` trong cùng database transaction.

### FR-2: Fulfillment Service sinh và quản lý `seller_skus` qua quy trình nhập kho
- **FR-2.1:** Tạo migration **`V6__seller_skus_and_sku_code_sequence.sql`** (tương thích `V1..V4` CI và `V1..V5` Supabase):
  - Sequence `fulfillment_sku_code_seq` (`START WITH 1 INCREMENT BY 1`).
  - Bảng `seller_skus (id, seller_id, sku_id UNIQUE, sku_code, status CHECK IN ('ACTIVE','INACTIVE'), created_at, updated_at, UNIQUE(seller_id, sku_code))`.
  - Cột `inventories.seller_sku_id NOT NULL REFERENCES seller_skus(id)` + `UNIQUE(seller_sku_id, warehouse_id)` và backfill 100% dòng tồn kho hiện có vào `seller_skus`, đồng bộ `fulfillment_sku_code_seq` tránh trùng mã.
  - Bảng `fulfillment_event_outbox` để ghi `InventoryInboundCompleted`.
- **FR-2.2:** Luồng `POST /api/v1/inventory/inbound` (`InventoryServiceImpl.inboundStock`):
  1. Trích xuất danh tính người dùng và quyền từ `SecurityContext` (`ROLE_SELLER` hoặc `ROLE_ADMIN`).
  2. Gọi `CatalogSkuClient` tới `GET /api/v1/skus/{skuId}` kiểm tra SKU tồn tại và đang hợp lệ:
     - `404` từ Catalog → trả HTTP `404` (`ResourceNotFoundException`).
     - Catalog lỗi mạng/`5xx` → trả HTTP `502 Bad Gateway` (`CATALOG_UNAVAILABLE`).
     - Nếu người gọi là `ROLE_SELLER` và `sellerId` từ Catalog khác với UUID người gọi → trả HTTP `403 Forbidden` (`AccessDeniedException`).
     - Nếu người gọi là `ROLE_ADMIN` → dùng `sellerId` chính chủ của SKU từ Catalog.
  3. Find-or-create bản ghi `seller_skus` an toàn dưới đồng thời (`UNIQUE(sku_id)` + xử lý đua khóa / retry đọc lại):
     - Nhập kho lần đầu cho `skuId`: sinh `skuCode = FSKU-XXXXXXXX` từ `fulfillment_sku_code_seq` và lưu `seller_skus` trạng thái `ACTIVE`.
     - Nhập kho lần sau (cùng kho hoặc kho khác cùng `skuId`): tái sử dụng `seller_skus` và `skuCode` cũ, **không** sinh mã mới.
  4. Khóa bi quan / upsert bản ghi `inventories` (gắn cả `seller_sku_id` và `sku_id`), cộng `total_qty`, ghi sổ cái `inventory_ledger` (`INBOUND`), ghi sự kiện **`InventoryInboundCompleted`** vào `fulfillment_event_outbox` trong cùng transaction.
  5. Trả response đầy đủ: `skuId`, `sellerSkuId`, `skuCode`, `warehouseId`, `warehouseCode`, `totalQty`, `availableQty`, `reservedQty`, `unavailableQty`.
- **FR-2.3:** API đọc SKU kho cho Seller/Admin:
  - `GET /api/v1/inventory/skus`: danh sách phân trang `seller_skus` tổng hợp tồn kho (`totalQty`, `availableQty`, `reservedQty`, `unavailableQty`, `warehouseCount`), hỗ trợ tìm kiếm `keyword` (khớp `skuCode` hoặc UUID) và lọc `status`. Seller chỉ xem được SKU của chính mình (`sellerId` lấy từ JWT); Admin xem được tất cả hoặc lọc theo `sellerId`.
  - `GET /api/v1/inventory/skus/{id}`: tra cứu chi tiết theo `sellerSkuId` hoặc `skuId`, kèm phân rã tồn kho từng kho; chặn `403` nếu Seller khác truy cập.

### FR-3: Tương thích chéo dịch vụ & Giao diện Frontend (`frontend-web`)
- **FR-3.1:** `commerce-service` không còn coi `skuCode == null` từ Catalog là lỗi `502` trong `CheckoutService`; `CartMapper`, `CheckoutService`, `OrderServiceImpl` sinh/giữ mã hiển thị an toàn hoặc đọc từ snapshot thay vì phụ thuộc `Catalog.skuCode`.
- **FR-3.2:** `frontend-web`:
  - Form tạo/sửa sản phẩm Seller (`CreateProductPage`) không còn input bắt buộc `skuCode` hay gửi `skuCode` lên `POST/PUT /products`; hỗ trợ chỉnh sửa sản phẩm giữ nguyên `sku.id` và hiển thị ghi chú mã SKU kho được cấp tự động khi nhập kho.
  - Trang Kho hàng Người bán (`SellerInventoryPage`) nối API thật (`GET /api/v1/inventory/skus`, `GET /api/v1/inventory/skus/{id}`, `POST /api/v1/inventory/inbound`, `POST /api/v1/inventory/adjust`, `GET /api/v1/inventory/ledger`), bỏ dữ liệu mock `PRODUCTS`, cho phép chọn SKU từ sản phẩm của Seller để nhập kho, hiển thị mã `FSKU-...` vừa sinh và kiểm chứng lần nhập tiếp theo giữ nguyên mã cũ.
  - Các màn hình Seller Products / Product Detail / Buyer Cart / Admin Warehouses hiển thị `skuCode` tùy chọn một cách an toàn khi Catalog không còn trường này.

---

## 3. Tiêu chí nghiệm thu (Acceptance Criteria)
1. Bảng `nexus_catalog.skus` trên Supabase và test DB không còn cột `sku_code`; tạo mới và cập nhật sản phẩm thành công qua Swagger/UI, `skuId` không đổi sau khi sửa.
2. Bảng `nexus_fulfillment.seller_skus`, sequence `fulfillment_sku_code_seq`, cột `inventories.seller_sku_id` và backfill 45 dòng tồn kho hiện có chạy sạch trên Supabase và H2/PostgreSQL tests.
3. Nhập kho lần 1 qua `POST /api/v1/inventory/inbound` sinh mã `FSKU-...` và lưu `seller_skus` + `inventories` + `inventory_ledger` + `fulfillment_event_outbox`; nhập kho lần 2 giữ nguyên `skuCode` đó; sai seller trả `403`, SKU không tồn tại trả `404`, quantity <= 0 trả `400/422`, thiếu service trả `502`.
4. CI checks (`.ci/jobs.yml` & `.ci/erd-alignment.yml`) đạt 100%: Checkstyle, JaCoCo >= 70%, ERD generator test & align, ESLint, TypeScript, Prettier, Vitest.
5. Đủ ảnh chụp Swagger API (mỗi trường hợp 1 ảnh rõ nét) và ảnh Browser E2E tại `docs/screenshots/HG-S2-6/`, kèm hướng dẫn chạy & tài liệu DTO cập nhật trong `docs/`.
