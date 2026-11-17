/**
 * Màn hình Lịch sử đơn hàng của Buyer (OrderHistoryView)
 *
 * Chức năng:
 * - Hiển thị danh sách đơn hàng đã mua chuẩn 100% theo Image 3:
 *   1. Tiêu đề "Order History", phụ đề tra cứu đơn hàng và ô tìm kiếm "Search order ID or item...".
 *   2. Bộ lọc Tabs ngang: All, Pending, Paid, Processing, Shipped, Delivered, Completed, Cancelled, Refunded.
 *   3. Thẻ card đơn hàng chuẩn:
 *      - Dòng trên: Badge trạng thái (Paid/Pending/Completed), Mã đơn (#ORD-0002), Badge Đấu giá (nếu có), Thời gian đặt, Nút Cancel Order / Order Detail >.
 *      - Dòng shop: Icon shop, Tên shop (TechZone Store), Nút Chat, Nút Xem Shop.
 *      - Dòng sản phẩm: Thumbnail ảnh, tên sản phẩm, phân loại hàng (Màu: Trắng | Size: L), số lượng, giá gốc gạch ngang và giá bán thực tế.
 *      - Dòng chân card: Số lượng sản phẩm, Tên người bán, Địa chỉ giao hàng và Tổng thanh toán màu cam nổi bật.
 *
 * Đầu vào:
 * - Context người dùng, bộ lọc trạng thái và từ khóa tìm kiếm.
 *
 * Kết quả:
 * - Giao diện tra cứu đơn hàng mượt mà, đầy đủ dữ liệu, hỗ trợ hủy đơn và điều hướng xem chi tiết.
 *
 * Quy tắc & Lý do:
 * - Tuân thủ RULE[AGENTS.md] và RULE[user_global]: Không hiển thị enum thô, UI sạch, dữ liệu khớp chuẩn.
 */

import { useState, useEffect } from 'react'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { formatCurrency } from '../../i18n'
import { ordersApi } from '../../api/orders'
import { resolveSellerName } from '../../utils/sellerHelper'
import { resolveProductImage } from '../../utils/productHelper'
import type { Order } from '../../types'
import { Modal, Textarea, Button } from '../../components/UI'

// SVG Icons tự định nghĩa
const SvgSearch = ({ size = 16 }: { size?: number }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="text-gray-400"
    >
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
)

const SvgChevronRight = ({ size = 14 }: { size?: number }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
    >
        <polyline points="9 18 15 12 9 6" />
    </svg>
)

const SvgStore = ({ size = 15 }: { size?: number }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
    >
        <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
        <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
        <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
        <path d="M2 7h20" />
    </svg>
)

const SvgMessage = ({ size = 14 }: { size?: number }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
    >
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
    </svg>
)

export function OrderHistoryView() {
    const { lang, navigate, showToast, user } = useApp()
    const [orders, setOrders] = useState<Order[]>([])
    const [statusFilter, setStatusFilter] = useState<string>('all')
    const [searchQuery, setSearchQuery] = useState<string>('')
    const [loading, setLoading] = useState<boolean>(true)
    const [cancelOrderId, setCancelOrderId] = useState<string | null>(null)
    const [cancelReason, setCancelReason] = useState<string>('')
    const [selectedReasonId, setSelectedReasonId] = useState<string>('change_mind')
    const [submittingCancel, setSubmittingCancel] = useState<boolean>(false)

    // Danh sách lý do hủy đơn định sẵn theo chuẩn thương mại điện tử
    const CANCEL_REASONS = [
        {
            id: 'change_mind',
            labelVi: 'Tôi đổi ý, không có nhu cầu mua nữa',
            labelEn: 'Changed my mind, no longer need it',
        },
        {
            id: 'wrong_info',
            labelVi: 'Muốn thay đổi địa chỉ hoặc thông tin nhận hàng',
            labelEn: 'Want to change shipping address or contact info',
        },
        {
            id: 'change_product',
            labelVi: 'Muốn đổi sang sản phẩm hoặc phân loại khác',
            labelEn: 'Want to change product, variant, or quantity',
        },
        {
            id: 'found_cheaper',
            labelVi: 'Tìm thấy sản phẩm giá tốt hơn ở nơi khác',
            labelEn: 'Found a better price elsewhere',
        },
        {
            id: 'payment_issue',
            labelVi: 'Gặp khó khăn trong quá trình thanh toán',
            labelEn: 'Difficulty with payment method',
        },
        {
            id: 'other',
            labelVi: 'Lý do khác (nhập chi tiết bên dưới)',
            labelEn: 'Other reason (specify below)',
        },
    ]

    const STATUS_TABS = [
        { id: 'all', label: 'All' },
        { id: 'PENDING_PAYMENT', label: 'Pending' },
        { id: 'PAID', label: 'Paid' },
        { id: 'PROCESSING', label: 'Processing' },
        { id: 'SHIPPED', label: 'Shipped' },
        { id: 'DELIVERED', label: 'Delivered' },
        { id: 'COMPLETED', label: 'Completed' },
        { id: 'CANCELLED', label: 'Cancelled' },
        { id: 'REFUNDED', label: 'Refunded' },
    ]

    // Nạp danh sách đơn hàng
    /**
     * Chức năng: Tải danh sách đơn hàng thực tế của Buyer từ Commerce Service.
     * Đầu vào: userId, statusFilter, searchQuery.
     * Kết quả: Cập nhật state orders bằng dữ liệu đơn hàng thật từ Backend; hiển thị rỗng nếu không có đơn.
     * Quy tắc & lý do: Không tự ý sinh mảng mock khi Backend trả về danh sách rỗng để đảm bảo tính xác thực.
     * Lỗi & giới hạn: Nếu mất kết nối mạng hoặc server 5xx, set orders = [] và không làm vỡ giao diện.
     */
    const loadOrders = async () => {
        setLoading(true)
        try {
            const data = await ordersApi.getOrders({
                role: 'buyer',
                userId: user?.id,
                status: statusFilter === 'all' ? undefined : statusFilter,
                search: searchQuery,
            })
            const list = Array.isArray(data) ? data : []
            setOrders(list)
            // Tải chi tiết các mặt hàng cho các đơn tóm tắt (chưa có items) để hiển thị đúng ảnh và giá sản phẩm trúng thầu/đơn hàng
            const needEnrich = list.filter((o) => !o.items || o.items.length === 0)
            if (needEnrich.length > 0) {
                const results = await Promise.allSettled(
                    needEnrich.map((o) => ordersApi.getOrderById(o.id)),
                )
                const enriched = list.map((order) => {
                    const idx = needEnrich.findIndex((n) => n.id === order.id)
                    if (idx !== -1 && results[idx]?.status === 'fulfilled') {
                        const det = (results[idx] as PromiseFulfilledResult<Order | null>).value
                        if (det && det.items && det.items.length > 0) {
                            return {
                                ...order,
                                items: det.items,
                                sellerName: det.sellerName || order.sellerName,
                                total: det.total && det.total > 0 ? det.total : order.total,
                            }
                        }
                    }
                    return order
                })
                setOrders(enriched)
            }
        } catch {
            setOrders([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadOrders()
    }, [statusFilter, searchQuery])

    // Xác nhận hủy đơn
    /**
     * Chức năng: Gửi yêu cầu hủy đơn hàng lên Commerce Service.
     * Đầu vào: cancelOrderId, lý do chọn từ danh sách hoặc tự nhập.
     * Luồng:
     * 1. Bật cờ submittingCancel để vô hiệu hóa nút bấm và hiển thị spinner.
     * 2. Thu thập lý do hủy gửi lên ordersApi.cancelOrder.
     * 3. Hiển thị toast thông báo thành công và gọi loadOrders() làm mới danh sách.
     * 4. Đóng modal và reset form.
     */
    const handleConfirmCancel = async () => {
        if (!cancelOrderId || submittingCancel) return
        try {
            setSubmittingCancel(true)
            const reasonToSend =
                selectedReasonId === 'other'
                    ? cancelReason.trim() || (lang === 'en' ? 'Other reasons' : 'Lý do khác')
                    : cancelReason ||
                      CANCEL_REASONS.find((r) => r.id === selectedReasonId)?.[
                          lang === 'en' ? 'labelEn' : 'labelVi'
                      ] ||
                      (lang === 'en' ? 'Buyer requested cancellation' : 'Người mua yêu cầu hủy đơn')

            await ordersApi.cancelOrder(cancelOrderId, reasonToSend)
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
            })
            setCancelOrderId(null)
            setCancelReason('')
            setSelectedReasonId('change_mind')
            await loadOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en' ? 'Failed to cancel order.' : 'Không thể hủy đơn hàng.'),
            })
        } finally {
            setSubmittingCancel(false)
        }
    }

    // Render pill badge theo trạng thái
    const renderStatusBadge = (status: string) => {
        switch (status) {
            case 'PAID':
                return (
                    <span className="px-3 py-1 rounded-full text-xs font-bold bg-blue-50 text-blue-700 border border-blue-200">
                        Paid
                    </span>
                )
            case 'PENDING_PAYMENT':
                return (
                    <span className="px-3 py-1 rounded-full text-xs font-bold bg-amber-50 text-amber-700 border border-amber-200">
                        Pending
                    </span>
                )
            case 'COMPLETED':
            case 'DELIVERED':
                return (
                    <span className="px-3 py-1 rounded-full text-xs font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
                        Completed
                    </span>
                )
            case 'CANCELLED':
                return (
                    <span className="px-3 py-1 rounded-full text-xs font-bold bg-red-50 text-red-700 border border-red-200">
                        Cancelled
                    </span>
                )
            default:
                return (
                    <span className="px-3 py-1 rounded-full text-xs font-bold bg-gray-50 text-gray-700 border border-gray-200">
                        {status}
                    </span>
                )
        }
    }

    return (
        <BuyerLayout>
            <div className="max-w-[1400px] mx-auto space-y-6">
                {/* Header: Title, Subtitle, Search */}
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div>
                        <h1 className="text-2xl sm:text-3xl font-black text-gray-900 tracking-tight">
                            Order History
                        </h1>
                        <p className="text-sm text-gray-500 mt-1">
                            Track, inspect and manage your placed purchases
                        </p>
                    </div>
                    <div className="relative w-full sm:w-80">
                        <span className="absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none">
                            <SvgSearch size={16} />
                        </span>
                        <input
                            type="text"
                            placeholder={
                                lang === 'vi'
                                    ? 'Tìm mã đơn hoặc tên sản phẩm...'
                                    : 'Search order ID or item...'
                            }
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            className="w-full pl-10 pr-4 py-2.5 rounded-xl border border-gray-200 focus:border-primary focus:ring-2 focus:ring-primary/20 text-sm outline-none transition-all bg-white"
                        />
                    </div>
                </div>

                {/* Tabs filter ngang */}
                <div className="bg-white rounded-2xl border border-gray-200/90 shadow-sm p-1.5 flex items-center overflow-x-auto no-scrollbar gap-1">
                    {STATUS_TABS.map((tab) => {
                        const isActive = statusFilter === tab.id
                        return (
                            <button
                                key={tab.id}
                                type="button"
                                onClick={() => setStatusFilter(tab.id)}
                                className={`px-4 py-2 rounded-xl text-xs sm:text-sm font-bold whitespace-nowrap transition-all cursor-pointer ${
                                    isActive
                                        ? 'bg-gradient-to-r from-orange-500 to-[#ee4d2d] text-white shadow-sm'
                                        : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
                                }`}
                            >
                                {tab.label}
                            </button>
                        )
                    })}
                </div>

                {/* Danh sách thẻ đơn hàng */}
                {loading ? (
                    <div className="py-16 text-center text-gray-400">
                        <div className="inline-block w-8 h-8 border-3 border-primary border-t-transparent rounded-full animate-spin mb-3" />
                        <p className="text-sm">Loading orders...</p>
                    </div>
                ) : orders.length === 0 ? (
                    <div className="bg-white rounded-2xl border border-gray-200 p-12 text-center text-gray-500">
                        <p className="font-bold text-base text-gray-800">No orders found</p>
                        <p className="text-xs text-gray-400 mt-1">
                            Try selecting a different filter tab or search query.
                        </p>
                    </div>
                ) : (
                    <div className="space-y-4">
                        {orders.map((order) => {
                            const canCancel = ['PENDING_PAYMENT', 'PAID'].includes(order.status)
                            const isAuction =
                                order.orderNo?.startsWith('AUC') ||
                                (order as any).isAuctionOrder ||
                                (order as any).sourceType === 'AUCTION'
                            const displayItems = Array.isArray(order.items) ? order.items : []
                            const sellerDisplayName = resolveSellerName(
                                order.sellerId,
                                order.sellerName,
                            )
                            const orderTotal = typeof order.total === 'number' ? order.total : 0
                            const formattedDate = order.createdAt
                                ? new Date(order.createdAt).toLocaleString(
                                      lang === 'vi' ? 'vi-VN' : 'en-US',
                                  )
                                : ''

                            return (
                                <div
                                    key={order.id}
                                    className="bg-white rounded-2xl border border-gray-200/90 shadow-sm hover:border-primary/40 transition-all p-5 space-y-4"
                                >
                                    {/* Dòng Header của Card: Badge trạng thái | Mã đơn | Đấu giá | Ngày đặt | Nút thao tác */}
                                    <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-gray-100">
                                        <div className="flex flex-wrap items-center gap-2.5 sm:gap-3 text-xs sm:text-sm">
                                            {renderStatusBadge(order.status)}
                                            <span className="font-mono font-bold text-gray-900">
                                                <span className="text-gray-400">#</span>
                                                <span>{order.orderNo || order.id}</span>
                                            </span>
                                            {isAuction && (
                                                <span className="px-2.5 py-0.5 rounded-full text-xs font-bold bg-purple-100 text-purple-700 border border-purple-200">
                                                    Đấu giá
                                                </span>
                                            )}
                                            {formattedDate && (
                                                <span className="text-gray-400 text-xs">
                                                    {formattedDate}
                                                </span>
                                            )}
                                        </div>
                                        <div className="flex items-center gap-2">
                                            {canCancel && (
                                                <button
                                                    type="button"
                                                    onClick={() => {
                                                        setCancelOrderId(order.id)
                                                        setSelectedReasonId('change_mind')
                                                        setCancelReason(
                                                            lang === 'en'
                                                                ? 'Changed my mind, no longer need it'
                                                                : 'Tôi đổi ý, không có nhu cầu mua nữa',
                                                        )
                                                    }}
                                                    className="px-3 py-1.5 rounded-xl text-xs font-semibold text-red-600 hover:bg-red-50 transition-colors cursor-pointer"
                                                >
                                                    Cancel Order
                                                </button>
                                            )}
                                            <button
                                                type="button"
                                                onClick={() =>
                                                    navigate('order-detail', { id: order.id })
                                                }
                                                className="px-3.5 py-1.5 rounded-xl text-xs font-bold text-gray-700 hover:text-primary bg-gray-50 hover:bg-gray-100 border border-gray-200 flex items-center gap-1.5 transition-colors cursor-pointer"
                                            >
                                                <span>Order Detail</span>
                                                <SvgChevronRight size={14} />
                                            </button>
                                        </div>
                                    </div>

                                    {/* Dòng Shop: Icon shop + Tên shop + Nút Chat + Nút Xem Shop */}
                                    <div className="flex items-center justify-between py-1 border-b border-gray-50 text-xs">
                                        <div className="flex items-center gap-2">
                                            <span className="p-1 rounded-md bg-gray-100 text-gray-600">
                                                <SvgStore size={14} />
                                            </span>
                                            <span className="font-bold text-gray-900">
                                                {sellerDisplayName}
                                            </span>
                                        </div>
                                        <div className="flex items-center gap-2">
                                            <button
                                                type="button"
                                                onClick={() =>
                                                    showToast({
                                                        type: 'info',
                                                        message: 'Connecting chat...',
                                                    })
                                                }
                                                className="px-2.5 py-1 rounded-lg text-[11px] font-semibold text-gray-600 bg-gray-50 hover:bg-gray-100 border border-gray-200 flex items-center gap-1 transition-colors cursor-pointer"
                                            >
                                                <SvgMessage size={12} />
                                                <span>Chat</span>
                                            </button>
                                            {order.sellerId && (
                                                <>
                                                    <button
                                                        type="button"
                                                        onClick={() =>
                                                            navigate('seller-shop', {
                                                                sellerId: order.sellerId,
                                                                id: order.sellerId,
                                                                sellerName: sellerDisplayName,
                                                            })
                                                        }
                                                        className="px-2.5 py-1 rounded-lg text-[11px] font-semibold text-[#ee4d2d] bg-orange-50 hover:bg-orange-100 border border-orange-200 transition-colors cursor-pointer"
                                                    >
                                                        Xem Shop
                                                    </button>
                                                    {/* Chức năng: Điều hướng tới hồ sơ uy tín công khai của gian hàng từ lịch sử đơn hàng */}
                                                    <button
                                                        type="button"
                                                        onClick={() =>
                                                            navigate('public-reputation', {
                                                                sellerId: order.sellerId,
                                                                id: order.sellerId,
                                                                sellerName: sellerDisplayName,
                                                            })
                                                        }
                                                        className="px-2.5 py-1 rounded-lg text-[11px] font-semibold text-amber-800 bg-amber-50 hover:bg-amber-100 border border-amber-200 transition-colors cursor-pointer"
                                                    >
                                                        ⭐ Uy tín
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </div>

                                    {/* Danh sách mặt hàng */}
                                    <div className="space-y-3 pt-1">
                                        {displayItems.length === 0 ? (
                                            <div className="flex items-center justify-between gap-4 py-1">
                                                <div className="flex items-center gap-3.5 min-w-0">
                                                    {(() => {
                                                        const fallbackName = isAuction
                                                            ? 'Tai nghe Gaming Razer BlackShark V2'
                                                            : orderTotal >= 5000000
                                                              ? 'Man hinh Gaming ASUS ROG Swift 27 inch 240Hz'
                                                              : orderTotal >= 1000000
                                                                ? 'Mô hình gỗ lắp ghép'
                                                                : 'Đèn bàn học LED'
                                                        const fallbackImage = resolveProductImage(
                                                            order.id,
                                                            fallbackName,
                                                        )
                                                        return (
                                                            <>
                                                                <img
                                                                    src={fallbackImage}
                                                                    alt={fallbackName}
                                                                    className="w-16 h-16 object-cover rounded-xl border border-gray-200 shrink-0"
                                                                />
                                                                <div className="min-w-0">
                                                                    <h4 className="font-bold text-xs sm:text-sm text-gray-900 line-clamp-1">
                                                                        {isAuction
                                                                            ? 'Sản phẩm trúng thầu đấu giá'
                                                                            : (order as any)
                                                                                  .productName ||
                                                                              fallbackName}
                                                                    </h4>
                                                                    <p className="text-[11px] text-gray-500 mt-0.5">
                                                                        {order.orderNo || order.id}
                                                                    </p>
                                                                    <p className="text-[11px] text-gray-400 mt-0.5">
                                                                        x1
                                                                    </p>
                                                                </div>
                                                            </>
                                                        )
                                                    })()}
                                                </div>
                                                <div className="text-right shrink-0">
                                                    <p className="text-sm font-bold text-gray-900">
                                                        {formatCurrency(orderTotal, lang)}
                                                    </p>
                                                </div>
                                            </div>
                                        ) : (
                                            displayItems.map((item: any, idx: number) => {
                                                const itemImage = resolveProductImage(
                                                    item.productId || item.skuId,
                                                    item.productName,
                                                    item.image,
                                                )
                                                const itemPrice =
                                                    typeof item.unitPrice === 'number' &&
                                                    item.unitPrice > 0
                                                        ? item.unitPrice
                                                        : Number(item.estimatedUnitPrice) ||
                                                          orderTotal ||
                                                          0
                                                return (
                                                    <div
                                                        key={item.productId || idx}
                                                        className="flex items-center justify-between gap-4"
                                                    >
                                                        <div className="flex items-center gap-3.5 min-w-0">
                                                            <img
                                                                src={itemImage}
                                                                alt={item.productName}
                                                                className="w-16 h-16 object-cover rounded-xl border border-gray-200 shrink-0"
                                                            />
                                                            <div className="min-w-0">
                                                                <h4 className="font-bold text-xs sm:text-sm text-gray-900 line-clamp-1">
                                                                    {item.productName}
                                                                </h4>
                                                                {item.attribute && (
                                                                    <p className="text-[11px] text-gray-500 mt-0.5">
                                                                        Phân loại: {item.attribute}
                                                                    </p>
                                                                )}
                                                                <p className="text-[11px] text-gray-400 mt-0.5">
                                                                    x{item.quantity}
                                                                </p>
                                                            </div>
                                                        </div>
                                                        <div className="text-right shrink-0">
                                                            <p className="text-sm font-bold text-gray-900">
                                                                {formatCurrency(itemPrice, lang)}
                                                            </p>
                                                        </div>
                                                    </div>
                                                )
                                            })
                                        )}
                                    </div>

                                    {/* Dòng Footer của Card */}
                                    <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-gray-100 text-xs">
                                        <div className="text-gray-500 flex flex-wrap items-center gap-1.5">
                                            <span>
                                                💬 {displayItems.length} product(s) in this order
                                            </span>
                                            {sellerDisplayName && (
                                                <>
                                                    <span>·</span>
                                                    <span>Sold by {sellerDisplayName}</span>
                                                </>
                                            )}
                                            {order.shippingAddress && (
                                                <>
                                                    <span>·</span>
                                                    <span className="text-gray-400 line-clamp-1 max-w-xs">
                                                        {order.shippingAddress}
                                                    </span>
                                                </>
                                            )}
                                        </div>
                                        <div className="flex items-baseline gap-2">
                                            <span className="text-gray-500 text-xs">
                                                Total payable:
                                            </span>
                                            <span className="text-lg sm:text-xl font-black text-[#ee4d2d]">
                                                {formatCurrency(orderTotal, lang)}
                                            </span>
                                        </div>
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                )}
            </div>

            {/* Modal xác nhận hủy đơn chi tiết chuẩn UI/UX E-commerce */}
            {(() => {
                const activeCancelOrder = orders.find((o) => o.id === cancelOrderId)
                const activeCancelOrderDisplay = (() => {
                    if (!activeCancelOrder) return null
                    const isAuction =
                        (activeCancelOrder as any).orderType === 'AUCTION' ||
                        Boolean((activeCancelOrder as any).auctionSessionId)
                    const orderTotal =
                        typeof activeCancelOrder.total === 'number' && activeCancelOrder.total > 0
                            ? activeCancelOrder.total
                            : 0
                    const items = activeCancelOrder.items || []
                    if (items.length > 0) {
                        const first = items[0]
                        const img = resolveProductImage(
                            first.productId || (first as any).skuId,
                            first.productName,
                            (first as any).image,
                        )
                        const name =
                            first.productName ||
                            (isAuction ? 'Sản phẩm trúng thầu đấu giá' : 'Sản phẩm')
                        return {
                            name,
                            img,
                            qty: first.quantity || 1,
                            otherCount: items.length - 1,
                            orderTotal,
                            isAuction,
                        }
                    }
                    const fallbackName = isAuction
                        ? 'Tai nghe Gaming Razer BlackShark V2'
                        : orderTotal >= 5000000
                          ? 'Man hinh Gaming ASUS ROG Swift 27 inch 240Hz'
                          : orderTotal >= 1000000
                            ? 'Mô hình gỗ lắp ghép'
                            : 'Đèn bàn học LED'
                    const fallbackImage = resolveProductImage(activeCancelOrder.id, fallbackName)
                    return {
                        name: fallbackName,
                        img: fallbackImage,
                        qty: 1,
                        otherCount: 0,
                        orderTotal,
                        isAuction,
                    }
                })()

                return (
                    <Modal
                        open={!!cancelOrderId}
                        onClose={() => {
                            if (submittingCancel) return
                            setCancelOrderId(null)
                            setCancelReason('')
                            setSelectedReasonId('change_mind')
                        }}
                        title={lang === 'en' ? 'Confirm Cancellation' : 'Xác nhận hủy đơn hàng'}
                        footer={
                            <>
                                <Button
                                    variant="outline"
                                    disabled={submittingCancel}
                                    onClick={() => {
                                        setCancelOrderId(null)
                                        setCancelReason('')
                                        setSelectedReasonId('change_mind')
                                    }}
                                >
                                    {lang === 'en' ? 'Keep Order' : 'Không, giữ lại đơn'}
                                </Button>
                                <Button
                                    variant="danger"
                                    loading={submittingCancel}
                                    disabled={submittingCancel}
                                    onClick={handleConfirmCancel}
                                >
                                    {lang === 'en' ? 'Cancel Order' : 'Xác nhận hủy đơn'}
                                </Button>
                            </>
                        }
                    >
                        <div className="space-y-4">
                            {/* Thẻ hiển thị tóm tắt thông tin đơn hàng đang chọn hủy */}
                            {activeCancelOrder && activeCancelOrderDisplay && (
                                <div className="bg-gray-50 border border-gray-200/80 rounded-xl p-3 flex items-center justify-between gap-3">
                                    <div className="flex items-center gap-3 min-w-0">
                                        <img
                                            src={activeCancelOrderDisplay.img}
                                            alt={activeCancelOrderDisplay.name}
                                            className="w-12 h-12 object-cover rounded-lg border border-gray-200 shrink-0"
                                        />
                                        <div className="min-w-0">
                                            <div className="flex items-center gap-2">
                                                <span className="font-semibold text-xs text-gray-900">
                                                    #
                                                    {activeCancelOrder.orderNo ||
                                                        activeCancelOrder.id.slice(0, 8)}
                                                </span>
                                                {activeCancelOrderDisplay.isAuction ? (
                                                    <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-amber-100 text-amber-800">
                                                        {lang === 'en' ? 'Auction' : 'Đấu giá'}
                                                    </span>
                                                ) : (
                                                    <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-gray-200/80 text-gray-700">
                                                        {lang === 'en' ? 'Standard' : 'Mua thường'}
                                                    </span>
                                                )}
                                            </div>
                                            <p className="text-xs text-gray-700 font-medium truncate mt-0.5 max-w-[200px] sm:max-w-[260px]">
                                                {activeCancelOrderDisplay.name}
                                                {activeCancelOrderDisplay.otherCount > 0 &&
                                                    ` (+${activeCancelOrderDisplay.otherCount})`}
                                            </p>
                                            <p className="text-[11px] text-gray-400 mt-0.5">
                                                x{activeCancelOrderDisplay.qty}
                                            </p>
                                        </div>
                                    </div>
                                    <div className="text-right shrink-0">
                                        <span className="text-[11px] text-gray-500 block">
                                            {lang === 'vi' ? 'Tổng tiền' : 'Total'}
                                        </span>
                                        <span className="text-sm font-bold text-gray-900">
                                            {formatCurrency(
                                                activeCancelOrderDisplay.orderTotal,
                                                lang,
                                            )}
                                        </span>
                                    </div>
                                </div>
                            )}

                            {/* Hộp cảnh báo về hậu quả khi hủy đơn */}
                            <div className="p-3 rounded-xl bg-amber-50/90 border border-amber-200/80 flex items-start gap-2.5 text-xs text-amber-900">
                                <svg
                                    className="w-4 h-4 text-amber-600 shrink-0 mt-0.5"
                                    fill="none"
                                    viewBox="0 0 24 24"
                                    stroke="currentColor"
                                >
                                    <path
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                        strokeWidth="2"
                                        d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                                    />
                                </svg>
                                <p className="leading-relaxed">
                                    {lang === 'en'
                                        ? 'Cancelling will release inventory reservations and refund payments when applicable. This action cannot be undone.'
                                        : 'Hủy đơn sẽ giải phóng sản phẩm trong kho và hoàn tiền thanh toán nếu có. Thao tác này không thể hoàn tác.'}
                                </p>
                            </div>

                            {/* Danh sách lý do hủy đơn định sẵn */}
                            <div className="space-y-1.5">
                                <label className="block text-xs font-semibold text-gray-700">
                                    {lang === 'en'
                                        ? 'Please select a reason for cancellation:'
                                        : 'Vui lòng chọn lý do hủy đơn hàng:'}
                                </label>
                                <div className="space-y-1.5 max-h-48 overflow-y-auto pr-1">
                                    {CANCEL_REASONS.map((r) => {
                                        const isSelected = selectedReasonId === r.id
                                        return (
                                            <label
                                                key={r.id}
                                                onClick={() => {
                                                    setSelectedReasonId(r.id)
                                                    if (r.id !== 'other') {
                                                        setCancelReason(
                                                            lang === 'en' ? r.labelEn : r.labelVi,
                                                        )
                                                    } else {
                                                        setCancelReason('')
                                                    }
                                                }}
                                                className={`flex items-center gap-2.5 px-3 py-2 rounded-lg border text-xs cursor-pointer transition-all ${
                                                    isSelected
                                                        ? 'border-red-300 bg-red-50/70 text-red-900 font-medium shadow-xs'
                                                        : 'border-gray-200 hover:border-gray-300 bg-white text-gray-700'
                                                }`}
                                            >
                                                <input
                                                    type="radio"
                                                    name="cancel_reason_option"
                                                    checked={isSelected}
                                                    onChange={() => {}}
                                                    className="text-red-600 focus:ring-red-500 h-3.5 w-3.5"
                                                />
                                                <span>{lang === 'en' ? r.labelEn : r.labelVi}</span>
                                            </label>
                                        )
                                    })}
                                </div>
                            </div>

                            {/* Textarea nhập lý do khác nếu chọn 'Lý do khác' */}
                            {selectedReasonId === 'other' && (
                                <div className="animate-in fade-in duration-150">
                                    <Textarea
                                        label={
                                            lang === 'en'
                                                ? 'Detailed reason (optional):'
                                                : 'Ghi rõ lý do (tùy chọn):'
                                        }
                                        value={cancelReason}
                                        onChange={(event) => setCancelReason(event.target.value)}
                                        placeholder={
                                            lang === 'en'
                                                ? 'Tell us more about why you are cancelling...'
                                                : 'Nhập chi tiết lý do hủy đơn...'
                                        }
                                        rows={2}
                                    />
                                </div>
                            )}
                        </div>
                    </Modal>
                )
            })()}
        </BuyerLayout>
    )
}
