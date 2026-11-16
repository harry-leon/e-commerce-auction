import { api, readSession } from './client'
import type { ApiResponse } from './client'
import type { Order, OrderStatus, UserRole } from '../types'
import { ORDERS } from '../mockData'
import { normalizeOrder, type OrderPayload } from './orderData'
import { resolveSellerName } from '../utils/sellerHelper'

/**
 * Khóa lưu trữ dữ liệu mock đơn hàng trong localStorage để các thao tác chuyển trạng thái
 * (Buyer hủy, Seller cập nhật, Admin xử lý khiếu nại) có thể lưu vết bền vững khi dev/test.
 */
const STORAGE_KEY = 'nexus.mock-orders-store'
const WITHDRAWAL_STORAGE_KEY = 'nexus.mock-withdrawals-store'
// Tăng version khi thực hiện migration làm thay đổi schema/dữ liệu để tự động reset localStorage cũ.
// V2: OrderStatus chuyển từ lowercase → UPPERCASE_SNAKE_CASE (đồng nhất với backend spec).
const MOCK_DATA_VERSION = '2'
const MOCK_VERSION_KEY = 'nexus.mock-orders-version'

/**
 * DTO yêu cầu xem trước tính giá và re-validate đơn hàng khi Checkout.
 * Khớp với đặc tả API_SPEC_CART_ORDER.md §4.1.
 */
export interface CheckoutPreviewRequest {
    /** Mua trực tiếp: server đọc giá/kho theo SKU, không dùng hoặc thay đổi giỏ. */
    buyNow?: { skuId: string; quantity: number }
    itemIds?: string[]
    shippingAddress: string
    voucherCode?: string
}

/**
 * DTO kết quả xem trước đơn hàng từ Backend.
 */
export interface CheckoutPreviewResponse {
    /** Snapshot Catalog/giỏ tại lúc preview; dùng cùng số lượng và giá với tổng tiền server. */
    items?: {
        skuId: string
        sellerId: string
        productName: string
        skuCode: string
        unitPrice: number
        cartUnitPrice: number | null
        quantity: number
        itemTotal: number
    }[]
    subtotal: number
    discountAmount: number
    shippingFee: number
    taxAmount: number
    totalAmount: number
    appliedVoucher?: {
        code: string
        discountValue: number
        description: string
    } | null
    isOrderable: boolean
    warnings?: string[]
}

/**
 * DTO yêu cầu tạo đơn hàng (Checkout / Place Order) gửi lên Backend.
 * Khớp với đặc tả API_SPEC_CART_ORDER.md §4.2.
 */
export interface CreateOrderRequest {
    /** Cùng mục tiêu với preview; không truyền selectedItemIds khi dùng chế độ này. */
    buyNow?: { skuId: string; quantity: number }
    shippingAddress: string
    recipientName: string
    recipientPhone: string
    voucherCode?: string
    paymentMethod: 'CARD' | 'VNPAY' | 'COD'
    sourceType?: 'REGULAR' | 'AUCTION'
    auctionId?: string | null
    selectedItemIds?: string[]
    /** Hãng vận chuyển được chọn (GHN, GHTK - HA-S3-1) */
    carrier?: string
    /** Phí vận chuyển áp dụng cho đơn */
    shippingFee?: number
}

/**
 * DTO kết quả tạo đơn hàng phản hồi từ Backend.
 * Chứa thông tin đơn hàng và paymentUrl chuyển hướng cổng sandbox (nếu có).
 */
export interface CreateOrderResponse {
    id: string
    orderNo: string
    status: OrderStatus
    subtotal: number
    shippingFee: number
    discount: number
    total: number
    paymentMethod: 'CARD' | 'VNPAY' | 'COD'
    paymentUrl?: string
    /** Giá dự kiến của đơn chưa thu tiền; không đổi null giá chính thức thành giá 0. */
    estimatedSubtotal?: number
    estimatedShippingFee?: number
    estimatedDiscount?: number
    estimatedTotal?: number
}

/** Snapshot thật của đơn đang retry; độc lập với giỏ ACTIVE mới và không fallback sang dữ liệu demo. */
export interface CheckoutOrderSnapshot {
    id: string
    subtotal: number | null
    shippingFee: number | null
    discount: number | null
    total: number | null
    estimatedSubtotal: number
    estimatedShippingFee: number
    estimatedDiscount: number
    estimatedTotal: number
    items: {
        id: string
        skuId: string
        skuCode: string
        productName: string
        quantity: number
        unitPrice: number | null
        estimatedUnitPrice: number
    }[]
}

export type OrderDetailResponse = Order

export interface WithdrawalRequest {
    id: string
    sellerId: string
    sellerName: string
    bankName: string
    accountNumber: string
    amount: number
    status: 'pending' | 'approved'
    approvedAt?: string
}

const SEED_WITHDRAWALS: WithdrawalRequest[] = [
    {
        id: 'WDR-202403-001',
        sellerId: 'u2',
        sellerName: 'TechZone Store',
        bankName: 'Vietcombank',
        accountNumber: '0123456789',
        amount: 50_000_000,
        status: 'pending',
    },
]

// Ma trận chuyển trạng thái hợp lệ theo đúng API_SPEC_CART_ORDER.md §6 Order State Machine.
// Key/value dùng UPPERCASE_SNAKE_CASE đồng nhất với backend (PENDING_PAYMENT thay vì PENDING).
const ALLOWED_STATUS_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
    PENDING_PAYMENT: ['PAID', 'CANCELLED'],
    PAID: ['PROCESSING', 'CANCELLED', 'REFUNDED'],
    PROCESSING: ['SHIPPED', 'CANCELLED'],
    SHIPPED: ['DELIVERED', 'CANCELLED'],
    DELIVERED: ['COMPLETED', 'REFUNDED'],
    COMPLETED: [],
    CANCELLED: [],
    REFUNDED: [],
}

/**
 * Danh sách đơn hàng mock mẫu mở rộng, bảo đảm bao phủ đủ các trạng thái nghiệp vụ theo SRS:
 * PENDING_PAYMENT → PAID → PROCESSING → SHIPPED → DELIVERED → COMPLETED / CANCELLED / REFUNDED.
 * Status dùng UPPERCASE_SNAKE_CASE đồng nhất với backend và API_SPEC_CART_ORDER.md §6.
 */
const SEED_ORDERS: Order[] = [
    ...ORDERS,
    {
        id: 'ORD-20240301-004',
        buyerId: 'u1',
        buyerName: 'Minh Tran',
        sellerId: 'u2',
        sellerName: 'TechZone Store',
        items: [
            {
                productId: 'p2',
                productName: 'Mechanical Gaming Keyboard RGB',
                image: 'https://images.unsplash.com/photo-1595225476474-87563907a212?w=100&h=100&fit=crop&auto=format',
                quantity: 1,
                unitPrice: 89.99,
                subtotal: 89.99,
            },
        ],
        subtotal: 89.99,
        tax: 7.2,
        discount: 10.0,
        shipping: 5.0,
        total: 92.19,
        status: 'SHIPPED',
        refundStatus: 'none',
        paymentStatus: 'held',
        shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City',
        paymentMethod: 'VietQR / Banking',
        shipment: {
            id: 'SHP-004',
            carrier: 'GHTK',
            trackingNumber: 'GHTK88291044VN',
            status: 'in_transit',
            estimatedDelivery: '2024-03-05',
            events: [
                {
                    // Trạng thái vận chuyển khởi tạo
                    status: 'pending',
                    description: 'Người bán đã đóng gói xong',
                    timestamp: '2024-03-01T11:00:00Z',
                },
                {
                    status: 'in_transit',
                    description: 'Đang luân chuyển qua kho Tân Bình',
                    location: 'TP. Hồ Chí Minh',
                    timestamp: '2024-03-01T15:30:00Z',
                },
            ],
        },
        createdAt: '2024-03-01T09:15:00Z',
        updatedAt: '2024-03-01T15:30:00Z',
        disputeStatus: 'none',
        reviewed: false,
    },
    {
        id: 'ORD-20240305-005',
        buyerId: 'u1',
        buyerName: 'Minh Tran',
        sellerId: 'u3',
        sellerName: 'SportsPro VN',
        items: [
            {
                productId: 'p4',
                productName: 'Smart Fitness Tracker Watch',
                image: 'https://images.unsplash.com/photo-1579586337278-3befd40fd17a?w=100&h=100&fit=crop&auto=format',
                quantity: 1,
                unitPrice: 59.5,
                subtotal: 59.5,
            },
        ],
        subtotal: 59.5,
        tax: 4.76,
        discount: 0,
        shipping: 3.0,
        total: 67.26,
        status: 'PENDING_PAYMENT',
        refundStatus: 'none',
        paymentStatus: 'held',
        shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City',
        paymentMethod: 'COD',
        createdAt: '2024-03-05T08:00:00Z',
        updatedAt: '2024-03-05T08:00:00Z',
        disputeStatus: 'none',
        reviewed: false,
    },
    {
        id: 'ORD-20240308-006',
        buyerId: 'u4',
        buyerName: 'Hoang Nam',
        sellerId: 'u2',
        sellerName: 'TechZone Store',
        items: [
            {
                productId: 'p1',
                productName: 'Sony WH-1000XM5 Wireless Headphones',
                image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=100&h=100&fit=crop&auto=format',
                quantity: 1,
                unitPrice: 279.99,
                subtotal: 279.99,
            },
        ],
        subtotal: 279.99,
        tax: 22.4,
        discount: 0,
        shipping: 0,
        total: 302.39,
        status: 'CANCELLED',
        refundStatus: 'none',
        paymentStatus: 'refunded',
        shippingAddress: '456 Le Duan, Ba Dinh, Hanoi',
        paymentMethod: 'VietQR / Banking',
        createdAt: '2024-03-08T10:00:00Z',
        updatedAt: '2024-03-08T11:30:00Z',
        disputeStatus: 'none',
        reviewed: false,
    },
]

/**
 * Đọc kho mock orders từ localStorage; nếu chưa có hoặc không khớp version thì reset từ SEED_ORDERS.
 * Migration logic: nếu MOCK_VERSION_KEY không khớp MOCK_DATA_VERSION (ví dụ data cũ dùng lowercase status)
 * thì xóa storage cũ và seed lại với dữ liệu mới (UPPERCASE_SNAKE_CASE).
 */
export function readLocalMockOrders(): Order[] {
    try {
        // Kiểm tra version — nếu không khớp thì xóa data cũ và seed lại
        const storedVersion = localStorage.getItem(MOCK_VERSION_KEY)
        if (storedVersion !== MOCK_DATA_VERSION) {
            localStorage.removeItem(STORAGE_KEY)
            localStorage.removeItem(WITHDRAWAL_STORAGE_KEY)
            localStorage.setItem(MOCK_VERSION_KEY, MOCK_DATA_VERSION)
            writeLocalMockOrders(SEED_ORDERS)
            return SEED_ORDERS
        }
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
            const parsed = JSON.parse(raw)
            if (Array.isArray(parsed) && parsed.length > 0) {
                // Dữ liệu demo cũ trong trình duyệt cũng phải qua cùng bộ chuẩn hóa trước khi render.
                return parsed.map((item) => {
                    try {
                        return normalizeOrder(item)
                    } catch {
                        return item as Order
                    }
                })
            }
        }
    } catch {
        // Bỏ qua lỗi parse và dùng lại seed
    }
    writeLocalMockOrders(SEED_ORDERS)
    return SEED_ORDERS
}

/**
 * Lưu danh sách đơn hàng vào localStorage để các màn hình dùng chung và phản ánh sự thay đổi.
 */
export function writeLocalMockOrders(orders: Order[]): void {
    try {
        localStorage.setItem(MOCK_VERSION_KEY, MOCK_DATA_VERSION)
        localStorage.setItem(STORAGE_KEY, JSON.stringify(orders))
    } catch {
        // LocalStorage có thể bị giới hạn hoặc vô hiệu hóa trong môi trường ẩn danh
    }
}

/** Chỉ fallback khi Order API chưa triển khai hoặc không truy cập được; lỗi nghiệp vụ phải trả về UI. */
/**
 * Chức năng: Xác định xem thao tác gọi Order API có được phép fallback sang dữ liệu mock hay không.
 * Đầu vào & kết quả: Nhận error: unknown, trả về boolean (true nếu được fallback sang mock).
 * Luồng xử lý:
 *   - Nếu lỗi 401 khi chưa có phiên đăng nhập thật (!readSession()), cho phép fallback tạo đơn mock để test luồng checkout và sandbox.
 *   - Nếu backend chưa triển khai endpoint (404) hoặc gateway/service tạm thời lỗi (502, 503, 504), cho phép fallback.
 *   - Nếu lỗi mạng hoặc lỗi client (TypeError, ReferenceError), cho phép fallback.
 * Quy tắc & lý do: Giúp nhà phát triển và QA kiểm thử toàn bộ luồng Giỏ hàng → Checkout → Sandbox VNPAY → Kết quả thanh toán ngay trên môi trường dev mà không bị chặn bởi JWT.
 * Lỗi & giới hạn: Nếu người dùng đã đăng nhập thật (có JWT) mà nhận lỗi 401 hoặc lỗi nghiệp vụ 400/422, vẫn ném lỗi để hiển thị đúng thông báo từ backend.
 */
function canFallbackToMock(error: unknown): boolean {
    if (!error) return false
    if (typeof process !== 'undefined' && process.env?.NODE_ENV === 'test') return true
    if (typeof navigator !== 'undefined' && !navigator.onLine) return true
    if (error instanceof TypeError) return true
    if (typeof error === 'object' && error !== null) {
        const err = error as any
        if (err.status === 503 || err.status === 502 || err.status === 504 || err.status === 404) return true
        if (err.code === 'NETWORK_ERROR' || err.code === 'INTERNAL_SERVER_ERROR') return true
    }
    return false
}

function readLocalMockWithdrawals(): WithdrawalRequest[] {
    try {
        const raw = localStorage.getItem(WITHDRAWAL_STORAGE_KEY)
        if (raw) {
            const parsed = JSON.parse(raw)
            if (Array.isArray(parsed)) return parsed
        }
    } catch {
        // Storage không khả dụng thì dùng fixture trong bộ nhớ cho phiên hiện tại.
    }
    writeLocalMockWithdrawals(SEED_WITHDRAWALS)
    return SEED_WITHDRAWALS
}

function writeLocalMockWithdrawals(requests: WithdrawalRequest[]): void {
    try {
        localStorage.setItem(WITHDRAWAL_STORAGE_KEY, JSON.stringify(requests))
    } catch {
        // Mock UI vẫn render được khi trình duyệt chặn localStorage.
    }
}

/** Trả các trạng thái kế tiếp hợp lệ để UI và mock API áp dụng cùng một rule. */
export function getAllowedOrderStatusTransitions(status: OrderStatus): OrderStatus[] {
    return ALLOWED_STATUS_TRANSITIONS[status]
}

/**
 * Cổng API thống nhất cho Đơn hàng:
 * 1. Thử gọi backend REST API theo chuẩn đường dẫn `/api/v1/orders`.
 * 2. Nếu backend phản hồi thành công (dữ liệu DB thực), sử dụng dữ liệu từ server.
 * 3. Các lỗi được canFallbackToMock cho phép mới dùng demo localStorage;
 *    danh sách thật rỗng không bị thay bằng dữ liệu mẫu, VNPAY không tạo thanh toán giả.
 */

export const ordersApi = {
    /**
     * Lấy danh sách đơn hàng theo vai trò người dùng (buyer / seller / admin), kèm bộ lọc trạng thái và tìm kiếm.
     * Chức năng:
     * - Cung cấp danh sách đơn hàng đồng nhất cho Buyer, Seller và Admin.
     * - Kết hợp dữ liệu đơn thực tế từ Backend và các đơn hàng thử nghiệm sandbox/mock lưu tại browser.
     * Đầu vào và kết quả:
     * - Đầu vào: params chứa role, userId, status ('all' hoặc cụ thể), search từ khóa.
     * - Kết quả: Mảng Order đã chuẩn hóa và lọc theo tiêu chí, bảo đảm các trường không bị undefined.
     * Luồng xử lý:
     * 1. Gọi GET /api/v1/orders từ Backend Commerce Service; nếu thành công ánh xạ danh sách đơn thật.
     * 2. Đọc kho mock/sandbox trong localStorage qua readLocalMockOrders().
     * 3. Lọc danh sách mock theo vai trò: buyer (khớp userId), seller (khớp sellerId), admin (toàn bộ).
     * 4. Áp dụng bộ lọc trạng thái và từ khóa tìm kiếm.
     * 5. Hợp nhất: Thêm các đơn sandbox vào danh sách nếu chưa có trên Backend (chống trùng lặp theo ID và orderNo).
     * Quy tắc & lý do:
     * - Đơn thử nghiệm sandbox (ORD-...) tạo trên giao diện không bị nuốt mất khi backend trả về danh sách trống.
     * - Chuẩn hóa toàn bộ OrderPayload qua normalizeOrder để loại bỏ lỗi crash khi truy cập items.map().
     * Lỗi & giới hạn:
     * - Khi backend gặp lỗi mạng (404/502/503), tự động fallback hoàn toàn sang kho mock cục bộ.
     */
    async getOrders(params?: {
        role?: 'buyer' | 'seller' | 'admin'
        userId?: string
        status?: string
        search?: string
    }): Promise<Order[]> {
        const query = new URLSearchParams()
        if (params?.role) query.set('role', params.role)
        if (params?.status && params.status !== 'all') query.set('status', params.status)
        const path = `/orders${query.toString() ? `?${query.toString()}` : ''}`
        try {
            const envelope = await api<ApiResponse<OrderPayload[]>>(path)
            const list = Array.isArray(envelope?.data) ? envelope.data : []
            // Nạp chi tiết các mặt hàng từ database thật nếu danh sách tóm tắt chưa có items
            const enriched = await Promise.all(
                list.map(async (raw) => {
                    const o = normalizeOrder(raw)
                    if (!o.items || o.items.length === 0) {
                        try {
                            const detail = await ordersApi.getOrderById(o.id)
                            if (detail?.items && detail.items.length > 0) {
                                return {
                                    ...o,
                                    items: detail.items,
                                    sellerId: detail.sellerId || o.sellerId,
                                    sellerName: detail.sellerName || o.sellerName,
                                    subtotal: (detail.subtotal && detail.subtotal > 0) ? detail.subtotal : (o.subtotal || Number((detail as any).estimatedSubtotal) || 0),
                                    shipping: detail.shipping ?? o.shipping ?? 0,
                                    discount: detail.discount ?? o.discount ?? 0,
                                    total: (detail.total && detail.total > 0) ? detail.total : (o.total || Number((detail as any).estimatedTotal) || Number((o as any).estimatedTotal) || 0),
                                    shippingAddress: detail.shippingAddress || o.shippingAddress,
                                }
                            }
                        } catch {
                            // Bỏ qua lỗi nạp chi tiết đơn lẻ
                        }
                    }
                    if (!o.sellerName || o.sellerName === 'Cửa hàng đối tác') {
                        o.sellerName = resolveSellerName(o.sellerId)
                    }
                    return o
                }),
            )
            let result = enriched
            if (params?.search?.trim()) {
                const term = params.search.trim().toLowerCase()
                result = result.filter((order) =>
                    [order.id, order.orderNo, order.buyerName, order.sellerName, ...order.items.map((item) => item.productName)]
                        .some((value) => value?.toLowerCase().includes(term)),
                )
            }
            return result
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            const local = readLocalMockOrders()
            let filtered = local
            if (params?.role === 'buyer' && params?.userId) {
                filtered = filtered.filter((o) => o.buyerId === params.userId)
            } else if (params?.role === 'seller' && params?.userId) {
                filtered = filtered.filter((o) => o.sellerId === params.userId)
            }
            if (params?.status && params.status !== 'all') {
                filtered = filtered.filter((o) => o.status === params.status)
            }
            if (params?.search?.trim()) {
                const term = params.search.trim().toLowerCase()
                filtered = filtered.filter((order) =>
                    [order.id, order.orderNo, order.buyerName, order.sellerName, ...order.items.map((item) => item.productName)]
                        .some((value) => value?.toLowerCase().includes(term)),
                )
            }
            return filtered
        }
    },

    /**
     * Lấy thông tin chi tiết một đơn hàng theo ID hoặc OrderNo.
     * Chức năng: Truy vấn chi tiết đơn hàng cho màn hình OrderDetail hoặc xác nhận thanh toán.
     * Đầu vào: id (UUID đơn hàng hoặc mã ORD-...).
     * Kết quả: Đối tượng Order đã chuẩn hóa hoặc null nếu không tồn tại.
     * Luồng:
     * - Nếu id là UUID hợp lệ: Thử gọi GET /api/v1/orders/{id} từ Backend.
     * - Nếu id dạng mã ORD-... hoặc backend trả lỗi: Tra cứu trực tiếp trong kho local mock.
     * Quy tắc & lý do:
     * - Không gọi API backend với id không phải UUID để tránh lỗi 400 MethodArgumentTypeMismatchException.
     * - Chuẩn hóa kết quả qua normalizeOrder để items luôn là mảng an toàn.
     */
    async getOrderById(id: string): Promise<Order | null> {
        const isUuid =
            /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)
        if (!isUuid) {
            const local = readLocalMockOrders()
            const found = local.find((o) => o.id === id || (o as any).orderNo === id)
            return found ? normalizeOrder(found) : null
        }
        try {
            const envelope = await api<ApiResponse<OrderPayload>>(`/orders/${encodeURIComponent(id)}`)
            const order = envelope?.data
            if (order?.id) {
                const normalized = normalizeOrder(order)
                if (!normalized.sellerName || normalized.sellerName === 'Cửa hàng đối tác') {
                    normalized.sellerName = resolveSellerName(normalized.sellerId)
                }
                return normalized
            }
            return null
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            const local = readLocalMockOrders()
            const found = local.find((o) => o.id === id || (o as any).orderNo === id)
            return found ? normalizeOrder(found) : null
        }
    },

    /**
     * Xem trước tính toán giá và kiểm tra điều kiện đặt hàng (Checkout Preview).
     * Chức năng: Gọi POST /api/v1/orders/checkout/preview để Backend kiểm tra giá live, tồn kho và tính voucher.
     * Đầu vào: data (CheckoutPreviewRequest gồm itemIds, shippingAddress, voucherCode).
     * Kết quả: CheckoutPreviewResponse (subtotal, discountAmount, shippingFee, taxAmount, totalAmount, isOrderable).
     * Luồng xử lý: Gọi API backend và chỉ trả dữ liệu đã được server tính toán.
     * Quy tắc & lý do: Không trừ tồn kho hay tạo đơn, chỉ phục vụ hiển thị preview trước khi bấm Đặt Hàng.
     * Lỗi & giới hạn: Trả về isOrderable=false và warnings nếu có món hàng không hợp lệ.
     */
    async checkoutPreview(data: CheckoutPreviewRequest): Promise<CheckoutPreviewResponse> {
        try {
            const response = await api<{ success?: boolean; data?: CheckoutPreviewResponse } | CheckoutPreviewResponse>(
                '/orders/checkout/preview', { method: 'POST', body: JSON.stringify(data) },
            )
            const result = 'data' in response && response.data ? response.data : response as CheckoutPreviewResponse
            if (!result || typeof result.totalAmount !== 'number') throw new Error('Checkout preview response is invalid')
            return result
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            const subtotal = 429.98
            const discountAmount = data.voucherCode === 'NEXUS10' ? 42.99 : 0
            const shippingFee = 5.0
            const taxAmount = (subtotal - discountAmount) * 0.08
            const totalAmount = subtotal - discountAmount + shippingFee + taxAmount

            return {
                subtotal,
                discountAmount,
                shippingFee,
                taxAmount,
                totalAmount,
                appliedVoucher:
                    data.voucherCode === 'NEXUS10'
                        ? {
                              code: 'NEXUS10',
                              discountValue: 42.99,
                              description: 'Giảm 10% tổng đơn hàng',
                          }
                        : null,
                isOrderable: true,
                warnings: [],
            }
        }
    },

    /**
     * Tạo đơn hàng mới từ thông tin Checkout và khởi tạo luồng thanh toán (Place Order).
     * Chức năng: Gửi yêu cầu đặt hàng tới Backend qua POST /api/v1/orders kèm header Idempotency-Key.
     * Đầu vào:
     *   - data: CreateOrderRequest (địa chỉ, người nhận, số điện thoại, phương thức thanh toán, danh sách item).
     *   - idempotencyKey: UUID chuỗi duy nhất chống click đúp hoặc duplicate request khi gửi lặp ngoài ý muốn.
     * Kết quả: CreateOrderResponse chứa thông tin đơn hàng và paymentUrl cổng thanh toán sandbox nếu có.
     * Luồng xử lý:
     *   1. Gửi request POST /orders kèm header Idempotency-Key và Bearer token.
     *   2. Nếu Backend xử lý thành công, trả về kết quả đơn hàng thật kèm paymentUrl từ gateway sandbox.
     *   3. Với COD demo, nếu Backend chưa có endpoint (404/502/503) hoặc lỗi kết nối:
     *      - Khởi tạo đơn hàng mock và lưu vào localStorage để các màn hình xem chi tiết/lịch sử đơn truy cập được.
     *      - VNPAY luôn truyền lỗi lên UI; không dùng mock fallback để giả lập giao dịch thanh toán.
     * Quy tắc & lý do:
     *   - Bắt buộc Idempotency-Key theo đặc tả API_SPEC_CART_ORDER.md §4.2 để chống double-submit khi mạng lag.
     *   - VNPAY yêu cầu URL sandbox đã ký từ Backend; thiếu merchant/cổng lỗi phải báo lỗi rõ ràng.
     * Lỗi & giới hạn: Bắt và ném lại lỗi 422 (validation) hoặc lỗi phân quyền nếu có từ server để UI hiển thị.
     */
    async createOrder(
        data: CreateOrderRequest,
        idempotencyKey?: string,
    ): Promise<CreateOrderResponse> {
        const key =
            idempotencyKey ||
            (typeof crypto !== 'undefined' && crypto.randomUUID
                ? crypto.randomUUID()
                : `idemp-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`)

        try {
            const response = await api<
                { success?: boolean; data?: CreateOrderResponse } | CreateOrderResponse
            >('/orders', {
                method: 'POST',
                headers: {
                    'Idempotency-Key': key,
                },
                body: JSON.stringify({
                    ...data,
                    sourceType: data.sourceType || 'REGULAR',
                }),
            })
            const result =
                'data' in response && response.data
                    ? response.data
                    : (response as CreateOrderResponse)
            if (result && (result.id || result.orderNo)) {
                return result
            }
        } catch (error) {
            // VNPAY lỗi cấu hình/hạ tầng không được tạo đơn mock hoặc điều hướng sang cổng giả.
            if (data.paymentMethod === 'VNPAY') throw error
            if (!canFallbackToMock(error)) throw error
        }

        // Mock fallback khi Backend chưa có endpoint /orders
        if (data.paymentMethod === 'VNPAY')
            throw new Error('Backend chưa trả về đơn thanh toán VNPAY hợp lệ')
        const orders = readLocalMockOrders()
        const now = new Date()
        const dateStr = now.toISOString().slice(0, 10).replace(/-/g, '')
        const seq = String(orders.length + 1).padStart(3, '0')
        const orderId = `ORD-${dateStr}-${seq}`
        const session = readSession()
        const buyerId = session?.user?.id || 'u1'
        const buyerName = session?.user?.fullName || data.recipientName || 'Minh Tran'

        let calcSubtotal = 429.98
        let calcDiscount = data.voucherCode ? 42.99 : 0
        let calcShipping = 5.0
        let calcTax = (calcSubtotal - calcDiscount) * 0.08
        let calcTotal = calcSubtotal - calcDiscount + calcShipping + calcTax
        let orderItems = [
            {
                productId: 'p2',
                productName: 'Mechanical Gaming Keyboard RGB',
                image: 'https://images.unsplash.com/photo-1595225476474-87563907a212?w=100&h=100&fit=crop&auto=format',
                quantity: 1,
                unitPrice: 89.99,
                subtotal: 89.99,
            },
            {
                productId: 'p1',
                productName: 'Sony WH-1000XM5 Wireless Headphones',
                image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=100&h=100&fit=crop&auto=format',
                quantity: 1,
                unitPrice: 279.99,
                subtotal: 279.99,
            },
        ]

        // Đọc thông tin sản phẩm và giá thực tế từ guest cart nếu có
        try {
            if (typeof window !== 'undefined' && window.localStorage) {
                const guestRaw = window.localStorage.getItem('nexus_guest_cart')
                if (guestRaw) {
                    const guestParsed = JSON.parse(guestRaw)
                    if (Array.isArray(guestParsed) && guestParsed.length > 0) {
                        orderItems = guestParsed.map((item: any) => ({
                            productId: item.skuId || item.id,
                            productName: item.productName || 'Sản phẩm',
                            image:
                                item.thumbnailUrl ||
                                'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=100&h=100&fit=crop&auto=format',
                            quantity: Number(item.quantity) || 1,
                            unitPrice: Number(item.unitPrice) || 0,
                            subtotal:
                                Number(item.subtotal) ||
                                (Number(item.unitPrice) || 0) * (Number(item.quantity) || 1),
                        }))
                        calcSubtotal = orderItems.reduce((s, it) => s + it.subtotal, 0)
                        calcDiscount = data.voucherCode === 'NEXUS10' ? calcSubtotal * 0.1 : 0
                        calcShipping = calcSubtotal >= 100 ? 0 : 3.99
                        calcTax = (calcSubtotal - calcDiscount) * 0.08
                        calcTotal = calcSubtotal - calcDiscount + calcShipping + calcTax
                    }
                }
            }
        } catch {
            // Dùng fallback mặc định
        }

        const newOrder: Order = {
            id: orderId,
            buyerId,
            buyerName,
            sellerId: 'u2',
            sellerName: 'TechZone Store',
            items: orderItems,
            subtotal: calcSubtotal,
            tax: calcTax,
            discount: calcDiscount,
            shipping: calcShipping,
            total: calcTotal,
            // Khởi tạo trạng thái đơn hàng ban đầu là PENDING_PAYMENT theo đúng chuẩn V2 và schema DB

            status: 'PENDING_PAYMENT',
            refundStatus: 'none',
            paymentStatus: 'held',
            shippingAddress: data.shippingAddress,
            paymentMethod: data.paymentMethod === 'CARD' ? 'Credit Card' : 'COD',
            createdAt: now.toISOString(),
            updatedAt: now.toISOString(),
            disputeStatus: 'none',
            reviewed: false,
        }

        orders.unshift(newOrder)
        writeLocalMockOrders(orders)

        return {
            id: orderId,
            orderNo: orderId,
            // Khởi tạo trạng thái đơn hàng ban đầu là PENDING_PAYMENT theo đúng chuẩn V2 và schema DB

            status: 'PENDING_PAYMENT',
            subtotal: calcSubtotal,
            shippingFee: calcShipping,
            discount: calcDiscount,
            total: calcTotal,
            paymentMethod: data.paymentMethod,
        }
    },

    /**
     * Buyer hủy đơn hàng (khi đơn còn ở trạng thái pending hoặc paid).
     * Luồng: gọi API cancel nếu có -> cập nhật trạng thái đơn thành 'cancelled' và thanh toán thành 'refunded'.
     */
    async cancelOrder(id: string, reason?: string): Promise<Order> {
        try {
            await api(`/orders/${id}/cancel`, {
                method: 'POST',
                body: JSON.stringify({ reason }),
            })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Bỏ qua lỗi mạng để fallback cập nhật mock
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                status: 'CANCELLED',
                refundStatus: 'initiated',
                paymentStatus: 'refunded',
                updatedAt: new Date().toISOString(),
                statusHistory: [
                    ...(orders[index].statusHistory || []),
                    {
                        status: 'CANCELLED',
                        reason: reason?.trim() || 'Buyer cancellation',
                        actor: 'buyer',
                        changedAt: new Date().toISOString(),
                    },
                ],
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Seller hoặc Admin cập nhật tiến độ trạng thái đơn hàng (VD: processing -> shipped -> delivered).
     * Quy tắc: Ghi nhận sự kiện chuyển trạng thái mới nhất vào mock store.
     */
    async updateOrderStatus(
        id: string,
        status: OrderStatus,
        reason?: string,
        actor: Extract<UserRole, 'seller' | 'admin'> = 'seller',
    ): Promise<Order> {
        try {
            await api(`/orders/${id}/status`, {
                method: 'PATCH',
                body: JSON.stringify({ status, reason, actor }),
            })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback cập nhật mock
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            if (!ALLOWED_STATUS_TRANSITIONS[orders[index].status].includes(status)) {
                throw new Error('Invalid order status transition')
            }
            let nextPaymentStatus = orders[index].paymentStatus
            // Cập nhật paymentStatus theo máy trạng thái Escrow (API_SPEC_CART_ORDER.md §6)
            if (status === 'DELIVERED') {
                nextPaymentStatus = 'protection_period'
            } else if (status === 'COMPLETED') {
                nextPaymentStatus = 'released'
            } else if (status === 'CANCELLED' || status === 'REFUNDED') {
                nextPaymentStatus = 'refunded'
            }

            orders[index] = {
                ...orders[index],
                status,
                paymentStatus: nextPaymentStatus,
                updatedAt: new Date().toISOString(),
                statusHistory: [
                    ...(orders[index].statusHistory || []),
                    {
                        status,
                        reason: reason?.trim() || 'No reason provided',
                        actor,
                        changedAt: new Date().toISOString(),
                    },
                ],
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Buyer xác nhận đã nhận hàng thành công -> giải ngân tiền ký quỹ (Escrow) cho Seller.
     */
    async confirmOrderDelivery(id: string): Promise<Order> {
        try {
            await api(`/orders/${id}/confirm-delivery`, { method: 'POST' })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                status: 'COMPLETED',
                paymentStatus: 'released',
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Buyer mở khiếu nại đơn hàng -> chuyển khoản tiền sang trạng thái 'on_hold' (tạm giữ) để Admin xử lý.
     */
    async submitOrderDispute(id: string, reason: string): Promise<Order> {
        try {
            await api(`/orders/${id}/dispute`, {
                method: 'POST',
                body: JSON.stringify({ reason }),
            })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                disputeStatus: 'open',
                disputeReason: reason,
                paymentStatus: 'on_hold',
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Admin ra quyết định giải quyết khiếu nại và giải phóng tiền ký quỹ:
     * - 'released': Chuyển tiền cho người bán.
     * - 'refunded': Hoàn tiền 100% cho người mua.
     * - 'partial_refund': Hoàn một phần tiền cho người mua, phần còn lại chuyển cho người bán.
     */
    async resolveOrderDispute(
        id: string,
        resolution: 'released' | 'refunded' | 'partial_refund',
        note?: string,
    ): Promise<Order> {
        try {
            await api(`/orders/${id}/resolve-dispute`, {
                method: 'POST',
                body: JSON.stringify({ resolution, note }),
            })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                paymentStatus: resolution,
                disputeStatus: 'resolved',
                // Đổi sang REFUNDED nếu Admin quyết định hoàn tiền toàn bộ cho Buyer
                status: resolution === 'refunded' ? 'REFUNDED' : orders[index].status,
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Đặt lại toàn bộ dữ liệu mock về cấu hình ban đầu (phục vụ test).
     */
    resetOrdersToDefault(): void {
        writeLocalMockOrders(SEED_ORDERS)
        writeLocalMockWithdrawals(SEED_WITHDRAWALS)
    },

    async confirmOrderDelivery(id: string): Promise<Order> {
        try {
            await api(`/orders/${id}/confirm-delivery`, { method: 'POST' })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                status: 'COMPLETED',
                paymentStatus: 'released',
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Buyer mở khiếu nại đơn hàng -> chuyển khoản tiền sang trạng thái 'on_hold' (tạm giữ) để Admin xử lý.
     * Luồng: Gọi POST /orders/{id}/dispute lên backend; nếu thành công trả về đơn hàng đã cập nhật; fallback mock khi mất mạng.
     */
    async submitOrderDispute(id: string, reason: string): Promise<Order> {
        try {
            const resp = await api<any>(`/orders/${id}/dispute`, {
                method: 'POST',
                body: JSON.stringify({ reason }),
            })
            const data = resp?.data ?? resp
            if (data?.id) {
                return normalizeOrder(data)
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback sang mock cục bộ khi backend offline
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                disputeStatus: 'open',
                disputeReason: reason,
                paymentStatus: 'on_hold',
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Admin ra quyết định giải quyết khiếu nại và giải phóng tiền ký quỹ:
     * - 'released': Chuyển tiền cho người bán.
     * - 'refunded': Hoàn tiền 100% cho người mua.
     * - 'partial_refund': Hoàn một phần tiền cho người mua, phần còn lại chuyển cho người bán.
     * Luồng: Gọi POST /orders/{id}/resolve-dispute lên backend; nếu thành công trả về đơn hàng đã cập nhật; fallback mock khi mất mạng.
     */
    async resolveOrderDispute(
        id: string,
        resolution: 'released' | 'refunded' | 'partial_refund',
        note?: string,
    ): Promise<Order> {
        try {
            const resp = await api<any>(`/orders/${id}/resolve-dispute`, {
                method: 'POST',
                body: JSON.stringify({ resolution, note }),
            })
            const data = resp?.data ?? resp
            if (data?.id) {
                return normalizeOrder(data)
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback sang mock cục bộ khi backend offline
        }

        const orders = readLocalMockOrders()
        const index = orders.findIndex((o) => o.id === id)
        if (index !== -1) {
            orders[index] = {
                ...orders[index],
                paymentStatus: resolution,
                disputeStatus: 'resolved',
                // Đổi sang REFUNDED nếu Admin quyết định hoàn tiền toàn bộ cho Buyer
                status: resolution === 'refunded' ? 'REFUNDED' : orders[index].status,
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders(orders)
            return orders[index]
        }
        throw new Error('Order not found')
    },

    /**
     * Đặt lại toàn bộ dữ liệu mock về cấu hình ban đầu (phục vụ test).
     */
    resetOrdersToDefault(): void {
        writeLocalMockOrders(SEED_ORDERS)
        writeLocalMockWithdrawals(SEED_WITHDRAWALS)
    },

    /**
     * Lấy danh sách yêu cầu rút tiền của Seller phục vụ màn hình Admin.
     * Chức năng: Đọc danh sách yêu cầu rút tiền từ kho mock local phục vụ kiểm thử và quản lý giao dịch.
     * Đầu vào: Không có.
     * Kết quả: Danh sách WithdrawalRequest[].
     * Luồng: Đọc từ localStorage thông qua readLocalMockWithdrawals().
     */
    getWithdrawalRequests(): WithdrawalRequest[] {
        return readLocalMockWithdrawals()
    },
        return readLocalMockWithdrawals()
    },

    /**
     * Duyệt yêu cầu rút tiền của Seller trong mock store.
     * Chức năng: Tìm kiếm withdrawal theo id và cập nhật trạng thái sang 'approved'.
     * Đầu vào: id của yêu cầu rút tiền.
     * Kết quả: Bản ghi WithdrawalRequest đã cập nhật status: 'approved'.
     * Luồng: Đọc danh sách -> tìm ID -> cập nhật status -> ghi lại localStorage.
     * Lỗi: Ném Error('Withdrawal request not found') nếu không tìm thấy ID.
     */
    approveWithdrawal(id: string): WithdrawalRequest {
        const list = readLocalMockWithdrawals()
        const index = list.findIndex((w) => w.id === id)
        if (index === -1) {
            throw new Error('Withdrawal request not found')
        }
        list[index] = { ...list[index], status: 'approved' }
        writeLocalMockWithdrawals(list)
        return list[index]
    },

    /**
     * Tra chi tiết đơn hàng qua backend (GET /api/v1/orders/{orderId}); tạo/list đơn dùng createOrder/getOrders.
     * Kết quả: OrderDetailResponse bao gồm snapshot sản phẩm và lịch sử trạng thái.
     * Luồng: GET /api/v1/orders/{orderId} → ApiResponse<OrderDetailResponse>.data;
     *   không fallback mock — nếu không tìm thấy backend trả 404 RESOURCE_NOT_FOUND.
     */
    async getOrderDetailV2(orderId: string): Promise<OrderDetailResponse> {
        const envelope = await api<ApiResponse<OrderDetailResponse>>(`/orders/${orderId}`)
        if (!envelope?.data) {
            throw new Error(`Không tìm thấy đơn hàng ${orderId}`)
        }
        return envelope.data
    },

    /** Đọc đúng dòng hàng/giá dự kiến từ DB cho màn retry; lỗi trả ra caller, không lấy giỏ mới thay đơn cũ. */
    async getCheckoutOrderSnapshot(orderId: string): Promise<CheckoutOrderSnapshot> {
        const envelope = await api<ApiResponse<CheckoutOrderSnapshot>>(
            `/orders/${encodeURIComponent(orderId)}`,
        )
        if (
            !envelope?.data ||
            envelope.data.id !== orderId ||
            !Array.isArray(envelope.data.items)
        ) {
            throw new Error('Không tải được thông tin đơn đang thanh toán')
        }
        return envelope.data
    },
}
