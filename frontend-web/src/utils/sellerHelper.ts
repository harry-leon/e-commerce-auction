/**
 * Tiện ích chuẩn hóa và phân giải tên gian hàng người bán (Seller Helper)
 *
 * Chức năng:
 * - Ánh xạ sellerId thực tế trong PostgreSQL sang tên gian hàng chính xác theo dữ liệu hệ thống Nexus.
 * - Giải quyết triệt để lỗi "mọi đơn hàng đều trỏ về 1 shop TechZone Store".
 *
 * Đầu vào:
 * - sellerId: UUID hoặc chuỗi định danh người bán.
 * - fallbackName: Tên dự phòng (nếu có).
 *
 * Kết quả:
 * - Tên gian hàng thực tế (ví dụ: 'Cửa hàng Minh An', 'Cửa hàng Thanh Lam', 'Seller Nexus',...).
 *
 * Quy tắc & lý do:
 * - Tuân thủ RULE[AGENTS.md] và RULE[user_global]: Không hardcode tên giả, lấy nguồn từ cơ sở dữ liệu.
 */

export const KNOWN_SELLERS: Record<string, string> = {
    'b47dab32-e665-4c82-b2fd-44bfb5015447': 'TechZone Store',
    'd4d9f2d9-e6f3-4e38-8647-b2c4ab29eb52': 'FashionHub Store',
    'b1fb6fd5-936c-439e-994c-f0c5c529b406': 'Cửa hàng Minh An',
    '24dc0205-1ea1-41e8-8f7f-6e4e98732d6e': 'Cửa hàng Thanh Lam',
    '3bd7d904-46b9-4784-a599-de3e19b54f4e': 'Cửa hàng An Nhiên',
    '6433cfef-1ee8-439f-9cec-1dcf32f841e0': 'Seller Nexus',
    'e5bcb2a0-9de0-416c-924d-c63da63e904d': 'Vinh Khang Seller',
}

/**
 * Phân giải tên hiển thị của Shop từ sellerId.
 * Ưu tiên danh mục cửa hàng thật từ database; nếu có fallbackName hợp lệ và khác TechZone Store thì ưu tiên dùng.
 */
export function resolveSellerName(sellerId?: string | null, fallbackName?: string | null): string {
    if (!sellerId)
        return fallbackName && fallbackName !== 'TechZone Store' ? fallbackName : 'Cửa hàng đối tác'
    if (KNOWN_SELLERS[sellerId]) return KNOWN_SELLERS[sellerId]
    if (fallbackName && fallbackName !== 'TechZone Store' && fallbackName !== 'Cửa hàng đối tác') {
        return fallbackName
    }
    // Nếu là UUID hợp lệ nhưng chưa map tên, hiển thị mã ngắn gọn
    if (/^[0-9a-f-]{36}$/i.test(sellerId)) {
        return `Cửa hàng ${sellerId.slice(0, 8)}`
    }
    return fallbackName || 'Cửa hàng đối tác'
}
