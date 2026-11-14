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

/** Lấy giá thấp nhất trong SKU ACTIVE; thiếu danh sách SKU thì báo chưa có giá, không lỗi render. */
export function price(product: ApiProduct) {
    const prices = (Array.isArray(product.skus) ? product.skus : [])
        .filter((sku) => sku.status === 'ACTIVE')
        .map((sku) => Number(sku.price))
    return prices.length ? `${Math.min(...prices).toLocaleString('vi-VN')} ₫` : 'Chưa có giá bán'
}

/**
 * Chỉ giữ sản phẩm có ít nhất một SKU đang hoạt động và còn số lượng bán được.
 * SKU không có dữ liệu tồn kho được xem là không khả dụng để tránh quảng bá hàng chưa xác nhận.
 */
export function filterInStockProducts(
    products: ApiProduct[],
    availableQtyBySku: Record<string, number>,
): ApiProduct[] {
    return products.filter((product) =>
        (Array.isArray(product.skus) ? product.skus : []).some(
            (sku) => sku.status === 'ACTIVE' && (availableQtyBySku[sku.id] ?? 0) > 0,
        ),
    )
}
