# Hướng dẫn cấu hình và quản lý ảnh sản phẩm trên Cloudinary - Project Nexus

Tài liệu này hướng dẫn cách cấu hình, tích hợp dịch vụ lưu trữ đám mây Cloudinary và đồng bộ ảnh sản phẩm độ phân giải cao cho hệ thống thương mại điện tử Nexus.

---

## 1. Thông tin tài khoản Cloudinary

| Thuộc tính | Biến môi trường (`.env`) | Giá trị cấu hình | Ghi chú |
| :--- | :--- | :--- | :--- |
| **Cloud Name** | `CLOUDINARY_CLOUD_NAME` | `f4wgawlg` | Tên cloud định danh tài khoản Cloudinary |
| **API Key** | `CLOUDINARY_API_KEY` | `184937136559179` | Khóa API dùng để xác thực các request |
| **API Secret** | `CLOUDINARY_API_SECRET` | `ILrwWU8z2t9uWYqh0MnwtlMeKVo` | Khóa bảo mật (không commit công khai) |
| **Root Folder** | `CLOUDINARY_ROOT_FOLDER` | `Nexus` | Thư mục gốc lưu trữ toàn bộ media của hệ thống |

> [!WARNING]
> Không commit file `.env` thật lên Git. Mọi thông tin bảo mật đã được ignore qua `.gitignore` và chỉ chia sẻ mẫu qua `.env.example`.

---

## 2. Cấu hình hệ thống

### 2.1. Cấu hình biến môi trường (`.env` & `.env.example`)
Thêm các biến cấu hình sau vào file `.env` ở thư mục gốc project:
```properties
# Cấu hình Cloudinary lưu trữ media/ảnh sản phẩm
CLOUDINARY_CLOUD_NAME=f4wgawlg
CLOUDINARY_API_KEY=184937136559179
CLOUDINARY_API_SECRET=ILrwWU8z2t9uWYqh0MnwtlMeKVo
```

### 2.2. Cấu hình Backend (`catalog-service`)
Trong `backend/catalog-service/src/main/resources/application.yml`:
```yaml
app:
  catalog:
    product:
      max-images: ${CATALOG_MAX_IMAGES:5}
    pagination:
      default-page-size: ${CATALOG_DEFAULT_PAGE_SIZE:20}
      max-page-size: ${CATALOG_MAX_PAGE_SIZE:100}
    cloudinary:
      cloud-name: ${CLOUDINARY_CLOUD_NAME:f4wgawlg}
      api-key: ${CLOUDINARY_API_KEY:184937136559179}
      api-secret: ${CLOUDINARY_API_SECRET:ILrwWU8z2t9uWYqh0MnwtlMeKVo}
      root-folder: ${CLOUDINARY_ROOT_FOLDER:Nexus}
```

Trong Java mã nguồn (`CatalogProperties.java`), thuộc tính đã được ánh xạ type-safe:
```java
@Valid
@NotNull
private CloudinaryProperties cloudinary = new CloudinaryProperties();

@Getter
@Setter
public static class CloudinaryProperties {
    private String cloudName;
    private String apiKey;
    private String apiSecret;
    private String rootFolder = "Nexus";
}
```

---

## 3. Cấu trúc thư mục lưu trữ trên Cloudinary

Toàn bộ ảnh sản phẩm được tổ chức theo cây phân cấp thư mục chuẩn:
```text
Nexus/
├── electronics/                        # Ngành hàng Công nghệ & Phụ kiện
│   ├── tai-nghe-khong-day-n1/          # Sản phẩm cụ thể
│   │   ├── tai-nghe-khong-day-n1-main.jpg
│   │   └── tai-nghe-khong-day-n1-detail-1.jpg
│   ├── ban-phim-co-k87/
│   ├── chuot-khong-day-m2/
│   ├── chuot-gaming-logitech-g102/
│   ├── tai-nghe-gaming-razer-blackshark-v2/
│   ├── man-hinh-gaming-asus-rog-swift/
│   ├── laptop-msi-modern-15/
│   └── iphone-18-pro-max-256gb/
├── fashion/                            # Ngành hàng Thời trang
│   ├── ao-thun-cotton-thuong-ngay/
│   ├── ao-khoac-gio-nhe/
│   └── ba-lo-di-hoc-20l/
├── home-living/                        # Ngành hàng Nhà cửa & Đời sống
│   ├── den-ban-hoc-led/
│   ├── binh-giu-nhiet-500ml/
│   └── bo-hop-thuc-pham-3-mon/
├── sports/                             # Ngành hàng Thể thao & Dã ngoại
│   ├── tham-tap-yoga-6mm/
│   ├── day-khang-luc-tap-luyen/
│   ├── tui-the-thao-cuoi-tuan/
│   └── giay-chay-bo-nam-kipcore/
├── stationery/                         # Ngành hàng Sách & Văn phòng phẩm
│   ├── so-tay-giay-cham-a5/
│   ├── bo-but-viet-5-mau/
│   ├── gia-do-sach-de-ban/
│   └── sach-hoc-tap-chuyen-de/
└── collectibles/                       # Ngành hàng Đồ sưu tầm & Thủ công
    ├── mo-hinh-go-lap-ghep/
    ├── tranh-in-nghe-thuat-a4/
    ├── bo-thiep-thu-cong-6-mau/
    └── vat-pham-decor-thu-cong/
```

### Tiêu chuẩn kỹ thuật ảnh:
- **Độ phân giải:** 1000x1000px (tự động crop & scale theo tham số `quality: auto:good`, `fetch_format: auto`).
- **Tối ưu CDN:** Phân phối qua mạng lưới Edge Cloudflare toàn cầu, tải nhanh với độ trễ thấp.
- **Quy ước đặt tên (Public ID):**
  - Ảnh chính: `<product-slug>-main`
  - Ảnh phụ/chi tiết: `<product-slug>-detail-1`, `<product-slug>-detail-2`

---

## 4. Hướng dẫn chạy script đồng bộ và cập nhật database

### 4.1. Điều kiện tiên quyết
- Đã cài đặt Node.js v20+
- Đã cài đặt thư viện `cloudinary` và `pg`:
  ```bash
  npm install --no-save cloudinary pg
  ```

### 4.2. Chạy script đồng bộ ảnh
Để upload ảnh sản phẩm lên Cloudinary và tự động đồng bộ URL mới vào bảng `nexus_catalog.product_images` trên Supabase:
```bash
node scripts/upload-and-update-product-images.mjs
```

### 4.3. Kiểm tra danh sách ảnh sau cập nhật
Chạy lệnh kiểm tra danh sách sản phẩm và ảnh đang liên kết trong cơ sở dữ liệu:
```bash
node scripts/list-products.mjs
```

---

## 5. Xác minh hoạt động (Verification)
Sau khi hoàn tất, bạn có thể kiểm tra:
1. **Cloudinary Console:** Đăng nhập vào console Cloudinary > Media Library > Thư mục `Nexus` để xem cây thư mục và toàn bộ ảnh.
2. **CDN Delivery:** Truy cập thử bất kỳ URL ảnh nào, ví dụ:
   `https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg`
   Kết quả trả về mã `HTTP 200 OK` với định dạng `image/jpeg`.
3. **Giao diện Web:** Chạy frontend web (`npm --prefix frontend-web dev`) và kiểm tra trang chi tiết sản phẩm, danh mục để thưởng thức giao diện hiển thị ảnh sản phẩm sắc nét.
