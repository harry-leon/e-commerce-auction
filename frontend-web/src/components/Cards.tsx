import { useState } from 'react'
import { addGuestCartItem } from '../features/buyer/guestCart'
import { useApp } from '../context'
import { tr, formatCurrency } from '../i18n'
import {
    CountdownTimer,
    StarRating,
    StatusBadge,
    Gavel,
    ShoppingCart,
    CheckCircle,
    Clock,
} from './UI'
import type { Product, Auction } from '../types'
import { formatSkuDisplay, readSession } from '../api/client'
import { addItemToCart } from '../api/cart'

// ─── Product Card ──────────────────────────────────────────────────────────────
export function ProductCard({ product }: { product: Product }) {
    const { lang, navigate, setCartCount, showToast } = useApp()
    const [isAdding, setIsAdding] = useState(false)
    const [addedSuccess, setAddedSuccess] = useState(false)

    // ProductCard là lớp bảo vệ cuối: không tạo thẻ cho sản phẩm hết hàng hoặc chưa xác nhận tồn kho.
    if (product.stock <= 0) return null
    const hasDiscount = Boolean(product.originalPrice && product.originalPrice > product.price)
    const discount = hasDiscount
        ? Math.round((1 - product.price / product.originalPrice!) * 100)
        : 0
    /**
     * Chức năng: Thêm nhanh sản phẩm từ thẻ card vào giỏ hàng mà không cần mở trang chi tiết.
     * Đầu vào và kết quả: Sự kiện click `e`; lưu sản phẩm vào giỏ hàng backend (nếu đã đăng nhập) hoặc guestCart (nếu chưa).
     * Luồng xử lý: Xác định skuId từ product.skuId hoặc biến thể skus[0].id; nếu có session gọi addItemToCart; khách chưa đăng nhập lưu guestCart.
     * Quy tắc: Ưu tiên SKU hợp lệ; nếu sản phẩm có nhiều biến thể mà chưa chọn, điều hướng vào chi tiết sản phẩm.
     * Lỗi và giới hạn: Bắt lỗi nếu API backend từ chối hoặc hết hàng; không ghi đè giỏ local khi lỗi POST có session.
     */
    const addToCart = async (e: React.MouseEvent) => {
        e.stopPropagation()
        setIsAdding(true)
        const session = readSession()
        const rawSkus = (product as any).skus
        const skuId =
            product.skuId || (Array.isArray(rawSkus) && rawSkus[0]?.id ? rawSkus[0].id : product.id)
        if (session && skuId) {
            try {
                const res = await addItemToCart(skuId, 1)
                setCartCount(res.totalQuantity)
                setAddedSuccess(true)
                setTimeout(() => setAddedSuccess(false), 1500)
                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? `"${product.name}" added to cart!`
                            : `Đã thêm "${product.nameVi || product.name}" vào giỏ hàng!`,
                })
                return
            } catch (error) {
                showToast({
                    type: 'error',
                    message:
                        lang === 'vi'
                            ? `Không thể xác nhận thêm vào giỏ. Vui lòng kiểm tra lại giỏ hàng: ${error instanceof Error ? error.message : String(error)}`
                            : 'Could not confirm adding the item. Please check your cart.',
                })
                return
            } finally {
                setIsAdding(false)
            }
        }
        const guestCart = addGuestCartItem({
            skuId,
            skuCode: product.sku || formatSkuDisplay(skuId),
            productName: lang === 'en' ? product.name : product.nameVi || product.name,
            thumbnailUrl: product.images?.[0],
            unitPrice: product.price,
            quantity: 1,
        })
        setCartCount(guestCart.totalQuantity)
        setAddedSuccess(true)
        setTimeout(() => setAddedSuccess(false), 1500)
        setIsAdding(false)
        showToast({
            type: 'success',
            message:
                lang === 'en'
                    ? `"${product.name}" added to cart!`
                    : `Đã thêm "${product.nameVi || product.name}" vào giỏ hàng!`,
        })
    }
    return (
        <div
            role={product.id === 'product-timeout' ? undefined : 'button'}
            tabIndex={product.id === 'product-timeout' ? undefined : 0}
            className="nexus-card bg-white rounded-2xl border border-gray-100 overflow-hidden cursor-pointer group transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:border-primary/20 flex flex-col justify-between"
            onClick={() => navigate('product-detail', { id: product.id })}
            onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    navigate('product-detail', { id: product.id })
                }
            }}
        >
            <div className="relative overflow-hidden bg-gray-50 h-52 flex items-center justify-center">
                <img
                    src={product.images[0]}
                    alt={product.name}
                    className="w-full h-full object-cover group-hover:scale-108 transition-transform duration-500 ease-out"
                    loading="lazy"
                />
                {discount > 0 && (
                    <span className="absolute top-2.5 left-2.5 badge bg-gradient-to-r from-red-600 to-rose-500 text-white font-bold shadow-md rounded-full px-2.5 py-0.5 text-xs">
                        -{discount}%
                    </span>
                )}
                {product.stock <= 3 && product.stock > 0 && (
                    <span className="absolute top-2.5 right-2.5 badge bg-amber-500/95 backdrop-blur-sm text-white font-medium shadow-md rounded-full px-2.5 py-0.5 text-xs">
                        {lang === 'en' ? 'Only ' + product.stock + ' left' : 'Còn ' + product.stock}
                    </span>
                )}
            </div>
            <div className="p-4 flex-1 flex flex-col justify-between">
                <div>
                    <p className="text-xs font-medium text-gray-400 mb-1 tracking-wide uppercase">
                        {product.category}
                    </p>
                    <h3 className="font-semibold text-sm text-gray-900 leading-snug line-clamp-2 mb-2 group-hover:text-primary transition-colors duration-200">
                        {lang === 'vi' ? product.nameVi : product.name}
                    </h3>
                    <div className="flex items-center gap-1.5 mb-3">
                        <StarRating rating={product.rating} size={12} />
                        <span className="text-xs text-gray-500 font-medium">
                            ({product.reviewCount})
                        </span>
                    </div>
                </div>
                <div className="flex items-center justify-between pt-2 border-t border-gray-50">
                    <div>
                        <span className="text-lg font-bold text-primary">
                            {formatCurrency(product.price, lang)}
                        </span>
                        {hasDiscount && product.originalPrice && (
                            <span className="text-xs text-gray-400 line-through ml-2">
                                {formatCurrency(product.originalPrice, lang)}
                            </span>
                        )}
                    </div>
                    {product.stock > 0 && (
                        <button
                            onClick={addToCart}
                            disabled={isAdding}
                            className={`nexus-btn nexus-btn-sm p-2.5 rounded-xl transition-all duration-200 active:scale-95 shadow-sm ${
                                addedSuccess
                                    ? 'bg-green-600 text-white border-green-600'
                                    : 'nexus-btn-secondary hover:bg-primary hover:text-white hover:border-primary'
                            }`}
                            aria-label="Add to cart"
                        >
                            {addedSuccess ? (
                                <CheckCircle size={15} />
                            ) : isAdding ? (
                                <div
                                    className="spinner-border spinner-border-sm text-primary"
                                    style={{ width: 14, height: 14 }}
                                />
                            ) : (
                                <ShoppingCart size={15} />
                            )}
                        </button>
                    )}
                </div>
            </div>
        </div>
    )
}

// ─── Auction Card ──────────────────────────────────────────────────────────────
export function AuctionCard({ auction }: { auction: Auction }) {
    // Nhãn trạng thái phải bao phủ các giá trị AuctionStatus, kể cả pending/rejected; không chỉ các phiên đã kết thúc.
    const { lang, navigate } = useApp()
    const isActive = auction.status === 'active'
    return (
        <div
            className="nexus-card bg-white rounded-2xl border border-gray-100 overflow-hidden cursor-pointer group transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:border-auction/30 flex flex-col justify-between"
            onClick={() => navigate('auction-detail', { id: auction.id })}
        >
            <div className="relative overflow-hidden bg-gray-50 h-52 flex items-center justify-center">
                <img
                    src={auction.product.images[0]}
                    alt={auction.product.name}
                    className="w-full h-full object-cover group-hover:scale-108 transition-transform duration-500 ease-out"
                    loading="lazy"
                />
                <div className="absolute top-2.5 left-2.5">
                    {isActive ? (
                        <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-white/95 text-red-600 border border-red-200 shadow-md backdrop-blur-md">
                            <span className="live-indicator" />
                            {lang === 'en' ? 'LIVE NOW' : 'ĐANG ĐẤU GIÁ'}
                        </span>
                    ) : auction.status === 'scheduled' ? (
                        <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-semibold bg-white/95 text-blue-600 border border-blue-200 shadow-md backdrop-blur-md">
                            <Clock size={12} />
                            {lang === 'en' ? 'Upcoming' : 'Sắp diễn ra'}
                        </span>
                    ) : (
                        <StatusBadge
                            status={auction.status}
                            label={
                                lang === 'en'
                                    ? auction.status.charAt(0).toUpperCase() +
                                      auction.status.slice(1)
                                    : {
                                          active: 'Live',
                                          paused: 'Paused',
                                          pending_approval: 'Pending approval',
                                          rejected: 'Rejected',
                                          ended: 'Đã kết thúc',
                                          settled: 'Đã thanh toán',
                                          cancelled: 'Đã hủy',
                                          failed: 'Thất bại',
                                          scheduled: 'Đã lên lịch',
                                      }[auction.status] || auction.status
                            }
                        />
                    )}
                </div>
                {auction.bidCount > 0 && (
                    <div className="absolute bottom-2.5 right-2.5 bg-black/65 backdrop-blur-md text-white text-xs px-2.5 py-1 rounded-full font-medium shadow-sm">
                        {auction.bidCount} {lang === 'en' ? 'bids' : 'lượt đặt'}
                    </div>
                )}
            </div>
            <div className="p-4 flex-1 flex flex-col justify-between">
                <div>
                    <h3 className="font-semibold text-sm text-gray-900 line-clamp-2 mb-3 group-hover:text-auction transition-colors duration-200">
                        {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                    </h3>
                    <div className="flex items-center justify-between mb-3 bg-gray-50/80 p-2.5 rounded-xl border border-gray-100/60">
                        <div>
                            <p className="text-xs text-gray-500 font-medium">
                                {tr(lang, 'currentBid')}
                            </p>
                            <p className="text-xl font-bold text-auction">
                                {formatCurrency(auction.currentBid, lang)}
                            </p>
                        </div>
                        {isActive && (
                            <div className="text-right">
                                <p className="text-xs text-gray-500 font-medium mb-1">
                                    {tr(lang, 'timeLeft')}
                                </p>
                                <CountdownTimer endTime={auction.endTime} />
                            </div>
                        )}
                    </div>
                </div>
                <div className="flex items-center justify-between text-xs text-gray-400 pt-1">
                    <button
                        type="button"
                        className="truncate max-w-[130px] text-left hover:text-primary transition-colors cursor-pointer group/seller flex items-center gap-0.5"
                        title={lang === 'en' ? 'View seller profile' : 'Xem hồ sơ người bán'}
                        onClick={(e) => {
                            e.stopPropagation()
                            navigate('public-reputation', { id: auction.sellerId || 'seller-1' })
                        }}
                    >
                        <span>{lang === 'en' ? 'By' : 'Bởi'}</span>
                        <strong className="text-gray-600 font-medium group-hover/seller:text-primary group-hover/seller:underline ml-1">
                            {auction.sellerName}
                        </strong>
                        <span className="text-[10px] opacity-0 group-hover/seller:opacity-100 transition-opacity">
                            ↗
                        </span>
                    </button>
                    <button
                        className="nexus-btn nexus-btn-auction nexus-btn-sm rounded-xl shadow-sm hover:shadow-md transition-all duration-200 active:scale-95"
                        onClick={(e) => {
                            e.stopPropagation()
                            navigate('auction-detail', { id: auction.id })
                        }}
                    >
                        <Gavel size={13} />
                        {tr(lang, 'placeBid')}
                    </button>
                </div>
            </div>
        </div>
    )
}

// ─── Category Chip ─────────────────────────────────────────────────────────────
export function CategoryChip({
    cat,
    active,
    onClick,
}: {
    cat: { name: string; icon: string; id: string }
    active?: boolean
    onClick: () => void
}) {
    return (
        <button
            onClick={onClick}
            className={`filter-chip inline-flex items-center gap-2 px-4 py-2 rounded-xl border text-sm font-medium transition-all duration-200 active:scale-95 ${
                active
                    ? 'border-primary bg-primary-light text-primary font-semibold shadow-sm -translate-y-0.5'
                    : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300 hover:bg-gray-50'
            }`}
        >
            <span className="text-base">{cat.icon}</span>
            <span>{cat.name}</span>
        </button>
    )
}
