/**
 * Client API chuẩn hóa cho Quản lý Kho hàng (Warehouses), SKU kho (seller_skus)
 * và Tồn kho (Inventory) của Fulfillment Service.
 *
 * Chức năng:
 * - Tra cứu kho hàng, tạo/cập nhật kho hàng (Admin).
 * - Quản lý danh sách Seller SKU nội bộ (`seller_skus`, mã `FSKU-XXXXXXXX` tách khỏi Catalog).
 * - Đọc danh sách tồn kho phân trang (theo phạm vi Seller trên backend), đọc theo skuId/skuCode và đọc hàng loạt (batch).
 * - Nhập kho (inbound) và điều chỉnh tồn kho (adjust) kèm khóa Idempotency-Key chống lặp thao tác.
 * - Tra cứu sổ cái bất biến (ledger) và đối soát tồn kho (reconcile).
 */

import {
    api,
    post,
    type ApiResponse,
    type MetaInfo,
    type PaginatedResult,
    type PaginationInfo,
} from './client'

/** Loại hình kho hàng hỗ trợ bởi Fulfillment Service */
export type WarehouseType = 'STANDARD' | 'BONDED' | 'RETURN' | 'CROSS_DOCK'

/** Loại biến động trong Sổ cái tồn kho bất biến (Inventory Ledger) */
export type LedgerMovementType = 'INBOUND' | 'OUTBOUND' | 'RESERVE' | 'RELEASE' | 'ADJUSTMENT'

/** Trạng thái bản ghi SKU nội bộ trong kho Fulfillment (seller_skus) */
export type SellerSkuStatus = 'ACTIVE' | 'INACTIVE'

/** Thông tin kho hàng vật lý */
export interface WarehouseResponse {
    id: string
    code: string
    name: string
    address: string
    type: WarehouseType
    active: boolean
}

/** Payload tạo kho hàng mới (yêu cầu quyền ADMIN) */
export interface CreateWarehouseRequest {
    code: string
    name: string
    address: string
    type?: WarehouseType
    active?: boolean
}

/** Payload cập nhật thông tin kho hàng (yêu cầu quyền ADMIN) */
export interface UpdateWarehouseRequest {
    name: string
    address: string
    type?: WarehouseType
    active?: boolean
}

/** Chi tiết 4 trạng thái tồn kho của SKU tại một kho hàng (kèm sellerSkuId và FSKU code) */
export interface InventoryStockResponse {
    skuId: string
    sellerSkuId?: string | null
    skuCode?: string | null
    warehouseId: string
    warehouseCode: string
    warehouseName?: string | null
    totalQty: number
    availableQty: number
    reservedQty: number
    unavailableQty: number
}

/** Một dòng SKU kho trong danh sách GET /api/v1/inventory/skus (tổng hợp số lượng đa kho) */
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

/** Chi tiết một Seller SKU tại GET /api/v1/inventory/skus/{id}, kèm tồn kho từng kho hàng */
export interface SellerSkuDetailResponse extends SellerSkuResponse {
    warehouses: InventoryStockResponse[]
}

/** Yêu cầu đọc tồn kho hàng loạt theo danh sách skuIds */
export interface BatchStockRequest {
    skuIds: string[]
    warehouseId?: string | null
}

/** Yêu cầu nhập kho hàng hóa (hỗ trợ skuId hoặc skuCode nội bộ, unitCost và idempotencyKey) */
export interface StockInboundRequest {
    skuId?: string | null
    skuCode?: string | null
    warehouseId?: string | null
    quantity: number
    refId?: string | null
    unitCost?: number | null
    reason?: string | null
    idempotencyKey?: string | null
}

/** Alias giữ tương thích cho code/test cũ của nhánh HG-S2-6 */
export type StockInboundPayload = StockInboundRequest

/** Yêu cầu điều chỉnh kiểm kê tồn kho (deltaQty tương đối hoặc targetTotalQty mục tiêu) */
export interface StockAdjustmentRequest {
    skuId?: string | null
    skuCode?: string | null
    warehouseId?: string | null
    deltaQty?: number | null
    targetTotalQty?: number | null
    unavailableDeltaQty?: number | null
    reason: string
    refId?: string | null
    idempotencyKey?: string | null
}

/** Alias giữ tương thích cho code/test cũ của nhánh HG-S2-6 */
export type StockAdjustmentPayload = StockAdjustmentRequest

/** Bản ghi Sổ cái tồn kho bất biến (chỉ đọc) */
export interface InventoryLedgerResponse {
    id: string
    skuId: string
    warehouseId: string
    warehouseCode?: string | null
    movementType: LedgerMovementType | string
    quantity: number
    refType?: string | null
    refId?: string | null
    reason?: string | null
    actorId?: string | null
    createdAt: string
}

/** Kết quả đối soát giữa tồn kho hiện tại, sổ cái và phiếu giữ kho PENDING */
export interface InventoryReconciliationResponse {
    skuId: string
    warehouseId: string
    warehouseCode: string
    totalQty: number
    availableQty: number
    reservedQty: number
    unavailableQty: number
    ledgerTotalQty: number
    ledgerReservedQty: number
    activeReservationQty: number
    nonNegative: boolean
    matched: boolean
}

/** Bộ lọc tra cứu danh sách kho */
export interface WarehouseListParams {
    activeOnly?: boolean
    type?: WarehouseType
    q?: string
    page?: number
    size?: number
    sort?: string
}

/** Bộ lọc tra cứu danh sách tồn kho (backend tự giới hạn SKU cho SELLER) */
export interface InventoryListParams {
    skuId?: string
    skuCode?: string
    warehouseId?: string
    sellerId?: string
    page?: number
    size?: number
    sort?: string
}

/** Bộ lọc tra cứu lịch sử biến động sổ cái */
export interface LedgerListParams {
    skuId?: string
    skuCode?: string
    warehouseId?: string
    movementType?: LedgerMovementType
    refType?: string
    refId?: string
    from?: string
    to?: string
    page?: number
    size?: number
    sort?: string
}

function buildQuery(params: Record<string, string | number | boolean | undefined | null>): string {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
        if (value === undefined || value === null || value === '') {
            continue
        }
        search.set(key, String(value))
    }
    const qs = search.toString()
    return qs ? `?${qs}` : ''
}

function toPaginatedResult<T>(response: ApiResponse<T[]>, page = 0, size = 20): PaginatedResult<T> {
    const items = Array.isArray(response.data) ? response.data : []
    const meta: MetaInfo = response.meta ?? {
        requestId: '',
        timestamp: new Date().toISOString(),
    }
    const pagination: PaginationInfo = meta.pagination ?? {
        page,
        size,
        totalElements: items.length,
        totalPages: items.length > 0 ? 1 : 0,
        hasNext: false,
        hasPrevious: page > 0,
    }
    return { items, pagination, meta }
}

/**
 * Tạo khóa chống lặp (Idempotency-Key) duy nhất cho một thao tác nhập/điều chỉnh từ UI;
 * caller nên giữ nguyên key này khi người dùng bấm "Thử lại" cho cùng phiếu.
 */
export function createInventoryIdempotencyKey(prefix = 'inv'): string {
    const randomPart =
        typeof globalThis.crypto?.randomUUID === 'function'
            ? globalThis.crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    return `${prefix}-${randomPart}`
}

/**
 * Liệt kê danh sách kho hàng (GET /api/v1/warehouses).
 * Phân quyền: ADMIN, SUPPORT, SELLER.
 */
export async function listWarehouses(
    params: WarehouseListParams = {},
    signal?: AbortSignal,
): Promise<PaginatedResult<WarehouseResponse>> {
    const query = buildQuery({ ...params })
    const response = await api<ApiResponse<WarehouseResponse[]>>(`/warehouses${query}`, { signal })
    return toPaginatedResult(response, params.page ?? 0, params.size ?? 50)
}

/**
 * Lấy thông tin kho hàng theo mã nghiệp vụ (GET /api/v1/warehouses/code/{code}).
 */
export async function getWarehouseByCode(
    code: string,
    signal?: AbortSignal,
): Promise<WarehouseResponse> {
    const response = await api<ApiResponse<WarehouseResponse>>(
        `/warehouses/code/${encodeURIComponent(code.trim())}`,
        { signal },
    )
    if (!response.data) {
        throw new Error('Không tìm thấy dữ liệu kho hàng')
    }
    return response.data
}

/**
 * Tạo kho hàng mới (POST /api/v1/warehouses - chỉ ADMIN).
 */
export async function createWarehouse(request: CreateWarehouseRequest): Promise<WarehouseResponse> {
    const response = await post<ApiResponse<WarehouseResponse>>('/warehouses', request)
    if (!response.data) {
        throw new Error('Phản hồi tạo kho hàng không hợp lệ')
    }
    return response.data
}

/**
 * Cập nhật thông tin kho hàng (PUT /api/v1/warehouses/{warehouseId} - chỉ ADMIN).
 */
export async function updateWarehouse(
    warehouseId: string,
    request: UpdateWarehouseRequest,
): Promise<WarehouseResponse> {
    const response = await api<ApiResponse<WarehouseResponse>>(
        `/warehouses/${encodeURIComponent(warehouseId)}`,
        {
            method: 'PUT',
            body: JSON.stringify(request),
        },
    )
    if (!response.data) {
        throw new Error('Phản hồi cập nhật kho hàng không hợp lệ')
    }
    return response.data
}

/**
 * Tải danh sách SKU kho (seller_skus) của Người bán hiện tại (GET /api/v1/inventory/skus).
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
    const query = buildQuery({
        keyword: params.keyword?.trim() || undefined,
        status: params.status,
        sellerId: params.sellerId,
        page: params.page ?? 0,
        size: params.size ?? 50,
    })
    const res = await api<ApiResponse<SellerSkuResponse[]>>(`/inventory/skus${query}`, {
        signal,
    })
    const items = Array.isArray(res?.data) ? res.data : []
    const total = res?.meta?.pagination?.totalElements ?? items.length
    return { items, total }
}

/**
 * Tra cứu chi tiết một bản ghi seller_skus và phân bổ tồn kho theo từng kho (GET /api/v1/inventory/skus/{id}).
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
 * Liệt kê tồn kho phân trang (GET /api/v1/inventory).
 * Seller chỉ nhận về tồn kho của các SKU mình sở hữu; Admin/Support xem toàn hệ thống.
 */
export async function listInventory(
    params: InventoryListParams = {},
    signal?: AbortSignal,
): Promise<PaginatedResult<InventoryStockResponse>> {
    const query = buildQuery({ ...params })
    const response = await api<ApiResponse<InventoryStockResponse[]>>(`/inventory${query}`, {
        signal,
    })
    return toPaginatedResult(response, params.page ?? 0, params.size ?? 20)
}

/**
 * Liệt kê tồn kho tại một kho cụ thể (GET /api/v1/warehouses/{warehouseId}/inventory).
 */
export async function listWarehouseInventory(
    warehouseId: string,
    params: Omit<InventoryListParams, 'warehouseId'> = {},
    signal?: AbortSignal,
): Promise<PaginatedResult<InventoryStockResponse>> {
    const query = buildQuery({ ...params })
    const response = await api<ApiResponse<InventoryStockResponse[]>>(
        `/warehouses/${encodeURIComponent(warehouseId)}/inventory${query}`,
        { signal },
    )
    return toPaginatedResult(response, params.page ?? 0, params.size ?? 20)
}

/**
 * Tra cứu tồn kho của 1 SKU theo skuId (GET /api/v1/inventory/sku/{skuId}).
 */
export async function getStockBySku(
    skuId: string,
    warehouseId?: string,
    signal?: AbortSignal,
): Promise<InventoryStockResponse> {
    const query = buildQuery({ warehouseId })
    const response = await api<ApiResponse<InventoryStockResponse>>(
        `/inventory/sku/${encodeURIComponent(skuId)}${query}`,
        { signal },
    )
    if (!response.data) {
        throw new Error('Không đọc được thông tin tồn kho SKU')
    }
    return response.data
}

/**
 * Tra cứu tồn kho của 1 SKU theo mã doanh nghiệp skuCode (GET /api/v1/inventory/sku/code/{skuCode}).
 */
export async function getStockBySkuCode(
    skuCode: string,
    warehouseId?: string,
    signal?: AbortSignal,
): Promise<InventoryStockResponse> {
    const query = buildQuery({ warehouseId })
    const response = await api<ApiResponse<InventoryStockResponse>>(
        `/inventory/sku/code/${encodeURIComponent(skuCode.trim())}${query}`,
        { signal },
    )
    if (!response.data) {
        throw new Error('Không đọc được thông tin tồn kho theo skuCode')
    }
    return response.data
}

/**
 * Đọc tồn kho hàng loạt cho danh sách skuIds (POST /api/v1/inventory/stocks/batch).
 */
export async function getBatchStock(
    request: BatchStockRequest,
    signal?: AbortSignal,
): Promise<InventoryStockResponse[]> {
    const response = await api<ApiResponse<InventoryStockResponse[]>>('/inventory/stocks/batch', {
        method: 'POST',
        body: JSON.stringify(request),
        signal,
    })
    return Array.isArray(response.data) ? response.data : []
}

/**
 * Nhập kho hàng hóa (POST /api/v1/inventory/inbound).
 * Nếu nhập lần đầu cho skuId, backend tự sinh mã FSKU-XXXXXXXX và trả về trong response.
 * Truyền Idempotency-Key header để retry HTTP không làm tăng kho hai lần.
 */
export async function inboundStock(
    request: StockInboundRequest,
    idempotencyKey?: string,
): Promise<InventoryStockResponse> {
    const effectiveKey = idempotencyKey || request.idempotencyKey || undefined
    const headers: Record<string, string> = {}
    if (effectiveKey) {
        headers['Idempotency-Key'] = effectiveKey
    }
    const response = await api<ApiResponse<InventoryStockResponse>>('/inventory/inbound', {
        method: 'POST',
        headers,
        body: JSON.stringify(request),
    })
    if (!response.data) {
        throw new Error('Phản hồi nhập kho không hợp lệ')
    }
    return response.data
}

/**
 * Điều chỉnh kiểm kê tồn kho (POST /api/v1/inventory/adjust).
 * Truyền Idempotency-Key header để tránh điều chỉnh lặp khi mạng chậm/retry.
 */
export async function adjustStock(
    request: StockAdjustmentRequest,
    idempotencyKey?: string,
): Promise<InventoryStockResponse> {
    const effectiveKey = idempotencyKey || request.idempotencyKey || undefined
    const headers: Record<string, string> = {}
    if (effectiveKey) {
        headers['Idempotency-Key'] = effectiveKey
    }
    const response = await api<ApiResponse<InventoryStockResponse>>('/inventory/adjust', {
        method: 'POST',
        headers,
        body: JSON.stringify(request),
    })
    if (!response.data) {
        throw new Error('Phản hồi điều chỉnh tồn kho không hợp lệ')
    }
    return response.data
}

/**
 * Tra cứu lịch sử biến động Sổ cái tồn kho bất biến (GET /api/v1/inventory/ledger).
 */
export async function getInventoryLedger(
    params: LedgerListParams = {},
    signal?: AbortSignal,
): Promise<PaginatedResult<InventoryLedgerResponse>> {
    const query = buildQuery({ ...params })
    const response = await api<ApiResponse<InventoryLedgerResponse[]>>(
        `/inventory/ledger${query}`,
        { signal },
    )
    return toPaginatedResult(response, params.page ?? 0, params.size ?? 20)
}

/**
 * Đối soát tồn kho thực tế với sổ cái bất biến (GET /api/v1/inventory/reconcile).
 */
export async function reconcileInventory(
    params: { skuId?: string; warehouseId?: string } = {},
    signal?: AbortSignal,
): Promise<InventoryReconciliationResponse[]> {
    const query = buildQuery({ ...params })
    const response = await api<ApiResponse<InventoryReconciliationResponse[]>>(
        `/inventory/reconcile${query}`,
        { signal },
    )
    return Array.isArray(response.data) ? response.data : []
}
