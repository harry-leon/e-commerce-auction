import type { Order, OrderItem } from '../types'

/** DTO Commerce khác model màn hình: bản tóm tắt không có items, chi tiết dùng skuId/shippingFee.
 * Chỉ chuẩn hóa dữ liệu hiển thị; không suy đoán thanh toán thành công từ trạng thái đơn.
 */
export type OrderPayload = Partial<Omit<Order, 'items'>> & {
    itemCount?: number
    shippingFee?: number
    sourceType?: string
    items?: (Partial<OrderItem> & { skuId?: string; thumbnailUrl?: string })[] | null
}

/** Đổi snapshot API/mock sang Order dùng chung cho Buyer/Seller/Admin/Support.
 * Thiếu collection thì dùng mảng rỗng; thiếu trạng thái thanh toán thì ghi rõ chưa xác định.
 * Không ghi DB/storage và không tạo sản phẩm giả cho bản tóm tắt.
 */
export function normalizeOrder(raw: OrderPayload): Order {
    if (!raw || typeof raw.id !== 'string' || !raw.status) {
        throw new Error('Dữ liệu đơn hàng không hợp lệ')
    }
    return {
        ...raw,
        id: raw.id,
        status: raw.status,
        buyerId: raw.buyerId ?? '',
        buyerName: raw.buyerName ?? '',
        sellerId: raw.sellerId ?? '',
        sellerName: raw.sellerName ?? '',
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
        total: Number(raw.total ?? 0),
        paymentStatus: raw.paymentStatus ?? 'unknown',
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
