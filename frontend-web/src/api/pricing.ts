import { api } from './client'

/**
 * Quy tắc tính giá động (Dynamic Pricing Rule) do Admin cấu hình theo danh mục hoặc toàn sàn.
 * Dùng trong Seller (xem trước giá bán dự kiến từ giá nhập minPrice) và Admin (CRUD quy tắc giá).
 */
export interface PricingRuleResponse {
    id: string
    name: string
    categoryId: string | null
    categoryName: string | null
    marginRate: number
    seasonFactor: number
    marketAdjustmentRate: number
    priority: number
    startDate: string | null
    endDate: string | null
    isActive: boolean
    createdAt: string
    updatedAt: string
}

/**
 * Payload tạo mới hoặc cập nhật quy tắc tính giá động trên Catalog Service.
 */
export interface PricingRuleRequest {
    name: string
    categoryId?: string | null
    marginRate: number
    seasonFactor: number
    marketAdjustmentRate: number
    priority: number
    startDate?: string | null
    endDate?: string | null
    isActive?: boolean
}

/**
 * Kết quả xem trước công thức tính giá bán động từ giá nhập tối thiểu (`sku.originalPrice`).
 * `sellingPrice = originalPrice * (1 + max(0, marginRate + seasonFactor + marketAdjustmentRate))`
 */
export interface PricePreviewResponse {
    originalPrice: number
    sellingPrice: number
    appliedRuleId: string | null
    appliedRuleName: string | null
    marginRate: number
    seasonFactor: number
    marketAdjustmentRate: number
    effectiveRate: number
}

/** Gỡ vỏ ApiResponse nếu gateway/service bọc trong `{ data }` hoặc trả trực tiếp. */
function unwrapData<T>(payload: { data?: T } | T): T {
    if (payload && typeof payload === 'object' && 'data' in payload && payload.data !== undefined) {
        return payload.data
    }
    return payload as T
}

/**
 * Lấy danh sách quy tắc giá đang hiệu lực công khai (dùng cho Seller/Buyer tham khảo).
 */
export async function listActivePricingRules(): Promise<PricingRuleResponse[]> {
    const res = await api<{ data?: PricingRuleResponse[] } | PricingRuleResponse[]>(
        '/pricing-rules',
    )
    const data = unwrapData(res)
    return Array.isArray(data) ? data : []
}

/**
 * Lấy toàn bộ quy tắc giá (kể cả quy tắc tắt/hết hạn) dành riêng cho màn hình Admin.
 */
export async function listAllPricingRules(): Promise<PricingRuleResponse[]> {
    const res = await api<{ data?: PricingRuleResponse[] } | PricingRuleResponse[]>(
        '/pricing-rules/all',
    )
    const data = unwrapData(res)
    return Array.isArray(data) ? data : []
}

/**
 * Gọi Backend xem trước giá bán động từ giá nhập tối thiểu (`originalPrice`) và danh mục (`categoryId`).
 * Nếu API tạm thời không phản hồi (chế độ demo/offline), ném lỗi để UI hiển thị cảnh báo phù hợp.
 */
export async function previewDynamicPrice(
    originalPrice: number,
    categoryId?: string | null,
): Promise<PricePreviewResponse> {
    const params = new URLSearchParams({
        originalPrice: String(originalPrice),
    })
    if (categoryId) {
        params.set('categoryId', categoryId)
    }
    const res = await api<{ data?: PricePreviewResponse } | PricePreviewResponse>(
        `/pricing-rules/preview?${params.toString()}`,
    )
    return unwrapData(res)
}

/** Tạo mới quy tắc tính giá động (chỉ Admin). */
export async function createPricingRule(req: PricingRuleRequest): Promise<PricingRuleResponse> {
    const res = await api<{ data?: PricingRuleResponse } | PricingRuleResponse>('/pricing-rules', {
        method: 'POST',
        body: JSON.stringify(req),
    })
    return unwrapData(res)
}

/** Cập nhật quy tắc tính giá động theo ID (chỉ Admin). */
export async function updatePricingRule(
    id: string,
    req: PricingRuleRequest,
): Promise<PricingRuleResponse> {
    const res = await api<{ data?: PricingRuleResponse } | PricingRuleResponse>(
        `/pricing-rules/${id}`,
        {
            method: 'PUT',
            body: JSON.stringify(req),
        },
    )
    return unwrapData(res)
}

/** Xóa quy tắc tính giá động theo ID (chỉ Admin). */
export async function deletePricingRule(id: string): Promise<void> {
    await api<void>(`/pricing-rules/${id}`, {
        method: 'DELETE',
    })
}
