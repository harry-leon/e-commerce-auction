export type Lang = 'en' | 'vi'
export type UserRole = 'buyer' | 'seller' | 'admin' | 'support'
export type TrustLevel = 'bronze' | 'silver' | 'gold' | 'platinum'
export type OrderStatus =
    | 'PENDING_PAYMENT'
    | 'PAID'
    | 'PROCESSING'
    | 'SHIPPED'
    | 'DELIVERED'
    | 'COMPLETED'
    | 'CANCELLED'
    | 'REFUNDED'
export type AuctionStatus =
    | 'pending_approval'
    | 'scheduled'
    | 'active'
    | 'paused'
    | 'ended'
    | 'settled'
    | 'cancelled'
    | 'failed'
    | 'rejected'
export type ProductStatus = 'draft' | 'active' | 'inactive'
export type ShipmentStatus =
    | 'pending'
    | 'picked_up'
    | 'in_transit'
    | 'out_for_delivery'
    | 'delivered'
    | 'failed'
    | 'returned'
export type RefundStatus = 'none' | 'initiated' | 'processing' | 'completed' | 'rejected'
export type PaymentStatus =
    // DTO đơn hàng chưa cung cấp trạng thái escrow; không mặc định thành đã thu tiền.
    | 'unknown'
    | 'held'
    | 'protection_period'
    | 'released'
    | 'on_hold'
    | 'refunded'
    | 'partial_refund'
export type DisputeStatus = 'none' | 'open' | 'reviewing' | 'resolved'
export type NotifType = 'order' | 'auction' | 'payment' | 'system' | 'promotion' | 'reputation'

export interface User {
    id: string
    fullName: string
    email: string
    phone: string
    idNumber: string
    gender: 'male' | 'female' | 'other'
    dateOfBirth: string
    address: string
    role: UserRole
    trustLevel: TrustLevel
    reputationScore: number
    avatar: string
    status: 'active' | 'locked'
    createdAt: string
}

export interface Category {
    id: string
    name: string
    nameVi: string
    icon: string
    slug: string
    parentId?: string
    children?: Category[]
    productCount?: number
}

export interface Product {
    id: string
    name: string
    nameVi: string
    description: string
    descriptionVi: string
    price: number
    originalPrice?: number
    images: string[]
    category: string
    categoryId: string
    location: string
    sku: string
    skuId?: string
    stock: number
    status: ProductStatus
    sellerId: string
    sellerName: string
    sellerTrust: TrustLevel
    sellerRating: number
    rating: number
    reviewCount: number
    weight: number
    enableAuction: boolean
    createdAt: string
}

export interface AuctionBid {
    id: string
    bidderId: string
    bidderName: string
    amount: number
    timestamp: string
}

export interface Auction {
    id: string
    productId: string
    product: Product
    sellerId: string
    sellerName: string
    /** Tên tài khoản người tạo phiên, tách với tên cửa hàng khi API cung cấp. */
    sellerAccountName?: string
    sellerEmail?: string
    status: AuctionStatus
    startingPrice: number
    currentBid: number
    minIncrement: number
    startTime: string
    endTime: string
    bidCount: number
    bids: AuctionBid[]
    winnerId?: string
    winnerName?: string
    visibility: 'public' | 'restricted'
    cancelReason?: string
    settledAt?: string
}

export interface OrderItem {
    productId: string
    productName: string
    image: string
    quantity: number
    unitPrice: number
    subtotal: number
}

/** Lưu vết thay đổi trạng thái trong mock để Admin/Seller không mất lý do thao tác sau khi tải lại trang. */
export interface OrderStatusHistoryEntry {
    status: OrderStatus
    reason: string
    actor: 'buyer' | 'seller' | 'admin' | 'system'
    changedAt: string
}

export interface ShipmentEvent {
    status: ShipmentStatus
    description: string
    location?: string
    timestamp: string
}

export interface Shipment {
    id: string
    carrier: 'GHN' | 'GHTK'
    trackingNumber: string
    status: ShipmentStatus
    estimatedDelivery: string
    events: ShipmentEvent[]
}

export interface Order {
    /** Số dòng từ bản tóm tắt; items chỉ có khi API cung cấp chi tiết. */
    itemCount?: number
    id: string
    orderNo?: string
    buyerId: string
    buyerName: string
    sellerId: string
    sellerName: string
    items: OrderItem[]
    subtotal: number
    tax: number
    discount: number
    shipping: number
    total: number
    status: OrderStatus
    refundStatus: RefundStatus
    paymentStatus: PaymentStatus
    shippingAddress: string
    paymentMethod: string
    voucher?: string
    shipment?: Shipment
    createdAt: string
    updatedAt: string
    deliveredAt?: string
    protectionEndsAt?: string
    disputeStatus?: DisputeStatus
    disputeReason?: string
    isAuctionOrder?: boolean
    auctionId?: string
    paymentDeadline?: string
    reviewed?: boolean
    statusHistory?: OrderStatusHistoryEntry[]
}

export interface Notification {
    id: string
    type: NotifType
    title: string
    titleVi: string
    body: string
    bodyVi: string
    read: boolean
    timestamp: string
    link?: string
}

export interface ReputationEntry {
    id: string
    fromUser: string
    score: number
    comment: string
    createdAt: string
    orderId: string
}

export interface Penalty {
    id: string
    reason: string
    points: number
    appliedBy: string
    appliedAt: string
}

export interface RepHistory {
    scores: ReputationEntry[]
    penalties: Penalty[]
}

export interface Permission {
    module: string
    read: boolean
    write: boolean
    delete: boolean
    admin: boolean
}
export interface Role {
    id: string
    name: string
    description: string
    permissions: Permission[]
    userCount: number
}

export interface EventLog {
    id: string
    type: string
    action: string
    message: string
    operator: string
    targetId?: string
    targetType?: string
    timestamp: string
    metadata?: Record<string, unknown>
}

export interface SystemService {
    name: string
    status: 'up' | 'down'
    latency?: number
    lastCheck: string
}

export type Page =
    | 'login'
    | 'register'
    | 'forgot-password'
    | 'home'
    | 'search'
    | 'product-detail'
    | 'cart'
    | 'checkout'
    | 'sandbox-payment'
    | 'payment-result'
    | 'order-history'
    | 'order-detail'
    | 'profile'
    | 'my-reputation'
    | 'public-reputation'
    | 'seller-shop'
    | 'notifications'
    | 'auction-list'
    | 'auction-detail'
    | 'my-bids'
    | 'auction-payment'
    | 'seller-dashboard'
    | 'seller-products'
    | 'seller-create-product'
    | 'seller-auctions'
    | 'seller-create-auction'
    | 'seller-orders'
    | 'seller-balance'
    | 'seller-inventory'
    | 'seller-reputation'
    | 'seller-notifications'
    | 'seller-profile'
    | 'admin-dashboard'
    | 'admin-users'
    | 'admin-user-detail'
    | 'admin-roles'
    | 'admin-categories'
    | 'admin-products'
    | 'admin-orders'
    | 'admin-auctions'
    | 'admin-warehouses'
    | 'admin-shipments'
    | 'admin-reputation'
    | 'admin-event-logs'
    | 'admin-audit-logs'
    | 'admin-system-config'
    | 'admin-system-health'
    | 'admin-profile'
    | 'support-dashboard'
    | 'support-order-lookup'
    | 'support-user-lookup'
    | 'support-profile'

export interface Toast {
    id: string
    type: 'success' | 'error' | 'warning' | 'info'
    message: string
    duration?: number
}

export interface AppState {
    lang: Lang
    setLang: (l: Lang) => void
    page: Page
    navigate: (p: Page, params?: Record<string, unknown>) => void
    pageParams: Record<string, unknown>
    user: User | null
    setUser: (u: User | null) => void
    cartCount: number
    setCartCount: (n: number) => void
    toasts: Toast[]
    showToast: (t: Omit<Toast, 'id'>) => void
    dismissToast: (id: string) => void
    // Chỉ phiên đã được xét duyệt mới có entry; phiên khác dùng status gốc của Auction, không mặc định có review.
    auctionReviews: Partial<Record<string, AuctionReview>>
    reviewAuction: (auctionId: string, status: 'scheduled' | 'rejected', reason?: string) => void
    createdAuctions: Auction[]
    submitAuction: (auction: Auction) => void
}

export interface AuctionReview {
    status: 'scheduled' | 'rejected'
    reason?: string
    reviewedAt: string
}

// ─── API Order DTOs (theo chuẩn V2 và COMMERCE_SERVICE.md) ──────────────────
// Các type dưới đây phản ánh đúng cấu trúc DTO mà backend /orders sẽ trả về
// khi được triển khai (hiện tại PLANNED theo docs/dto-contracts/COMMERCE_SERVICE.md §4).
// OrderStatus đã đồng nhất UPPERCASE với backend — không cần ApiOrderStatus riêng nữa.

/**
 * Một dòng sản phẩm trong đơn hàng — dữ liệu snapshot tại thời điểm đặt hàng.
 * Theo API_SPEC_CART_ORDER.md §4.4: snapshot tên, mã SKU, giá đã được lưu lại.
 */
export interface ApiOrderItem {
    skuId: string
    skuCode?: string | null
    productName: string
    thumbnailUrl: string | null
    unitPrice: number
    quantity: number
    itemTotal: number
}

/**
 * Tóm tắt đơn hàng dùng cho danh sách lịch sử (GET /api/v1/orders).
 * Theo API_SPEC_CART_ORDER.md §4.3: PageResponse<OrderSummaryResponse>.
 */
export interface OrderSummaryResponse {
    orderId: string
    /** Snapshot mã đơn hiển thị (có thể dạng ORD-YYYYMMDD-XXX) */
    orderCode: string
    status: OrderStatus
    /** Tổng số dòng SKU */
    totalItems: number
    /** Tổng tiền thanh toán thực tế */
    totalAmount: number
    paymentMethod: string
    createdAt: string
    updatedAt: string
}

/**
 * Chi tiết đầy đủ một đơn hàng (GET /api/v1/orders/{orderId}).
 * Bao gồm snapshot sản phẩm và lịch sử trạng thái theo API_SPEC_CART_ORDER.md §4.4.
 */
export interface OrderDetailResponse {
    orderId: string
    orderCode: string
    status: OrderStatus
    items: ApiOrderItem[]
    subtotal: number
    discountAmount: number
    shippingFee: number
    taxAmount: number
    totalAmount: number
    shippingAddress: string
    recipientName: string
    recipientPhone: string
    paymentMethod: string
    /** URL thanh toán online (VNPay, MoMo...) nếu còn hạn */
    paymentUrl: string | null
    /** Hạn chót thanh toán (15 phút sau khi tạo đơn) */
    paymentDeadline: string | null
    appliedVoucherCode: string | null
    statusHistory: ApiOrderStatusHistoryEntry[]
    createdAt: string
    updatedAt: string
}

/**
 * Một sự kiện trong lịch sử chuyển trạng thái của đơn hàng.
 * Theo API_SPEC_CART_ORDER.md §6: ghi log vào order_status_history.
 */
export interface ApiOrderStatusHistoryEntry {
    status: OrderStatus
    note: string | null
    actor: 'BUYER' | 'SELLER' | 'ADMIN' | 'SYSTEM'
    changedAt: string
}

/**
 * Request tạo đơn hàng (POST /api/v1/orders).
 * Bắt buộc có header Idempotency-Key phía gọi.
 * Theo API_SPEC_CART_ORDER.md §4.2.
 */
export interface PlaceOrderRequest {
    shippingAddress: string
    recipientName: string
    recipientPhone: string
    voucherCode?: string | null
    paymentMethod: string
    /** 'REGULAR' hoặc 'AUCTION' */
    sourceType: 'REGULAR' | 'AUCTION'
    auctionId?: string | null
    /** UUID các dòng cart item được chọn thanh toán */
    selectedItemIds: string[]
}

/**
 * Request preview trước khi đặt hàng (POST /api/v1/orders/checkout/preview).
 * Theo API_SPEC_CART_ORDER.md §4.1.
 */
export interface CheckoutPreviewRequest {
    itemIds: string[]
    shippingAddress: string
    voucherCode?: string | null
}

/**
 * Response preview đơn hàng: giá tạm tính, voucher, phí ship, cảnh báo tồn kho.
 * Theo API_SPEC_CART_ORDER.md §4.1.
 */
export interface CheckoutPreviewResponse {
    subtotal: number
    discountAmount: number
    shippingFee: number
    taxAmount: number
    totalAmount: number
    appliedVoucher: {
        code: string
        discountValue: number
        description: string
    } | null
    items: {
        skuId: string
        productName: string
        unitPrice: number
        quantity: number
        itemTotal: number
    }[]
    /** Đơn có thể đặt được không (tồn kho hợp lệ, giá không thay đổi quá mức) */
    isOrderable: boolean
    /** Cảnh báo về giá thay đổi hoặc sắp hết hàng */
    warnings: string[]
}

/**
 * Request hủy đơn hàng (POST /api/v1/orders/{orderId}/cancel).
 * Theo API_SPEC_CART_ORDER.md §4.5.
 */
export interface CancelOrderRequest {
    reason: string
}
