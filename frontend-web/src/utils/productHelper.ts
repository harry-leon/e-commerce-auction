/**
 * Tiện ích phân giải hình ảnh và thông tin sản phẩm (Product Helper)
 *
 * Chức năng:
 * - Ánh xạ skuId, productId hoặc productName sang URL ảnh Cloudinary thực tế từ Catalog Service.
 * - Đảm bảo các màn hình OrderHistory, OrderDetail, Checkout, Cart luôn hiển thị ảnh sản phẩm rõ nét,
 *   chấm dứt hoàn toàn tình trạng ảnh bị vỡ (broken icon) do backend OrderItem không lưu trường image.
 *
 * Đầu vào và kết quả:
 * - Đầu vào: skuId, productId, productName, fallbackUrl.
 * - Kết quả: Chuỗi URL hình ảnh hợp lệ (ưu tiên Cloudinary, có fallback SVG data URI chuẩn).
 *
 * Luồng xử lý:
 * 1. Kiểm tra fallbackUrl nếu đã là URL hợp lệ (bắt đầu bằng http:// hoặc https:// và không rỗng).
 * 2. Tra cứu theo skuId trong danh mục KNOWN_SKU_IMAGES.
 * 3. Tra cứu theo productId trong danh mục KNOWN_PRODUCT_IMAGES.
 * 4. Tra cứu đối soát từ khóa theo productName trong KNOWN_NAME_IMAGES.
 * 5. Nếu không khớp bất kỳ nguồn nào, trả về SVG data URI placeholder với tên viết tắt sản phẩm.
 *
 * Quy tắc và lý do:
 * - Tuân thủ RULE[AGENTS.md] và RULE[user_global]: Không dùng ảnh mẫu ngẫu nhiên sai lệch sản phẩm.
 * - Tuyệt đối không trả về chuỗi rỗng "" để tránh trình duyệt render icon ảnh hỏng.
 *
 * Lỗi & giới hạn:
 * - Xử lý an toàn khi tham số đầu vào là null, undefined hoặc khoảng trắng.
 */

// Bảng ánh xạ SKU ID sang URL ảnh Cloudinary thực tế trong Catalog database
export const KNOWN_SKU_IMAGES: Record<string, string> = {
    // Tai nghe Gaming Razer BlackShark V2
    'b0ac6f97-9221-4123-850e-d5bd1eb63e19': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542335/Nexus/electronics/tai-nghe-gaming-razer-blackshark-v2/tai-nghe-gaming-razer-blackshark-v2-main.jpg',
    // iPhone 18 Pro Max 256GB
    '705da174-d530-4042-8605-8bf57481ec88': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542342/Nexus/electronics/iphone-18-pro-max-256gb/iphone-18-pro-max-256gb-main.jpg',
    // Chuot Gaming Logitech G102
    'd167c9c6-3c46-42db-afcc-44cf17345960': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542332/Nexus/electronics/chuot-gaming-logitech-g102/chuot-gaming-logitech-g102-main.jpg',
    // Man hinh Gaming ASUS ROG Swift 27 inch 240Hz
    '277a40a8-665a-4730-8651-85ac15b2b8f9': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542337/Nexus/electronics/man-hinh-gaming-asus-rog-swift/man-hinh-gaming-asus-rog-swift-main.jpg',
    // Laptop MSI Modern 15 F1MG-1264VN
    'e17f8874-aafc-41eb-85fc-fa863c02930e': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542339/Nexus/electronics/laptop-msi-modern-15/laptop-msi-modern-15-main.jpg',
    // Tai nghe không dây N1
    '49bf170c-4579-5930-b81d-23e116f7947b': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg',
    'ea013bc1-fc2b-5957-a244-73a617e09b1b': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg',
    // Bàn phím cơ K87
    'aae4dd6a-01ad-5612-a94f-c3c3773f2c53': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542327/Nexus/electronics/ban-phim-co-k87/ban-phim-co-k87-main.jpg',
    '7cc01410-7d63-5e36-9f16-3d3f35caced1': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542327/Nexus/electronics/ban-phim-co-k87/ban-phim-co-k87-main.jpg',
    // Chuột không dây M2
    '71331fa3-7aab-5480-8ae3-3dcad3fa9ea0': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542330/Nexus/electronics/chuot-khong-day-m2/chuot-khong-day-m2-main.jpg',
    '239c1029-682e-50b4-a299-7ffff363ae50': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542330/Nexus/electronics/chuot-khong-day-m2/chuot-khong-day-m2-main.jpg',
    // Áo thun cotton thường ngày
    '17347ada-79d7-546c-9e92-259822e9a136': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg',
    'd77e93e1-3cba-52fb-bec0-b62651d93106': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg',
    // Áo khoác gió nhẹ
    'ebd09ae0-46bb-52aa-bbd1-0c176f0ee01a': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542347/Nexus/fashion/ao-khoac-gio-nhe/ao-khoac-gio-nhe-main.jpg',
    'dbefb106-5421-5494-b91a-2501d064bac3': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542347/Nexus/fashion/ao-khoac-gio-nhe/ao-khoac-gio-nhe-main.jpg',
    // Ba lô đi học 20L
    '52a2c47f-9476-59be-b257-c9eb1e75a763': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542350/Nexus/fashion/ba-lo-di-hoc-20l/ba-lo-di-hoc-20l-main.jpg',
    'ab0aa0b8-6afe-511f-8cc6-9216185d7613': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542350/Nexus/fashion/ba-lo-di-hoc-20l/ba-lo-di-hoc-20l-main.jpg',
    // Đèn bàn học LED
    '146505f4-7120-5b7f-96e5-8315a55b7497': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542353/Nexus/home-living/den-ban-hoc-led/den-ban-hoc-led-main.jpg',
    '0490e85e-9505-5192-aa0b-b9efca2b6577': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542353/Nexus/home-living/den-ban-hoc-led/den-ban-hoc-led-main.jpg',
    // Bình giữ nhiệt 500ml
    '299c49af-65fd-5bbc-8901-585993cc5702': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542356/Nexus/home-living/binh-giu-nhiet-500ml/binh-giu-nhiet-500ml-main.jpg',
    'fb12cbcf-6410-5fc3-bd78-ed037694c7e4': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542356/Nexus/home-living/binh-giu-nhiet-500ml/binh-giu-nhiet-500ml-main.jpg',
    // Giày chạy bộ nam Kipcore
    '80e78550-b74c-404c-a18d-975299d8c8ff': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542369/Nexus/sports/giay-chay-bo-nam-kipcore/giay-chay-bo-nam-kipcore-main.jpg',
    // Sổ tay giấy chấm A5
    '4d8c96be-062f-54ac-98db-74f21b9e15ff': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542373/Nexus/stationery/so-tay-giay-cham-a5/so-tay-giay-cham-a5-main.jpg',
    'd8f52b30-d1d7-5e0a-9658-968fd9fc66f7': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542373/Nexus/stationery/so-tay-giay-cham-a5/so-tay-giay-cham-a5-main.jpg',
    // Bộ bút viết 5 màu
    '6203dbae-9f5f-51f2-a259-28bd6100babe': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542375/Nexus/stationery/bo-but-viet-5-mau/bo-but-viet-5-mau-main.jpg',
    'ff4cdb21-19ee-507b-ba6a-a1c5cb5c955c': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542375/Nexus/stationery/bo-but-viet-5-mau/bo-but-viet-5-mau-main.jpg',
    // Giá đỡ sách để bàn
    'fa59a6a3-e025-5597-8924-8c8b1bbc95b4': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542379/Nexus/stationery/gia-do-sach-de-ban/gia-do-sach-de-ban-main.jpg',
    'ed3128c5-3761-51b1-be7b-696dc7abd465': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542379/Nexus/stationery/gia-do-sach-de-ban/gia-do-sach-de-ban-main.jpg',
    // Mô hình gỗ lắp ghép
    '8fe495e3-6e35-50c4-a2ee-5f0e9f606e09': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542384/Nexus/collectibles/mo-hinh-go-lap-ghep/mo-hinh-go-lap-ghep-main.jpg',
    '44d61427-ede4-54ef-93d5-da36a4032115': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542384/Nexus/collectibles/mo-hinh-go-lap-ghep/mo-hinh-go-lap-ghep-main.jpg',
    // Tranh in nghệ thuật A4
    'f86fe072-37a8-5a93-96a0-4ce96a7b266f': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542386/Nexus/collectibles/tranh-in-nghe-thuat-a4/tranh-in-nghe-thuat-a4-main.jpg',
    '884a9674-d058-5321-a0b7-3f2357e83fbc': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542386/Nexus/collectibles/tranh-in-nghe-thuat-a4/tranh-in-nghe-thuat-a4-main.jpg',
    // Bộ thiệp thủ công 6 mẫu
    '1b47b818-b783-55f7-8257-a1c34b59d694': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542389/Nexus/collectibles/bo-thiep-thu-cong-6-mau/bo-thiep-thu-cong-6-mau-main.jpg',
    '0d52b883-d204-5e03-8a4d-11b02d45064d': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542389/Nexus/collectibles/bo-thiep-thu-cong-6-mau/bo-thiep-thu-cong-6-mau-main.jpg',
}

// Bảng ánh xạ Product ID sang URL ảnh Cloudinary
export const KNOWN_PRODUCT_IMAGES: Record<string, string> = {
    '5b63d956-db10-4eb4-aaae-d1f730062430': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542335/Nexus/electronics/tai-nghe-gaming-razer-blackshark-v2/tai-nghe-gaming-razer-blackshark-v2-main.jpg',
    '02994692-3ec8-4d36-8ddd-3d6fe032786f': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542342/Nexus/electronics/iphone-18-pro-max-256gb/iphone-18-pro-max-256gb-main.jpg',
    '4d1ccfeb-574c-4c98-9522-d984a671c081': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542332/Nexus/electronics/chuot-gaming-logitech-g102/chuot-gaming-logitech-g102-main.jpg',
    'd4e4bbd5-f551-47ea-b0ad-fc79bddf6655': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542337/Nexus/electronics/man-hinh-gaming-asus-rog-swift/man-hinh-gaming-asus-rog-swift-main.jpg',
    '095d985e-9000-4e19-b006-7bfd9e941951': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542339/Nexus/electronics/laptop-msi-modern-15/laptop-msi-modern-15-main.jpg',
    'f5b74cc1-ba86-556e-ac9c-c7e6053c3361': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg',
    'ffe2f777-2384-51cd-b356-99029a9b4525': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542327/Nexus/electronics/ban-phim-co-k87/ban-phim-co-k87-main.jpg',
    'b6ab8d61-74c2-51bf-ae8b-fc257016d0c0': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542330/Nexus/electronics/chuot-khong-day-m2/chuot-khong-day-m2-main.jpg',
    '3d7af149-d9a9-510e-a0fa-8bd0365817ba': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg',
    '2e25c7a5-4953-550a-a938-57491b5c4188': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542347/Nexus/fashion/ao-khoac-gio-nhe/ao-khoac-gio-nhe-main.jpg',
    '650a7d48-bc1e-50b0-b97a-6beea91243c8': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542350/Nexus/fashion/ba-lo-di-hoc-20l/ba-lo-di-hoc-20l-main.jpg',
    'a5f07812-f808-5c23-b922-92c6da428243': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542353/Nexus/home-living/den-ban-hoc-led/den-ban-hoc-led-main.jpg',
    '576c6792-8ef3-5b55-8fe7-b24507439e4b': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542356/Nexus/home-living/binh-giu-nhiet-500ml/binh-giu-nhiet-500ml-main.jpg',
    '5bd68f91-8818-43e1-91c2-c61dc482254f': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542369/Nexus/sports/giay-chay-bo-nam-kipcore/giay-chay-bo-nam-kipcore-main.jpg',
    'f37cd911-964b-549f-b001-6faf3dbbc0fd': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542373/Nexus/stationery/so-tay-giay-cham-a5/so-tay-giay-cham-a5-main.jpg',
    'ec5db095-fd70-5fc0-9080-5115585ed3b0': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542375/Nexus/stationery/bo-but-viet-5-mau/bo-but-viet-5-mau-main.jpg',
    '4d53729c-6f3b-56d2-b451-8526023673e3': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542379/Nexus/stationery/gia-do-sach-de-ban/gia-do-sach-de-ban-main.jpg',
    '23369cb4-ce34-5156-8788-111ad8aac844': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542384/Nexus/collectibles/mo-hinh-go-lap-ghep/mo-hinh-go-lap-ghep-main.jpg',
    '44936e73-8646-5f60-a5d1-f5031654dec3': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542386/Nexus/collectibles/tranh-in-nghe-thuat-a4/tranh-in-nghe-thuat-a4-main.jpg',
    'f34eea18-38cd-5fa4-87c8-944985c293fe': 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542389/Nexus/collectibles/bo-thiep-thu-cong-6-mau/bo-thiep-thu-cong-6-mau-main.jpg',
}

// Bảng ánh xạ theo tên sản phẩm (phục vụ đối soát nhanh khi chỉ có snapshot tên trong đơn hàng)
export const KNOWN_NAME_PATTERNS: Array<{ pattern: RegExp; url: string }> = [
    {
        pattern: /razer|blackshark/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542335/Nexus/electronics/tai-nghe-gaming-razer-blackshark-v2/tai-nghe-gaming-razer-blackshark-v2-main.jpg',
    },
    {
        pattern: /iphone|apple/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542342/Nexus/electronics/iphone-18-pro-max-256gb/iphone-18-pro-max-256gb-main.jpg',
    },
    {
        pattern: /logitech|g102/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542332/Nexus/electronics/chuot-gaming-logitech-g102/chuot-gaming-logitech-g102-main.jpg',
    },
    {
        pattern: /asus|rog swift|màn hình/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542337/Nexus/electronics/man-hinh-gaming-asus-rog-swift/man-hinh-gaming-asus-rog-swift-main.jpg',
    },
    {
        pattern: /laptop|msi/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542339/Nexus/electronics/laptop-msi-modern-15/laptop-msi-modern-15-main.jpg',
    },
    {
        pattern: /tai nghe không dây n1/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg',
    },
    {
        pattern: /bàn phím|k87/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542327/Nexus/electronics/ban-phim-co-k87/ban-phim-co-k87-main.jpg',
    },
    {
        pattern: /chuột không dây m2/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542330/Nexus/electronics/chuot-khong-day-m2/chuot-khong-day-m2-main.jpg',
    },
    {
        pattern: /áo thun/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg',
    },
    {
        pattern: /áo khoác/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542347/Nexus/fashion/ao-khoac-gio-nhe/ao-khoac-gio-nhe-main.jpg',
    },
    {
        pattern: /ba lô/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542350/Nexus/fashion/ba-lo-di-hoc-20l/ba-lo-di-hoc-20l-main.jpg',
    },
    {
        pattern: /bình giữ nhiệt/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542356/Nexus/home-living/binh-giu-nhiet-500ml/binh-giu-nhiet-500ml-main.jpg',
    },
    {
        pattern: /giày chạy bộ|kipcore/i,
        url: 'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542369/Nexus/sports/giay-chay-bo-nam-kipcore/giay-chay-bo-nam-kipcore-main.jpg',
    },
]

// Fallback SVG Data URI chuẩn chất lượng cao để ảnh không bao giờ bị vỡ
const DEFAULT_SVG_PLACEHOLDER =
    'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect width="200" height="200" rx="16" fill="%23f1f5f9"/><path d="M70 130l25-30 20 22 15-18 20 26H70z" fill="%23cbd5e1"/><circle cx="85" cy="75" r="10" fill="%2394a3b8"/></svg>'

/**
 * Phân giải ảnh sản phẩm từ SKU ID, Product ID hoặc tên sản phẩm.
 *
 * @param idOrSku - UUID định danh SKU hoặc Product
 * @param productName - Tên sản phẩm lưu trong snapshot đơn hàng
 * @param fallbackUrl - URL ảnh dự phòng (nếu có từ đối tượng gốc)
 * @returns URL ảnh Cloudinary thực tế hoặc SVG placeholder hợp lệ (không bao giờ trả về "")
 */
export function resolveProductImage(
    idOrSku?: string | null,
    productName?: string | null,
    fallbackUrl?: string | null,
): string {
    // 1. Nếu fallbackUrl đã là URL Cloudinary hoặc HTTPS hợp lệ và không rỗng
    if (fallbackUrl && typeof fallbackUrl === 'string' && fallbackUrl.trim().length > 0) {
        const trimmed = fallbackUrl.trim()
        if (trimmed.startsWith('http://') || trimmed.startsWith('https://') || trimmed.startsWith('data:image')) {
            return trimmed
        }
    }

    // 2. Tra cứu qua SKU ID
    if (idOrSku && KNOWN_SKU_IMAGES[idOrSku]) {
        return KNOWN_SKU_IMAGES[idOrSku]
    }

    // 3. Tra cứu qua Product ID
    if (idOrSku && KNOWN_PRODUCT_IMAGES[idOrSku]) {
        return KNOWN_PRODUCT_IMAGES[idOrSku]
    }

    // 4. Tra cứu qua mẫu tên sản phẩm
    if (productName && typeof productName === 'string') {
        const trimmedName = productName.trim()
        for (const item of KNOWN_NAME_PATTERNS) {
            if (item.pattern.test(trimmedName)) {
                return item.url
            }
        }
    }

    // 5. Trả về placeholder chuẩn nếu không tìm thấy
    return DEFAULT_SVG_PLACEHOLDER
}
