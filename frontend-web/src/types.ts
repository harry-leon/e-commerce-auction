export type Lang = 'en' | 'vi'
export type UserRole = 'buyer' | 'seller' | 'admin' | 'support'
export type TrustLevel = 'bronze' | 'silver' | 'gold' | 'platinum'
export type OrderStatus = 'pending' | 'paid' | 'processing' | 'shipped' | 'delivered' | 'completed' | 'cancelled' | 'refunded'
export type AuctionStatus = 'pending_approval' | 'scheduled' | 'active' | 'ended' | 'settled' | 'cancelled' | 'failed' | 'rejected'
export type ProductStatus = 'draft' | 'active' | 'inactive'
export type ShipmentStatus = 'pending' | 'picked_up' | 'in_transit' | 'out_for_delivery' | 'delivered' | 'failed' | 'returned'
export type RefundStatus = 'none' | 'initiated' | 'processing' | 'completed' | 'rejected'
export type PaymentStatus = 'held' | 'protection_period' | 'released' | 'on_hold' | 'refunded' | 'partial_refund'
export type DisputeStatus = 'none' | 'open' | 'reviewing' | 'resolved'
export type NotifType = 'order' | 'auction' | 'payment' | 'system' | 'promotion' | 'reputation'

export interface User {
  id: string; fullName: string; email: string; phone: string; idNumber: string
  gender: 'male' | 'female' | 'other'; dateOfBirth: string; address: string
  role: UserRole; trustLevel: TrustLevel; reputationScore: number; avatar: string
  status: 'active' | 'locked'; createdAt: string
}

export interface Category {
  id: string; name: string; nameVi: string; icon: string; slug: string
  parentId?: string; children?: Category[]; productCount?: number
}

export interface Product {
  id: string; name: string; nameVi: string; description: string; descriptionVi: string
  price: number; originalPrice?: number; images: string[]; category: string; categoryId: string
  location: string
  sku: string; stock: number; status: ProductStatus; sellerId: string; sellerName: string
  sellerTrust: TrustLevel; sellerRating: number; rating: number; reviewCount: number
  weight: number; enableAuction: boolean; createdAt: string
}

export interface AuctionBid {
  id: string; bidderId: string; bidderName: string; amount: number; timestamp: string
}

export interface Auction {
  id: string; productId: string; product: Product; sellerId: string; sellerName: string
  status: AuctionStatus; startingPrice: number; currentBid: number; minIncrement: number
  startTime: string; endTime: string; bidCount: number; bids: AuctionBid[]
  winnerId?: string; winnerName?: string; visibility: 'public' | 'restricted'
  cancelReason?: string; settledAt?: string
}

export interface OrderItem {
  productId: string; productName: string; image: string; quantity: number
  unitPrice: number; subtotal: number
}

export interface ShipmentEvent {
  status: ShipmentStatus; description: string; location?: string; timestamp: string
}

export interface Shipment {
  id: string; carrier: 'GHN' | 'GHTK'; trackingNumber: string; status: ShipmentStatus
  estimatedDelivery: string; events: ShipmentEvent[]
}

export interface Order {
  id: string; buyerId: string; buyerName: string; sellerId: string; sellerName: string
  items: OrderItem[]; subtotal: number; tax: number; discount: number; shipping: number; total: number
  status: OrderStatus; refundStatus: RefundStatus; paymentStatus: PaymentStatus
  shippingAddress: string; paymentMethod: string; voucher?: string
  shipment?: Shipment; createdAt: string; updatedAt: string
  deliveredAt?: string; protectionEndsAt?: string; disputeStatus?: DisputeStatus; disputeReason?: string
  isAuctionOrder?: boolean; auctionId?: string; paymentDeadline?: string; reviewed?: boolean
}

export interface Notification {
  id: string; type: NotifType; title: string; titleVi: string
  body: string; bodyVi: string; read: boolean; timestamp: string; link?: string
}

export interface ReputationEntry {
  id: string; fromUser: string; score: number; comment: string; createdAt: string; orderId: string
}

export interface Penalty {
  id: string; reason: string; points: number; appliedBy: string; appliedAt: string
}

export interface RepHistory {
  scores: ReputationEntry[]; penalties: Penalty[]
}

export interface Permission { module: string; read: boolean; write: boolean; delete: boolean; admin: boolean }
export interface Role {
  id: string; name: string; description: string; permissions: Permission[]; userCount: number
}

export interface EventLog {
  id: string; type: string; action: string; message: string; operator: string
  targetId?: string; targetType?: string; timestamp: string; metadata?: Record<string, unknown>
}

export interface SystemService {
  name: string; status: 'up' | 'down'; latency?: number; lastCheck: string
}

export type Page =
  | 'login' | 'register' | 'forgot-password'
  | 'home' | 'search' | 'product-detail' | 'cart' | 'checkout' | 'payment-result'
  | 'order-history' | 'order-detail' | 'profile' | 'my-reputation' | 'public-reputation' | 'notifications'
  | 'auction-list' | 'auction-detail' | 'my-bids' | 'auction-payment'
  | 'seller-dashboard' | 'seller-products' | 'seller-create-product' | 'seller-auctions'
  | 'seller-create-auction' | 'seller-orders' | 'seller-balance' | 'seller-inventory' | 'seller-reputation'
  | 'seller-notifications' | 'seller-profile'
  | 'admin-dashboard' | 'admin-users' | 'admin-user-detail' | 'admin-roles' | 'admin-categories'
  | 'admin-products' | 'admin-orders' | 'admin-auctions' | 'admin-warehouses'
  | 'admin-shipments' | 'admin-reputation' | 'admin-event-logs' | 'admin-audit-logs'
  | 'admin-system-config' | 'admin-system-health' | 'admin-profile'
  | 'support-dashboard' | 'support-order-lookup' | 'support-user-lookup' | 'support-profile'

export interface Toast {
  id: string; type: 'success' | 'error' | 'warning' | 'info'; message: string; duration?: number
}

export interface AppState {
  lang: Lang; setLang: (l: Lang) => void
  page: Page; navigate: (p: Page, params?: Record<string, unknown>) => void
  pageParams: Record<string, unknown>
  user: User | null; setUser: (u: User | null) => void
  cartCount: number; setCartCount: (n: number) => void
  toasts: Toast[]; showToast: (t: Omit<Toast, 'id'>) => void; dismissToast: (id: string) => void
  auctionReviews: Record<string, AuctionReview>
  reviewAuction: (auctionId: string, status: 'scheduled' | 'rejected', reason?: string) => void
  createdAuctions: Auction[]
  submitAuction: (auction: Auction) => void
}

export interface AuctionReview {
  status: 'scheduled' | 'rejected'
  reason?: string
  reviewedAt: string
}
