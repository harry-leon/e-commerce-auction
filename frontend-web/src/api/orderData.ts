import type { Order, OrderItem } from '../types'
import { resolveProductImage } from '../utils/productHelper'

/**
 * Kiểu dữ liệu nhận về từ Commerce API hoặc dữ liệu Mock.
 * Chức năng: Định nghĩa cấu trúc OrderPayload linh hoạt nhận từ backend, bao gồm cả giá chính thức và giá ước tính (estimatedTotal).
 * Đầu vào/kết quả: Nhận payload thô từ HTTP response, chuẩn hóa thành kiểu Order hoàn chỉnh.
 * Luồng xử lý: Cung cấp fallback sang estimatedTotal/estimatedSubtotal khi giá chính thức chưa finalize (đơn trúng đấu giá chờ thanh toán).
 * Quy tắc: Không ép null thành 0 nếu có giá trị dự kiến hợp lệ.
 */
export type OrderPayload = Partial<Omit<Order, 'items'>> & {
    itemCount?: number
    shippingFee?: number
    sourceType?: string
    estimatedTotal?: number
    estimatedSubtotal?: number
    estimatedShippingFee?: number
    estimatedDiscount?: number
    items?:
        | (Partial<OrderItem> & {
              skuId?: string
              thumbnailUrl?: string
              estimatedUnitPrice?: number
          })[]
        | null
}

/**
 * Đổi snapshot API/mock sang Order dùng chung cho Buyer/Seller/Admin/Support.
 *
 * Chức năng:
 * - Chuẩn hóa đối tượng đơn hàng nhận từ server hoặc storage để hiển thị trên UI.
 * - Giải quyết triệt để lỗi đơn hàng hiển thị giá 0 đồng khi đơn đấu giá chưa finalize (dùng estimatedTotal, estimatedUnitPrice).
 *
 * Đầu vào & kết quả:
 * - Đầu vào: raw (OrderPayload) chứa dữ liệu thô từ API.
 * - Kết quả: Đối tượng Order an toàn, items luôn là mảng, số tiền luôn là số hợp lệ >= 0.
 *
 * Luồng xử lý:
 * 1. Kiểm tra tính hợp lệ cơ bản của ID và status đơn hàng.
 * 2. Chuẩn hóa mảng items: ưu tiên unitPrice, nếu null/0 dùng estimatedUnitPrice; tính subtotal an toàn.
 * 3. Chuẩn hóa tổng tiền: ưu tiên total, nếu null/0 dùng estimatedTotal.
 * 4. Gán các trường trạng thái, thông tin người mua, người bán và lịch sử trạng thái.
 *
 * Quy tắc & lý do:
 * - Tuân thủ AGENTS.md: Ghi rõ tài liệu tiếng Việt, bảo vệ tính toàn vẹn dữ liệu.
 * - Đơn đấu giá ở bước chờ thanh toán có total=null trên DB Postgres, phải hiển thị đúng giá trúng thầu từ estimatedTotal.
 *
 * Lỗi & giới hạn:
 * - Ném lỗi 'Dữ liệu đơn hàng không hợp lệ' nếu raw rỗng hoặc thiếu ID/status.
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
            ? raw.items.map((item) => {
                  const unitPrice = Number(item.unitPrice ?? item.estimatedUnitPrice ?? 0)
                  const quantity = Number(item.quantity ?? 1)
                  const subtotal = Number(item.subtotal ?? unitPrice * (quantity || 1))
                  return {
                      productId: item.productId ?? item.skuId ?? '',
                      productName: item.productName ?? '',
                      image: resolveProductImage(
                          item.skuId || item.productId,
                          item.productName,
                          item.image ?? item.thumbnailUrl,
                      ),
                      quantity,
                      unitPrice,
                      subtotal,
                  }
              })
            : [],
        subtotal: Number(raw.subtotal ?? raw.estimatedSubtotal ?? 0),
        tax: Number(raw.tax ?? 0),
        discount: Number(raw.discount ?? raw.estimatedDiscount ?? 0),
        shipping: Number(raw.shippingFee ?? raw.shipping ?? raw.estimatedShippingFee ?? 0),
        total: resolvedTotal,
        paymentStatus:
            raw.paymentStatus && raw.paymentStatus !== 'unknown'
                ? raw.paymentStatus
                : defaultPaymentStatus,
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
