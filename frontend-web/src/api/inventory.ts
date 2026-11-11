import { api, post, type ApiResponse } from './client'

/** Trạng thái bản ghi SKU nội bộ trong kho Fulfillment (seller_skus). */
export type SellerSkuStatus = 'ACTIVE' | 'INACTIVE'

/**
 * Thông tin tồn kho chi tiết theo từng kho hàng, kèm sellerSkuId và skuCode vừa cấp/tái sử dụng.
 */
export interface InventoryStockResponse {
    skuId: string
    sellerSkuId?: string | null
    skuCode?: string | null
    warehouseId: string
    warehouseCode: string
    totalQty: number
    availableQty: number
    reservedQty: number
    unavailableQty: number
}

/**
 * Một dòng SKU kho trong danh sách GET /api/v1/inventory/skus (đã tổng hợp số lượng từ các kho).
 */
export interface SellerSkuResponse {
    id: string
    sellerSkuId: string
    sellerId: string
    skuId: string
    skuCode: string
    status: SellerSkuStatus
    totalQty: number
    availableQty: number
    reservedQty: number
    unavailableQty: number
    warehouseCount: number
    createdAt: string
    updatedAt: string
}

/**
 * Chi tiết một Seller SKU tại GET /api/v1/inventory/skus/{id}, kèm danh sách tồn theo từng kho hàng.
 */
export interface SellerSkuDetailResponse extends SellerSkuResponse {
    warehouses: InventoryStockResponse[]
}

/**
 * Dữ liệu sổ cái tồn kho bất biến từ GET /api/v1/inventory/ledger.
 */
export interface InventoryLedgerResponse {
    id: string
    skuId: string
    warehouseId: string
    movementType: string
    quantity: number
    refType: string
    refId?: string | null
    createdAt: string
}

/** Payload gửi yêu cầu nhập kho POST /api/v1/inventory/inbound. */
export interface StockInboundPayload {
    skuId: string
    warehouseId?: string | null
    quantity: number
    refId?: string | null
    unitCost?: number | null
}

/** Payload điều chỉnh kiểm kê tồn kho POST /api/v1/inventory/adjust (Admin/Kho). */
export interface StockAdjustmentPayload {
    skuId: string
    warehouseId?: string | null
    deltaQty: number
    reason: string
    refId?: string | null
}

/**
 * Tải danh sách SKU kho (seller_skus) của Người bán hiện tại (hoặc lọc theo sellerId với Admin).
 * Luồng: Gọi GET /api/v1/inventory/skus → bóc tách dữ liệu phân trang và meta.pagination.totalElements.
 */
export async function listSellerSkus(
    params: {
        keyword?: string
        status?: SellerSkuStatus
        sellerId?: string
        page?: number
        size?: number
    } = {},
    signal?: AbortSignal,
): Promise<{ items: SellerSkuResponse[]; total: number }> {
    const query = new URLSearchParams()
    if (params.keyword?.trim()) query.set('keyword', params.keyword.trim())
    if (params.status) query.set('status', params.status)
    if (params.sellerId) query.set('sellerId', params.sellerId)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 50))

    const res = await api<ApiResponse<SellerSkuResponse[]>>(`/inventory/skus?${query.toString()}`, {
        signal,
    })
    const items = Array.isArray(res?.data) ? res.data : []
    const total = res?.meta?.pagination?.totalElements ?? items.length
    return { items, total }
}

/**
 * Tra cứu chi tiết một bản ghi seller_skus và danh sách kho trực thuộc.
 */
export async function getSellerSkuDetail(
    id: string,
    signal?: AbortSignal,
): Promise<SellerSkuDetailResponse> {
    const res = await api<ApiResponse<SellerSkuDetailResponse>>(
        `/inventory/skus/${encodeURIComponent(id)}`,
        { signal },
    )
    if (!res?.data) {
        throw new Error('Không tìm thấy thông tin chi tiết SKU kho')
    }
    return res.data
}

/**
 * Nhập hàng vào kho Fulfillment theo skuId Catalog.
 * Nếu nhập kho lần đầu cho skuId, backend tự động sinh mã FSKU-XXXXXXXX và trả về trong response.
 */
export async function inboundStock(payload: StockInboundPayload): Promise<InventoryStockResponse> {
    const res = await post<ApiResponse<InventoryStockResponse>>('/inventory/inbound', payload)
    if (!res?.data) {
        throw new Error('Không nhận được phản hồi tồn kho sau khi nhập hàng')
    }
    return res.data
}

/**
 * Điều chỉnh kiểm kê số lượng tồn kho tại kho hàng (chỉ Admin / INVENTORY.MANAGE).
 */
export async function adjustStock(
    payload: StockAdjustmentPayload,
): Promise<InventoryStockResponse> {
    const res = await post<ApiResponse<InventoryStockResponse>>('/inventory/adjust', payload)
    if (!res?.data) {
        throw new Error('Không thể điều chỉnh tồn kho')
    }
    return res.data
}

/**
 * Tra cứu lịch sử sổ cái tồn kho bất biến (Admin / Support / INVENTORY.VIEW).
 */
export async function getInventoryLedger(
    params: { skuId?: string; refId?: string; page?: number; size?: number } = {},
    signal?: AbortSignal,
): Promise<InventoryLedgerResponse[]> {
    const query = new URLSearchParams()
    if (params.skuId) query.set('skuId', params.skuId)
    if (params.refId) query.set('refId', params.refId)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))

    const res = await api<ApiResponse<InventoryLedgerResponse[]>>(
        `/inventory/ledger?${query.toString()}`,
        { signal },
    )
    return Array.isArray(res?.data) ? res.data : []
}
