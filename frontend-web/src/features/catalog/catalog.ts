import type { ApiCategory, ApiProduct } from '../../api/client'

/** Trải cây danh mục thành danh sách chọn; node lá thiếu children được coi là hết nhánh. */
export function flatten(categories: ApiCategory[]): ApiCategory[] {
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
