import type { ApiCategory, ApiProduct } from '../../api/client'

/** Trải cây danh mục thành danh sách chọn; vẫn giữ level để UI thụt lề đúng cấp. */
export function flatten(categories: ApiCategory[]): ApiCategory[] {
    return categories.flatMap((category) => [category, ...flatten(category.children)])
}

/** Giá đại diện là giá thấp nhất trong các SKU ACTIVE; không có SKU bán được thì báo chưa có giá. */
export function price(product: ApiProduct) {
    const prices = product.skus
        .filter((sku) => sku.status === 'ACTIVE')
        .map((sku) => Number(sku.price))
    return prices.length ? `${Math.min(...prices).toLocaleString('vi-VN')} ₫` : 'Chưa có giá bán'
}
