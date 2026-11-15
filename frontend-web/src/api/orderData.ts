import type { Order, OrderItem } from '../types'

/** DTO Commerce khác model màn hình: bản tóm tắt không có items, chi tiết dùng skuId/shippingFee.
 * Chỉ chuẩn hóa dữ liệu hiển thị; bổ sung estimatedTotal nếu total chưa được chốt.
 */
export type OrderPayload = Partial<Omit<Order, 'items'>> & {
    itemCount?: number
    shippingFee?: number
    sourceType?: string
    estimatedTotal?: number
    items?: (Partial<OrderItem> & { skuId?: string; thumbnailUrl?: string })[] | null
}

/** Đổi snapshot API/mock sang Order dùng chung cho Buyer/Seller/Admin/Support.
 * - Chức năng: Chuẩn hóa DTO đơn hàng sang cấu trúc hiển thị đồng nhất toàn frontend.
 * - Đầu vào và kết quả: Nhận OrderPayload (từ API backend hoặc mock), trả về đối tượng Order hoàn chỉnh.
 * - Luồng xử lý: Kiểm tra hợp lệ id/status -> ánh xạ các trường tính toán, tên người mua/bán và trạng thái thanh toán.
 * - Quy tắc và lý do: Ưu tiên total thật; nếu chưa chốt (ví dụ đơn chờ thanh toán) dùng estimatedTotal để tránh hiện 0 $.
 *   Nếu backend chỉ trả UUID, hiển thị mã rút gọn thay vì để trống. Suy luận trạng thái thanh toán tự nhiên nếu thiếu.
 * - Lỗi và giới hạn: Ném Error nếu dữ liệu không có id hoặc status.
 */
export function normalizeOrder(raw: OrderPayload): Order {
    if (!raw || typeof raw.id !== 'string' || !raw.status) {
        throw new Error('Dữ liệu đơn hàng không hợp lệ')
    }
    const resolvedTotal = Number(raw.total ?? raw.estimatedTotal ?? 0)
    const buyerName = raw.buyerName || (raw.buyerId ? `User ***${raw.buyerId.slice(-4)}` : '')
    const sellerName = raw.sellerName || (raw.sellerId ? `Seller ***${raw.sellerId.slice(-4)}` : '')
    const defaultPaymentStatus =
        raw.status === 'PAID'
            ? 'released'
            : raw.status === 'CANCELLED'
              ? 'refunded'
              : raw.status === 'PENDING_PAYMENT'
                ? 'on_hold'
                : 'unknown'

    return {
        ...raw,
        id: raw.id,
        status: raw.status,
        buyerId: raw.buyerId ?? '',
        buyerName,
        sellerId: raw.sellerId ?? '',
        sellerName,
        items: Array.isArray(raw.items)
            ? raw.items.map((item) => ({
                  productId: item.productId ?? item.skuId ?? '',
                  productName: item.productName ?? '',
                  image: item.image ?? item.thumbnailUrl ?? '',
                  quantity: Number(item.quantity ?? 0),
                  unitPrice: Number(item.unitPrice ?? 0),
                  subtotal: Number(item.subtotal ?? 0),
              }))
            : [],
        subtotal: Number(raw.subtotal ?? 0),
        tax: Number(raw.tax ?? 0),
        discount: Number(raw.discount ?? 0),
        shipping: Number(raw.shippingFee ?? raw.shipping ?? 0),
        total: resolvedTotal,
        paymentStatus: raw.paymentStatus || defaultPaymentStatus,
        refundStatus: raw.refundStatus ?? 'none',
        paymentMethod: raw.paymentMethod ?? '',
        shippingAddress: raw.shippingAddress ?? '',
        createdAt: raw.createdAt ?? '',
        updatedAt: raw.updatedAt ?? raw.createdAt ?? '',
        isAuctionOrder: raw.isAuctionOrder ?? raw.sourceType === 'AUCTION',
        statusHistory: Array.isArray(raw.statusHistory) ? raw.statusHistory : [],
        shipment: raw.shipment
            ? {
                  ...raw.shipment,
                  events: Array.isArray(raw.shipment.events) ? raw.shipment.events : [],
              }
            : undefined,
    }
}
