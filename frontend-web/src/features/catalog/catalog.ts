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

import type { Lang } from '../../types'
import { formatCurrency } from '../../i18n'

/**
 * Định dạng khoảng giá động [minPrice .. maxPrice] của sản phẩm cho các màn danh sách/chi tiết.
 * Đầu vào: minPrice/maxPrice do Backend tổng hợp từ SKU ACTIVE, fallback về đơn giá lẻ nếu chưa có.
 * Quy tắc: Nếu min == max hiển thị 1 mức giá; nếu khác hiển thị "min ~ max"; nếu không có trả về thông báo phù hợp.
 */
export function formatPriceRange(
    minPrice?: number | null,
    maxPrice?: number | null,
    lang?: Lang,
): string | null {
    const validMin = minPrice != null && Number.isFinite(Number(minPrice)) ? Number(minPrice) : null
    const validMax = maxPrice != null && Number.isFinite(Number(maxPrice)) ? Number(maxPrice) : null
    if (validMin === null && validMax === null) return null
    const lo = validMin ?? validMax!
    const hi = validMax ?? validMin!
    const fmt = (value: number) =>
        lang ? formatCurrency(value, lang) : `${value.toLocaleString('vi-VN')} ₫`
    return Math.abs(lo - hi) < 0.001 ? fmt(lo) : `${fmt(lo)} – ${fmt(hi)}`
}

/** Lấy khoảng giá (hoặc giá thấp nhất) trong SKU ACTIVE; thiếu dữ liệu giá thì báo chưa có giá, không lỗi render. */
export function price(product: ApiProduct, lang?: Lang) {
    const backendRange = formatPriceRange(product.minPrice, product.maxPrice, lang)
    if (backendRange) {
        return backendRange
    }
    const prices = (Array.isArray(product.skus) ? product.skus : [])
        .filter((sku) => sku.status === 'ACTIVE')
        .map((sku) => Number(sku.price))
        .filter((v) => Number.isFinite(v))
    if (!prices.length) {
        return 'Chưa có giá bán'
    }
    const min = Math.min(...prices)
    const max = Math.max(...prices)
    return formatPriceRange(min, max, lang) ?? `${min.toLocaleString('vi-VN')} ₫`
}
