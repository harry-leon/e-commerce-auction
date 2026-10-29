import { api, ApiClientError, readSession } from './client'
import type { Order, OrderStatus, PaymentStatus, UserRole } from '../types'
import { ORDERS } from '../mockData'

/**
 * Khóa lưu trữ dữ liệu mock đơn hàng trong localStorage để các thao tác chuyển trạng thái
 * (Buyer hủy, Seller cập nhật, Admin xử lý khiếu nại) có thể lưu vết bền vững khi dev/test.
 */
const STORAGE_KEY = 'nexus.mock-orders-store'
const WITHDRAWAL_STORAGE_KEY = 'nexus.mock-withdrawals-store'

/**
 * DTO yêu cầu xem trước tính giá và re-validate đơn hàng khi Checkout.
 * Khớp với đặc tả API_SPEC_CART_ORDER.md §4.1.
 */
export interface CheckoutPreviewRequest {
    itemIds?: string[]
    shippingAddress: string
    voucherCode?: string
}

/**
 * DTO kết quả xem trước đơn hàng từ Backend.
 */
export interface CheckoutPreviewResponse {
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
    shippingAddress: string
    recipientName: string
    recipientPhone: string
    voucherCode?: string
    paymentMethod: 'CARD' | 'VNPAY' | 'COD'
    sourceType?: 'REGULAR' | 'AUCTION'
    auctionId?: string | null
    selectedItemIds?: string[]
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
}

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

const ALLOWED_STATUS_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
    pending: ['paid', 'cancelled'],
    paid: ['processing', 'cancelled'],
    processing: ['shipped', 'cancelled'],
    shipped: ['delivered'],
    delivered: ['completed'],
    completed: [],
    cancelled: [],
    refunded: [],
}

/**
 * Danh sách đơn hàng mock mẫu mở rộng, bảo đảm bao phủ đủ các trạng thái nghiệp vụ theo SRS:
 * pending -> paid -> processing -> shipped -> delivered -> completed / cancelled / refunded.
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
        status: 'shipped',
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
        status: 'pending',
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
        status: 'cancelled',
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
 * Đọc kho mock orders từ localStorage; nếu chưa có thì khởi tạo từ SEED_ORDERS.
 * Luồng: đọc storage → parse JSON → nếu lỗi hoặc rỗng thì ghi seed orders và trả về.
 */
function readLocalMockOrders(): Order[] {
    try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
            const parsed = JSON.parse(raw)
            if (Array.isArray(parsed) && parsed.length > 0) {
                return parsed
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
function writeLocalMockOrders(orders: Order[]): void {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(orders))
    } catch {
        // LocalStorage có thể bị giới hạn hoặc vô hiệu hóa trong môi trường ẩn danh
    }
}

/** Chỉ fallback khi Order API chưa triển khai hoặc không truy cập được; lỗi nghiệp vụ phải trả về UI. */
function canFallbackToMock(error: unknown): boolean {
    if (error instanceof ApiClientError) {
        return [404, 502, 503, 504].includes(error.status)
    }
    return error instanceof TypeError || error instanceof ReferenceError
}

/** Kho withdrawal dùng chung localStorage để trạng thái duyệt không chỉ tồn tại trong component Admin. */
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
 * 3. Nếu backend trả lỗi (404, 503, không kết nối được) hoặc DB chưa có dữ liệu:
 *    tự động fallback sang Mock JSON data, đồng thời duy trì trạng thái cập nhật qua localStorage.
 */
export const ordersApi = {
    /**
     * Lấy danh sách đơn hàng theo vai trò người dùng (buyer / seller / admin), kèm bộ lọc trạng thái và tìm kiếm.
     * Đầu vào: params chứa role, status ('all' hoặc cụ thể), search từ khóa.
     * Kết quả: Mảng danh sách Order đã được lọc theo tiêu chí.
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
        if (params?.search) query.set('search', params.search)

        // 1. Cố gắng lấy từ backend API nếu có
        try {
            const path = `/orders${query.toString() ? `?${query.toString()}` : ''}`
            const response = await api<{ success?: boolean; data?: Order[] } | Order[]>(path)
            const list = Array.isArray(response) ? response : response.data
            if (Array.isArray(list) && list.length > 0) {
                return list
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Backend chưa có endpoint hoặc lỗi kết nối -> Fallback sang mock data
        }

        // 2. Fallback sang mock JSON data
        let orders = readLocalMockOrders()
        const { role, status, search } = params || {}

        if (role === 'buyer') {
            orders = params?.userId ? orders.filter((o) => o.buyerId === params.userId) : []
        } else if (role === 'seller') {
            orders = params?.userId ? orders.filter((o) => o.sellerId === params.userId) : []
        }

        if (status && status !== 'all') {
            orders = orders.filter((o) => o.status.toLowerCase() === status.toLowerCase())
        }

        if (search && search.trim()) {
            const q = search.trim().toLowerCase()
            orders = orders.filter(
                (o) =>
                    o.id.toLowerCase().includes(q) ||
                    o.buyerName.toLowerCase().includes(q) ||
                    o.sellerName.toLowerCase().includes(q) ||
                    o.items.some((item) => item.productName.toLowerCase().includes(q)),
            )
        }

        return orders
    },

    /**
     * Lấy thông tin chi tiết một đơn hàng theo ID.
     * Đầu vào: id (mã đơn hàng, e.g. 'ORD-20240215-001').
     * Kết quả: Đối tượng Order tương ứng hoặc null nếu không tìm thấy.
     */
    async getOrderById(id: string): Promise<Order | null> {
        // 1. Thử gọi backend API
        try {
            const response = await api<{ success?: boolean; data?: Order } | Order>(`/orders/${id}`)
            const order = 'data' in response && response.data ? response.data : (response as Order)
            if (order && order.id) {
                return order
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
            // Fallback mock
        }

        // 2. Tìm trong mock data
        const orders = readLocalMockOrders()
        return orders.find((o) => o.id === id) || null
    },

    /**
     * Xem trước tính toán giá và kiểm tra điều kiện đặt hàng (Checkout Preview).
     * Chức năng: Gọi POST /api/v1/orders/checkout/preview để Backend kiểm tra giá live, tồn kho và tính voucher.
     * Đầu vào: data (CheckoutPreviewRequest gồm itemIds, shippingAddress, voucherCode).
     * Kết quả: CheckoutPreviewResponse (subtotal, discountAmount, shippingFee, taxAmount, totalAmount, isOrderable).
     * Luồng xử lý: Gọi API backend -> nếu backend chưa triển khai (404/503) thì tính toán mock fallback.
     * Quy tắc & lý do: Không trừ tồn kho hay tạo đơn, chỉ phục vụ hiển thị preview trước khi bấm Đặt Hàng.
     * Lỗi & giới hạn: Trả về isOrderable=false và warnings nếu có món hàng không hợp lệ.
     */
    async checkoutPreview(data: CheckoutPreviewRequest): Promise<CheckoutPreviewResponse> {
        try {
            const response = await api<{ success?: boolean; data?: CheckoutPreviewResponse } | CheckoutPreviewResponse>(
                '/orders/checkout/preview',
                {
                    method: 'POST',
                    body: JSON.stringify(data),
                },
            )
            const result =
                'data' in response && response.data
                    ? response.data
                    : (response as CheckoutPreviewResponse)
            if (result && typeof result.totalAmount === 'number') {
                return result
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
        }

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
     *   3. Nếu Backend chưa có endpoint (404/502/503) hoặc lỗi kết nối:
     *      - Khởi tạo đơn hàng mock và lưu vào localStorage để các màn hình xem chi tiết/lịch sử đơn truy cập được.
     *      - Nếu phương thức thanh toán là VNPAY, sinh paymentUrl chuyển hướng cổng sandbox mô phỏng (/sandbox-payment).
     * Quy tắc & lý do:
     *   - Bắt buộc Idempotency-Key theo đặc tả API_SPEC_CART_ORDER.md §4.2 để chống double-submit khi mạng lag.
     *   - Chế độ mock giúp kiểm thử trọn vẹn luồng chuyển hướng sandbox khi backend chưa cấp sandbox credentials.
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
            const response = await api<{ success?: boolean; data?: CreateOrderResponse } | CreateOrderResponse>(
                '/orders',
                {
                    method: 'POST',
                    headers: {
                        'Idempotency-Key': key,
                    },
                    body: JSON.stringify({
                        ...data,
                        sourceType: data.sourceType || 'REGULAR',
                    }),
                },
            )
            const result =
                'data' in response && response.data
                    ? response.data
                    : (response as CreateOrderResponse)
            if (result && (result.id || result.orderNo)) {
                return result
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
        }

        // Mock fallback khi Backend chưa có endpoint /orders
        const orders = readLocalMockOrders()
        const now = new Date()
        const dateStr = now.toISOString().slice(0, 10).replace(/-/g, '')
        const seq = String(orders.length + 1).padStart(3, '0')
        const orderId = `ORD-${dateStr}-${seq}`
        const session = readSession()
        const buyerId = session?.user?.id || 'u1'
        const buyerName = session?.user?.fullName || data.recipientName || 'Minh Tran'

        const calcSubtotal = 429.98
        const calcDiscount = data.voucherCode ? 42.99 : 0
        const calcShipping = 5.0
        const calcTax = (calcSubtotal - calcDiscount) * 0.08
        const calcTotal = calcSubtotal - calcDiscount + calcShipping + calcTax

        // Sinh Sandbox paymentUrl cho VNPAY nếu thanh toán qua cổng trực tuyến
        const sandboxPaymentUrl =
            data.paymentMethod === 'VNPAY'
                ? `/sandbox-payment?orderId=${encodeURIComponent(orderId)}&amount=${calcTotal.toFixed(2)}&provider=vnpay`
                : undefined

        const newOrder: Order = {
            id: orderId,
            buyerId,
            buyerName,
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
                {
                    productId: 'p1',
                    productName: 'Sony WH-1000XM5 Wireless Headphones',
                    image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=100&h=100&fit=crop&auto=format',
                    quantity: 1,
                    unitPrice: 279.99,
                    subtotal: 279.99,
                },
            ],
            subtotal: calcSubtotal,
            tax: calcTax,
            discount: calcDiscount,
            shipping: calcShipping,
            total: calcTotal,
            status: 'pending',
            refundStatus: 'none',
            paymentStatus: 'held',
            shippingAddress: data.shippingAddress,
            paymentMethod:
                data.paymentMethod === 'VNPAY'
                    ? 'VNPAY QR'
                    : data.paymentMethod === 'CARD'
                      ? 'Credit Card'
                      : 'COD',
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
            status: 'pending',
            subtotal: calcSubtotal,
            shippingFee: calcShipping,
            discount: calcDiscount,
            total: calcTotal,
            paymentMethod: data.paymentMethod,
            paymentUrl: sandboxPaymentUrl,
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
                status: 'cancelled',
                refundStatus: 'initiated',
                paymentStatus: 'refunded',
                updatedAt: new Date().toISOString(),
                statusHistory: [
                    ...(orders[index].statusHistory || []),
                    { status: 'cancelled', reason: reason?.trim() || 'Buyer cancellation', actor: 'buyer', changedAt: new Date().toISOString() },
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
            if (status === 'delivered') {
                nextPaymentStatus = 'protection_period'
            } else if (status === 'completed') {
                nextPaymentStatus = 'released'
            } else if (status === 'cancelled' || status === 'refunded') {
                nextPaymentStatus = 'refunded'
            }

            orders[index] = {
                ...orders[index],
                status,
                paymentStatus: nextPaymentStatus,
                updatedAt: new Date().toISOString(),
                statusHistory: [
                    ...(orders[index].statusHistory || []),
                    { status, reason: reason?.trim() || 'No reason provided', actor, changedAt: new Date().toISOString() },
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
                status: 'completed',
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
                status: resolution === 'refunded' ? 'refunded' : orders[index].status,
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

    /** Danh sách yêu cầu rút tiền mock dùng chung màn Admin; không gọi backend tài chính thật. */
    getWithdrawalRequests(): WithdrawalRequest[] {
        return readLocalMockWithdrawals()
    },

    /** Duyệt yêu cầu rút tiền trong mock store và giữ lại thời điểm duyệt sau khi reload. */
    approveWithdrawal(id: string): WithdrawalRequest {
        const requests = readLocalMockWithdrawals()
        const index = requests.findIndex((request) => request.id === id)
        if (index === -1) throw new Error('Withdrawal request not found')
        requests[index] = { ...requests[index], status: 'approved', approvedAt: new Date().toISOString() }
        writeLocalMockWithdrawals(requests)
        return requests[index]
    },
}
