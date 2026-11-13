/** Các màn Buyer đang được router dùng; Catalog và Profile có module riêng. Shop/auction demo được giữ cho sprint tiếp theo. */
import { useEffect, useState, useCallback, useRef, useMemo } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
import { BuyerLayout } from '../../components/Layout'
import { ProductCard } from '../../components/Cards'
import { AuctionCard } from '../../components/Cards'
import {
    Button,
    Input,
    NumberStepper,
    ConfirmModal,
    AlertBanner,
    EmptyState,
    ProgressBar,
    AlertTriangle,
    CheckCircle,
    Truck,
    Gavel,
    Package,
    ShoppingCart,
    CreditCard,
    Shield,
    Spinner,
    Trash2,
    Clock,
} from '../../components/UI'
import { OrderDetailView } from './OrderDetailView'
import { OrderHistoryView } from './OrderHistoryView'
import { SellerShopView } from './SellerShopView'
import { CATEGORY_ICONS } from '../../components/Layout'
import { calculateEstimatedPricing } from '../../utils/pricingEngine'
import { getGuestCart, updateGuestCartItem, removeGuestCartItem, clearGuestCart } from './guestCart'
import { VnpayPaymentResult } from './VnpayPaymentResult'
import { syncGuestCart } from './syncGuestCart'
import { purchaseAvailabilityMessage } from './purchaseAvailability'
import { VerifiedOrderResult } from './VerifiedOrderResult'
import { createPaymentAttempt, redirectToGateway } from '../../api/payments'
import {
    readCheckoutCommand,
    recoverOrCreateOrder,
    checkoutAttemptKey,
    clearCheckoutCommand,
} from './checkoutRecovery'
import type { Auction, Product } from '../../types'
import {
    ordersApi,
    type CreateOrderRequest,
    type CreateOrderResponse,
    type CheckoutPreviewResponse,
    type CheckoutOrderSnapshot,
    readLocalMockOrders,
    writeLocalMockOrders,
} from '../../api/orders'
import {
    shippingApi,
    type ShippingQuoteResponse,
    type ShippingQuoteOption,
} from '../../api/shipping'
import { auctionsApi } from '../../api/auctions'
import { useLoad } from '../../hooks/useLoad'
import {
    formatSkuDisplay,
    readSession,
    saveSession,
    ApiClientError,
    errorMessage,
    type ApiCategory,
    type ApiProduct,
    type Envelope,
} from '../../api/client'
import {
    getActiveCart,
    updateCartItemQuantity,
    removeCartItem,
    clearCart,
    type CartResponse,
    type CartItemResponse,
} from '../../api/cart'
import { getBatchStock } from '../../api/inventory'

function getAuctionCurrentBid(auction: unknown) {
    if (!auction || typeof auction !== 'object') return 0
    const value = Reflect.get(auction, 'currentBid')
    return typeof value === 'number' ? value : 0
}

/** Dẹt cây category từ API để sản phẩm gắn ở node lá vẫn tìm được đúng tên song ngữ. */
function flattenLiveCategories(categories: ApiCategory[]): ApiCategory[] {
    return categories.flatMap((category) => [
        category,
        ...flattenLiveCategories(category.children || []),
    ])
}

// ─── Home Page ─────────────────────────────────────────────────────────────────
export function HomePage() {
    const { lang, navigate, auctionReviews, createdAuctions } = useApp()
    const [homeAuctions, setHomeAuctions] = useState<Auction[]>([])
    const [loadingAuctions, setLoadingAuctions] = useState(true)
    const [heroIndex, setHeroIndex] = useState(0)
    const heroAuction =
        homeAuctions.length > 0 ? homeAuctions[heroIndex % homeAuctions.length] : null

    /**
     * Lấy danh sách từ Auction API giống trang danh sách, cộng phiên vừa tạo trong context nếu API chưa đồng bộ.
     * Chỉ giữ phiên đang diễn ra hoặc sắp diễn ra; khi API lỗi thì hiển thị empty state, không chèn fixture.
     */
    useEffect(() => {
        let mounted = true
        let pollTimer: ReturnType<typeof setTimeout> | undefined
        let failureCount = 0
        setLoadingAuctions(true)
        const refreshAuctions = () => {
            auctionsApi
                .getAuctions({
                    status: 'all',
                    sortBy: 'ending-soon',
                    limit: 8,
                    extraAuctions: createdAuctions,
                    reviews: auctionReviews,
                })
                .then((response) => {
                    if (!mounted) return
                    failureCount = 0
                    setHomeAuctions(
                        response.items.filter(
                            (auction) =>
                                ['active', 'scheduled'].includes(auction.status) &&
                                auction.product?.images?.[0],
                        ),
                    )
                    // Thành công: lên lịch làm mới sau 5 giây theo chu kỳ scheduler
                    pollTimer = setTimeout(refreshAuctions, 5000)
                })
                .catch(() => {
                    if (!mounted) return
                    failureCount++
                    setHomeAuctions([])
                    // Khi API lỗi hoặc backend offline: backoff (15s, 30s, tối đa 60s) để tránh spam request liên tục làm nghẽn console
                    const backoffMs = Math.min(15000 * failureCount, 60000)
                    pollTimer = setTimeout(refreshAuctions, backoffMs)
                })
                .finally(() => {
                    if (mounted) setLoadingAuctions(false)
                })
        }
        refreshAuctions()
        return () => {
            mounted = false
            clearTimeout(pollTimer)
        }
    }, [auctionReviews, createdAuctions])

    // Tải danh mục thật từ Catalog Service backend (/api/v1/categories) với SWR cache
    const { data: catData, loading: catLoading } = useLoad<Envelope<ApiCategory[]>>('/categories')
    // Tải danh sách sản phẩm thật từ Catalog Service backend (/api/v1/products) với SWR cache
    const { data: prodData, loading: prodLoading } = useLoad<Envelope<ApiProduct[]>>('/products')
    const [liveStockBySku, setLiveStockBySku] = useState<Record<string, number>>({})

    // Buyer cards dùng cùng nguồn Fulfillment với trang chi tiết; SKU chưa có bản ghi kho được xem là 0.
    useEffect(() => {
        let active = true
        const skuIds = (prodData?.data || []).flatMap((product) =>
            (product.skus || []).map((sku) => sku.id).filter(Boolean),
        )
        if (skuIds.length === 0) {
            setLiveStockBySku({})
            return () => { active = false }
        }
        getBatchStock({ skuIds })
            .then((stocks) => {
                if (!active) return
                setLiveStockBySku(Object.fromEntries(stocks.map((stock) => [stock.skuId, stock.availableQty])))
            })
            .catch(() => {
                if (active) setLiveStockBySku({})
            })
        return () => { active = false }
    }, [prodData])

    /**
     * Chức năng: Ánh xạ danh mục trực tiếp từ Catalog backend kết nối Supabase; không dùng CATEGORIES mockData.
     */
    const categoryTree = catData?.data || []
    const allLiveCategories = flattenLiveCategories(categoryTree)
    const liveCategories = categoryTree.map((c) => ({
        id: c.id,
        name: c.name,
        nameVi: c.nameVi || c.name,
        productCount: c.productCount ?? 0,
    }))

    /**
     * Chức năng: Ánh xạ sản phẩm trực tiếp từ Catalog backend (/api/v1/products); không dùng PRODUCTS mockData.
     * Đầu vào: Dữ liệu thực tế từ Supabase qua prodData.
     * Kết quả: Danh sách sản phẩm với giá, SKU và ảnh từ database.
     */
    const liveProducts: Product[] = (prodData?.data || []).map((p) => {
        const sku = p.skus?.[0]
        const categoryObj = allLiveCategories.find((c) => c.id === p.categoryId)
        const categoryName = categoryObj
            ? lang === 'vi'
                ? categoryObj.nameVi || categoryObj.name
                : categoryObj.name
            : lang === 'vi'
              ? 'Chưa phân loại'
              : 'Uncategorized'
        return {
            id: p.id,
            name: p.name,
            nameVi: p.name,
            category: categoryName,
            categoryId: p.categoryId,
            price: sku?.price || 0,
            originalPrice: sku?.originalPrice,
            sku: formatSkuDisplay(sku?.id, sku?.skuCode),
            skuId: sku?.id,
            images:
                p.images && p.images.length > 0
                    ? p.images.map((img) => img.url)
                    : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
            description: p.description || '',
            descriptionVi: p.description || '',
            enableAuction: p.auctionEnabled || false,
            stock: liveStockBySku[sku?.id || ''] ?? 0,
            rating: 5.0,
            reviewCount: 0,
            condition: 'new' as const,
            location: 'ho-chi-minh',
            createdAt: p.createdAt || new Date().toISOString(),
            status: (p.status || 'active').toLowerCase() as any,
            sellerId: (p as any).sellerId || 'seller-nexus-1',
            sellerName: 'Nexus Official Store',
            sellerTrust: 'gold' as const,
            sellerRating: 4.9,
            weight: 500,
        }
    })

    useEffect(() => {
        setHeroIndex((index) => (homeAuctions.length > 0 ? index % homeAuctions.length : 0))
        if (homeAuctions.length < 2) return
        const timer = window.setInterval(() => {
            setHeroIndex((index) => (index + 1) % homeAuctions.length)
        }, 5000)
        return () => window.clearInterval(timer)
    }, [homeAuctions.length])

    return (
        <BuyerLayout noPad>
            {/* Hero Banner - Thể hiện dữ liệu thực tế từ Database */}
            <section className="relative min-h-[500px] overflow-hidden bg-gray-950 text-white">
                {homeAuctions.length > 0 ? (
                    homeAuctions.map((auction, index) => (
                        <img
                            key={auction.id}
                            src={auction.product.images[0]
                                .replace(/w=\d+/, 'w=1800')
                                .replace(/h=\d+/, 'h=720')}
                            alt=""
                            aria-hidden={index !== heroIndex}
                            className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-1000 ease-in-out ${index === heroIndex ? 'opacity-100' : 'opacity-0'}`}
                        />
                    ))
                ) : (
                    <img
                        src="https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=1800&h=720&fit=crop&auto=format"
                        alt="Nexus Marketplace"
                        className="absolute inset-0 h-full w-full object-cover opacity-85"
                    />
                )}
                <div className="absolute inset-0 bg-black/60" />

                <div className="relative z-10 max-w-[1440px] min-h-[500px] mx-auto px-8 py-14 flex items-center">
                    <div className="max-w-2xl">
                        <div className="inline-flex items-center gap-2 border border-white/30 rounded-full px-4 py-1.5 text-sm font-semibold mb-5 bg-white/10 backdrop-blur-sm">
                            <span className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                            {/* Giới thiệu ngắn giá trị của Nexus, không gắn badge hero với số lượng dữ liệu backend. */}
                            {tr(lang, 'homeHeroTagline')}
                        </div>
                        <h1 className="text-5xl font-black leading-tight mb-5">
                            {lang === 'en' ? (
                                <>
                                    Buy, Sell &<br />
                                    <span className="text-gray-300">Nexus Marketplace</span>
                                </>
                            ) : (
                                <>
                                    Mua, Bán &<br />
                                    <span className="text-gray-300">Đấu giá chính hãng</span>
                                </>
                            )}
                        </h1>
                        <p className="text-white/80 text-lg mb-8 max-w-md">
                            {tr(lang, 'homeHeroDescription')}
                        </p>
                        <div className="flex gap-3">
                            <Button
                                variant="primary"
                                size="lg"
                                className="bg-white text-gray-900 hover:bg-gray-100 font-semibold"
                                onClick={() => navigate('search')}
                            >
                                {tr(lang, 'shopNow')}
                            </Button>
                            <Button
                                variant="ghost"
                                size="lg"
                                className="border-white/40 text-white hover:bg-white/10 border"
                                onClick={() => navigate('auction-list')}
                            >
                                <Gavel size={16} />
                                {tr(lang, 'auctionNow')}
                            </Button>
                        </div>

                        {heroAuction && (
                            <div className="mt-8 pt-5 border-t border-white/25 flex items-end gap-8 max-w-xl">
                                <div className="min-w-0">
                                    <p className="text-xs uppercase tracking-wider text-white/60 mb-1">
                                        {lang === 'en' ? 'Live now' : 'Đang diễn ra'}
                                    </p>
                                    <p className="font-semibold text-white truncate">
                                        {lang === 'vi'
                                            ? heroAuction.product?.nameVi
                                            : heroAuction.product?.name}
                                    </p>
                                </div>
                                <div className="shrink-0">
                                    <p className="text-xs text-white/60 mb-1">
                                        {tr(lang, 'currentBid')}
                                    </p>
                                    <p className="font-bold text-white">
                                        {formatCurrency(getAuctionCurrentBid(heroAuction), lang)}
                                    </p>
                                </div>
                            </div>
                        )}
                    </div>
                </div>

                {homeAuctions.length > 1 && (
                    <div className="absolute z-20 bottom-7 left-1/2 -translate-x-1/2 flex items-center gap-2">
                        {homeAuctions.map((auction, index) => (
                            <button
                                key={auction.id}
                                type="button"
                                aria-label={
                                    lang === 'en'
                                        ? `Show auction ${index + 1}`
                                        : `Hiển thị phiên đấu giá ${index + 1}`
                                }
                                onClick={() => setHeroIndex(index)}
                                className={`h-1.5 rounded-full transition-all ${index === heroIndex ? 'w-8 bg-white' : 'w-2 bg-white/45 hover:bg-white/70'}`}
                            />
                        ))}
                    </div>
                )}
            </section>

            <div className="max-w-[1440px] mx-auto px-8 py-10 space-y-12">
                {/* Categories */}
                <section>
                    <div className="flex items-center justify-between mb-5">
                        <h2 className="text-2xl font-bold text-gray-900">
                            {lang === 'en' ? 'Shop by Category' : 'Mua theo danh mục'}
                        </h2>
                    </div>
                    {catLoading && liveCategories.length === 0 ? (
                        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-4">
                            {[1, 2, 3, 4, 5, 6].map((i) => (
                                <div
                                    key={i}
                                    className="nexus-card p-5 flex flex-col items-center gap-3 animate-pulse"
                                >
                                    <div className="w-14 h-14 rounded-2xl bg-orange-50/60" />
                                    <div className="h-4 w-20 bg-gray-200 rounded" />
                                    <div className="h-3 w-12 bg-gray-100 rounded" />
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-4">
                            {liveCategories.map((cat) => {
                                const Icon = CATEGORY_ICONS[cat.id]
                                return (
                                    <button
                                        key={cat.id}
                                        onClick={() => navigate('search', { category: cat.id })}
                                        className="nexus-card p-5 flex flex-col items-center gap-3 hover:border-primary hover:shadow-md transition-all group cursor-pointer"
                                    >
                                        {/* Icon danh mục kiểu Shopee: nền cam nhạt bg-orange-50 và hover chuyển sang nền cam đậm */}
                                        <div className="w-14 h-14 rounded-2xl bg-orange-50 text-primary flex items-center justify-center group-hover:scale-105 group-hover:bg-primary group-hover:text-white transition-all shadow-2xs">
                                            {Icon && (
                                                <Icon width="28" height="28" strokeWidth="1.4" />
                                            )}
                                        </div>
                                        <span className="text-sm font-semibold text-gray-700 group-hover:text-primary text-center leading-tight">
                                            {lang === 'vi' ? cat.nameVi : cat.name}
                                        </span>
                                        <span className="text-xs text-gray-400">
                                            {(cat.productCount ?? 0).toLocaleString()}{' '}
                                            {lang === 'en' ? 'items' : 'sản phẩm'}
                                        </span>
                                    </button>
                                )
                            })}
                        </div>
                    )}
                </section>

                {/* Live Auctions */}
                <section aria-labelledby="home-live-auctions-heading">
                    <div className="flex items-center justify-between mb-5">
                        <div className="flex items-center gap-3">
                            <h2
                                id="home-live-auctions-heading"
                                className="text-2xl font-bold text-gray-900"
                            >
                                {tr(lang, 'liveAuctions')}
                            </h2>
                            {homeAuctions.some((auction) => auction.status === 'active') && (
                                <span className="badge badge-orange auction-live text-sm px-3 py-1">
                                    {lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}
                                </span>
                            )}
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => navigate('auction-list')}
                        >
                            {tr(lang, 'viewAll')} →
                        </Button>
                    </div>
                    {loadingAuctions ? (
                        <div
                            className="nexus-card py-10 text-center text-sm text-gray-500"
                            role="status"
                        >
                            <div className="flex justify-center mb-2">
                                <div
                                    className="spinner-border text-primary"
                                    style={{ width: '2rem', height: '2rem', color: '#ee4d2d' }}
                                    role="status"
                                >
                                    <span className="visually-hidden">Loading...</span>
                                </div>
                            </div>
                            {tr(lang, 'loadingAuctions')}
                        </div>
                    ) : homeAuctions.length > 0 ? (
                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
                            {homeAuctions.map((auction) => (
                                <AuctionCard key={auction.id} auction={auction} />
                            ))}
                        </div>
                    ) : (
                        <p className="nexus-card px-5 py-8 text-center text-gray-500">
                            {tr(lang, 'liveAuctionsEmpty')}
                        </p>
                    )}
                </section>

                {/* Popular Products - Danh sách sản phẩm thực tế từ Database */}
                <section>
                    <div className="flex items-center justify-between mb-5">
                        <h2 className="text-2xl font-bold text-gray-900">
                            {tr(lang, 'popularProducts')}
                        </h2>
                        <Button variant="outline" size="sm" onClick={() => navigate('search')}>
                            {tr(lang, 'viewAll')} →
                        </Button>
                    </div>
                    {prodLoading && liveProducts.length === 0 ? (
                        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-5">
                            {[1, 2, 3, 4].map((i) => (
                                <div
                                    key={i}
                                    className="nexus-card p-4 flex flex-col gap-3 animate-pulse"
                                >
                                    <div className="w-full h-48 bg-gray-100 rounded-xl" />
                                    <div className="h-4 w-3/4 bg-gray-200 rounded" />
                                    <div className="h-4 w-1/2 bg-gray-100 rounded" />
                                    <div className="h-6 w-1/3 bg-gray-200 rounded mt-2" />
                                </div>
                            ))}
                        </div>
                    ) : liveProducts.length > 0 ? (
                        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-5">
                            {liveProducts.map((p) => (
                                <ProductCard key={p.id} product={p} />
                            ))}
                        </div>
                    ) : (
                        <p className="text-gray-500 py-8 text-center">
                            {lang === 'en'
                                ? 'No products available.'
                                : 'Chưa có sản phẩm nào trong cơ sở dữ liệu.'}
                        </p>
                    )}
                </section>

                {/* New Arrivals - Hiển thị các sản phẩm mới nhất từ Database */}
                {liveProducts.length >= 3 && (
                    <section>
                        <div className="flex items-center justify-between mb-5">
                            <h2 className="text-2xl font-bold text-gray-900">
                                {tr(lang, 'newArrivals')}
                            </h2>
                            <Button variant="outline" size="sm" onClick={() => navigate('search')}>
                                {tr(lang, 'viewAll')} →
                            </Button>
                        </div>
                        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-5">
                            {[...liveProducts].reverse().map((p) => (
                                <ProductCard key={p.id} product={p} />
                            ))}
                        </div>
                    </section>
                )}
            </div>
        </BuyerLayout>
    )
}

// ─── Cart Page ─────────────────────────────────────────────────────────────────
/**
 * Trang giỏ hàng của Buyer:
 * 1. Chức năng:
 *    - Lấy và xem danh sách sản phẩm trong giỏ hàng từ Backend qua GET /api/v1/cart.
 *    - Cập nhật số lượng sản phẩm (PUT /api/v1/cart/items/{itemId}).
 *    - Xóa sản phẩm khỏi giỏ hàng (DELETE /api/v1/cart/items/{itemId}) hoặc xóa toàn bộ (DELETE /api/v1/cart/items).
 *    - Hiển thị bảng tổng tiền và chi phí tạm tính, phí ship, thuế dựa trên dữ liệu API trả về.
 *    - Áp dụng mã giảm giá voucher (client/backend).
 *    - Xử lý các trạng thái: Đang tải (loading), Giỏ hàng trống (empty), và Lỗi API (error).
 * 2. Đầu vào và kết quả:
 *    - Nhận dữ liệu giỏ hàng CartResponse từ Commerce Service.
 *    - Cập nhật lại cartCount toàn cục trong AppContext khi số lượng thay đổi.
 * 3. Luồng xử lý:
 *    - Mount: loadCart() gọi getActiveCart() -> lưu vào state cart.
 *    - Tăng/giảm số lượng: gọi updateCartItemQuantity() -> cập nhật cart state.
 *    - Xóa sản phẩm: gọi removeCartItem() -> cập nhật cart state.
 * 4. Quy tắc và lý do:
 *    - Khi số lượng giảm xuống 0, tự động chuyển sang luồng xóa sản phẩm (removeCartItem).
 *    - Hỗ trợ fallback giỏ hàng demo khi chưa đăng nhập hoặc khi server offline để không gián đoạn giao diện.
 * 5. Lỗi và giới hạn:
 *    - Bắt lỗi mạng hoặc lỗi API 401/500 qua AlertBanner và cung cấp nút "Thử lại".
 */
export function CartPage() {
    const { lang, navigate, setCartCount, showToast } = useApp()
    const [cart, setCart] = useState<CartResponse | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [actionLoadingId, setActionLoadingId] = useState<string | null>(null)
    const [voucher, setVoucher] = useState('')
    const [appliedVoucher, setAppliedVoucher] = useState<string | null>(null)
    const [voucherError, setVoucherError] = useState('')
    const [showClearConfirm, setShowClearConfirm] = useState(false)
    const [checkingCheckout, setCheckingCheckout] = useState(false)

    /** Chặn hết hàng ngay tại giỏ trước điều hướng; không cho lỗi kết nối vượt qua bước kiểm tra kho. */
    const beginCheckout = async () => {
        if (!cart || checkingCheckout) return
        setCheckingCheckout(true)
        try {
            // Khi đi từ Giỏ hàng, mục tiêu là thanh toán toàn bộ giỏ; dọn dẹp mục tiêu "Mua ngay" lẻ nếu có
            try {
                sessionStorage.removeItem('nexus.buy-now-target')
            } catch {
                // Bỏ qua lỗi storage
            }
            const message = await purchaseAvailabilityMessage(cart.items, lang)
            if (message) {
                setError(message)
                return
            }
            navigate('checkout')
        } catch (error) {
            setError(errorMessage(error))
        } finally {
            setCheckingCheckout(false)
        }
    }

    const loadCart = useCallback(async () => {
        setLoading(true)
        setError(null)
        try {
            const session = readSession()
            const guestCart = getGuestCart()

            // Cart/Checkout dùng chung Promise để effect đồng thời không cộng giỏ khách hai lần.
            if (session && guestCart.items.length > 0) {
                await syncGuestCart(session.user.id)
            }

            const data = await getActiveCart()
            setCart(data)
            setCartCount(data.totalQuantity)
        } catch (err) {
            const session = readSession()
            const guestCart = getGuestCart()
            if (session && guestCart.items.length > 0) {
                // Nếu có session nhưng backend lỗi tạm thời, vẫn hiển thị guestCart để người dùng xem được
                setCart(guestCart)
                setCartCount(guestCart.totalQuantity)
            } else if (session) {
                // Người dùng đã đăng nhập nhưng gọi API bị lỗi -> báo lỗi API thực tế
                setError(errorMessage(err))
            } else {
                // Khách chưa đăng nhập -> nạp dữ liệu từ giỏ hàng khách guestCart trong localStorage
                setCart(guestCart)
                setCartCount(guestCart.totalQuantity)
            }
        } finally {
            setLoading(false)
        }
    }, [setCartCount])

    useEffect(() => {
        loadCart()
    }, [loadCart])

    // Cập nhật số lượng sản phẩm (Accept Criteria 2)
    const handleUpdateQty = async (item: CartItemResponse, newQty: number) => {
        setActionLoadingId(item.id)
        try {
            if (newQty <= 0) {
                await handleRemoveItem(item.id)
                return
            }
            const session = readSession()
            if (session) {
                const updated = await updateCartItemQuantity(item.id, newQty)
                setCart(updated)
                setCartCount(updated.totalQuantity)
            } else {
                // Cập nhật cục bộ cho guest và đồng bộ vào localStorage
                updateGuestCartItem(item.id, newQty)
                setCart((prev) => {
                    if (!prev) return null
                    const newItems = prev.items.map((it) =>
                        it.id === item.id
                            ? { ...it, quantity: newQty, subtotal: it.unitPrice * newQty }
                            : it,
                    )
                    const newSubtotal = newItems.reduce((s, i) => s + i.subtotal, 0)
                    const newTotalQty = newItems.reduce((s, i) => s + i.quantity, 0)
                    const fee = newSubtotal >= 100 ? 0 : 8.99
                    const tax = newSubtotal * 0.08
                    setCartCount(newTotalQty)
                    return {
                        ...prev,
                        items: newItems,
                        totalItems: newItems.length,
                        totalQuantity: newTotalQty,
                        subtotal: newSubtotal,
                        shippingFee: fee,
                        taxAmount: tax,
                        totalAmount: newSubtotal + fee + tax,
                    }
                })
            }
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Cart quantity updated.' : 'Đã cập nhật số lượng sản phẩm.',
            })
        } catch (err) {
            showToast({
                type: 'error',
                message: errorMessage(err),
            })
        } finally {
            setActionLoadingId(null)
        }
    }

    // Xóa sản phẩm khỏi giỏ hàng (Accept Criteria 2)
    const handleRemoveItem = async (itemId: string) => {
        setActionLoadingId(itemId)
        try {
            const session = readSession()
            if (session) {
                const updated = await removeCartItem(itemId)
                setCart(updated)
                setCartCount(updated.totalQuantity)
            } else {
                removeGuestCartItem(itemId)
                setCart((prev) => {
                    if (!prev) return null
                    const newItems = prev.items.filter((it) => it.id !== itemId)
                    const newSubtotal = newItems.reduce((s, i) => s + i.subtotal, 0)
                    const newTotalQty = newItems.reduce((s, i) => s + i.quantity, 0)
                    const fee = newSubtotal >= 100 ? 0 : 8.99
                    const tax = newSubtotal * 0.08
                    setCartCount(newTotalQty)
                    return {
                        ...prev,
                        items: newItems,
                        totalItems: newItems.length,
                        totalQuantity: newTotalQty,
                        subtotal: newSubtotal,
                        shippingFee: fee,
                        taxAmount: tax,
                        totalAmount: newSubtotal + fee + tax,
                    }
                })
            }
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Item removed from cart.' : 'Đã xóa sản phẩm khỏi giỏ hàng.',
            })
        } catch (err) {
            showToast({
                type: 'error',
                message: errorMessage(err),
            })
        } finally {
            setActionLoadingId(null)
        }
    }

    // Xóa toàn bộ giỏ hàng
    const handleClearCart = async () => {
        setActionLoadingId('all')
        try {
            const session = readSession()
            if (session) {
                await clearCart()
            }
            const emptyCart = clearGuestCart()
            setCart(emptyCart)
            setCartCount(0)
            setShowClearConfirm(false)
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Cart cleared.' : 'Đã dọn sạch giỏ hàng.',
            })
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        } finally {
            setActionLoadingId(null)
        }
    }

    // Áp dụng mã giảm giá voucher
    const applyVoucher = () => {
        if (voucher === 'NEXUS10') {
            setAppliedVoucher(voucher)
            setVoucherError('')
            showToast({ type: 'success', message: tr(lang, 'voucherApplied') })
        } else {
            setVoucherError(tr(lang, 'invalidVoucher'))
        }
    }

    // Trạng thái đang tải (Accept Criteria 5)
    if (loading) {
        return (
            <BuyerLayout>
                <div className="flex flex-col items-center justify-center py-24 space-y-4">
                    <div
                        className="spinner-border text-primary"
                        style={{ width: '2.5rem', height: '2.5rem', color: '#ee4d2d' }}
                        role="status"
                    >
                        <span className="visually-hidden">Loading...</span>
                    </div>
                    <p className="text-gray-500 text-sm font-medium animate-pulse">
                        {lang === 'en'
                            ? 'Loading cart from server...'
                            : 'Đang tải giỏ hàng từ máy chủ...'}
                    </p>
                </div>
            </BuyerLayout>
        )
    }

    // Trạng thái lỗi API (Accept Criteria 5)
    if (error && !cart) {
        return (
            <BuyerLayout>
                <div className="max-w-2xl mx-auto py-12 space-y-4">
                    <AlertBanner
                        type="error"
                        message={
                            lang === 'en' ? `API Error: ${error}` : `Lỗi tải giỏ hàng: ${error}`
                        }
                    />
                    <div className="flex justify-center gap-3 mt-4">
                        <Button variant="primary" onClick={loadCart}>
                            {lang === 'en' ? 'Try Again' : 'Thử lại'}
                        </Button>
                        <Button variant="outline" onClick={() => navigate('login')}>
                            {lang === 'en' ? 'Sign In' : 'Đăng nhập'}
                        </Button>
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    // Trạng thái giỏ hàng trống (Accept Criteria 5)
    if (!cart || cart.items.length === 0) {
        return (
            <BuyerLayout>
                <EmptyState
                    icon={<ShoppingCart size={24} />}
                    title={tr(lang, 'emptyCart')}
                    subtitle={
                        lang === 'en'
                            ? "You haven't added anything to your cart yet."
                            : 'Bạn chưa thêm bất cứ thứ gì vào giỏ hàng.'
                    }
                    action={
                        <Button variant="primary" onClick={() => navigate('home')}>
                            {lang === 'en' ? 'Start Shopping' : 'Bắt đầu mua sắm'}
                        </Button>
                    }
                />
            </BuyerLayout>
        )
    }

    // Chức năng: Động cơ tính giá tạm tính phía Frontend (Client-side Estimated Pricing)
    // - Tính chiết khấu mua số lượng cùng một nhà bán/nhà sản xuất (Merchant Bulk Discount)
    // - Tính khuyến mãi nhóm ngành hàng (ví dụ: mua cua giảm 10%, tôm giảm 15%)
    // - Kiểm tra phụ phí / điều phối giờ cao điểm (Peak-hour surcharge)
    // - Áp dụng Voucher toàn sàn
    // Quy tắc: FE chỉ tính tạm để hiển thị, BE sẽ tính toán độc lập tại thời điểm thanh toán
    const items = cart?.items || []
    const pricing = calculateEstimatedPricing(items, appliedVoucher)
    const subtotal = pricing.baseSubtotal || cart?.subtotal || 0
    const discount =
        pricing.merchantVolumeDiscount + pricing.categoryDiscount + pricing.voucherDiscount
    const shipping =
        cart?.shippingFee !== undefined && cart.shippingFee > 0
            ? cart.shippingFee
            : pricing.estimatedShipping
    const tax = pricing.estimatedTax
    const peakHourFee = pricing.peakHourFee
    const total = Math.max(0, subtotal - discount + shipping + tax + peakHourFee)

    return (
        <BuyerLayout>
            <div className="flex items-center justify-between mb-6">
                <h1 className="text-2xl font-bold text-gray-900">
                    {tr(lang, 'cart')}{' '}
                    <span className="text-gray-400 font-normal text-lg">
                        ({cart?.totalItems ?? items.length})
                    </span>
                </h1>
                <Button
                    variant="ghost"
                    size="sm"
                    className="text-danger hover:bg-red-50 text-xs"
                    onClick={() => setShowClearConfirm(true)}
                >
                    <Trash2 size={14} className="mr-1" />
                    {lang === 'en' ? 'Clear cart' : 'Xóa tất cả'}
                </Button>
            </div>

            {/* Thông báo miễn phí vận chuyển: tự động nhận diện đơn vị VND (ngưỡng 500.000đ) hoặc USD (ngưỡng 100) */}
            {(() => {
                const freeShippingThreshold = subtotal > 1000 ? 500_000 : 100
                if (subtotal >= freeShippingThreshold) return null
                const diff = freeShippingThreshold - subtotal
                return (
                    <>
                        <AlertBanner
                            type="info"
                            message={
                                lang === 'en'
                                    ? `Add ${formatCurrency(diff, lang)} more to get free shipping!`
                                    : `Thêm ${formatCurrency(diff, lang)} để được miễn phí vận chuyển!`
                            }
                        />
                        <div className="mt-3 mb-5">
                            <ProgressBar value={subtotal} max={freeShippingThreshold} />
                        </div>
                    </>
                )
            })()}

            {error && <AlertBanner type="error" message={error} />}
            <div className="grid grid-cols-[1fr_360px] gap-8 mt-4">
                {/* Danh sách sản phẩm từ Backend (Accept Criteria 1) */}
                <div className="space-y-3">
                    {items.map((item) => {
                        const displayImage =
                            item.thumbnailUrl ||
                            'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=200'
                        const isActing = actionLoadingId === item.id

                        return (
                            <div key={item.id} className="nexus-card p-4 flex gap-4 relative">
                                <img
                                    src={displayImage}
                                    alt={item.productName}
                                    className="w-20 h-20 object-cover rounded-xl bg-gray-100 shrink-0"
                                />
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-start justify-between gap-2">
                                        <div>
                                            <p className="font-semibold text-gray-900 line-clamp-2">
                                                {item.productName}
                                            </p>
                                            <p className="text-xs text-gray-500 mt-0.5">
                                                SKU:{' '}
                                                <span className="font-mono text-gray-700">
                                                    {formatSkuDisplay(item.skuId, item.skuCode)}
                                                </span>
                                            </p>
                                        </div>
                                        {/* Nút xóa sản phẩm */}
                                        <button
                                            type="button"
                                            aria-label={
                                                lang === 'en' ? 'Remove item' : 'Xóa sản phẩm'
                                            }
                                            className="text-gray-400 hover:text-danger p-1 rounded-lg hover:bg-red-50 transition-colors"
                                            onClick={() => handleRemoveItem(item.id)}
                                            disabled={isActing}
                                        >
                                            <Trash2 size={16} />
                                        </button>
                                    </div>

                                    {!item.isAvailable && (
                                        <span className="inline-block text-xs bg-danger-light text-danger px-2 py-0.5 rounded-full font-medium mt-1">
                                            {tr(lang, 'outOfStock')}
                                        </span>
                                    )}

                                    <div className="flex items-center justify-between mt-3">
                                        {/* Điều chỉnh số lượng sản phẩm; khi đang gọi API thì làm mờ và chặn tương tác */}
                                        <div
                                            className={`flex items-center gap-2 ${isActing ? 'opacity-50 pointer-events-none' : ''}`}
                                        >
                                            <NumberStepper
                                                value={item.quantity}
                                                min={0}
                                                max={item.stockAvailable ?? 99}
                                                onChange={(qty) => handleUpdateQty(item, qty)}
                                            />
                                            {isActing && <Spinner size={14} color="#6366f1" />}
                                        </div>
                                        <span className="text-lg font-bold text-primary">
                                            {formatCurrency(
                                                item.subtotal || item.unitPrice * item.quantity,
                                                lang,
                                            )}
                                        </span>
                                    </div>
                                </div>
                            </div>
                        )
                    })}
                </div>

                {/* Bảng tổng tiền và thanh toán (Accept Criteria 3) */}
                <div className="space-y-4">
                    <div className="nexus-card p-5">
                        <h3 className="font-bold text-gray-900 mb-4">{tr(lang, 'orderSummary')}</h3>
                        <div className="space-y-2.5 text-sm">
                            {[
                                [tr(lang, 'subtotal'), formatCurrency(subtotal, lang)],
                                ...(pricing.merchantVolumeDiscount > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Shop Bulk Discount'
                                                  : 'Ưu đãi mua nhiều cùng Shop',
                                              `-${formatCurrency(pricing.merchantVolumeDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(pricing.categoryDiscount > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Category Promotion'
                                                  : 'Khuyến mãi ngành hàng',
                                              `-${formatCurrency(pricing.categoryDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(pricing.voucherDiscount > 0
                                    ? [
                                          [
                                              tr(lang, 'discount') + ' (Voucher)',
                                              `-${formatCurrency(pricing.voucherDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(peakHourFee > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Peak Hour Surcharge'
                                                  : 'Phụ phí giờ cao điểm',
                                              `+${formatCurrency(peakHourFee, lang)}`,
                                          ],
                                      ]
                                    : []),
                                [
                                    tr(lang, 'shipping'),
                                    shipping === 0
                                        ? tr(lang, 'freeShipping')
                                        : formatCurrency(shipping, lang),
                                ],
                                [tr(lang, 'tax'), formatCurrency(tax, lang)],
                            ].map(([k, v]) => (
                                <div key={k} className="flex justify-between text-gray-600">
                                    <span>{k}</span>
                                    <span
                                        className={
                                            String(v).startsWith('-')
                                                ? 'text-success font-semibold'
                                                : String(v).startsWith('+')
                                                  ? 'text-amber-600 font-semibold'
                                                  : ''
                                        }
                                    >
                                        {v}
                                    </span>
                                </div>
                            ))}
                            <div className="border-t border-border pt-3 flex justify-between font-bold text-base text-gray-900">
                                <span>{tr(lang, 'total')}</span>
                                <span className="text-primary">{formatCurrency(total, lang)}</span>
                            </div>
                        </div>

                        {/* Banner thông báo giờ cao điểm (nếu có) */}
                        {pricing.isPeakHour && (
                            <div className="mt-3 p-2 bg-amber-50 border border-amber-200/60 rounded-lg text-[11px] text-amber-800 flex items-center gap-1.5">
                                <Clock size={12} className="text-amber-600 shrink-0" />
                                <span>{pricing.peakHourMessage}</span>
                            </div>
                        )}
                    </div>

                    <div className="nexus-card p-4">
                        <label className="text-sm font-semibold text-gray-700 mb-2 block">
                            {tr(lang, 'voucher')}
                        </label>
                        <div className="flex gap-2">
                            <input
                                value={voucher}
                                onChange={(e) => setVoucher(e.target.value)}
                                placeholder="NEXUS10"
                                className="nexus-input flex-1 py-2"
                            />
                            <Button variant="outline" onClick={applyVoucher}>
                                {tr(lang, 'applyVoucher')}
                            </Button>
                        </div>
                        {voucherError && (
                            <p className="text-xs text-danger mt-1.5">{voucherError}</p>
                        )}
                        {appliedVoucher && (
                            <p className="text-xs text-success mt-1.5 flex items-center gap-1">
                                <CheckCircle size={12} />
                                {tr(lang, 'voucherApplied')}
                            </p>
                        )}
                    </div>

                    <Button
                        variant="primary"
                        size="lg"
                        className="w-full"
                        onClick={beginCheckout}
                        loading={checkingCheckout}
                    >
                        {tr(lang, 'checkout')} →
                    </Button>
                </div>
            </div>

            {/* Modal xác nhận xóa toàn bộ giỏ hàng */}
            {/* Modal xác nhận xóa toàn bộ giỏ hàng theo props chuẩn của ConfirmModal */}
            <ConfirmModal
                open={showClearConfirm}
                title={lang === 'en' ? 'Clear Shopping Cart' : 'Xóa toàn bộ giỏ hàng'}
                message={
                    lang === 'en'
                        ? 'Are you sure you want to remove all items from your cart?'
                        : 'Bạn có chắc chắn muốn xóa toàn bộ sản phẩm khỏi giỏ hàng không?'
                }
                danger
                onConfirm={handleClearCart}
                onClose={() => setShowClearConfirm(false)}
            />
        </BuyerLayout>
    )
}

// ─── Checkout Page ─────────────────────────────────────────────────────────────
/**
 * Trang thanh toán (Checkout Page):
 * 1. Chức năng:
 *    - Quy trình đặt hàng 4 bước: (1) Địa chỉ nhận hàng -> (2) Phương thức vận chuyển -> (3) Phương thức thanh toán -> (4) Xác nhận & Đặt hàng.
 *    - Kiểm tra tính hợp lệ dữ liệu biểu mẫu (Form Validation - Accept Criteria 4) trước khi chuyển bước và gửi đơn.
 *    - Xác nhận và hoàn tất đặt hàng, dọn sạch giỏ hàng khi thành công.
 * 2. Đầu vào và kết quả:
 *    - Đầu vào: Thông tin giao hàng của người nhận, lựa chọn vận chuyển, thông tin thanh toán.
 *    - Kết quả: Đặt hàng thành công, xóa giỏ hàng và điều hướng sang trang kết quả thanh toán PaymentResultPage.
 * 3. Luồng xử lý:
 *    - Bước 1: Kiểm tra họ tên (>= 2 ký tự), số điện thoại VN (10 số, đầu 0), địa chỉ, phường/xã, quận/huyện, tỉnh/thành.
 *    - Bước 2: Chọn GHN Express hoặc GHTK Standard.
 *    - Bước 3: Nếu chọn thẻ, kiểm tra số thẻ 16 số, tên in trên thẻ, ngày hết hạn MM/YY, mã CVV 3-4 số.
 *    - Bước 4: Tóm tắt thông tin, gửi đơn hàng, dọn giỏ và chuyển sang payment-result.
 * 4. Quy tắc và lý do:
 *    - Tất cả các trường bắt buộc không được để trống hoặc sai định dạng; hiển thị lỗi màu đỏ ngay dưới ô nhập liệu.
 * 5. Lỗi và giới hạn:
 *    - Chặn bấm Next khi dữ liệu không hợp lệ và hiển thị thông báo lỗi chi tiết.
 */
interface ShippingFormData {
    fullName: string
    phone: string
    street: string
    city: string
    district: string
    ward: string
}

interface PaymentFormData {
    method: 'CARD' | 'VNPAY' | 'COD'
    cardNumber: string
    cardHolder: string
    expiryDate: string
    cvv: string
}

export function CheckoutPage() {
    const { lang, navigate, showToast, setCartCount } = useApp()
    const [step, setStep] = useState(1)
    const [shipping, setShipping] = useState<string>('GHN')
    const [shippingQuoteLoading, setShippingQuoteLoading] = useState<boolean>(false)
    const [shippingQuoteError, setShippingQuoteError] = useState<string | null>(null)
    const [shippingQuoteResponse, setShippingQuoteResponse] = useState<ShippingQuoteResponse | null>(null)
    const [shippingOptions, setShippingOptions] = useState<ShippingQuoteOption[]>([])
    const [loading, setLoading] = useState(false)

    // Dữ liệu biểu mẫu giao hàng (Step 1) - khôi phục từ bản nháp sessionStorage nếu có
    const [shippingData, setShippingData] = useState<ShippingFormData>(() => {
        try {
            const rawDraft = sessionStorage.getItem('nexus.checkout-draft')
            if (rawDraft) {
                const parsed = JSON.parse(rawDraft)
                if (parsed.shippingData) return parsed.shippingData
            }
        } catch {}
        return {
            fullName: 'Minh Tran',
            phone: '0901234567',
            street: '123 Nguyen Hue Street',
            city: 'Ho Chi Minh City',
            district: 'District 1',
            ward: 'Ben Nghe',
        }
    })

    // Dữ liệu biểu mẫu thanh toán (Step 3) - chỉ khôi phục phương thức thanh toán từ bản nháp sessionStorage (không lưu/khôi phục thông tin thẻ vì bảo mật)
    const [paymentData, setPaymentData] = useState<PaymentFormData>(() => {
        let restoredMethod: 'CARD' | 'VNPAY' | 'COD' = 'CARD'
        try {
            const rawDraft = sessionStorage.getItem('nexus.checkout-draft')
            if (rawDraft) {
                const parsed = JSON.parse(rawDraft)
                if (parsed.paymentMethod) restoredMethod = parsed.paymentMethod
                else if (parsed.paymentData?.method) restoredMethod = parsed.paymentData.method
            }
        } catch {}
        return {
            method: restoredMethod,
            cardNumber: '4242 4242 4242 4242',
            cardHolder: 'MINH TRAN',
            expiryDate: '12/28',
            cvv: '123',
        }
    })

    // State lưu lỗi kiểm tra dữ liệu biểu mẫu (Accept Criteria 4)
    const [shippingErrors, setShippingErrors] = useState<
        Partial<Record<keyof ShippingFormData, string>>
    >({})
    const [paymentErrors, setPaymentErrors] = useState<
        Partial<Record<keyof PaymentFormData, string>>
    >({})

    // State lưu lỗi chung khi xử lý checkout / API backend (Accept Criteria 2 & 5)
    const [checkoutError, setCheckoutError] = useState<string | null>(null)
    // Ref chặn click đúp / gửi request lặp ngoài ý muốn (Accept Criteria 4)
    const isSubmittingRef = useRef(false)
    const [cartItems, setCartItems] = useState<CartItemResponse[]>([])
    // State lưu dữ liệu xem trước tính giá từ Backend (POST /api/v1/orders/checkout/preview)
    const [previewData, setPreviewData] = useState<CheckoutPreviewResponse | null>(null)
    // Bỏ response cũ khi địa chỉ/giỏ đổi hoặc effect bị cleanup; không cho response đến muộn ghi đè.
    const previewRequestRef = useRef(0)
    const [priceMismatchNotice, setPriceMismatchNotice] = useState<string | null>(null)
    const [cartReloadTrigger, setCartReloadTrigger] = useState(0)

    // State lưu mục tiêu "Mua ngay" độc lập (nếu có) từ ProductDetailPage.
    // Nếu buyNowTarget có dữ liệu, CheckoutPage chỉ xử lý và thanh toán riêng mặt hàng này.
    const [buyNowTarget, setBuyNowTarget] = useState<{ skuId: string; quantity: number } | null>(
        () => {
            try {
                const raw = sessionStorage.getItem('nexus.buy-now-target')
                return raw ? JSON.parse(raw) : null
            } catch {
                return null
            }
        },
    )

    // Reload giữ command/idempotency của đơn đang mua; chỉ click Mua ngay mới mới xóa command tại trang chi tiết.
    const [createdOrder, setCreatedOrder] = useState<CreateOrderResponse | null>(
        () => readCheckoutCommand(readSession()?.user.id || 'guest')?.order || null,
    )
    const [authExpired, setAuthExpired] = useState(false)
    const [orderSnapshot, setOrderSnapshot] = useState<CheckoutOrderSnapshot | null>(null)
    const [orderSnapshotError, setOrderSnapshotError] = useState<string | null>(null)

    // Danh sách sản phẩm thực sự tham gia đơn checkout:
    // - Nếu người dùng bấm "Mua ngay" tại trang chi tiết sản phẩm, chỉ checkout riêng SKU đó.
    // - Nếu người dùng đi từ Giỏ hàng, checkout toàn bộ các mặt hàng trong giỏ.
    const activeCheckoutItems: CartItemResponse[] = useMemo(() => {
        if (!buyNowTarget?.skuId) return cartItems
        // Dòng Mua ngay độc lập với giỏ: chỉ dùng snapshot UI và số lượng vừa chọn; không fallback cả giỏ.
        const target = buyNowTarget as typeof buyNowTarget & { item?: CartItemResponse }
        return target.item
            ? [
                  {
                      ...target.item,
                      quantity: target.quantity,
                      subtotal: target.item.unitPrice * target.quantity,
                  },
              ]
            : []
    }, [cartItems, buyNowTarget])

    // Khi đã có đơn, mọi dòng hàng và tổng dự kiến phải lấy từ đơn đó; giỏ mới thuộc lần mua khác.
    const summaryItems: CartItemResponse[] = createdOrder
        ? (orderSnapshot?.items || []).map((item) => ({
              ...item,
              thumbnailUrl: null,
              stockAvailable: null,
              isAvailable: true,
              unitPrice: item.unitPrice ?? item.estimatedUnitPrice,
              subtotal: (item.unitPrice ?? item.estimatedUnitPrice) * item.quantity,
          }))
        : activeCheckoutItems
    const savedPricing = orderSnapshot || createdOrder
    const savedTotal = savedPricing?.total ?? savedPricing?.estimatedTotal
    const [availabilityError, setAvailabilityError] = useState<string | null>(null)
    const [checkingAvailability, setCheckingAvailability] = useState(true)
    const [availabilityChecked, setAvailabilityChecked] = useState(false)
    const [availabilityRetry, setAvailabilityRetry] = useState(0)
    const purchaseBlocked = checkingAvailability || !availabilityChecked || !!availabilityError

    /**
     * Chức năng: Hủy bỏ đơn hàng dở dang đang lưu trong session để quay lại đặt đơn mới.
     * Đầu vào: userId hiện tại.
     * Kết quả: Xóa command đơn cũ, reset createdOrder/orderSnapshot, nạp lại giỏ hàng active.
     */
    const handleDiscardPendingOrder = () => {
        const uid = readSession()?.user.id || 'guest'
        clearCheckoutCommand(uid)
        setCreatedOrder(null)
        setOrderSnapshot(null)
        setAvailabilityError(null)
        try {
            sessionStorage.removeItem('nexus.buy-now-target')
        } catch {
            // Bỏ qua lỗi storage
        }
        setBuyNowTarget(null)
        setCartReloadTrigger((v) => v + 1)
    }

    /** Kiểm tra ngay khi nạp giỏ/đơn cũ, không chờ tới bước xác nhận; cleanup bỏ response của giỏ trước. */
    useEffect(() => {
        const lines = createdOrder ? orderSnapshot?.items : activeCheckoutItems
        let active = true
        setAvailabilityChecked(false)
        setAvailabilityError(null)
        if (!lines?.length) {
            setCheckingAvailability(false)
            return
        }
        setCheckingAvailability(true)
        purchaseAvailabilityMessage(lines, lang)
            .then((message) => {
                if (active) {
                    setAvailabilityError(message)
                    setAvailabilityChecked(true)
                }
            })
            .catch((error) => {
                if (active) setAvailabilityError(errorMessage(error))
            })
            .finally(() => {
                if (active) setCheckingAvailability(false)
            })
        return () => {
            active = false
        }
    }, [createdOrder, orderSnapshot, activeCheckoutItems, lang, availabilityRetry])

    /** Reload/retry chỉ đọc snapshot đơn đã tạo; cleanup bỏ kết quả muộn và lỗi không hiển thị giỏ ACTIVE thay thế. */
    useEffect(() => {
        if (!createdOrder) return
        let active = true
        setOrderSnapshot(null)
        setOrderSnapshotError(null)
        setPreviewData(null)
        ordersApi
            .getCheckoutOrderSnapshot(createdOrder.id)
            .then((snapshot) => {
                if (active) setOrderSnapshot(snapshot)
            })
            .catch((error) => {
                // Đơn đã xóa/không tìm thấy phải báo ngay lúc phục hồi, không đợi người dùng thử thanh toán.
                if (active)
                    setOrderSnapshotError(
                        error instanceof ApiClientError && error.status === 404
                            ? lang === 'vi'
                                ? 'Đơn hàng không còn tồn tại. Vui lòng kiểm tra lịch sử đơn hàng.'
                                : 'This order no longer exists. Please check your order history.'
                            : errorMessage(error),
                    )
            })
        return () => {
            active = false
        }
    }, [createdOrder, lang])

    /**
     * Kiểm tra tính giá thực tế và điều kiện đặt hàng từ Backend (POST /api/v1/orders/checkout/preview).
     * 1. Chức năng: Gửi danh sách SKU và địa chỉ để Commerce Backend tính lại giá live mới nhất từ Catalog DB và kiểm tra tồn kho.
     * 2. Đầu vào & kết quả: shippingAddress, selectedItemIds; cập nhật previewData (subtotal, shippingFee, tax, total, warnings).
     * 3. Luồng xử lý:
     *    - Gọi ordersApi.checkoutPreview().
     *    - So sánh tổng tạm tính do BE tính toán với tổng tạm tính client đang hiển thị.
     *    - Nếu có sự chênh lệch hoặc BE trả về warnings (giá SKU thay đổi), hiển thị thông báo giá mới cho Buyer.
     * 4. Quy tắc & lý do: Không gửi tổng tiền để BE tin tưởng; giá chốt luôn do Catalog và Commerce Backend tính.
     * 5. Lỗi & giới hạn: Bắt lỗi nếu mạng chậm hoặc endpoint gặp sự cố; không chặn người dùng nhập liệu.
     */
    const fetchCheckoutPreview = useCallback(async () => {
        const requestId = ++previewRequestRef.current
        const fullAddress = `${shippingData.street.trim()}, ${shippingData.ward.trim()}, ${shippingData.district.trim()}, ${shippingData.city.trim()}`
        if (!fullAddress.trim() || fullAddress.trim().length < 5) return

        const hasLocalId = activeCheckoutItems.some(
            (item) => !item.id || item.id.startsWith('guest-item-'),
        )
        const itemIds = hasLocalId ? undefined : activeCheckoutItems.map((item) => item.id)

        try {
            const preview = await ordersApi.checkoutPreview({
                shippingAddress: fullAddress,
                itemIds: buyNowTarget ? undefined : itemIds,
                buyNow: buyNowTarget
                    ? { skuId: buyNowTarget.skuId, quantity: buyNowTarget.quantity }
                    : undefined,
            })
            if (requestId !== previewRequestRef.current) return
            setPreviewData(preview)
            // Nếu không phải chế độ Mua ngay lẻ, badge cũng dùng số lượng đã được server xác nhận.
            if (!buyNowTarget && preview.items) {
                setCartCount(preview.items.reduce((total, line) => total + line.quantity, 0))
            }

            const clientSubtotal = activeCheckoutItems.reduce(
                (acc, i) => acc + i.unitPrice * i.quantity,
                0,
            )
            const isDiff = Math.abs(preview.subtotal - clientSubtotal) > 0.01
            const warnings = preview.warnings || []

            const quantityChanged = preview.items?.some(
                (line) =>
                    activeCheckoutItems.find((item) => item.skuId === line.skuId)?.quantity !==
                    line.quantity,
            )
            if (quantityChanged) {
                setPriceMismatchNotice(
                    [
                        lang === 'vi'
                            ? 'Số lượng trong giỏ đã thay đổi. Vui lòng kiểm tra lại tóm tắt đơn hàng trước khi đặt.'
                            : 'Cart quantities have changed. Please review the order summary before placing your order.',
                        ...warnings,
                    ].join('; '),
                )
            } else if (isDiff || warnings.length > 0) {
                const warningMsg =
                    warnings.length > 0
                        ? warnings.join('; ')
                        : lang === 'vi'
                          ? `Giá sản phẩm đã được tính toán lại từ hệ thống: Tạm tính hiển thị (${formatCurrency(clientSubtotal, lang)}) khác với giá hệ thống tính (${formatCurrency(preview.subtotal, lang)}). Đơn hàng sẽ áp dụng giá mới nhất.`
                          : `Price updated by system: Displayed subtotal (${formatCurrency(clientSubtotal, lang)}) differs from backend calculated price (${formatCurrency(preview.subtotal, lang)}). Latest price will be applied.`
                setPriceMismatchNotice(warningMsg)
            } else {
                setPriceMismatchNotice(null)
            }
        } catch {
            // Không chặn form nếu preview lỗi mạng
        }
    }, [shippingData, activeCheckoutItems, buyNowTarget, lang, setCartCount])

    useEffect(() => {
        if (step === 4 && activeCheckoutItems.length > 0 && !createdOrder) {
            void fetchCheckoutPreview()
        }
        return () => {
            previewRequestRef.current++
        }
    }, [step, activeCheckoutItems, fetchCheckoutPreview, createdOrder])

    /**
     * Tích hợp API lấy báo giá và phương thức vận chuyển (POST /api/v1/shipping/quotes - Sprint 3 HA-S3-1).
     * 1. Chức năng: Gửi địa chỉ người nhận và tổng tiền đơn hàng để Fulfillment Service tính toán
     *    danh sách gói vận chuyển khả dụng (GHN Express, GHTK Standard...), phí ship thực tế và quy tắc Free Shipping.
     * 2. Đầu vào & kết quả: shippingData, activeCheckoutItems; cập nhật shippingOptions, selected carrier và shippingQuoteResponse.
     * 3. Luồng xử lý: Gọi shippingApi.getShippingQuotes, kiểm tra danh sách options; fallback an toàn khi offline.
     * 4. Quy tắc & lý do: Đảm bảo khách hàng thấy được phí ship minh bạch trước khi chốt đơn.
     * 5. Lỗi & giới hạn: Quản lý loading, empty và error state rõ ràng với nút Thử lại.
     */
    const fetchShippingQuotes = useCallback(async () => {
        setShippingQuoteLoading(true)
        setShippingQuoteError(null)
        try {
            const fullAddress = `${shippingData.street.trim()}, ${shippingData.ward.trim()}, ${shippingData.district.trim()}, ${shippingData.city.trim()}`
            const subtotal = activeCheckoutItems.reduce((acc, i) => acc + i.unitPrice * i.quantity, 0)
            const quote = await shippingApi.getShippingQuotes({
                shippingAddress: fullAddress || 'Ho Chi Minh City',
                orderTotal: subtotal,
            })
            setShippingQuoteResponse(quote)
            const opts = quote.options || []
            setShippingOptions(opts)
            if (opts.length > 0) {
                const found = opts.find((o) => o.carrier === shipping)
                if (!found) {
                    setShipping(quote.defaultCarrier || opts[0].carrier)
                }
            }
        } catch (err: any) {
            setShippingQuoteError(
                err?.message || (lang === 'vi' ? 'Không thể tải phương thức vận chuyển' : 'Failed to load shipping methods'),
            )
        } finally {
            setShippingQuoteLoading(false)
        }
    }, [shippingData, activeCheckoutItems, shipping, lang])

    useEffect(() => {
        if (step === 2) {
            void fetchShippingQuotes()
        }
    }, [step, fetchShippingQuotes])

    const selectedShippingOption = useMemo(() => {
        if (shippingOptions.length > 0) {
            return shippingOptions.find((o) => o.carrier === shipping) || shippingOptions[0]
        }
        return null
    }, [shippingOptions, shipping])

    const currentShippingFee = useMemo(() => {
        if (selectedShippingOption) {
            return selectedShippingOption.shippingFee
        }
        return shipping === 'GHN' ? (lang === 'vi' ? 55000 : 3.99) : (lang === 'vi' ? 35000 : 2.49)
    }, [selectedShippingOption, shipping, lang])

    /**
     * Chức năng: Nạp và tự động đồng bộ giỏ hàng trước khi thực hiện Checkout.
     * Đầu vào và kết quả: Đọc session, guestCart từ localStorage; nạp giỏ hàng thực tế từ Commerce Service backend.
     * Luồng xử lý:
     *   1. Nếu đã đăng nhập và guestCart còn sản phẩm tạm (ví dụ bấm Thêm vào giỏ trước khi login hoặc Mua ngay offline),
     *      dùng syncGuestCart để cộng từng dòng một lần giữa các effect, chỉ dọn dòng đã nhận ACK.
     *   2. Gọi getActiveCart() lấy giỏ hàng chính xác từ Database với đầy đủ mã cart_item.id thật.
     *   3. Cập nhật state cartItems và cartCount.
     * Quy tắc: Đảm bảo toàn bộ sản phẩm hiển thị trên màn hình Checkout đều đã được lưu vào Database của Backend,
     *          loại bỏ hoàn toàn hiện tượng lệch ID gây lỗi CART_ITEM_NOT_FOUND ("Món hàng không tồn tại trong giỏ hàng").
     */
    useEffect(() => {
        let isMounted = true

        async function initCheckoutCart() {
            // Mua ngay không cần tải/hợp nhất giỏ: dòng hiển thị đã lưu khi chọn sản phẩm.
            if (buyNowTarget) {
                if (!(buyNowTarget as typeof buyNowTarget & { item?: CartItemResponse }).item) {
                    setCheckoutError(
                        lang === 'vi'
                            ? 'Lựa chọn Mua ngay đã cũ. Vui lòng chọn lại sản phẩm.'
                            : 'Your Buy Now selection is outdated. Please select the product again.',
                    )
                }
                return
            }
            const session = readSession()
            const rawBuyNow = sessionStorage.getItem('nexus.buy-now-target')
            // Đơn cũ đang thanh toán có snapshot riêng; không nạp giỏ mới vào phần tóm tắt của đơn (trừ khi đang Mua ngay).
            if (session && !rawBuyNow && readCheckoutCommand(session.user.id)?.order) return
            const guestCart = getGuestCart()

            if (
                session &&
                guestCart.items.length > 0 &&
                (!readCheckoutCommand(session.user.id)?.order || rawBuyNow)
            ) {
                try {
                    await syncGuestCart(session.user.id)
                } catch (error) {
                    if (isMounted) setCheckoutError(errorMessage(error))
                    return
                }
            }

            try {
                const res = await getActiveCart()
                if (isMounted && res?.items && res.items.length > 0) {
                    setCartItems(res.items)
                    setCartCount(res.totalQuantity)
                    return
                }
            } catch {
                // Fallback nếu server lỗi mạng
            }

            if (isMounted) {
                const guest = getGuestCart()
                if (guest.items.length > 0) {
                    setCartItems(guest.items)
                    setCartCount(guest.totalQuantity)
                }
            }
        }

        void initCheckoutCart()
        return () => {
            isMounted = false
        }
    }, [setCartCount, cartReloadTrigger, buyNowTarget, lang])

    const STEPS = [
        tr(lang, 'shippingAddress'),
        tr(lang, 'shippingMethod'),
        tr(lang, 'payment'),
        tr(lang, 'confirmation'),
    ]

    // Hàm kiểm tra tính hợp lệ dữ liệu biểu mẫu giao hàng (Accept Criteria 4)
    const validateStep1 = (): boolean => {
        const errors: Partial<Record<keyof ShippingFormData, string>> = {}

        // Họ và tên
        if (!shippingData.fullName.trim()) {
            errors.fullName = lang === 'en' ? 'Full name is required.' : 'Họ và tên là bắt buộc.'
        } else if (shippingData.fullName.trim().length < 2) {
            errors.fullName =
                lang === 'en'
                    ? 'Name must be at least 2 characters.'
                    : 'Họ và tên phải có ít nhất 2 ký tự.'
        }

        // Số điện thoại Việt Nam (10 số, bắt đầu bằng 0)
        const phoneClean = shippingData.phone.replace(/[\s-]/g, '')
        const phoneRegex = /^0(3|5|7|8|9)\d{8}$/
        if (!phoneClean) {
            errors.phone =
                lang === 'en' ? 'Phone number is required.' : 'Số điện thoại là bắt buộc.'
        } else if (!phoneRegex.test(phoneClean)) {
            errors.phone =
                lang === 'en'
                    ? 'Invalid phone number (10 digits starting with 0).'
                    : 'Số điện thoại không hợp lệ (gồm 10 số, bắt đầu bằng 0).'
        }

        // Địa chỉ cụ thể
        if (!shippingData.street.trim()) {
            errors.street =
                lang === 'en' ? 'Street address is required.' : 'Địa chỉ cụ thể là bắt buộc.'
        } else if (shippingData.street.trim().length < 5) {
            errors.street =
                lang === 'en'
                    ? 'Address must be at least 5 characters.'
                    : 'Địa chỉ phải có ít nhất 5 ký tự.'
        }

        // Tỉnh / Thành phố
        if (!shippingData.city.trim()) {
            errors.city =
                lang === 'en' ? 'City is required.' : 'Vui lòng chọn hoặc nhập Tỉnh/Thành phố.'
        }

        // Quận / Huyện
        if (!shippingData.district.trim()) {
            errors.district =
                lang === 'en' ? 'District is required.' : 'Vui lòng chọn hoặc nhập Quận/Huyện.'
        }

        // Phường / Xã
        if (!shippingData.ward.trim()) {
            errors.ward = lang === 'en' ? 'Ward is required.' : 'Vui lòng chọn hoặc nhập Phường/Xã.'
        }

        setShippingErrors(errors)
        const isValid = Object.keys(errors).length === 0
        if (!isValid) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please fill in all required shipping fields correctly.'
                        : 'Vui lòng điền đầy đủ và chính xác các thông tin giao hàng bắt buộc.',
            })
        }
        return isValid
    }

    // Hàm kiểm tra tính hợp lệ dữ liệu biểu mẫu thanh toán (Accept Criteria 4)
    const validateStep3 = (): boolean => {
        if (paymentData.method !== 'CARD') {
            setPaymentErrors({})
            return true
        }

        const errors: Partial<Record<keyof PaymentFormData, string>> = {}
        const cleanCard = paymentData.cardNumber.replace(/\s+/g, '')

        // Số thẻ (16 chữ số)
        if (!cleanCard) {
            errors.cardNumber = lang === 'en' ? 'Card number is required.' : 'Số thẻ là bắt buộc.'
        } else if (!/^\d{16}$/.test(cleanCard)) {
            errors.cardNumber =
                lang === 'en' ? 'Card number must be 16 digits.' : 'Số thẻ phải gồm đúng 16 chữ số.'
        }

        // Tên in trên thẻ
        if (!paymentData.cardHolder.trim()) {
            errors.cardHolder =
                lang === 'en' ? 'Card holder name is required.' : 'Tên in trên thẻ là bắt buộc.'
        } else if (paymentData.cardHolder.trim().length < 2) {
            errors.cardHolder =
                lang === 'en'
                    ? 'Card holder name must be at least 2 characters.'
                    : 'Tên chủ thẻ phải có tối thiểu 2 ký tự.'
        }

        // Ngày hết hạn (MM/YY)
        const expiryRegex = /^(0[1-9]|1[0-2])\/\d{2}$/
        if (!paymentData.expiryDate.trim()) {
            errors.expiryDate =
                lang === 'en' ? 'Expiry date is required.' : 'Ngày hết hạn là bắt buộc.'
        } else if (!expiryRegex.test(paymentData.expiryDate.trim())) {
            errors.expiryDate =
                lang === 'en'
                    ? 'Format must be MM/YY (e.g. 12/28).'
                    : 'Định dạng phải là MM/YY (ví dụ: 12/28).'
        }

        // Mã CVV (3 hoặc 4 số)
        if (!paymentData.cvv.trim()) {
            errors.cvv = lang === 'en' ? 'CVV is required.' : 'Mã CVV là bắt buộc.'
        } else if (!/^\d{3,4}$/.test(paymentData.cvv.trim())) {
            errors.cvv =
                lang === 'en' ? 'CVV must be 3 or 4 digits.' : 'Mã CVV phải gồm 3 hoặc 4 chữ số.'
        }

        setPaymentErrors(errors)
        const isValid = Object.keys(errors).length === 0
        if (!isValid) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please check the payment card details.'
                        : 'Vui lòng kiểm tra lại thông tin thẻ thanh toán.',
            })
        }
        return isValid
    }

    /**
     * Validate lần đầu, replay command theo tài khoản khi mất ACK/reload; payment lỗi giữ đúng đơn và key.
     * BE quyết định tiền/trạng thái; response terminal mở trang tra cứu attempt, không cần URL hoặc tạo đơn khác.
     * COD chỉ báo đã ghi nhận đơn; lỗi 401 lưu nháp an toàn và đề nghị đăng nhập lại.
     */
    const placeOrder = async () => {
        // Hạn chế việc gửi yêu cầu nhiều lần ngoài ý muốn (Accept Criteria 4)
        if (isSubmittingRef.current || loading) return
        // Không gửi Create Order/Payment khi kiểm tra sớm đang chạy, thất bại hoặc đã xác nhận hết hàng.
        if (purchaseBlocked) return

        const userId = readSession()?.user.id || 'guest'
        const restored = readCheckoutCommand(userId)
        // Command đã gửi được replay nguyên payload; không buộc sửa form khi giỏ server đã chuyển thành đơn.
        if (!restored && !validateStep1()) {
            setStep(1)
            return
        }
        if (!restored && !validateStep3()) {
            setStep(3)
            return
        }

        setLoading(true)
        setCheckoutError(null)
        setAuthExpired(false)
        isSubmittingRef.current = true

        try {
            const fullAddress = `${shippingData.street.trim()}, ${shippingData.ward.trim()}, ${shippingData.district.trim()}, ${shippingData.city.trim()}`

            // Giỏ khách thay đổi sau lúc xem: đồng bộ một lần rồi yêu cầu xem lại, chưa tạo đơn từ số lượng mới.
            const session = readSession()
            const guestCart = getGuestCart()
            if (
                session &&
                !buyNowTarget &&
                guestCart.items.length > 0 &&
                !readCheckoutCommand(session.user.id)?.order
            ) {
                await syncGuestCart(session.user.id)
                const refreshed = await getActiveCart()
                setCartItems(refreshed.items)
                setCartCount(refreshed.totalQuantity)
                setPreviewData(null)
                setCheckoutError(
                    lang === 'vi'
                        ? 'Giỏ hàng đã cập nhật. Vui lòng kiểm tra lại số lượng trước khi đặt.'
                        : 'Cart updated. Please review quantities before placing your order.',
                )
                return
            }

            // Nếu đang trong chế độ Mua ngay (buyNowTarget), chỉ chọn những item thuộc về target SKU
            const isBuyNow = !!buyNowTarget?.skuId
            const checkoutItems = isBuyNow ? activeCheckoutItems : cartItems
            const hasLocalId = checkoutItems.some(
                (item) => !item.id || item.id.startsWith('guest-item-'),
            )

            // Hai chế độ tách biệt: Mua ngay gửi buyNow, checkout giỏ gửi selectedItemIds.
            // Không dùng ID dòng giỏ cho Mua ngay vì số lượng dòng đó có thể khác số lượng vừa chọn.
            const orderPayload: CreateOrderRequest = {
                // Backend tự xác thực giá/quyền/kho, không đọc hoặc xóa dòng giỏ của cùng SKU.
                buyNow: buyNowTarget
                    ? { skuId: buyNowTarget.skuId, quantity: buyNowTarget.quantity }
                    : undefined,
                shippingAddress: fullAddress,
                recipientName: shippingData.fullName.trim(),
                recipientPhone: shippingData.phone.replace(/[\s-]/g, ''),
                paymentMethod: paymentData.method,
                sourceType: 'REGULAR',
                carrier: shipping,
                shippingFee: currentShippingFee,
                selectedItemIds: isBuyNow
                    ? undefined
                    : hasLocalId
                      ? undefined
                      : cartItems.map((item) => item.id),
            }

            // Lưu key/payload trước Create Order; mất ACK/reload không được sinh đơn mới từ giỏ đã rỗng.
            const response = await recoverOrCreateOrder(userId, orderPayload)
            setCreatedOrder(response)
            if (response.paymentMethod === 'VNPAY') {
                const attempt = await createPaymentAttempt(
                    response.id,
                    checkoutAttemptKey(userId, response.id),
                )
                if (attempt.status !== 'PENDING') {
                    // BE đã có kết quả: chuyển đến attempt thật, không báo thiếu URL hoặc replay key FAILED vô hạn.
                    clearCheckoutCommand(userId)
                    try {
                        sessionStorage.removeItem('nexus.buy-now-target')
                    } catch {
                        // Bỏ qua lỗi storage
                    }
                    navigate('payment-result', { vnp_TxnRef: attempt.paymentId })
                    return
                }
                if (!attempt.paymentUrl)
                    throw new Error('Không nhận được đường dẫn thanh toán từ máy chủ.')
                // Điều hướng lỗi giữ command/key để thử lại; chỉ dọn sau khi bàn giao URL thành công.
                redirectToGateway(attempt.paymentUrl)
                clearCheckoutCommand(userId)
                if (!isBuyNow) {
                    clearGuestCart()
                    setCartCount(0)
                }
                try {
                    sessionStorage.removeItem('nexus.buy-now-target')
                } catch {
                    // Bỏ qua lỗi storage
                }
                try {
                    sessionStorage.removeItem('nexus.checkout-draft')
                } catch {
                    /* Không đổi trạng thái đơn thật. */
                }
                return
            }

            // Dọn sạch giỏ hoặc sản phẩm mua ngay sau khi đặt hàng thành công
            if (!isBuyNow) {
                if (session) {
                    try {
                        await clearCart()
                    } catch {
                        // Bỏ qua lỗi dọn giỏ phụ
                    }
                }
                clearGuestCart()
                setCartCount(0)
            } else {
                // Mua ngay không thay đổi giỏ backend/local nên giữ nguyên badge hiện tại.
            }
            try {
                sessionStorage.removeItem('nexus.buy-now-target')
            } catch {
                // Bỏ qua lỗi storage
            }

            clearCheckoutCommand(userId)
            // Phản hồi Checkout thành công cho COD hoặc thẻ (Accept Criteria 2)
            showToast({ type: 'success', message: tr(lang, 'orderPlaced') })
            navigate('payment-result', {
                status: 'success',
                orderId: response.id || response.orderNo,
            })
        } catch (err: unknown) {
            // Xử lý và hiển thị phản hồi Checkout thất bại rõ ràng (Accept Criteria 2 & 5)
            let msg =
                err instanceof Error
                    ? err.message
                    : lang === 'en'
                      ? 'Failed to process checkout. Please try again.'
                      : 'Đặt hàng thất bại. Vui lòng thử lại.'

            /**
             * Chức năng: Nhận diện và chuẩn hóa lỗi xác thực / hết phiên 401 khi đặt hàng.
             * Đầu vào: Đối tượng lỗi err bắt được từ ordersApi.createOrder().
             * Kết quả: Chuyển đổi mã lỗi kỹ thuật 401 thành câu thông báo tiếng Việt có ý nghĩa,
             *          lưu nháp an toàn (chỉ lưu thông tin giao hàng và method, không lưu số thẻ) và xóa session.
             * Quy tắc: Phải dựa trên HTTP status 401 hoặc mã lỗi xác thực của ApiClientError,
             *          tuyệt đối không dựa vào chuỗi "hết hạn" hay dò text trên generic Error.message.
             */
            const isAuthError =
                err instanceof ApiClientError &&
                (err.status === 401 ||
                    err.code === 'AUTH_UNAUTHORIZED' ||
                    err.code === 'UNAUTHORIZED')

            if (isAuthError) {
                setAuthExpired(true)
                msg =
                    lang === 'vi'
                        ? 'Phiên đăng nhập đã hết hạn hoặc chưa xác thực (401). Vui lòng đăng nhập lại để hoàn tất đặt hàng.'
                        : 'Session has expired or is unauthorized (401). Please sign in again to complete checkout.'
                try {
                    sessionStorage.setItem(
                        'nexus.checkout-draft',
                        JSON.stringify({
                            shippingData,
                            paymentMethod: paymentData.method,
                        }),
                    )
                } catch {
                    // Bỏ qua lỗi storage
                }
                saveSession(null)
            }

            setCheckoutError(msg)
            showToast({
                type: 'error',
                message: msg,
            })
        } finally {
            setLoading(false)
            isSubmittingRef.current = false
        }
    }

    return (
        <BuyerLayout>
            <h1 className="text-2xl font-bold text-gray-900 mb-6">{tr(lang, 'checkout')}</h1>

            {/* Step indicator */}
            <div className="flex items-center mb-8 nexus-card p-4">
                {STEPS.map((s, i) => (
                    <span key={i} className="flex items-center flex-1">
                        <span
                            className={`checkout-step ${i < step - 1 ? 'done' : i === step - 1 ? 'current' : 'pending'}`}
                        >
                            <span className="checkout-step-num">
                                {i < step - 1 ? <CheckCircle size={13} /> : i + 1}
                            </span>
                            <span className="text-xs ml-1.5">{s}</span>
                        </span>
                        {i < STEPS.length - 1 && (
                            <span
                                className={`checkout-connector mx-2 ${i < step - 1 ? 'done' : ''}`}
                            />
                        )}
                    </span>
                ))}
            </div>

            <div className="grid grid-cols-[1fr_340px] gap-8">
                <div className="nexus-card p-6">
                    {/* Chưa reserve thì không hiển thị đồng hồ giữ hàng giả; kho được kiểm tra trước bước nhập liệu. */}
                    <div className="flex items-center gap-2 mb-5 p-3 bg-warning-light rounded-xl text-sm text-yellow-800">
                        <AlertTriangle size={16} className="text-gray-500" />
                        {lang === 'en'
                            ? 'Stock will be reserved when payment starts. Availability may change.'
                            : 'Hàng được giữ khi bắt đầu thanh toán. Tồn kho có thể thay đổi trước thời điểm đó.'}
                    </div>
                    {checkingAvailability && (
                        <p role="status">
                            {lang === 'vi' ? 'Đang kiểm tra tồn kho…' : 'Checking stock…'}
                        </p>
                    )}
                    {availabilityError && (
                        <div className="mb-4">
                            <AlertBanner type="error" message={availabilityError} />
                            {/* Chỉ đọc lại kho, không tự gửi lại Create Order/Payment khi người dùng yêu cầu kiểm tra. */}
                            <div className="flex flex-wrap gap-2 mt-3">
                                <Button
                                    variant="outline"
                                    onClick={() => setAvailabilityRetry((value) => value + 1)}
                                >
                                    {lang === 'vi' ? 'Kiểm tra lại tồn kho' : 'Check stock again'}
                                </Button>
                                {createdOrder && (
                                    <Button
                                        variant="outline"
                                        className="text-red-600 border-red-300 hover:bg-red-50"
                                        onClick={handleDiscardPendingOrder}
                                    >
                                        {lang === 'vi'
                                            ? 'Hủy đơn dở dang này để đặt đơn mới'
                                            : 'Discard pending order & start new'}
                                    </Button>
                                )}
                                <Button
                                    variant="outline"
                                    onClick={() =>
                                        navigate(createdOrder ? 'order-history' : 'cart')
                                    }
                                >
                                    {lang === 'vi'
                                        ? createdOrder
                                            ? 'Xem đơn hàng'
                                            : 'Quay lại giỏ hàng'
                                        : createdOrder
                                          ? 'View orders'
                                          : 'Back to cart'}
                                </Button>
                            </div>
                        </div>
                    )}

                    {/* Bước 1: Địa chỉ giao hàng với Form Validation (Accept Criteria 4) */}
                    {step === 1 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'shippingAddress')}
                            </h2>
                            <div className="grid grid-cols-2 gap-4">
                                <Input
                                    label={lang === 'en' ? 'Full Name *' : 'Họ và tên *'}
                                    value={shippingData.fullName}
                                    onChange={(e) => {
                                        setShippingData((prev) => ({
                                            ...prev,
                                            fullName: e.target.value,
                                        }))
                                        if (shippingErrors.fullName)
                                            setShippingErrors((prev) => ({
                                                ...prev,
                                                fullName: undefined,
                                            }))
                                    }}
                                    error={shippingErrors.fullName}
                                    placeholder="Nguyen Van A"
                                />
                                <Input
                                    label={lang === 'en' ? 'Phone *' : 'Số điện thoại *'}
                                    value={shippingData.phone}
                                    onChange={(e) => {
                                        setShippingData((prev) => ({
                                            ...prev,
                                            phone: e.target.value,
                                        }))
                                        if (shippingErrors.phone)
                                            setShippingErrors((prev) => ({
                                                ...prev,
                                                phone: undefined,
                                            }))
                                    }}
                                    error={shippingErrors.phone}
                                    placeholder="0901234567"
                                />
                            </div>
                            <Input
                                label={lang === 'en' ? 'Street Address *' : 'Địa chỉ cụ thể *'}
                                value={shippingData.street}
                                onChange={(e) => {
                                    setShippingData((prev) => ({
                                        ...prev,
                                        street: e.target.value,
                                    }))
                                    if (shippingErrors.street)
                                        setShippingErrors((prev) => ({
                                            ...prev,
                                            street: undefined,
                                        }))
                                }}
                                error={shippingErrors.street}
                                placeholder="123 Nguyen Hue Street"
                            />
                            <div className="grid grid-cols-3 gap-4">
                                <Input
                                    label={lang === 'en' ? 'City *' : 'Thành phố *'}
                                    value={shippingData.city}
                                    onChange={(e) => {
                                        setShippingData((prev) => ({
                                            ...prev,
                                            city: e.target.value,
                                        }))
                                        if (shippingErrors.city)
                                            setShippingErrors((prev) => ({
                                                ...prev,
                                                city: undefined,
                                            }))
                                    }}
                                    error={shippingErrors.city}
                                    placeholder="Ho Chi Minh City"
                                />
                                <Input
                                    label={lang === 'en' ? 'District *' : 'Quận/Huyện *'}
                                    value={shippingData.district}
                                    onChange={(e) => {
                                        setShippingData((prev) => ({
                                            ...prev,
                                            district: e.target.value,
                                        }))
                                        if (shippingErrors.district)
                                            setShippingErrors((prev) => ({
                                                ...prev,
                                                district: undefined,
                                            }))
                                    }}
                                    error={shippingErrors.district}
                                    placeholder="District 1"
                                />
                                <Input
                                    label={lang === 'en' ? 'Ward *' : 'Phường/Xã *'}
                                    value={shippingData.ward}
                                    onChange={(e) => {
                                        setShippingData((prev) => ({
                                            ...prev,
                                            ward: e.target.value,
                                        }))
                                        if (shippingErrors.ward)
                                            setShippingErrors((prev) => ({
                                                ...prev,
                                                ward: undefined,
                                            }))
                                    }}
                                    error={shippingErrors.ward}
                                    placeholder="Ben Nghe"
                                />
                            </div>
                            <Button
                                variant="primary"
                                size="lg"
                                disabled={purchaseBlocked}
                                onClick={() => {
                                    if (!purchaseBlocked && validateStep1()) {
                                        setStep(2)
                                    }
                                }}
                            >
                                {tr(lang, 'next')} →
                            </Button>
                        </div>
                    )}

                    {/* Bước 2: Phương thức vận chuyển & Báo giá cước (Sprint 3 - HA-S3-1) */}
                    {step === 2 && (
                        <div className="space-y-4">
                            <div className="flex items-center justify-between mb-1">
                                <h2 className="font-bold text-gray-900 text-lg">
                                    {tr(lang, 'shippingMethod')}
                                </h2>
                                {shippingQuoteResponse?.freeShippingApplied && (
                                    <span className="text-xs font-semibold px-2.5 py-1 bg-green-100 text-green-700 rounded-full flex items-center gap-1">
                                        <CheckCircle size={12} />
                                        {lang === 'vi' ? 'Miễn phí vận chuyển' : 'Free Shipping Applied'}
                                    </span>
                                )}
                            </div>

                            {/* Xử lý trạng thái Loading khi gọi API báo giá */}
                            {shippingQuoteLoading && (
                                <div className="p-8 text-center text-gray-500 border border-dashed rounded-xl bg-gray-50/50" role="status">
                                    <div className="spinner-border spinner-border-sm text-primary mb-2 inline-block w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin" />
                                    <p className="text-sm font-medium">
                                        {lang === 'vi'
                                            ? 'Đang tính toán cước vận chuyển từ các hãng...'
                                            : 'Calculating shipping quotes from carriers...'}
                                    </p>
                                </div>
                            )}

                            {/* Xử lý trạng thái Error khi gọi API báo giá */}
                            {!shippingQuoteLoading && shippingQuoteError && (
                                <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-center justify-between text-sm text-red-700">
                                    <span>{shippingQuoteError}</span>
                                    <Button size="sm" variant="outline" onClick={fetchShippingQuotes}>
                                        {lang === 'vi' ? 'Thử lại' : 'Retry'}
                                    </Button>
                                </div>
                            )}

                            {/* Hiển thị danh sách phương thức vận chuyển khả dụng */}
                            {!shippingQuoteLoading && !shippingQuoteError && shippingOptions.length > 0 && (
                                <div className="space-y-3">
                                    {shippingOptions.map((opt) => {
                                        const isSelected = shipping === opt.carrier
                                        const carrierDisplayName =
                                            opt.carrier === 'GHN'
                                                ? 'GHN Express'
                                                : opt.carrier === 'GHTK'
                                                  ? 'GHTK Standard'
                                                  : opt.carrier
                                        return (
                                            <label
                                                key={`${opt.carrier}-${opt.serviceType}`}
                                                className={`flex items-center gap-4 p-4 border-2 rounded-xl cursor-pointer transition-colors ${
                                                    isSelected
                                                        ? 'border-primary bg-primary-light'
                                                        : 'border-border hover:border-gray-300'
                                                }`}
                                            >
                                                <input
                                                    type="radio"
                                                    name="shippingMethodOption"
                                                    className="w-4 h-4 accent-primary"
                                                    checked={isSelected}
                                                    onChange={() => setShipping(opt.carrier)}
                                                />
                                                <Truck
                                                    size={22}
                                                    className={isSelected ? 'text-primary' : 'text-gray-400'}
                                                />
                                                <div className="flex-1 min-w-0">
                                                    <div className="flex items-center gap-2">
                                                        <p className="font-semibold text-gray-900">
                                                            {carrierDisplayName}
                                                        </p>
                                                        {opt.freeShippingApplied && (
                                                            <span className="text-[11px] font-bold text-green-700 bg-green-100 px-2 py-0.5 rounded">
                                                                {lang === 'vi' ? 'FREESHIP' : 'FREE'}
                                                            </span>
                                                        )}
                                                    </div>
                                                    <p className="text-xs text-gray-500 mt-0.5">
                                                        {tr(lang, 'deliveryEstimate')}: {opt.estimatedDeliveryDays}{' '}
                                                        {lang === 'vi' ? 'ngày làm việc' : 'business days'}
                                                        {opt.estimatedDeliveryAt && (
                                                            <span className="text-gray-400 ml-1">
                                                                ({formatDate(opt.estimatedDeliveryAt, lang)})
                                                            </span>
                                                        )}
                                                    </p>
                                                </div>
                                                <div className="text-right">
                                                    {opt.freeShippingApplied ? (
                                                        <div className="flex flex-col items-end">
                                                            {opt.originalFee !== undefined && opt.originalFee > 0 && (
                                                                <span className="text-xs line-through text-gray-400">
                                                                    {formatCurrency(opt.originalFee, lang)}
                                                                </span>
                                                            )}
                                                            <span className="font-bold text-green-600 text-sm">
                                                                {lang === 'vi' ? 'Miễn phí' : 'Free'}
                                                            </span>
                                                        </div>
                                                    ) : (
                                                        <span className="font-bold text-primary text-base">
                                                            {formatCurrency(opt.shippingFee, lang)}
                                                        </span>
                                                    )}
                                                </div>
                                            </label>
                                        )
                                    })}
                                </div>
                            )}

                            {/* Xử lý trạng thái rỗng (Empty state) */}
                            {!shippingQuoteLoading && !shippingQuoteError && shippingOptions.length === 0 && (
                                <div className="p-6 text-center text-gray-500 bg-gray-50 rounded-xl">
                                    <Package size={28} className="mx-auto text-gray-400 mb-2" />
                                    <p className="text-sm font-medium">
                                        {lang === 'vi'
                                            ? 'Không có phương thức vận chuyển khả dụng cho địa chỉ này.'
                                            : 'No shipping methods available for this address.'}
                                    </p>
                                    <Button size="sm" variant="outline" className="mt-3" onClick={fetchShippingQuotes}>
                                        {lang === 'vi' ? 'Tải lại' : 'Reload'}
                                    </Button>
                                </div>
                            )}

                            <div className="flex gap-3">
                                <Button variant="outline" size="lg" onClick={() => setStep(1)}>
                                    {tr(lang, 'back')}
                                </Button>
                                <Button
                                    variant="primary"
                                    size="lg"
                                    disabled={shippingQuoteLoading || (!shippingQuoteLoading && shippingOptions.length === 0)}
                                    onClick={() => setStep(3)}
                                >
                                    {tr(lang, 'next')} →
                                </Button>
                            </div>
                        </div>
                    )}

                    {/* Bước 3: Phương thức thanh toán với Form Validation (Accept Criteria 4) */}
                    {step === 3 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'payment')}
                            </h2>

                            {/* Lựa chọn phương thức thanh toán */}
                            <div className="grid grid-cols-3 gap-3 mb-4">
                                <label
                                    className={`flex flex-col items-center p-3 border-2 rounded-xl cursor-pointer text-center transition-colors ${paymentData.method === 'CARD' ? 'border-primary bg-primary-light font-semibold' : 'border-border'}`}
                                >
                                    <input
                                        type="radio"
                                        name="paymentMethod"
                                        className="sr-only"
                                        checked={paymentData.method === 'CARD'}
                                        onChange={() =>
                                            setPaymentData((p) => ({ ...p, method: 'CARD' }))
                                        }
                                    />
                                    <CreditCard
                                        size={22}
                                        className={
                                            paymentData.method === 'CARD'
                                                ? 'text-primary'
                                                : 'text-gray-400'
                                        }
                                    />
                                    <span className="text-xs mt-1.5">
                                        {lang === 'en' ? 'Credit Card' : 'Thẻ quốc tế'}
                                    </span>
                                </label>
                                <label
                                    className={`flex flex-col items-center p-3 border-2 rounded-xl cursor-pointer text-center transition-colors ${paymentData.method === 'VNPAY' ? 'border-primary bg-primary-light font-semibold' : 'border-border'}`}
                                >
                                    <input
                                        type="radio"
                                        name="paymentMethod"
                                        className="sr-only"
                                        checked={paymentData.method === 'VNPAY'}
                                        onChange={() =>
                                            setPaymentData((p) => ({ ...p, method: 'VNPAY' }))
                                        }
                                    />
                                    <Package
                                        size={22}
                                        className={
                                            paymentData.method === 'VNPAY'
                                                ? 'text-primary'
                                                : 'text-gray-400'
                                        }
                                    />
                                    <span className="text-xs mt-1.5">VNPAY QR</span>
                                </label>
                                <label
                                    className={`flex flex-col items-center p-3 border-2 rounded-xl cursor-pointer text-center transition-colors ${paymentData.method === 'COD' ? 'border-primary bg-primary-light font-semibold' : 'border-border'}`}
                                >
                                    <input
                                        type="radio"
                                        name="paymentMethod"
                                        className="sr-only"
                                        checked={paymentData.method === 'COD'}
                                        onChange={() =>
                                            setPaymentData((p) => ({ ...p, method: 'COD' }))
                                        }
                                    />
                                    <Truck
                                        size={22}
                                        className={
                                            paymentData.method === 'COD'
                                                ? 'text-primary'
                                                : 'text-gray-400'
                                        }
                                    />
                                    <span className="text-xs mt-1.5">
                                        {lang === 'en' ? 'COD' : 'Khi nhận hàng'}
                                    </span>
                                </label>
                            </div>

                            {paymentData.method === 'CARD' ? (
                                <>
                                    <div className="p-3 bg-primary-light rounded-xl flex items-center gap-2 text-sm text-primary mb-3">
                                        <svg
                                            width="16"
                                            height="16"
                                            viewBox="0 0 24 24"
                                            fill="none"
                                            stroke="currentColor"
                                            strokeWidth="2"
                                        >
                                            <rect
                                                x="1"
                                                y="4"
                                                width="22"
                                                height="16"
                                                rx="2"
                                                ry="2"
                                            />
                                            <line x1="1" y1="10" x2="23" y2="10" />
                                        </svg>
                                        {lang === 'en'
                                            ? 'Powered by Stripe — your payment is secure'
                                            : 'Bảo mật bởi Stripe — thanh toán của bạn được bảo vệ'}
                                    </div>
                                    <Input
                                        label={tr(lang, 'cardNumber') + ' *'}
                                        value={paymentData.cardNumber}
                                        onChange={(e) => {
                                            setPaymentData((p) => ({
                                                ...p,
                                                cardNumber: e.target.value,
                                            }))
                                            if (paymentErrors.cardNumber)
                                                setPaymentErrors((p) => ({
                                                    ...p,
                                                    cardNumber: undefined,
                                                }))
                                        }}
                                        error={paymentErrors.cardNumber}
                                        placeholder="4242 4242 4242 4242"
                                    />
                                    <Input
                                        label={tr(lang, 'cardHolder') + ' *'}
                                        value={paymentData.cardHolder}
                                        onChange={(e) => {
                                            setPaymentData((p) => ({
                                                ...p,
                                                cardHolder: e.target.value,
                                            }))
                                            if (paymentErrors.cardHolder)
                                                setPaymentErrors((p) => ({
                                                    ...p,
                                                    cardHolder: undefined,
                                                }))
                                        }}
                                        error={paymentErrors.cardHolder}
                                        placeholder="MINH TRAN"
                                    />
                                    <div className="grid grid-cols-2 gap-4">
                                        <Input
                                            label={tr(lang, 'expiryDate') + ' *'}
                                            value={paymentData.expiryDate}
                                            onChange={(e) => {
                                                setPaymentData((p) => ({
                                                    ...p,
                                                    expiryDate: e.target.value,
                                                }))
                                                if (paymentErrors.expiryDate)
                                                    setPaymentErrors((p) => ({
                                                        ...p,
                                                        expiryDate: undefined,
                                                    }))
                                            }}
                                            error={paymentErrors.expiryDate}
                                            placeholder="MM/YY"
                                        />
                                        <Input
                                            label={tr(lang, 'cvv') + ' *'}
                                            value={paymentData.cvv}
                                            onChange={(e) => {
                                                setPaymentData((p) => ({
                                                    ...p,
                                                    cvv: e.target.value,
                                                }))
                                                if (paymentErrors.cvv)
                                                    setPaymentErrors((p) => ({
                                                        ...p,
                                                        cvv: undefined,
                                                    }))
                                            }}
                                            error={paymentErrors.cvv}
                                            placeholder="123"
                                            type="password"
                                        />
                                    </div>
                                </>
                            ) : (
                                <div className="p-4 border rounded-xl bg-gray-50 text-sm text-gray-600">
                                    {paymentData.method === 'VNPAY'
                                        ? lang === 'en'
                                            ? 'You will be redirected to VNPAY portal to scan QR code and complete payment.'
                                            : 'Bạn sẽ được chuyển hướng sang cổng VNPAY để quét mã QR thanh toán sau khi bấm Đặt hàng.'
                                        : lang === 'en'
                                          ? 'You will pay in cash directly to the courier when receiving the package.'
                                          : 'Bạn sẽ thanh toán tiền mặt trực tiếp cho nhân viên giao hàng khi nhận được kiện hàng.'}
                                </div>
                            )}

                            <div className="flex gap-3">
                                <Button variant="outline" size="lg" onClick={() => setStep(2)}>
                                    {tr(lang, 'back')}
                                </Button>
                                <Button
                                    variant="primary"
                                    size="lg"
                                    disabled={purchaseBlocked}
                                    onClick={() => {
                                        if (validateStep3()) {
                                            setStep(4)
                                        }
                                    }}
                                >
                                    {tr(lang, 'next')} →
                                </Button>
                            </div>
                        </div>
                    )}

                    {/* Bước 4: Xác nhận thông tin và Đặt hàng */}
                    {step === 4 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'confirmation')}
                            </h2>

                            {/* Cảnh báo khi giá BE tính khác với giá đang hiển thị */}
                            {priceMismatchNotice && (
                                <div className="space-y-2">
                                    <AlertBanner type="warning" message={priceMismatchNotice} />
                                </div>
                            )}

                            {/* Cảnh báo khi Backend thông báo không đủ điều kiện đặt hàng */}
                            {previewData && !previewData.isOrderable && (
                                <div className="space-y-2">
                                    <AlertBanner
                                        type="error"
                                        message={
                                            lang === 'vi'
                                                ? 'Đơn hàng hiện không đủ điều kiện đặt (hết tồn kho hoặc SKU không khả dụng).'
                                                : 'Order cannot be placed (insufficient stock or SKU unavailable).'
                                        }
                                    />
                                </div>
                            )}

                            {/* Hiển thị lỗi rõ ràng khi đặt hàng hoặc chuyển hướng thất bại (Accept Criteria 2 & 5) */}
                            {checkoutError && (
                                <div className="space-y-3">
                                    <AlertBanner type="error" message={checkoutError} />
                                    {authExpired && (
                                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3.5 bg-amber-50 rounded-xl border border-amber-200 text-xs text-amber-800">
                                            <div>
                                                <p className="font-bold text-amber-900">
                                                    {lang === 'vi'
                                                        ? 'Phiên đăng nhập đã hết hạn'
                                                        : 'Session Expired'}
                                                </p>
                                                <p className="text-amber-700 mt-0.5">
                                                    {lang === 'vi'
                                                        ? 'Token xác thực đã hết hạn hoặc bạn chưa đăng nhập. Vui lòng đăng nhập lại (tài khoản: buyer@nexus.vn / Nexus123@) để hoàn tất thanh toán mà không mất thông tin đơn hàng.'
                                                        : 'Your authentication token has expired or you are not signed in. Please sign in (buyer@nexus.vn / Nexus123@) to complete payment without losing order details.'}
                                                </p>
                                            </div>
                                            <Button
                                                variant="primary"
                                                size="sm"
                                                className="shrink-0"
                                                onClick={() => {
                                                    try {
                                                        sessionStorage.setItem(
                                                            'nexus.checkout-draft',
                                                            JSON.stringify({
                                                                shippingData,
                                                                paymentMethod: paymentData.method,
                                                            }),
                                                        )
                                                    } catch {
                                                        // Bỏ qua lỗi storage
                                                    }
                                                    navigate('login', {
                                                        redirect: 'checkout',
                                                        reason: 'expired',
                                                    })
                                                }}
                                            >
                                                {lang === 'vi' ? 'Đăng nhập ngay' : 'Log in now'}
                                            </Button>
                                        </div>
                                    )}
                                </div>
                            )}

                            <div className="space-y-2 text-sm">
                                {[
                                    [
                                        lang === 'en' ? 'Ship to' : 'Giao đến',
                                        `${shippingData.fullName} (${shippingData.phone}) - ${shippingData.street}, ${shippingData.ward}, ${shippingData.district}, ${shippingData.city}`,
                                    ],
                                    [
                                        tr(lang, 'shippingMethod'),
                                        `${
                                            shipping === 'GHN'
                                                ? 'GHN Express'
                                                : shipping === 'GHTK'
                                                  ? 'GHTK Standard'
                                                  : shipping
                                        }${
                                            selectedShippingOption?.serviceType
                                                ? ` (${selectedShippingOption.serviceType})`
                                                : ''
                                        } - ${
                                            selectedShippingOption?.freeShippingApplied
                                                ? lang === 'vi'
                                                    ? 'Miễn phí'
                                                    : 'Free'
                                                : formatCurrency(currentShippingFee, lang)
                                        }`,
                                    ],
                                    [
                                        tr(lang, 'payment'),
                                        paymentData.method === 'CARD'
                                            ? `Visa ending ${paymentData.cardNumber.replace(/\s+/g, '').slice(-4) || '4242'}`
                                            : paymentData.method,
                                    ],
                                    [
                                        tr(lang, 'total'),
                                        formatCurrency(
                                            createdOrder
                                                ? (savedTotal ?? 0)
                                                : previewData
                                                  ? previewData.totalAmount - (previewData.shippingFee ?? 0) + currentShippingFee
                                                  : activeCheckoutItems.reduce(
                                                        (acc, i) => acc + i.unitPrice * i.quantity,
                                                        0,
                                                    ) + currentShippingFee,
                                            lang,
                                        ),
                                    ],
                                ].map(([k, v]) => (
                                    <div
                                        key={k}
                                        className="flex justify-between p-3 bg-gray-50 rounded-lg gap-4"
                                    >
                                        <span className="text-gray-500 shrink-0">{k}</span>
                                        <span className="font-semibold text-right">{v}</span>
                                    </div>
                                ))}
                            </div>
                            <div className="flex gap-3">
                                <Button
                                    variant="outline"
                                    size="lg"
                                    disabled={loading}
                                    onClick={() => setStep(3)}
                                >
                                    {tr(lang, 'back')}
                                </Button>
                                <Button
                                    variant="primary"
                                    size="lg"
                                    loading={loading}
                                    disabled={
                                        loading ||
                                        purchaseBlocked ||
                                        (!createdOrder &&
                                            previewData !== null &&
                                            !previewData.isOrderable)
                                    }
                                    onClick={placeOrder}
                                    className="flex-1"
                                >
                                    {createdOrder
                                        ? lang === 'vi'
                                            ? 'Thử lại thanh toán'
                                            : 'Retry Payment'
                                        : tr(lang, 'placeOrder')}
                                </Button>
                                {createdOrder && (
                                    <Button
                                        variant="outline"
                                        size="lg"
                                        disabled={loading}
                                        className="text-red-600 border-red-300 hover:bg-red-50"
                                        onClick={handleDiscardPendingOrder}
                                    >
                                        {lang === 'vi'
                                            ? 'Hủy đơn dở dang'
                                            : 'Discard pending order'}
                                    </Button>
                                )}
                            </div>
                        </div>
                    )}
                </div>

                {/* Order summary sidebar */}
                <div className="nexus-card p-5 h-fit">
                    <h3 className="font-bold mb-4">{tr(lang, 'orderSummary')}</h3>
                    {buyNowTarget && !createdOrder && (
                        <div className="mb-3 p-2.5 bg-blue-50 border border-blue-200 rounded-lg text-xs text-blue-800 flex items-center justify-between">
                            <span>
                                {lang === 'vi'
                                    ? '⚡ Mua ngay 1 sản phẩm đã chọn'
                                    : '⚡ Buy Now selected product'}
                            </span>
                            <button
                                type="button"
                                className="text-blue-600 underline hover:text-blue-800 font-medium ml-2 cursor-pointer"
                                onClick={() => {
                                    try {
                                        sessionStorage.removeItem('nexus.buy-now-target')
                                    } catch {
                                        // Bỏ qua lỗi storage
                                    }
                                    setBuyNowTarget(null)
                                }}
                            >
                                {lang === 'vi' ? 'Thanh toán cả giỏ' : 'Checkout all'}
                            </button>
                        </div>
                    )}
                    {createdOrder && (
                        <div className="mb-3 p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-900">
                            <p className="font-medium" role="status">
                                {lang === 'vi'
                                    ? `Đang thanh toán đơn ${createdOrder.orderNo}. Sản phẩm thêm vào giỏ sau đó không thuộc đơn này.`
                                    : `Paying for ${createdOrder.orderNo}. Items added later belong to your new cart.`}
                            </p>
                            <button
                                type="button"
                                className="text-xs text-red-600 underline font-semibold mt-2 hover:text-red-700 block cursor-pointer"
                                onClick={handleDiscardPendingOrder}
                            >
                                {lang === 'vi'
                                    ? 'Hủy đơn dở dang này để đặt đơn mới'
                                    : 'Discard pending order & start new'}
                            </button>
                        </div>
                    )}
                    {orderSnapshotError && (
                        <div>
                            <p role="alert">{orderSnapshotError}</p>
                            <Button variant="outline" onClick={() => navigate('order-history')}>
                                {lang === 'vi' ? 'Xem đơn hàng' : 'View orders'}
                            </Button>
                        </div>
                    )}
                    {/* Danh sách sản phẩm thực tế trong giỏ hàng (không dùng PRODUCTS mock) */}
                    <div className="space-y-3 mb-4">
                        {summaryItems.length > 0 ? (
                            summaryItems.map((item) => (
                                <div key={item.id} className="flex gap-3">
                                    <img
                                        src={
                                            item.thumbnailUrl ||
                                            'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=100'
                                        }
                                        alt={item.productName}
                                        className="w-12 h-12 object-cover rounded-lg"
                                    />
                                    <div className="flex-1 min-w-0">
                                        <p className="text-xs font-semibold line-clamp-1">
                                            {item.productName}
                                        </p>
                                        <p className="text-xs text-gray-500">
                                            {item.skuCode ? (
                                                <span className="font-mono text-gray-700 mr-1.5">
                                                    SKU: {item.skuCode}
                                                </span>
                                            ) : null}
                                            {/* Số lượng/giá cùng snapshot với tổng tiền, không trộn state giỏ cũ. */}
                                            x
                                            {(!createdOrder &&
                                                previewData?.items?.find(
                                                    (line) => line.skuId === item.skuId,
                                                )?.quantity) ||
                                                item.quantity}
                                        </p>
                                        <p className="text-sm font-bold text-primary mt-0.5">
                                            {formatCurrency(
                                                (createdOrder
                                                    ? undefined
                                                    : previewData?.items?.find(
                                                          (line) => line.skuId === item.skuId,
                                                      )?.unitPrice) ?? item.unitPrice,
                                                lang,
                                            )}
                                        </p>
                                    </div>
                                </div>
                            ))
                        ) : (
                            <p className="text-xs text-gray-400 italic py-2">
                                {createdOrder
                                    ? lang === 'vi'
                                        ? 'Đang tải thông tin đơn đã tạo…'
                                        : 'Loading saved order…'
                                    : lang === 'en'
                                      ? 'Cart is empty'
                                      : 'Chưa có sản phẩm trong giỏ hàng'}
                            </p>
                        )}
                    </div>
                    <div className="border-t border-border pt-3 space-y-2 text-sm">
                        {(() => {
                            // Retry hiển thị giá chính thức nếu có, nếu chưa thì dùng estimated của đúng đơn.
                            if (createdOrder && savedPricing) {
                                return [
                                    [
                                        tr(lang, 'subtotal'),
                                        savedPricing.subtotal ?? savedPricing.estimatedSubtotal,
                                    ],
                                    [
                                        tr(lang, 'shipping'),
                                        savedPricing.shippingFee ??
                                            savedPricing.estimatedShippingFee,
                                    ],
                                    [
                                        tr(lang, 'discount'),
                                        savedPricing.discount ?? savedPricing.estimatedDiscount,
                                    ],
                                    [tr(lang, 'total'), savedTotal],
                                ].map(([label, amount]) => (
                                    <div key={String(label)} className="flex justify-between">
                                        <span>{label}</span>
                                        <span>
                                            {typeof amount === 'number'
                                                ? formatCurrency(amount, lang)
                                                : '—'}
                                        </span>
                                    </div>
                                ))
                            }
                            const pricing = calculateEstimatedPricing(activeCheckoutItems, null)
                            // Ưu tiên hiển thị giá, phí ship và tổng tiền do Backend tính toán qua checkoutPreview
                            const subtotal = previewData
                                ? previewData.subtotal
                                : pricing.baseSubtotal
                            // Phí vận chuyển cập nhật động theo lựa chọn phương thức (HA-S3-1)
                            const shippingFee = currentShippingFee
                            const discount = previewData
                                ? previewData.discountAmount
                                : pricing.merchantVolumeDiscount +
                                  pricing.categoryDiscount +
                                  pricing.voucherDiscount
                            const total = previewData
                                ? previewData.totalAmount - (previewData.shippingFee ?? 0) + currentShippingFee
                                : Math.max(
                                      0,
                                      subtotal -
                                          discount +
                                          shippingFee +
                                          pricing.estimatedTax +
                                          pricing.peakHourFee,
                                  )

                            return [
                                [tr(lang, 'subtotal'), formatCurrency(subtotal, lang)],
                                ...(pricing.merchantVolumeDiscount > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Shop Bulk Discount'
                                                  : 'Ưu đãi mua nhiều cùng Shop',
                                              `-${formatCurrency(pricing.merchantVolumeDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(pricing.categoryDiscount > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Category Promotion'
                                                  : 'Khuyến mãi ngành hàng',
                                              `-${formatCurrency(pricing.categoryDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(pricing.voucherDiscount > 0
                                    ? [
                                          [
                                              tr(lang, 'discount'),
                                              `-${formatCurrency(pricing.voucherDiscount, lang)}`,
                                          ],
                                      ]
                                    : []),
                                ...(pricing.peakHourFee > 0
                                    ? [
                                          [
                                              lang === 'en'
                                                  ? 'Peak Hour Surcharge'
                                                  : 'Phụ phí giờ cao điểm',
                                              `+${formatCurrency(pricing.peakHourFee, lang)}`,
                                          ],
                                      ]
                                    : []),
                                [tr(lang, 'shipping'), formatCurrency(shippingFee, lang)],
                                [tr(lang, 'tax'), formatCurrency(pricing.estimatedTax, lang)],
                                [tr(lang, 'total'), formatCurrency(total, lang)],
                            ].map(([k, v], i, arr) => (
                                <div
                                    key={k}
                                    className={`flex justify-between ${i === arr.length - 1 ? 'font-bold text-base border-t border-border pt-2 mt-2 text-primary' : 'text-gray-600'}`}
                                >
                                    <span>{k}</span>
                                    <span
                                        className={
                                            String(v).startsWith('-')
                                                ? 'text-success font-medium'
                                                : ''
                                        }
                                    >
                                        {v}
                                    </span>
                                </div>
                            ))
                        })()}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Sandbox Payment Page (VNPAY Sandbox Gateway) ──────────────────────────────
/**
 * Chức năng: Cổng thanh toán VNPAY Sandbox độc lập (Standalone Gateway), giả lập hoàn chỉnh quy trình thanh toán VNPAY.
 * Đầu vào: orderId (mã đơn hàng), amount (số tiền thanh toán), provider (vnpay) từ URL query params.
 * Kết quả: Cập nhật trạng thái đơn hàng và chuyển hướng sang trang kết quả thanh toán (/payment-result).
 * Luồng xử lý:
 *   1. Hiển thị giao diện cổng thanh toán độc lập của VNPAY (không phụ thuộc vào khung điều hướng của shop).
 *   2. Hỗ trợ 3 hình thức: Thẻ nội địa ATM (với ngân hàng NCB Sandbox và xác thực OTP), Quét mã VNPAY-QR, Thẻ quốc tế.
 *   3. Người dùng chọn phương thức -> xác thực -> gọi ordersApi.updateOrderStatus(orderId, 'PAID') -> điều hướng về /payment-result?status=success.
 *   4. Các nút phụ hỗ trợ kiểm thử:
 *      - Mô phỏng thất bại: chuyển sang /payment-result?status=failed&reason=Sandbox transaction rejected.
 *      - Hủy thanh toán: chuyển sang /payment-result?status=cancelled.
 * Quy tắc & lý do: Hoạt động như cổng thanh toán độc lập (Full-screen standalone portal) như VNPAY thật ngoài đời thực,
 *                  giúp kiểm thử toàn bộ luồng thanh toán mà không yêu cầu cấu hình merchant key từ cổng ngoài.
 * Lỗi & giới hạn: Giao diện mô phỏng sandbox phục vụ dev/test nội bộ, không phát sinh giao dịch tài chính thật.
 */
export function SandboxPaymentPage() {
    const { lang, navigate, pageParams, showToast } = useApp()
    const [simulating, setSimulating] = useState(false)
    const [tab, setTab] = useState<'qr' | 'atm' | 'intl'>('atm')
    const [selectedBank, setSelectedBank] = useState<string>('NCB')
    const [cardNumber, setCardNumber] = useState('9704 1985 2619 1432 198')
    const [cardHolder, setCardHolder] = useState('NGUYEN VAN A')
    const [issueDate, setIssueDate] = useState('07/15')
    const [otpStep, setOtpStep] = useState(false)
    const [otpCode, setOtpCode] = useState('123456')
    const [secondsLeft, setSecondsLeft] = useState(899)

    // Đếm ngược 15 phút thanh toán theo quy chuẩn VNPAY
    useEffect(() => {
        const timer = setInterval(() => {
            setSecondsLeft((prev) => (prev > 0 ? prev - 1 : 0))
        }, 1000)
        return () => clearInterval(timer)
    }, [])

    const formatTimer = (secs: number) => {
        const m = Math.floor(secs / 60)
        const s = secs % 60
        return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    }

    const orderId = (pageParams?.orderId as string) || 'ORD-SANDBOX-DEMO'
    const amount =
        typeof pageParams?.amount === 'number'
            ? pageParams.amount
            : typeof pageParams?.amount === 'string'
              ? parseFloat(pageParams.amount) || 329.97
              : 329.97

    const displayAmount =
        amount > 1000
            ? `${Math.round(amount).toLocaleString('vi-VN')} ₫`
            : formatCurrency(amount, lang)

    // Mô phỏng thanh toán thành công
    const handleSimulateSuccess = async () => {
        setSimulating(true)
        try {
            await new Promise((r) => setTimeout(r, 600))
            try {
                await ordersApi.updateOrderStatus(orderId, 'PAID', 'VNPAY Sandbox payment approved')
            } catch {
                // Cập nhật trạng thái đơn hàng mock trong localStorage
                try {
                    const localOrders = readLocalMockOrders()
                    const idx = localOrders.findIndex(
                        (o) => o.id === orderId || (o as any).orderNo === orderId,
                    )
                    if (idx !== -1) {
                        localOrders[idx].status = 'PAID'
                        localOrders[idx].paymentStatus = 'held'
                        localOrders[idx].updatedAt = new Date().toISOString()
                        writeLocalMockOrders(localOrders)
                    }
                } catch {
                    // Bỏ qua
                }
            }
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'VNPAY payment approved successfully!'
                        : 'Thanh toán VNPAY thành công!',
            })
            navigate('payment-result', { status: 'success', orderId })
        } catch {
            navigate('payment-result', { status: 'success', orderId })
        } finally {
            setSimulating(false)
        }
    }

    // Mô phỏng thanh toán thất bại
    const handleSimulateFailure = async () => {
        setSimulating(true)
        await new Promise((r) => setTimeout(r, 400))
        setSimulating(false)
        showToast({
            type: 'error',
            message:
                lang === 'en'
                    ? 'Payment declined by VNPAY gateway.'
                    : 'Giao dịch bị từ chối bởi cổng VNPAY.',
        })
        navigate('payment-result', {
            status: 'failed',
            orderId,
            reason: 'Sandbox transaction rejected',
        })
    }

    // Hủy thanh toán
    const handleCancel = () => {
        showToast({
            type: 'info',
            message:
                lang === 'en' ? 'Payment transaction cancelled.' : 'Đã hủy giao dịch thanh toán.',
        })
        navigate('payment-result', { status: 'cancelled', orderId })
    }

    // Điền nhanh thông tin thẻ test VNPAY
    const handleFillTestCard = () => {
        setCardNumber('9704 1985 2619 1432 198')
        setCardHolder('NGUYEN VAN A')
        setIssueDate('07/15')
        setSelectedBank('NCB')
        showToast({
            type: 'info',
            message: 'Đã điền thông tin thẻ test VNPAY (Ngân hàng NCB)',
        })
    }

    const banks = [
        { code: 'NCB', name: 'NCB (Test VNPAY)', recommended: true },
        { code: 'VCB', name: 'Vietcombank' },
        { code: 'BIDV', name: 'BIDV' },
        { code: 'CTG', name: 'VietinBank' },
        { code: 'TCB', name: 'Techcombank' },
        { code: 'MB', name: 'MBBank' },
        { code: 'TPB', name: 'TPBank' },
        { code: 'ACB', name: 'ACB' },
    ]

    return (
        <div className="min-h-screen bg-[#f3f5f8] flex flex-col justify-between text-slate-800">
            {/* Header Cổng thanh toán độc lập chuẩn VNPAY */}
            <header className="bg-white border-b border-slate-200 shadow-sm sticky top-0 z-40">
                <div className="max-w-4xl mx-auto px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="flex items-center">
                            <span className="text-2xl font-black text-[#005baa] tracking-tight">
                                VN
                            </span>
                            <span className="text-2xl font-black text-[#e11b22] tracking-tight">
                                PAY
                            </span>
                        </div>
                        <div className="h-6 w-px bg-slate-200 hidden sm:block" />
                        <div className="hidden sm:block">
                            <div className="text-xs font-bold text-slate-700 uppercase tracking-wide">
                                CỔNG THANH TOÁN ĐIỆN TỬ
                            </div>
                            <div className="text-[10px] text-slate-400">VNPAY Payment Gateway</div>
                        </div>
                    </div>

                    <div className="flex items-center gap-2 sm:gap-4">
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                            <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
                            Môi trường Sandbox / Sandbox Environment
                        </span>
                        <div className="flex items-center gap-1.5 px-2.5 py-1 bg-slate-100 rounded-lg text-xs font-mono font-bold text-slate-700">
                            <Clock size={13} className="text-slate-500" />
                            <span>{formatTimer(secondsLeft)}</span>
                        </div>
                    </div>
                </div>
            </header>

            {/* Nội dung thanh toán chính */}
            <main className="flex-1 py-8 px-4 flex items-center justify-center">
                <div className="w-full max-w-2xl bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden">
                    {/* Header banner thông tin VNPAY */}
                    <div className="bg-gradient-to-r from-[#005baa] to-[#003b73] text-white p-5 border-b-4 border-[#e11b22]">
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div>
                                <h1 className="text-lg font-black tracking-wide flex items-center gap-2">
                                    CỔNG THANH TOÁN VNPAY
                                </h1>
                                <p className="text-xs text-blue-100 mt-0.5">
                                    Thanh toán an toàn qua cổng điện tử VNPAY
                                </p>
                            </div>
                            <div className="flex items-center gap-2 text-xs bg-white/10 px-3 py-1.5 rounded-xl border border-white/15 self-start sm:self-auto">
                                <Shield size={14} className="text-emerald-300" />
                                <span>Bảo mật 256-bit SSL</span>
                            </div>
                        </div>
                    </div>

                    {/* Thân card thanh toán */}
                    <div className="p-6 space-y-6">
                        {/* Hộp tóm tắt đơn hàng */}
                        <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 text-sm divide-y divide-slate-200">
                            <div className="flex justify-between items-center pb-2.5">
                                <span className="text-slate-500">Đơn vị chấp nhận thanh toán:</span>
                                <span className="font-semibold text-slate-800">
                                    NEXUS E-COMMERCE
                                </span>
                            </div>
                            <div className="flex justify-between items-center py-2.5">
                                <span className="text-slate-500">Mã đơn hàng:</span>
                                <span className="font-mono font-bold text-slate-900 bg-white px-2 py-0.5 rounded border border-slate-200">
                                    {orderId}
                                </span>
                            </div>
                            <div className="flex justify-between items-center pt-2.5">
                                <span className="text-slate-500 font-medium">
                                    Số tiền thanh toán:
                                </span>
                                <span className="text-2xl font-black text-[#e11b22]">
                                    {displayAmount}
                                </span>
                            </div>
                        </div>

                        {/* Thanh chọn phương thức thanh toán */}
                        <div className="flex border-b border-gray-200 text-sm font-semibold">
                            <button
                                type="button"
                                onClick={() => {
                                    setTab('atm')
                                    setOtpStep(false)
                                }}
                                className={`flex-1 py-3 text-center border-b-2 transition-colors ${
                                    tab === 'atm'
                                        ? 'border-[#005baa] text-[#005baa] bg-blue-50/50'
                                        : 'border-transparent text-gray-500 hover:text-gray-700'
                                }`}
                            >
                                💳 Thẻ nội địa / ATM
                            </button>
                            <button
                                type="button"
                                onClick={() => {
                                    setTab('qr')
                                    setOtpStep(false)
                                }}
                                className={`flex-1 py-3 text-center border-b-2 transition-colors ${
                                    tab === 'qr'
                                        ? 'border-[#005baa] text-[#005baa] bg-blue-50/50'
                                        : 'border-transparent text-gray-500 hover:text-gray-700'
                                }`}
                            >
                                📱 VNPAY-QR
                            </button>
                            <button
                                type="button"
                                onClick={() => {
                                    setTab('intl')
                                    setOtpStep(false)
                                }}
                                className={`flex-1 py-3 text-center border-b-2 transition-colors ${
                                    tab === 'intl'
                                        ? 'border-[#005baa] text-[#005baa] bg-blue-50/50'
                                        : 'border-transparent text-gray-500 hover:text-gray-700'
                                }`}
                            >
                                🌐 Thẻ quốc tế
                            </button>
                        </div>

                        {/* Tab 1: Thẻ nội địa / ATM */}
                        {tab === 'atm' && (
                            <div className="space-y-4 pt-1">
                                {!otpStep ? (
                                    <>
                                        {/* Danh sách ngân hàng */}
                                        <div>
                                            <label className="block text-xs font-semibold text-slate-700 mb-2">
                                                Chọn ngân hàng thanh toán:
                                            </label>
                                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                                                {banks.map((b) => (
                                                    <button
                                                        key={b.code}
                                                        type="button"
                                                        onClick={() => setSelectedBank(b.code)}
                                                        className={`p-2.5 rounded-xl border text-xs font-bold transition-all text-center relative ${
                                                            selectedBank === b.code
                                                                ? 'border-[#005baa] bg-blue-50/80 text-[#005baa] ring-2 ring-blue-200'
                                                                : 'border-slate-200 bg-white text-slate-700 hover:border-slate-300'
                                                        }`}
                                                    >
                                                        {b.name}
                                                        {b.recommended && (
                                                            <span className="block text-[9px] font-normal text-emerald-600 mt-0.5">
                                                                (Sandbox Test)
                                                            </span>
                                                        )}
                                                    </button>
                                                ))}
                                            </div>
                                        </div>

                                        {/* Nút tự động điền thẻ test */}
                                        <button
                                            type="button"
                                            onClick={handleFillTestCard}
                                            className="w-full text-xs font-semibold text-[#005baa] bg-blue-50 hover:bg-blue-100 p-2.5 rounded-xl border border-blue-200 transition-colors flex items-center justify-center gap-1.5"
                                        >
                                            ⚡ Bấm vào đây để tự động điền thẻ test VNPAY Sandbox
                                            (NCB)
                                        </button>

                                        {/* Form nhập thẻ */}
                                        <div className="space-y-3">
                                            <Input
                                                label="Số thẻ *"
                                                value={cardNumber}
                                                onChange={(e) => setCardNumber(e.target.value)}
                                                placeholder="9704 1985 2619 1432 198"
                                            />
                                            <Input
                                                label="Tên in trên thẻ *"
                                                value={cardHolder}
                                                onChange={(e) =>
                                                    setCardHolder(e.target.value.toUpperCase())
                                                }
                                                placeholder="NGUYEN VAN A"
                                            />
                                            <Input
                                                label="Ngày phát hành (MM/YY) *"
                                                value={issueDate}
                                                onChange={(e) => setIssueDate(e.target.value)}
                                                placeholder="07/15"
                                            />
                                        </div>

                                        <Button
                                            variant="primary"
                                            size="lg"
                                            className="w-full bg-[#005baa] hover:bg-[#004885] text-white font-bold"
                                            loading={simulating}
                                            onClick={handleSimulateSuccess}
                                        >
                                            ✓{' '}
                                            {lang === 'en'
                                                ? 'Simulate Success'
                                                : 'Xác nhận thanh toán thành công (Simulate Success)'}
                                        </Button>
                                    </>
                                ) : (
                                    /* Bước xác thực OTP */
                                    <div className="space-y-4 p-5 bg-blue-50/50 rounded-2xl border border-blue-200">
                                        <div className="text-center space-y-1">
                                            <div className="w-12 h-12 bg-[#005baa] text-white rounded-full flex items-center justify-center mx-auto text-xl font-bold">
                                                🔒
                                            </div>
                                            <h3 className="font-bold text-slate-800 text-sm">
                                                Xác thực giao dịch OTP ({selectedBank})
                                            </h3>
                                            <p className="text-xs text-slate-500">
                                                Mã OTP bảo mật được gửi đến SĐT đăng ký của chủ thẻ{' '}
                                                {cardHolder}.
                                            </p>
                                        </div>

                                        <Input
                                            label="Nhập mã xác thực OTP (Mã test mặc định: 123456) *"
                                            value={otpCode}
                                            onChange={(e) => setOtpCode(e.target.value)}
                                            placeholder="123456"
                                        />

                                        <div className="space-y-2 pt-2">
                                            <Button
                                                variant="primary"
                                                size="lg"
                                                className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold"
                                                loading={simulating}
                                                onClick={handleSimulateSuccess}
                                            >
                                                ✓ Xác nhận thanh toán thành công (Simulate Success)
                                            </Button>
                                            <Button
                                                variant="outline"
                                                size="md"
                                                className="w-full"
                                                disabled={simulating}
                                                onClick={() => setOtpStep(false)}
                                            >
                                                ← Quay lại sửa thông tin thẻ
                                            </Button>
                                        </div>
                                    </div>
                                )}
                            </div>
                        )}

                        {/* Tab 2: VNPAY-QR */}
                        {tab === 'qr' && (
                            <div className="space-y-4 pt-1">
                                <div className="flex flex-col items-center justify-center p-6 border-2 border-dashed border-blue-200 rounded-2xl bg-gradient-to-b from-blue-50/60 to-white">
                                    <div className="w-52 h-52 bg-white border-2 border-[#005baa] rounded-2xl p-3 flex flex-col items-center justify-center shadow-md relative">
                                        <svg
                                            className="w-full h-full text-[#003b73]"
                                            viewBox="0 0 100 100"
                                            fill="currentColor"
                                        >
                                            <rect
                                                x="5"
                                                y="5"
                                                width="28"
                                                height="28"
                                                rx="3"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="9"
                                                y="9"
                                                width="20"
                                                height="20"
                                                rx="2"
                                                fill="#fff"
                                            />
                                            <rect
                                                x="13"
                                                y="13"
                                                width="12"
                                                height="12"
                                                fill="#005baa"
                                            />

                                            <rect
                                                x="67"
                                                y="5"
                                                width="28"
                                                height="28"
                                                rx="3"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="71"
                                                y="9"
                                                width="20"
                                                height="20"
                                                rx="2"
                                                fill="#fff"
                                            />
                                            <rect
                                                x="75"
                                                y="13"
                                                width="12"
                                                height="12"
                                                fill="#005baa"
                                            />

                                            <rect
                                                x="5"
                                                y="67"
                                                width="28"
                                                height="28"
                                                rx="3"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="9"
                                                y="71"
                                                width="20"
                                                height="20"
                                                rx="2"
                                                fill="#fff"
                                            />
                                            <rect
                                                x="13"
                                                y="75"
                                                width="12"
                                                height="12"
                                                fill="#005baa"
                                            />

                                            <rect
                                                x="38"
                                                y="10"
                                                width="8"
                                                height="20"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="50"
                                                y="24"
                                                width="12"
                                                height="8"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="38"
                                                y="72"
                                                width="14"
                                                height="8"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="56"
                                                y="64"
                                                width="8"
                                                height="16"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="70"
                                                y="70"
                                                width="16"
                                                height="16"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="70"
                                                y="44"
                                                width="10"
                                                height="10"
                                                fill="#005baa"
                                            />
                                            <rect
                                                x="44"
                                                y="44"
                                                width="12"
                                                height="12"
                                                fill="#005baa"
                                            />

                                            {/* Logo VNPAY trung tâm QR */}
                                            <rect
                                                x="35"
                                                y="35"
                                                width="30"
                                                height="30"
                                                rx="6"
                                                fill="#fff"
                                                stroke="#005baa"
                                                strokeWidth="2"
                                            />
                                            <text
                                                x="37"
                                                y="52"
                                                fontFamily="Arial, sans-serif"
                                                fontWeight="900"
                                                fontSize="9"
                                                fill="#005baa"
                                            >
                                                VN
                                            </text>
                                            <text
                                                x="51"
                                                y="52"
                                                fontFamily="Arial, sans-serif"
                                                fontWeight="900"
                                                fontSize="9"
                                                fill="#e11b22"
                                            >
                                                PAY
                                            </text>
                                        </svg>
                                    </div>

                                    <p className="text-xs font-semibold text-slate-700 mt-3 text-center">
                                        Quét mã để thanh toán bằng ứng dụng Ngân hàng hoặc ví VNPAY
                                    </p>
                                    <p className="text-[11px] text-slate-500 mt-1 text-center max-w-sm">
                                        Hỗ trợ VCB Digibank, BIDV SmartBanking, VietinBank iPay,
                                        Agribank, MB Bank, App VNPAY...
                                    </p>
                                </div>

                                <Button
                                    variant="primary"
                                    size="lg"
                                    className="w-full bg-[#005baa] hover:bg-[#004885] text-white font-bold shadow-md"
                                    loading={simulating}
                                    onClick={handleSimulateSuccess}
                                >
                                    ✓ Xác nhận thanh toán thành công (Simulate Success)
                                </Button>
                            </div>
                        )}

                        {/* Tab 3: Thẻ quốc tế */}
                        {tab === 'intl' && (
                            <div className="space-y-4 pt-1">
                                <div className="p-3 bg-slate-50 rounded-xl text-xs text-slate-600 border border-slate-200">
                                    ℹ️ Chấp nhận thẻ Visa, MasterCard, JCB phát hành trong nước và
                                    quốc tế.
                                </div>
                                <Input
                                    label="Số thẻ quốc tế *"
                                    defaultValue="4242 4242 4242 4242"
                                    placeholder="4242 4242 4242 4242"
                                />
                                <div className="grid grid-cols-2 gap-3">
                                    <Input
                                        label="Hết hạn (MM/YY) *"
                                        defaultValue="12/28"
                                        placeholder="MM/YY"
                                    />
                                    <Input label="CVV/CVC *" defaultValue="123" placeholder="123" />
                                </div>
                                <Button
                                    variant="primary"
                                    size="lg"
                                    className="w-full bg-[#005baa] hover:bg-[#004885] text-white font-bold"
                                    loading={simulating}
                                    onClick={handleSimulateSuccess}
                                >
                                    ✓ Xác nhận thanh toán thành công (Simulate Success)
                                </Button>
                            </div>
                        )}

                        {/* Các nút hành động phụ (Mô phỏng lỗi / Hủy đơn) */}
                        <div className="space-y-2.5 pt-3 border-t border-slate-200">
                            <Button
                                variant="danger"
                                size="md"
                                className="w-full text-xs font-semibold"
                                disabled={simulating}
                                onClick={handleSimulateFailure}
                            >
                                ✕ Mô phỏng thanh toán thất bại (Simulate Failure)
                            </Button>
                            <Button
                                variant="outline"
                                size="md"
                                className="w-full text-xs font-semibold"
                                disabled={simulating}
                                onClick={handleCancel}
                            >
                                ← Hủy thanh toán (Cancel payment)
                            </Button>
                        </div>
                    </div>
                </div>
            </main>

            {/* Footer Cổng thanh toán độc lập */}
            <footer className="bg-white border-t border-slate-200 py-4 px-4 text-center text-[11px] text-slate-500 space-y-1">
                <div>
                    Cổng thanh toán điện tử VNPAY thuộc Công ty Cổ phần Giải pháp Thanh toán Việt
                    Nam (VNPAY)
                </div>
                <div className="text-slate-400">
                    Hotline hỗ trợ: 1900 55 55 77 | Email: hotro@vnpay.vn | Môi trường thử nghiệm
                    Sandbox
                </div>
            </footer>
        </div>
    )
}

/** URL chỉ mang ID tra cứu; trạng thái hiển thị lấy từ API có xác thực. */
export function PaymentResultPage() {
    const { pageParams } = useApp()
    const paymentId = String(pageParams?.vnp_TxnRef || pageParams?.paymentId || '')
    const orderId = String(pageParams?.orderId || '')
    return (
        <BuyerLayout>
            {paymentId ? (
                <VnpayPaymentResult key={paymentId} paymentId={paymentId} />
            ) : (
                <VerifiedOrderResult key={orderId} orderId={orderId} />
            )}
        </BuyerLayout>
    )
}

/** Lịch sử đơn lấy từ backend; lỗi hiển thị để người mua có thể thử lại. */
/**
 * Chức năng: Màn hình Lịch sử đơn hàng của Buyer (OrderHistoryPage) - Chuẩn Figma & Image 3.
 * Đầu vào: Bộ lọc trạng thái, tìm kiếm, context người dùng.
 * Kết quả: Render OrderHistoryView chuẩn Image 3 với đầy đủ chức năng tra cứu, hủy đơn và điều hướng.
 */
export function OrderHistoryPage() {
    return <OrderHistoryView />
}

/**
 * Chức năng: Màn hình Chi tiết đơn hàng của Buyer (OrderDetailPage) - Chuẩn Figma & Image 2.
 * Đầu vào: ID đơn hàng từ pageParams.
 * Kết quả: Render OrderDetailView chuẩn Image 2 với Stepper 5 bước, đa sản phẩm, giá chuẩn không 0đ,
 *          địa chỉ, thanh toán VNPAY và thông tin người bán.
 */
export function OrderDetailPage() {
    return <OrderDetailView />
}

/**
 * Chức năng: Màn hình Gian hàng công khai của Người bán (SellerShopPage) - Chuẩn Figma & Image 4.
 * Đầu vào: sellerId, sellerName từ URL query/params.
 * Kết quả: Render SellerShopView chuẩn Image 4 với banner tông trầm sang trọng, lưới 2x3 metrics Shopee,
 *          voucher shop, 3 sản phẩm chủ lực, live auctions và đánh giá.
 */
export function SellerShopPage() {
    return <SellerShopView />
}

/**
 * Chức năng: Màn hình Hồ sơ uy tín & Đánh giá (MyReputationPage) - Chuẩn Figma & Image 4.
 * Đầu vào: Context người dùng, ID shop.
 * Kết quả: Render SellerShopView chuẩn Image 4 với đầy đủ tabs đánh giá và uy tín người bán.
 */
export function MyReputationPage() {
    return <SellerShopView />
}

export { NotificationInbox as NotificationPage } from './NotificationInbox'
