/**
 * Nexus Fulfillment & Shipping API Client (Sprint 3 - HA-S3-1)
 *
 * 1. Chức năng:
 *    - Giao tiếp với Fulfillment Service qua API Gateway (/api/v1/shipping và /api/v1/shipments).
 *    - Báo giá phí giao hàng đa hãng (GHN, GHTK) theo địa chỉ, khối lượng và tổng giá trị đơn hàng.
 *    - Quản lý vận đơn, tra cứu thông tin hành trình giao hàng thời gian thực cho Buyer và Seller.
 * 2. Đầu vào & kết quả:
 *    - Đầu vào: Địa chỉ giao hàng, thông tin đơn, mã vận đơn, ID đơn hàng.
 *    - Kết quả: Báo giá các phương thức vận chuyển, thông tin vận đơn và lịch sử trạng thái tracking.
 * 3. Luồng xử lý:
 *    - Gọi api() client từ src/api/client.ts gửi request có kèm JWT/Cookie xác thực.
 *    - Mở gói dữ liệu từ chuẩn envelope ApiResponse<T>.
 *    - Cung cấp cơ chế fallback an toàn (Resilience Fallback) khi dịch vụ backend chưa hoàn tất khởi động
 *      hoặc gặp sự cố mạng, bảo đảm giao dịch Checkout của người dùng không bị tắc nghẽn.
 * 4. Quy tắc & lý do:
 *    - Phí vận chuyển và điều kiện miễn phí vận chuyển (freeShippingApplied) được backend tính toán tự động.
 *    - Hỗ trợ đầy đủ các trạng thái vận đơn chuẩn hóa: PENDING, PICKING, PACKED, SHIPPED, IN_TRANSIT, DELIVERED, RETURNED, FAILED, CANCELLED.
 * 5. Lỗi & giới hạn:
 *    - Bắt lỗi HTTP 404 (chưa tạo vận đơn) và trả về null thay vì crash UI.
 *    - Có fallback mock báo giá chuẩn (GHN Express, GHTK Standard) khi backend trả lỗi hoặc timeout.
 */

import { api, ApiClientError } from './client'
import { ApiResponse } from './types'

// ─── Định nghĩa kiểu dữ liệu (Types & Interfaces) ─────────────────────────────

/** Trạng thái chuẩn hóa của vận đơn trong Nexus Fulfillment Service */
export type ShipmentStatus =
    | 'PENDING'
    | 'PICKING'
    | 'PACKED'
    | 'SHIPPED'
    | 'IN_TRANSIT'
    | 'DELIVERED'
    | 'RETURNED'
    | 'FAILED'
    | 'CANCELLED'

/** Dữ liệu yêu cầu báo giá vận chuyển (POST /api/v1/shipping/quotes) */
export interface ShippingQuoteRequest {
    orderId?: string
    warehouseId?: string
    carrier?: string
    serviceType?: string
    shippingAddress: string
    toDistrictId?: number
    toWardCode?: string
    weightGrams?: number
    orderTotal?: number
}

/** Một phương án báo giá vận chuyển từ từng hãng vận chuyển đối tác */
export interface ShippingQuoteOption {
    carrier: string
    serviceType: string
    shippingFee: number
    originalFee: number
    freeShippingApplied: boolean
    estimatedDeliveryDays: number
    estimatedDeliveryAt?: string
}

/** Kết quả báo giá vận chuyển tổng hợp từ Fulfillment Service */
export interface ShippingQuoteResponse {
    orderId?: string
    warehouseId?: string
    defaultCarrier: string
    freeShippingThreshold: number
    freeShippingApplied: boolean
    options: ShippingQuoteOption[]
}

/** Thông tin hãng vận chuyển được hệ thống hỗ trợ */
export interface CarrierInfo {
    code: string
    name: string
    active: boolean
    serviceTypes?: string[]
}

/** Chi tiết vận đơn từ Fulfillment Service */
export interface ShipmentResponse {
    id: string
    orderId: string
    warehouseId?: string
    warehouseCode?: string
    carrier: string
    serviceType?: string
    shippingFee: number
    freeShippingApplied: boolean
    trackingNumber: string
    status: ShipmentStatus
    estimatedDeliveryDays?: number
    estimatedDeliveryAt?: string
    recipientName?: string
    recipientPhone?: string
    shippingAddress?: string
    createdAt?: string
    updatedAt?: string
}

/** Một mốc lịch sử hành trình vận đơn */
export interface TrackingHistoryItem {
    carrierStatus: string
    internalStatus: ShipmentStatus
    occurredAt: string
    location?: string
    description?: string
}

/** Phản hồi tra cứu hành trình vận đơn thời gian thực */
export interface ShipmentTrackingResponse {
    shipmentId: string
    orderId: string
    carrier: string
    trackingNumber: string
    status: ShipmentStatus
    carrierStatus?: string
    currentLocation?: string
    estimatedDeliveryAt?: string
    history: TrackingHistoryItem[]
}

/** Yêu cầu tạo vận đơn thủ công từ Seller hoặc hệ thống */
export interface CreateShipmentRequest {
    orderId: string
    carrier: string
    serviceType?: string
    warehouseId?: string
    recipientName: string
    recipientPhone: string
    shippingAddress: string
    weightGrams?: number
    codAmount?: number
}

// ─── Dữ liệu Fallback dự phòng khi Backend Offline ──────────────────────────

/** Báo giá mặc định dự phòng theo tiêu chuẩn Nexus SRS §3.6.2 */
export function getFallbackShippingQuotes(orderTotal: number = 0): ShippingQuoteResponse {
    const freeShippingThreshold = 500000 // 500.000 VNĐ
    const isFree = orderTotal >= freeShippingThreshold

    const now = Date.now()
    const ghnDate = new Date(now + 3 * 24 * 3600 * 1000).toISOString()
    const ghtkDate = new Date(now + 5 * 24 * 3600 * 1000).toISOString()

    return {
        defaultCarrier: 'GHN',
        freeShippingThreshold,
        freeShippingApplied: isFree,
        options: [
            {
                carrier: 'GHN',
                serviceType: 'EXPRESS',
                shippingFee: isFree ? 0 : 55000,
                originalFee: 55000,
                freeShippingApplied: isFree,
                estimatedDeliveryDays: 3,
                estimatedDeliveryAt: ghnDate,
            },
            {
                carrier: 'GHTK',
                serviceType: 'STANDARD',
                shippingFee: isFree ? 0 : 35000,
                originalFee: 35000,
                freeShippingApplied: isFree,
                estimatedDeliveryDays: 5,
                estimatedDeliveryAt: ghtkDate,
            },
        ],
    }
}

// ─── API Client Service ───────────────────────────────────────────────────────

export const shippingApi = {
    /**
     * Lấy báo giá phí vận chuyển từ các hãng đối tác (POST /api/v1/shipping/quotes).
     * Luồng: Gửi địa chỉ + orderTotal sang Fulfillment Service.
     * Nếu backend chưa có hoặc gặp lỗi mạng, tự động sử dụng fallback chuẩn để không chặn luồng thanh toán.
     */
    async getShippingQuotes(request: ShippingQuoteRequest): Promise<ShippingQuoteResponse> {
        try {
            const envelope = await api<ApiResponse<ShippingQuoteResponse>>('/shipping/quotes', {
                method: 'POST',
                body: JSON.stringify(request),
            })
            if (envelope?.data?.options && envelope.data.options.length > 0) {
                return envelope.data
            }
        } catch (error) {
            // Log cảnh báo dev và kích hoạt fallback
            console.warn('[shippingApi] Backend quote unavailable, using resilient fallback:', error)
        }
        return getFallbackShippingQuotes(request.orderTotal || 0)
    },

    /**
     * Lấy danh sách hãng vận chuyển hỗ trợ trong hệ thống (GET /api/v1/shipping/carriers).
     */
    async getCarriers(): Promise<CarrierInfo[]> {
        try {
            const envelope = await api<ApiResponse<CarrierInfo[]>>('/shipping/carriers')
            if (envelope?.data && Array.isArray(envelope.data)) {
                return envelope.data
            }
        } catch {
            // Fallback danh sách hãng tiêu chuẩn
        }
        return [
            { code: 'GHN', name: 'Giao Hàng Nhanh (GHN Express)', active: true, serviceTypes: ['EXPRESS'] },
            { code: 'GHTK', name: 'Giao Hàng Tiết Kiệm (GHTK)', active: true, serviceTypes: ['STANDARD'] },
        ]
    },

    /**
     * Lấy chi tiết vận đơn theo mã đơn hàng (GET /api/v1/shipments/order/{orderId}).
     * Trả về null nếu đơn hàng chưa có vận đơn (404 Not Found).
     */
    async getShipmentByOrderId(orderId: string): Promise<ShipmentResponse | null> {
        try {
            const envelope = await api<ApiResponse<ShipmentResponse>>(`/shipments/order/${encodeURIComponent(orderId)}`)
            return envelope?.data || null
        } catch (error) {
            if (error instanceof ApiClientError && error.status === 404) {
                return null
            }
            // Nếu lỗi khác, ghi log cảnh báo
            console.warn(`[shippingApi] Could not load shipment for order ${orderId}:`, error)
            return null
        }
    },

    /**
     * Lấy chi tiết vận đơn theo ID vận đơn (GET /api/v1/shipments/{shipmentId}).
     */
    async getShipmentById(shipmentId: string): Promise<ShipmentResponse | null> {
        try {
            const envelope = await api<ApiResponse<ShipmentResponse>>(`/shipments/${encodeURIComponent(shipmentId)}`)
            return envelope?.data || null
        } catch {
            return null
        }
    },

    /**
     * Tra cứu hành trình vận đơn thời gian thực theo shipmentId (GET /api/v1/shipments/{shipmentId}/tracking).
     */
    async getShipmentTracking(shipmentId: string): Promise<ShipmentTrackingResponse | null> {
        try {
            const envelope = await api<ApiResponse<ShipmentTrackingResponse>>(`/shipments/${encodeURIComponent(shipmentId)}/tracking`)
            return envelope?.data || null
        } catch (error) {
            console.warn(`[shippingApi] Could not load tracking for shipment ${shipmentId}:`, error)
            return null
        }
    },

    /**
     * Tra cứu hành trình vận đơn theo mã trackingNumber (GET /api/v1/shipments/tracking/{trackingNumber}).
     */
    async trackByTrackingNumber(trackingNumber: string): Promise<ShipmentTrackingResponse | null> {
        try {
            const envelope = await api<ApiResponse<ShipmentTrackingResponse>>(`/shipments/tracking/${encodeURIComponent(trackingNumber)}`)
            return envelope?.data || null
        } catch {
            return null
        }
    },

    /**
     * Tạo vận đơn mới cho đơn hàng (POST /api/v1/shipments).
     */
    async createShipment(request: CreateShipmentRequest, idempotencyKey?: string): Promise<ShipmentResponse> {
        const headers: Record<string, string> = {}
        if (idempotencyKey) {
            headers['Idempotency-Key'] = idempotencyKey
        }
        const envelope = await api<ApiResponse<ShipmentResponse>>('/shipments', {
            method: 'POST',
            headers,
            body: JSON.stringify(request),
        })
        if (!envelope?.data) {
            throw new Error('Tạo vận đơn không thành công')
        }
        return envelope.data
    },
}
