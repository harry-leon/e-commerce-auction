import type { ApiCategory, ApiProduct } from '../../api/client'

/**
 * Trải cây danh mục lồng nhau thành mảng phẳng phục vụ hiển thị danh sách chọn và quản trị.
 * Chức năng: Đệ quy duyệt cây danh mục, bảo đảm an toàn khi categories hoặc category.children là undefined/null.
 * Đầu vào: Mảng categories (có thể rỗng hoặc chứa node con lồng nhau).
 * Kết quả: Danh sách phẳng ApiCategory[] bảo lưu thứ tự cấp bậc (level) để giao diện thụt lề chuẩn.
 */
export function flatten(categories: ApiCategory[] = []): ApiCategory[] {
    if (!Array.isArray(categories)) return []
    return categories.flatMap((category) => [
        category,
        ...flatten(Array.isArray(category.children) ? category.children : []),
    ])
}

/** Giá đại diện là giá thấp nhất trong các SKU ACTIVE; không có SKU bán được thì báo chưa có giá. */
export function price(product: ApiProduct) {
    const prices = product.skus
        .filter((sku) => sku.status === 'ACTIVE')
        .map((sku) => Number(sku.price))
    return prices.length ? `${Math.min(...prices).toLocaleString('vi-VN')} ₫` : 'Chưa có giá bán'
}
