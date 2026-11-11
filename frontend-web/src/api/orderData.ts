import type { Order, OrderItem } from '../types'

/** DTO Commerce khác model màn hình: bản tóm tắt không có items, chi tiết dùng skuId/shippingFee.
 * Tách giá chính thức (nullable trước thanh toán) với giá ước tính (estimated*) theo priceFinalized.
 * Chỉ chuẩn hóa dữ liệu hiển thị; không suy đoán thanh toán thành công từ trạng thái đơn.
 */
export type OrderPayload = Partial<
    Omit<Order, 'items' | 'subtotal' | 'shipping' | 'discount' | 'total'>
> & {
    itemCount?: number
    subtotal?: number | null
    shippingFee?: number | null
    shipping?: number | null
    discount?: number | null
    total?: number | null
    estimatedSubtotal?: number | null
    estimatedShippingFee?: number | null
    estimatedDiscount?: number | null
    estimatedTotal?: number | null
    priceFinalized?: boolean
    sourceType?: string
    items?:
        | (Partial<Omit<OrderItem, 'unitPrice' | 'subtotal'>> & {
              skuId?: string
              thumbnailUrl?: string
              unitPrice?: number | null
              subtotal?: number | null
              estimatedUnitPrice?: number | null
              estimatedSubtotal?: number | null
          })[]
        | null
}

/** Đổi snapshot API/mock sang Order dùng chung cho Buyer/Seller/Admin/Support.
 * Khi đơn chưa chốt giá chính thức (official null / priceFinalized=false), hiển thị fallback từ estimated*
 * đồng thời giữ nguyên cờ priceFinalized để UI gắn nhãn "Ước tính" / "Chờ thanh toán".
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
            ? raw.items.map((item) => {
                  const officialUnit = item.unitPrice != null ? Number(item.unitPrice) : null
                  const estimatedUnit =
                      item.estimatedUnitPrice != null ? Number(item.estimatedUnitPrice) : null
                  const officialSub = item.subtotal != null ? Number(item.subtotal) : null
                  const estimatedSub =
                      item.estimatedSubtotal != null ? Number(item.estimatedSubtotal) : null
                  return {
                      productId: item.productId ?? item.skuId ?? '',
                      productName: item.productName ?? '',
                      image: item.image ?? item.thumbnailUrl ?? '',
                      quantity: Number(item.quantity ?? 0),
                      unitPrice: officialUnit ?? estimatedUnit ?? 0,
                      subtotal: officialSub ?? estimatedSub ?? 0,
                      officialUnitPrice: officialUnit,
                      officialSubtotal: officialSub,
                      estimatedUnitPrice: estimatedUnit,
                      estimatedSubtotal: estimatedSub,
                  }
              })
            : [],
        subtotal: Number(raw.subtotal ?? raw.estimatedSubtotal ?? 0),
        tax: Number(raw.tax ?? 0),
        discount: Number(raw.discount ?? raw.estimatedDiscount ?? 0),
        shipping: Number(raw.shippingFee ?? raw.shipping ?? raw.estimatedShippingFee ?? 0),
        total: Number(raw.total ?? raw.estimatedTotal ?? 0),
        priceFinalized: raw.priceFinalized ?? raw.total != null,
        estimatedSubtotal: raw.estimatedSubtotal ?? null,
        estimatedShippingFee: raw.estimatedShippingFee ?? null,
        estimatedDiscount: raw.estimatedDiscount ?? null,
        estimatedTotal: raw.estimatedTotal ?? null,
        officialSubtotal: raw.subtotal ?? null,
        officialShippingFee: raw.shippingFee ?? raw.shipping ?? null,
        officialDiscount: raw.discount ?? null,
        officialTotal: raw.total ?? null,
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
