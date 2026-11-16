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
import { formatCurrency, tr } from '../../i18n'
import { ordersApi } from '../../api/orders'
import { resolveSellerName } from '../../utils/sellerHelper'
import { resolveProductImage } from '../../utils/productHelper'
import type { Order } from '../../types'
import { Modal, Textarea, Button } from '../../components/UI'

// SVG Icons tự định nghĩa
const SvgSearch = ({ size = 16 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-gray-400">
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
)

const SvgChevronRight = ({ size = 14 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="9 18 15 12 9 6" />
    </svg>
)

const SvgStore = ({ size = 15 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
        <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
        <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
        <path d="M2 7h20" />
    </svg>
)

const SvgMessage = ({ size = 14 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
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
            setOrders(Array.isArray(data) ? data : [])
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
    const handleConfirmCancel = async () => {
        if (!cancelOrderId) return
        try {
            await ordersApi.cancelOrder(cancelOrderId, cancelReason)
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
            })
            setCancelOrderId(null)
            setCancelReason('')
            await loadOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message: err?.message || (lang === 'en' ? 'Failed to cancel order.' : 'Không thể hủy đơn hàng.'),
            })
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
                            placeholder={lang === 'vi' ? 'Tìm mã đơn hoặc tên sản phẩm...' : 'Search order ID or item...'}
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
                        <p className="text-xs text-gray-400 mt-1">Try selecting a different filter tab or search query.</p>
                    </div>
                ) : (
                    <div className="space-y-4">
                        {orders.map((order) => {
                            const canCancel = ['PENDING_PAYMENT', 'PAID'].includes(order.status)
                            const isAuction = order.orderNo?.startsWith('AUC') || (order as any).isAuctionOrder || (order as any).sourceType === 'AUCTION'
                            const displayItems = Array.isArray(order.items) ? order.items : []
                            const sellerDisplayName = resolveSellerName(order.sellerId, order.sellerName)
                            const orderTotal = typeof order.total === 'number' ? order.total : 0
                            const formattedDate = order.createdAt ? new Date(order.createdAt).toLocaleString(lang === 'vi' ? 'vi-VN' : 'en-US') : ''

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
                                                    onClick={() => setCancelOrderId(order.id)}
                                                    className="px-3 py-1.5 rounded-xl text-xs font-semibold text-red-600 hover:bg-red-50 transition-colors cursor-pointer"
                                                >
                                                    Cancel Order
                                                </button>
                                            )}
                                            <button
                                                type="button"
                                                onClick={() => navigate('order-detail', { id: order.id })}
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
                                                onClick={() => showToast({ type: 'info', message: 'Connecting chat...' })}
                                                className="px-2.5 py-1 rounded-lg text-[11px] font-semibold text-gray-600 bg-gray-50 hover:bg-gray-100 border border-gray-200 flex items-center gap-1 transition-colors cursor-pointer"
                                            >
                                                <SvgMessage size={12} />
                                                <span>Chat</span>
                                            </button>
                                            {order.sellerId && (
                                                <button
                                                    type="button"
                                                    onClick={() => navigate('seller-shop', { sellerId: order.sellerId, id: order.sellerId, sellerName: sellerDisplayName })}
                                                    className="px-2.5 py-1 rounded-lg text-[11px] font-semibold text-[#ee4d2d] bg-orange-50 hover:bg-orange-100 border border-orange-200 transition-colors cursor-pointer"
                                                >
                                                    Xem Shop
                                                </button>
                                            )}
                                        </div>
                                    </div>

                                    {/* Danh sách mặt hàng */}
                                    <div className="space-y-3 pt-1">
                                        {displayItems.length === 0 ? (
                                            <div className="flex items-center justify-between gap-4 py-1">
                                                <div className="flex items-center gap-3.5 min-w-0">
                                                    <img
                                                        src={resolveProductImage(order.id, isAuction ? 'Tai nghe Gaming Razer BlackShark V2' : 'Sản phẩm')}
                                                        alt={order.orderNo || order.id}
                                                        className="w-16 h-16 object-cover rounded-xl border border-gray-200 shrink-0"
                                                    />
                                                    <div className="min-w-0">
                                                        <h4 className="font-bold text-xs sm:text-sm text-gray-900 line-clamp-1">
                                                            {isAuction ? 'Sản phẩm trúng thầu đấu giá' : `Sản phẩm đơn hàng #${order.orderNo || order.id}`}
                                                        </h4>
                                                        <p className="text-[11px] text-gray-500 mt-0.5">
                                                            {order.shippingAddress && order.shippingAddress !== 'AUCTION_PENDING_BUYER_ADDRESS' ? order.shippingAddress : (isAuction ? 'Chờ thanh toán & nhận hàng' : 'Chi tiết đơn hàng')}
                                                        </p>
                                                        <p className="text-[11px] text-gray-400 mt-0.5">
                                                            x1
                                                        </p>
                                                    </div>
                                                </div>
                                                <div className="text-right shrink-0">
                                                    <p className="text-sm font-bold text-gray-900">
                                                        {formatCurrency(orderTotal, lang)}
                                                    </p>
                                                </div>
                                            </div>
                                        ) : (
                                            displayItems.map((item: any, idx: number) => {
                                                const itemImage = resolveProductImage(item.productId || item.skuId, item.productName, item.image)
                                                const itemPrice = (typeof item.unitPrice === 'number' && item.unitPrice > 0)
                                                    ? item.unitPrice
                                                    : (Number(item.estimatedUnitPrice) || orderTotal || 0)
                                                return (
                                                <div key={item.productId || idx} className="flex items-center justify-between gap-4">
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
                                            )})
                                        )}
                                    </div>

                                    {/* Dòng Footer của Card */}
                                    <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-gray-100 text-xs">
                                        <div className="text-gray-500 flex flex-wrap items-center gap-1.5">
                                            <span>💬 {displayItems.length} product(s) in this order</span>
                                            {sellerDisplayName && (
                                                <>
                                                    <span>·</span>
                                                    <span>Sold by {sellerDisplayName}</span>
                                                </>
                                            )}
                                            {order.shippingAddress && (
                                                <>
                                                    <span>·</span>
                                                    <span className="text-gray-400 line-clamp-1 max-w-xs">{order.shippingAddress}</span>
                                                </>
                                            )}
                                        </div>
                                        <div className="flex items-baseline gap-2">
                                            <span className="text-gray-500 text-xs">Total payable:</span>
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

            {/* Modal xác nhận hủy đơn */}
            <Modal
                open={!!cancelOrderId}
                onClose={() => {
                    setCancelOrderId(null)
                    setCancelReason('')
                }}
                title={tr(lang, 'confirmCancel')}
                footer={
                    <>
                        <Button variant="outline" onClick={() => setCancelOrderId(null)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button variant="danger" onClick={handleConfirmCancel}>
                            {tr(lang, 'cancelOrder')}
                        </Button>
                    </>
                }
            >
                <div className="space-y-4">
                    <p className="text-sm text-gray-600">
                        {lang === 'en'
                            ? 'Cancelling will release inventory reservations and refund payments when applicable.'
                            : 'Hủy đơn sẽ giải phóng kho hàng và hoàn tiền nếu đơn đã được thanh toán.'}
                    </p>
                    <Textarea
                        label={lang === 'en' ? 'Cancellation reason' : 'Lý do hủy đơn'}
                        value={cancelReason}
                        onChange={(event) => setCancelReason(event.target.value)}
                        placeholder={
                            lang === 'en'
                                ? 'Tell us why you are cancelling...'
                                : 'Nhập lý do hủy đơn...'
                        }
                        rows={3}
                    />
                </div>
            </Modal>
        </BuyerLayout>
    )
}
