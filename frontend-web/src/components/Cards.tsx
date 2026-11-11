import { addGuestCartItem } from '../features/buyer/guestCart'
import { useApp } from '../context'
import { tr, formatCurrency } from '../i18n'
import { CountdownTimer, StarRating, StatusBadge, Gavel, ShoppingCart } from './UI'
import type { Product, Auction } from '../types'
import { formatSkuDisplay, readSession } from '../api/client'
import { addItemToCart } from '../api/cart'

// ─── Product Card ──────────────────────────────────────────────────────────────
export function ProductCard({ product }: { product: Product }) {
    const { lang, navigate, setCartCount, showToast } = useApp()
    const discount = product.originalPrice
        ? Math.round((1 - product.price / product.originalPrice) * 100)
        : 0
    /**
     * Chức năng: Thêm nhanh sản phẩm từ thẻ card vào giỏ hàng mà không cần mở trang chi tiết.
     * Đầu vào và kết quả: Sự kiện click `e`; lưu sản phẩm vào giỏ hàng backend (nếu đã đăng nhập) hoặc guestCart (nếu chưa).
     * Luồng xử lý: Chặn nổi bọt sự kiện -> nếu có session gọi addItemToCart -> nếu lỗi hoặc chưa đăng nhập thì lưu guestCart.
     * Quy tắc và lý do: Giữ giỏ hàng đồng bộ cho cả người dùng đăng nhập lẫn khách vãng lai.
     */
    const addToCart = async (e: React.MouseEvent) => {
        e.stopPropagation()
        const session = readSession()
        const skuId = product.skuId || product.id
        if (session && skuId) {
            try {
                const res = await addItemToCart(skuId, 1)
                setCartCount(res.totalQuantity)
                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? `"${product.name}" added to cart!`
                            : `Đã thêm "${product.nameVi || product.name}" vào giỏ hàng!`,
                })
                return
            } catch {
                // Fallback xuống giỏ hàng cục bộ nếu API backend tạm thời lỗi
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
            className="nexus-card overflow-hidden cursor-pointer group transition-colors hover:border-gray-300"
            onClick={() => navigate('product-detail', { id: product.id })}
        >
            <div className="relative overflow-hidden bg-gray-100 h-48">
                <img
                    src={product.images[0]}
                    alt={product.name}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    loading="lazy"
                />
                {discount > 0 && (
                    <span className="absolute top-2 left-2 badge badge-red">-{discount}%</span>
                )}
                {product.stock <= 3 && product.stock > 0 && (
                    <span className="absolute top-2 right-2 badge badge-yellow">
                        {lang === 'en' ? 'Only ' + product.stock + ' left' : 'Còn ' + product.stock}
                    </span>
                )}
                {product.stock === 0 && (
                    <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                        <span className="badge badge-gray text-base px-4 py-2">Out of Stock</span>
                    </div>
                )}
            </div>
            <div className="p-4">
                <p className="text-xs text-gray-400 mb-1">{product.category}</p>
                <h3 className="font-semibold text-sm text-gray-900 leading-snug line-clamp-2 mb-2">
                    {lang === 'vi' ? product.nameVi : product.name}
                </h3>
                <div className="flex items-center gap-1.5 mb-3">
                    <StarRating rating={product.rating} size={12} />
                    <span className="text-xs text-gray-500">({product.reviewCount})</span>
                </div>
                <div className="flex items-center justify-between">
                    <div>
                        <span className="text-lg font-bold text-primary">
                            {formatCurrency(product.price, lang)}
                        </span>
                        {product.originalPrice && (
                            <span className="text-xs text-gray-400 line-through ml-2">
                                {formatCurrency(product.originalPrice, lang)}
                            </span>
                        )}
                    </div>
                    {product.stock > 0 && (
                        <button
                            onClick={addToCart}
                            className="nexus-btn nexus-btn-secondary nexus-btn-sm p-2"
                        >
                            <ShoppingCart size={14} />
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
            className="nexus-card overflow-hidden cursor-pointer group transition-colors hover:border-gray-300"
            onClick={() => navigate('auction-detail', { id: auction.id })}
        >
            <div className="relative overflow-hidden bg-gray-100 h-48">
                <img
                    src={auction.product.images[0]}
                    alt={auction.product.name}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    loading="lazy"
                />
                <div className="absolute top-2 left-2">
                    {isActive ? (
                        <span className="badge badge-orange auction-live">
                            {lang === 'en' ? 'LIVE' : 'TRỰC TIẾP'}
                        </span>
                    ) : auction.status === 'scheduled' ? (
                        <span className="badge badge-blue">
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
                    <div className="absolute bottom-2 right-2 bg-black/60 text-white text-xs px-2 py-1 rounded-lg">
                        {auction.bidCount} {lang === 'en' ? 'bids' : 'lượt đặt'}
                    </div>
                )}
            </div>
            <div className="p-4">
                <h3 className="font-semibold text-sm text-gray-900 line-clamp-2 mb-3">
                    {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                </h3>
                <div className="flex items-center justify-between mb-3">
                    <div>
                        <p className="text-xs text-gray-500">{tr(lang, 'currentBid')}</p>
                        <p className="text-xl font-bold text-auction">
                            {formatCurrency(auction.currentBid, lang)}
                        </p>
                    </div>
                    {isActive && (
                        <div className="text-right">
                            <p className="text-xs text-gray-500 mb-1">{tr(lang, 'timeLeft')}</p>
                            <CountdownTimer endTime={auction.endTime} />
                        </div>
                    )}
                </div>
                <div className="flex items-center justify-between text-xs text-gray-400">
                    <span>
                        {lang === 'en' ? 'By' : 'Bởi'} {auction.sellerName}
                    </span>
                    <button
                        className="nexus-btn nexus-btn-auction nexus-btn-sm"
                        onClick={(e) => {
                            e.stopPropagation()
                            navigate('auction-detail', { id: auction.id })
                        }}
                    >
                        <Gavel size={12} />
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
        <button onClick={onClick} className={`filter-chip ${active ? 'active' : ''}`}>
            <span>{cat.icon}</span>
            <span>{cat.name}</span>
        </button>
    )
}
