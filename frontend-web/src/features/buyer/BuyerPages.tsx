/** Các màn Buyer đang được router dùng; Catalog và Profile có module riêng. Shop/auction demo được giữ cho sprint tiếp theo. */
import { useEffect, useState, useCallback, useRef } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
import { BuyerLayout } from '../../components/Layout'
import { ProductCard } from '../../components/Cards'
import { AuctionCard } from '../../components/Cards'
import {
    Button,
    Input,
    Textarea,
    NumberStepper,
    Tabs,
    Modal,
    ConfirmModal,
    AlertBanner,
    EmptyState,
    StarRating,
    StatusBadge,
    OrderTimeline,
    ProgressBar,
    Breadcrumb,
    Download,
    Search,
    ChevronRight,
    AlertTriangle,
    CheckCircle,
    Truck,
    Package,
    ShoppingCart,
    CreditCard,
    Shield,
    Spinner,
    Trash2,
} from '../../components/UI'
import { PRODUCTS, AUCTIONS } from '../../mockData'
import { CATEGORY_ICONS } from '../../components/Layout'
import { getEffectivePaymentStatus } from '../../payment'
import { getGuestCart, updateGuestCartItem, removeGuestCartItem, clearGuestCart } from './guestCart'
import { VnpayPaymentResult } from './VnpayPaymentResult'
import { VerifiedOrderResult } from './VerifiedOrderResult'
import type { Order, Product } from '../../types'
import { ordersApi, type CreateOrderRequest } from '../../api/orders'
import { useLoad } from '../../hooks/useLoad'
import {
    readSession,
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

function getAuctionCurrentBid(auction: unknown) {
    if (!auction || typeof auction !== 'object') return 0
    const value = Reflect.get(auction, 'currentBid')
    return typeof value === 'number' ? value : 0
}

// ─── Home Page ─────────────────────────────────────────────────────────────────
export function HomePage() {
    const { lang, navigate, createdAuctions, auctionReviews } = useApp()

    /**
     * Chức năng: Lấy danh sách các phiên đấu giá đang diễn ra (Live Auctions) trên trang chủ.
     * Đầu vào: createdAuctions (các phiên do người dùng tạo trong phiên làm việc) kết hợp AUCTIONS đang hoạt động.
     * Quy tắc: Lọc các phiên có trạng thái 'active' và có ảnh sản phẩm để hiển thị trên Carousel Hero và Section Đấu giá trực tiếp.
     */
    const activeAuctions = [...createdAuctions, ...AUCTIONS]
        .map((auction) => ({
            ...auction,
            status: auctionReviews[auction.id]?.status || auction.status,
        }))
        .filter((auction) => auction?.status === 'active' && auction.product?.images?.[0])
    const [heroIndex, setHeroIndex] = useState(0)
    const heroAuction =
        activeAuctions.length > 0 ? activeAuctions[heroIndex % activeAuctions.length] : null

    // Tải danh mục thật từ Catalog Service backend (/api/v1/categories)
    const { data: catData } = useLoad<Envelope<ApiCategory[]>>('/categories')
    // Tải danh sách sản phẩm thật từ Catalog Service backend (/api/v1/products)
    const { data: prodData } = useLoad<Envelope<ApiProduct[]>>('/products')

    /**
     * Chức năng: Ánh xạ danh mục trực tiếp từ Catalog backend kết nối Supabase; không dùng CATEGORIES mockData.
     */
    const liveCategories = (catData?.data || []).map((c) => ({
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
        const categoryObj = catData?.data?.find((c) => c.id === p.categoryId)
        const categoryName = categoryObj
            ? categoryObj.nameVi || categoryObj.name
            : 'Thiết bị điện tử'
        return {
            id: p.id,
            name: p.name,
            nameVi: p.name,
            category: categoryName,
            categoryId: p.categoryId,
            price: sku?.price || 0,
            originalPrice: sku?.originalPrice,
            sku: sku?.skuCode || '',
            images:
                p.images && p.images.length > 0
                    ? p.images.map((img) => img.url)
                    : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
            description: p.description || '',
            descriptionVi: p.description || '',
            enableAuction: p.auctionEnabled || false,
            stock: 50,
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
        setHeroIndex((index) => (activeAuctions.length > 0 ? index % activeAuctions.length : 0))
        if (activeAuctions.length < 2) return
        const timer = window.setInterval(() => {
            setHeroIndex((index) => (index + 1) % activeAuctions.length)
        }, 5000)
        return () => window.clearInterval(timer)
    }, [activeAuctions.length])

    return (
        <BuyerLayout noPad>
            {/* Hero Banner - Thể hiện dữ liệu thực tế từ Database */}
            <section className="relative min-h-[500px] overflow-hidden bg-gray-950 text-white">
                {activeAuctions.length > 0 ? (
                    activeAuctions.map((auction, index) => (
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
                            {lang === 'en'
                                ? `${liveProducts.length} verified products in database`
                                : `${liveProducts.length} sản phẩm thực tế từ Database`}
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
                            {lang === 'en'
                                ? 'Real-time verified catalog and inventory directly queried from Cloud Supabase Database.'
                                : 'Toàn bộ danh mục và sản phẩm được truy vấn trực tiếp từ cơ sở dữ liệu Cloud Supabase.'}
                        </p>
                        <div className="flex gap-3">
                            <Button
                                variant="primary"
                                size="lg"
                                className="bg-white text-gray-900 hover:bg-gray-100 font-semibold"
                                onClick={() => navigate('search')}
                            >
                                {lang === 'en' ? 'Shop Now' : 'Mua sắm ngay'}
                            </Button>
                            {liveProducts[0] && (
                                <Button
                                    variant="ghost"
                                    size="lg"
                                    className="border-white/40 text-white hover:bg-white/10 border"
                                    onClick={() =>
                                        navigate('product-detail', { id: liveProducts[0].id })
                                    }
                                >
                                    <Package size={16} />
                                    {liveProducts[0].name}
                                </Button>
                            )}
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

                {activeAuctions.length > 1 && (
                    <div className="absolute z-20 bottom-7 left-1/2 -translate-x-1/2 flex items-center gap-2">
                        {activeAuctions.map((auction, index) => (
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
                    <div className="grid grid-cols-6 gap-4">
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
                                        {Icon && <Icon width="28" height="28" strokeWidth="1.4" />}
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
                </section>

                {/* Live Auctions */}
                {activeAuctions.length > 0 && (
                    <section>
                        <div className="flex items-center justify-between mb-5">
                            <div className="flex items-center gap-3">
                                <h2 className="text-2xl font-bold text-gray-900">
                                    {tr(lang, 'liveAuctions')}
                                </h2>
                                <span className="badge badge-orange auction-live text-sm px-3 py-1">
                                    {lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}
                                </span>
                            </div>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={() => navigate('auction-list')}
                            >
                                {tr(lang, 'viewAll')} →
                            </Button>
                        </div>
                        <div className="grid grid-cols-4 gap-5">
                            {activeAuctions.map((a) => (
                                <AuctionCard key={a.id} auction={a} />
                            ))}
                        </div>
                    </section>
                )}

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
                    {liveProducts.length > 0 ? (
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

    const loadCart = useCallback(async () => {
        setLoading(true)
        setError(null)
        try {
            const data = await getActiveCart()
            setCart(data)
            setCartCount(data.totalQuantity)
        } catch (err) {
            const session = readSession()
            if (session) {
                // Người dùng đã đăng nhập nhưng gọi API bị lỗi -> báo lỗi API thực tế
                setError(errorMessage(err))
            } else {
                // Khách chưa đăng nhập -> nạp dữ liệu từ giỏ hàng khách guestCart trong localStorage
                const guestCart = getGuestCart()
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
                    <Spinner size={36} color="#ee4d2d" />
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

    // Tính toán tổng tiền an toàn dựa trên API response (Accept Criteria 3)
    const items = cart?.items || []
    const subtotal = cart?.subtotal || 0
    const discount = appliedVoucher ? subtotal * 0.1 : cart?.discountAmount || 0
    const shipping = cart?.shippingFee || (subtotal >= 100 ? 0 : 8.99)
    const tax = cart?.taxAmount || (subtotal - discount) * 0.08
    const total = subtotal - discount + shipping + tax

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

            {/* Thông báo miễn phí vận chuyển */}
            {subtotal < 100 && (
                <AlertBanner
                    type="info"
                    message={
                        lang === 'en'
                            ? `Add $${(100 - subtotal).toFixed(2)} more to get free shipping!`
                            : `Thêm ${formatCurrency(100 - subtotal, lang)} để được miễn phí vận chuyển!`
                    }
                />
            )}
            {subtotal < 100 && (
                <div className="mt-3 mb-5">
                    <ProgressBar value={subtotal} max={100} />
                </div>
            )}

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
                                                    {item.skuCode}
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
                                [
                                    tr(lang, 'shipping'),
                                    shipping === 0
                                        ? tr(lang, 'freeShipping')
                                        : formatCurrency(shipping, lang),
                                ],
                                [tr(lang, 'tax'), formatCurrency(tax, lang)],
                                ...(discount > 0
                                    ? [[tr(lang, 'discount'), `-${formatCurrency(discount, lang)}`]]
                                    : []),
                            ].map(([k, v]) => (
                                <div key={k} className="flex justify-between text-gray-600">
                                    <span>{k}</span>
                                    <span
                                        className={
                                            String(v).startsWith('-')
                                                ? 'text-success font-semibold'
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
                        onClick={() => navigate('checkout')}
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
    const [shipping, setShipping] = useState<'GHN' | 'GHTK'>('GHN')
    const [loading, setLoading] = useState(false)

    // Dữ liệu biểu mẫu giao hàng (Step 1)
    const [shippingData, setShippingData] = useState<ShippingFormData>({
        fullName: 'Minh Tran',
        phone: '0901234567',
        street: '123 Nguyen Hue Street',
        city: 'Ho Chi Minh City',
        district: 'District 1',
        ward: 'Ben Nghe',
    })

    // Dữ liệu biểu mẫu thanh toán (Step 3)
    const [paymentData, setPaymentData] = useState<PaymentFormData>({
        method: 'CARD',
        cardNumber: '4242 4242 4242 4242',
        cardHolder: 'MINH TRAN',
        expiryDate: '12/28',
        cvv: '123',
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

    useEffect(() => {
        let isMounted = true
        getActiveCart()
            .then((res) => {
                if (isMounted && res?.items && res.items.length > 0) {
                    setCartItems(res.items)
                } else if (isMounted) {
                    const guest = getGuestCart()
                    if (guest.items.length > 0) {
                        setCartItems(guest.items)
                    }
                }
            })
            .catch(() => {
                if (isMounted) {
                    const guest = getGuestCart()
                    if (guest.items.length > 0) {
                        setCartItems(guest.items)
                    }
                }
            })
        return () => {
            isMounted = false
        }
    }, [])

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
     * Gửi thông tin Checkout đến Backend và chuyển hướng thanh toán Sandbox.
     * Chức năng: Đặt hàng theo đặc tả API_SPEC_CART_ORDER.md §4.2, xử lý phản hồi thành công/thất bại
     *            và chuyển hướng Buyer tới cổng Sandbox nếu thanh toán trực tuyến.
     * Đầu vào: Dữ liệu giao hàng (Step 1) và thông tin thanh toán (Step 3).
     * Luồng xử lý:
     *   1. Kiểm tra validation Step 1 và Step 3; nếu lỗi thì dừng và chuyển về bước tương ứng.
     *   2. Bật loading và chặn isSubmittingRef để hạn chế gửi yêu cầu nhiều lần ngoài ý muốn (Accept Criteria 4).
     *   3. Sinh UUID Idempotency-Key và gọi ordersApi.createOrder() gửi thông tin đến Backend (Accept Criteria 1).
     *   4. Khi thành công:
     *      - Dọn giỏ hàng của Buyer qua clearCart().
     *      - Nếu chọn VNPAY (hoặc Backend phản hồi paymentUrl):
     *        Kiểm tra tính hợp lệ của paymentUrl và chuyển hướng đến cổng sandbox (Accept Criteria 3).
     *      - Nếu thanh toán thẻ trực tiếp hoặc COD: Điều hướng tới trang /payment-result?status=success.
     *   5. Khi thất bại:
     *      - Bắt lỗi API / mạng, hiển thị thông báo lỗi rõ ràng qua AlertBanner và Toast (Accept Criteria 2 & 5).
     *      - Dữ liệu form của Buyer được giữ nguyên vẹn để người dùng kiểm tra và thử lại.
     * Lỗi & giới hạn: Kiểm tra tính hợp lệ của paymentUrl trước khi redirect để phòng tránh URL giả mạo/lỗi.
     */
    const placeOrder = async () => {
        // Hạn chế việc gửi yêu cầu nhiều lần ngoài ý muốn (Accept Criteria 4)
        if (isSubmittingRef.current || loading) return

        if (!validateStep1()) {
            setStep(1)
            return
        }
        if (!validateStep3()) {
            setStep(3)
            return
        }

        setLoading(true)
        setCheckoutError(null)
        isSubmittingRef.current = true

        try {
            const fullAddress = `${shippingData.street.trim()}, ${shippingData.ward.trim()}, ${shippingData.district.trim()}, ${shippingData.city.trim()}`
            const orderPayload: CreateOrderRequest = {
                shippingAddress: fullAddress,
                recipientName: shippingData.fullName.trim(),
                recipientPhone: shippingData.phone.replace(/[\s-]/g, ''),
                paymentMethod: paymentData.method,
                sourceType: 'REGULAR',
                selectedItemIds: cartItems.map((item) => item.id),
            }

            // Sinh UUID Idempotency-Key chống duplicate do mạng lag hoặc retry
            const idempotencyKey =
                typeof crypto !== 'undefined' && crypto.randomUUID
                    ? crypto.randomUUID()
                    : `idemp-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`

            // Gửi thông tin Checkout đến Backend (Accept Criteria 1)
            const response = await ordersApi.createOrder(orderPayload, idempotencyKey)

            // Dọn sạch giỏ sau khi đặt hàng thành công
            const session = readSession()
            if (session) {
                try {
                    await clearCart()
                } catch {
                    // Bỏ qua lỗi dọn giỏ phụ
                }
            }
            clearGuestCart()
            setCartCount(0)

            // Chuyển hướng đến cổng thanh toán sandbox khi Backend trả về URL hợp lệ (Accept Criteria 3)
            if (paymentData.method === 'VNPAY' || response.paymentUrl) {
                const url = response.paymentUrl
                if (!url) {
                    throw new Error(
                        lang === 'en'
                            ? 'No sandbox payment URL received from backend.'
                            : 'Không nhận được đường dẫn thanh toán sandbox từ Backend.',
                    )
                }

                const isExternal = /^https?:\/\//i.test(url)
                const isInternal = url.startsWith('/')

                if (!isExternal && !isInternal) {
                    throw new Error(
                        lang === 'en'
                            ? `Invalid payment gateway URL: ${url}`
                            : `Đường dẫn cổng thanh toán sandbox không hợp lệ: ${url}`,
                    )
                }

                showToast({
                    type: 'info',
                    message:
                        lang === 'en'
                            ? 'Redirecting to sandbox payment portal...'
                            : 'Đang chuyển hướng sang cổng thanh toán sandbox...',
                })

                if (isExternal) {
                    // Chuyển hướng tới cổng sandbox ngoài (VNPAY sandbox)
                    window.location.assign(url)
                    return
                }

                // Chuyển hướng nội bộ tới màn hình Sandbox Payment Portal
                const [targetPage, search] = url.replace(/^\//, '').split('?')
                const params = search
                    ? Object.fromEntries(new URLSearchParams(search).entries())
                    : {}
                navigate(targetPage as any, {
                    ...params,
                    orderId: response.id || response.orderNo,
                    amount: response.total,
                })
                return
            }

            // Phản hồi Checkout thành công cho COD hoặc thẻ (Accept Criteria 2)
            showToast({ type: 'success', message: tr(lang, 'orderPlaced') })
            navigate('payment-result', {
                status: 'success',
                orderId: response.id || response.orderNo,
            })
        } catch (err: unknown) {
            // Xử lý và hiển thị phản hồi Checkout thất bại rõ ràng (Accept Criteria 2 & 5)
            const msg =
                err instanceof Error
                    ? err.message
                    : lang === 'en'
                      ? 'Failed to process checkout. Please try again.'
                      : 'Đặt hàng thất bại. Vui lòng thử lại.'
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
                    {/* Countdown */}
                    <div className="flex items-center gap-2 mb-5 p-3 bg-warning-light rounded-xl text-sm text-yellow-800">
                        <AlertTriangle size={16} className="text-gray-500" />
                        {lang === 'en'
                            ? 'Complete your order within 15:00 to reserve your items'
                            : 'Hoàn tất đơn hàng trong 15:00 để giữ sản phẩm'}
                        <span className="ml-auto font-mono font-bold text-warning">14:23</span>
                    </div>

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
                                onClick={() => {
                                    if (validateStep1()) {
                                        setStep(2)
                                    }
                                }}
                            >
                                {tr(lang, 'next')} →
                            </Button>
                        </div>
                    )}

                    {/* Bước 2: Phương thức vận chuyển */}
                    {step === 2 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'shippingMethod')}
                            </h2>
                            {[
                                {
                                    id: 'GHN',
                                    name: 'GHN Express',
                                    time: lang === 'en' ? '3-5 business days' : '3-5 ngày làm việc',
                                    price: 3.99,
                                    priceVi: '55.000₫',
                                },
                                {
                                    id: 'GHTK',
                                    name: 'GHTK Standard',
                                    time: lang === 'en' ? '5-7 business days' : '5-7 ngày làm việc',
                                    price: 2.49,
                                    priceVi: '35.000₫',
                                },
                            ].map((m) => (
                                <label
                                    key={m.id}
                                    className={`flex items-center gap-4 p-4 border-2 rounded-xl cursor-pointer transition-colors ${shipping === m.id ? 'border-primary bg-primary-light' : 'border-border hover:border-gray-300'}`}
                                >
                                    <input
                                        type="radio"
                                        className="w-4 h-4 accent-primary"
                                        checked={shipping === (m.id as any)}
                                        onChange={() => setShipping(m.id as 'GHN' | 'GHTK')}
                                    />
                                    <Truck
                                        size={20}
                                        className={
                                            shipping === m.id ? 'text-primary' : 'text-gray-400'
                                        }
                                    />
                                    <div className="flex-1">
                                        <p className="font-semibold">{m.name}</p>
                                        <p className="text-sm text-gray-500">
                                            {tr(lang, 'deliveryEstimate')}: {m.time}
                                        </p>
                                    </div>
                                    <span className="font-bold text-primary">
                                        {lang === 'vi' ? m.priceVi : `$${m.price}`}
                                    </span>
                                </label>
                            ))}
                            <div className="flex gap-3">
                                <Button variant="outline" size="lg" onClick={() => setStep(1)}>
                                    {tr(lang, 'back')}
                                </Button>
                                <Button variant="primary" size="lg" onClick={() => setStep(3)}>
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

                            {/* Hiển thị lỗi rõ ràng khi đặt hàng hoặc chuyển hướng thất bại (Accept Criteria 2 & 5) */}
                            {checkoutError && <AlertBanner type="error" message={checkoutError} />}

                            <div className="space-y-2 text-sm">
                                {[
                                    [
                                        lang === 'en' ? 'Ship to' : 'Giao đến',
                                        `${shippingData.fullName} (${shippingData.phone}) - ${shippingData.street}, ${shippingData.ward}, ${shippingData.district}, ${shippingData.city}`,
                                    ],
                                    [
                                        tr(lang, 'shippingMethod'),
                                        shipping === 'GHN' ? 'GHN Express' : 'GHTK Standard',
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
                                            cartItems.reduce(
                                                (acc, i) => acc + i.unitPrice * i.quantity,
                                                0,
                                            ) +
                                                (shipping === 'GHN'
                                                    ? lang === 'vi'
                                                        ? 55000
                                                        : 3.99
                                                    : lang === 'vi'
                                                      ? 35000
                                                      : 2.49),
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
                                    disabled={loading}
                                    onClick={placeOrder}
                                    className="flex-1"
                                >
                                    {tr(lang, 'placeOrder')}
                                </Button>
                            </div>
                        </div>
                    )}
                </div>

                {/* Order summary sidebar */}
                <div className="nexus-card p-5 h-fit">
                    <h3 className="font-bold mb-4">{tr(lang, 'orderSummary')}</h3>
                    {/* Danh sách sản phẩm thực tế trong giỏ hàng (không dùng PRODUCTS mock) */}
                    <div className="space-y-3 mb-4">
                        {cartItems.length > 0 ? (
                            cartItems.map((item) => (
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
                                        <p className="text-xs text-gray-500">x{item.quantity}</p>
                                        <p className="text-sm font-bold text-primary mt-0.5">
                                            {formatCurrency(item.unitPrice, lang)}
                                        </p>
                                    </div>
                                </div>
                            ))
                        ) : (
                            <p className="text-xs text-gray-400 italic py-2">
                                {lang === 'en'
                                    ? 'Cart is empty'
                                    : 'Chưa có sản phẩm trong giỏ hàng'}
                            </p>
                        )}
                    </div>
                    <div className="border-t border-border pt-3 space-y-2 text-sm">
                        {(() => {
                            const subtotal = cartItems.reduce(
                                (acc, i) => acc + i.unitPrice * i.quantity,
                                0,
                            )
                            const shippingFee =
                                shipping === 'GHN'
                                    ? lang === 'vi'
                                        ? 55000
                                        : 3.99
                                    : lang === 'vi'
                                      ? 35000
                                      : 2.49
                            const total = subtotal > 0 ? subtotal + shippingFee : 0
                            return [
                                [tr(lang, 'subtotal'), formatCurrency(subtotal, lang)],
                                [tr(lang, 'shipping'), formatCurrency(shippingFee, lang)],
                                [tr(lang, 'total'), formatCurrency(total, lang)],
                            ].map(([k, v], i) => (
                                <div
                                    key={k}
                                    className={`flex justify-between ${i === 2 ? 'font-bold text-base border-t border-border pt-2 mt-2 text-primary' : 'text-gray-600'}`}
                                >
                                    <span>{k}</span>
                                    <span>{v}</span>
                                </div>
                            ))
                        })()}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Sandbox Payment Page ───────────────────────────────────────────────────────
/**
 * Màn hình Cổng thanh toán Sandbox (Sandbox Payment Portal):
 * Chức năng: Giả lập giao diện cổng thanh toán sandbox (như VNPAY / Ngân hàng trực tuyến)
 *            khi Buyer được chuyển hướng từ luồng Checkout (Accept Criteria 3).
 * Đầu vào: pageParams chứa orderId, amount, provider.
 * Luồng xử lý:
 *   - Hiển thị thông tin giao dịch cần thanh toán.
 *   - Cung cấp các nút tương tác mô phỏng:
 *     + "Xác nhận thanh toán thành công": Cập nhật trạng thái đơn sang 'paid' và điều hướng tới /payment-result?status=success.
 *     + "Mô phỏng thanh toán thất bại": Điều hướng tới /payment-result?status=failed.
 *     + "Hủy bỏ": Quay lại trang Checkout.
 * Quy tắc & lý do: Đóng vai trò là sandbox testbed cho cả Developer và QA kiểm thử toàn vẹn luồng thanh toán
 *                  khi backend trả về URL sandbox chuyển hướng.
 * Lỗi & giới hạn: Môi trường sandbox chỉ ghi nhận dữ liệu kiểm thử, không xử lý giao dịch tiền thật.
 */
export function SandboxPaymentPage() {
    const { lang, navigate, pageParams, showToast } = useApp()
    const [simulating, setSimulating] = useState(false)

    const orderId = (pageParams?.orderId as string) || 'ORD-SANDBOX-DEMO'
    const amount =
        typeof pageParams?.amount === 'number'
            ? pageParams.amount
            : typeof pageParams?.amount === 'string'
              ? parseFloat(pageParams.amount) || 329.97
              : 329.97
    const provider = (pageParams?.provider as string) || 'vnpay'

    // Mô phỏng thanh toán thành công
    const handleSimulateSuccess = async () => {
        setSimulating(true)
        try {
            await new Promise((r) => setTimeout(r, 600))
            try {
                // Cố gắng cập nhật trạng thái đơn hàng trong mock store nếu có
                // Cố gắng cập nhật trạng thái đơn hàng sang PAID theo chuẩn OrderStatus

                await ordersApi.updateOrderStatus(orderId, 'PAID', 'Sandbox payment approved')
            } catch {
                // Bỏ qua nếu đơn là ID tạm thời
            }
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Sandbox payment successful!'
                        : 'Thanh toán Sandbox thành công!',
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
                    ? 'Payment declined by sandbox provider.'
                    : 'Giao dịch bị từ chối bởi cổng Sandbox.',
        })
        navigate('payment-result', {
            status: 'failed',
            orderId,
            reason: 'Sandbox transaction rejected',
        })
    }

    return (
        <BuyerLayout>
            <div className="max-w-xl mx-auto py-8">
                {/* Header Sandbox Gateway */}
                <div className="bg-gradient-to-r from-blue-700 to-indigo-800 text-white rounded-t-2xl p-6 shadow-md">
                    <div className="flex items-center justify-between mb-2">
                        <span className="text-xs uppercase tracking-wider font-semibold bg-white/20 px-2.5 py-1 rounded-full border border-white/30">
                            🛡️ {lang === 'en' ? 'Sandbox Environment' : 'Môi trường Sandbox'}
                        </span>
                        <span className="text-xs text-blue-100 font-mono">
                            Gateway: {provider.toUpperCase()}
                        </span>
                    </div>
                    <h1 className="text-xl font-bold">
                        {lang === 'en'
                            ? 'Nexus Payment Sandbox Portal'
                            : 'Cổng thanh toán Sandbox Nexus'}
                    </h1>
                    <p className="text-xs text-blue-100 mt-1">
                        {lang === 'en'
                            ? 'Simulated Payment Gateway for testing checkout integration.'
                            : 'Cổng thanh toán giả lập hỗ trợ kiểm thử tích hợp luồng Checkout.'}
                    </p>
                </div>

                {/* Thân giao diện thanh toán */}
                <div className="nexus-card rounded-t-none border-t-0 p-6 space-y-6 shadow-md">
                    {/* Thông tin đơn hàng */}
                    <div className="bg-gray-50 border border-gray-200 rounded-xl p-4 space-y-2.5 text-sm">
                        <div className="flex justify-between items-center">
                            <span className="text-gray-500">
                                {lang === 'en' ? 'Order Reference:' : 'Mã đơn hàng:'}
                            </span>
                            <span className="font-mono font-bold text-gray-800">{orderId}</span>
                        </div>
                        <div className="flex justify-between items-center">
                            <span className="text-gray-500">
                                {lang === 'en' ? 'Payable Amount:' : 'Số tiền thanh toán:'}
                            </span>
                            <span className="text-xl font-bold text-primary">
                                {formatCurrency(amount, lang)}
                            </span>
                        </div>
                        <div className="flex justify-between items-center">
                            <span className="text-gray-500">
                                {lang === 'en' ? 'Payment Method:' : 'Phương thức:'}
                            </span>
                            <span className="font-medium text-gray-700">
                                VNPAY QR / Banking Sandbox
                            </span>
                        </div>
                    </div>

                    {/* Giả lập quét mã QR VNPAY */}
                    <div className="flex flex-col items-center justify-center p-6 border-2 border-dashed border-gray-200 rounded-xl bg-blue-50/50">
                        <div className="w-44 h-44 bg-white border border-gray-300 rounded-lg p-2 flex flex-col items-center justify-center shadow-inner relative">
                            <svg
                                className="w-full h-full text-gray-800"
                                viewBox="0 0 100 100"
                                fill="currentColor"
                            >
                                <rect x="5" y="5" width="30" height="30" rx="4" fill="#1e40af" />
                                <rect x="10" y="10" width="20" height="20" rx="2" fill="#fff" />
                                <rect x="15" y="15" width="10" height="10" fill="#1e40af" />

                                <rect x="65" y="5" width="30" height="30" rx="4" fill="#1e40af" />
                                <rect x="70" y="10" width="20" height="20" rx="2" fill="#fff" />
                                <rect x="75" y="15" width="10" height="10" fill="#1e40af" />

                                <rect x="5" y="65" width="30" height="30" rx="4" fill="#1e40af" />
                                <rect x="10" y="70" width="20" height="20" rx="2" fill="#fff" />
                                <rect x="15" y="75" width="10" height="10" fill="#1e40af" />

                                <circle cx="50" cy="50" r="8" fill="#1e40af" />
                                <rect x="42" y="20" width="6" height="16" fill="#1e40af" />
                                <rect x="70" y="50" width="16" height="6" fill="#1e40af" />
                                <rect x="50" y="72" width="14" height="6" fill="#1e40af" />
                                <rect x="72" y="72" width="14" height="14" fill="#1e40af" />
                            </svg>
                        </div>
                        <p className="text-xs text-gray-500 mt-3 text-center">
                            {lang === 'en'
                                ? 'Scan QR with banking app or click simulation buttons below'
                                : 'Mã QR thanh toán mô phỏng cổng VNPAY Sandbox'}
                        </p>
                    </div>

                    {/* Các nút hành động giả lập */}
                    <div className="space-y-3 pt-2">
                        <Button
                            variant="primary"
                            size="lg"
                            className="w-full bg-emerald-600 hover:bg-emerald-700 text-white"
                            loading={simulating}
                            onClick={handleSimulateSuccess}
                        >
                            ✓{' '}
                            {lang === 'en'
                                ? 'Simulate Success'
                                : 'Xác nhận thanh toán thành công (Simulate Success)'}
                        </Button>
                        <Button
                            variant="danger"
                            size="lg"
                            className="w-full"
                            disabled={simulating}
                            onClick={handleSimulateFailure}
                        >
                            ✕{' '}
                            {lang === 'en'
                                ? 'Simulate Failure'
                                : 'Mô phỏng thanh toán thất bại (Simulate Failure)'}
                        </Button>
                        {/* Hủy thanh toán → chuyển sang payment-result với status cancelled thay vì quay về Checkout */}
                        <Button
                            variant="outline"
                            size="lg"
                            className="w-full"
                            disabled={simulating}
                            onClick={() =>
                                navigate('payment-result', { status: 'cancelled', orderId })
                            }
                        >
                            ← {lang === 'en' ? 'Cancel Payment' : 'Hủy thanh toán'}
                        </Button>
                    </div>

                    <div className="text-center text-xs text-gray-400">
                        {lang === 'en'
                            ? 'Test Mode: No real funds are deducted.'
                            : 'Chế độ Sandbox: Không thực hiện trừ tiền thật.'}
                    </div>
                </div>
            </div>
        </BuyerLayout>
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
export function OrderHistoryPage() {
    const { lang, navigate, showToast, user } = useApp()
    const [orders, setOrders] = useState<Order[]>([])
    const [statusFilter, setStatusFilter] = useState<string>('all')
    const [searchQuery, setSearchQuery] = useState<string>('')
    const [loading, setLoading] = useState<boolean>(true)
    const [cancelOrderId, setCancelOrderId] = useState<string | null>(null)
    const [cancelReason, setCancelReason] = useState<string>('')

    const STATUS_TABS = [
        { id: 'all', label: tr(lang, 'all') },
        { id: 'PENDING_PAYMENT', label: tr(lang, 'statusPending') },
        { id: 'PAID', label: tr(lang, 'statusPaid') },
        { id: 'PROCESSING', label: tr(lang, 'statusProcessing') },
        { id: 'SHIPPED', label: tr(lang, 'statusShipped') },
        { id: 'DELIVERED', label: tr(lang, 'statusDelivered') },
        { id: 'COMPLETED', label: tr(lang, 'statusCompleted') },
        { id: 'CANCELLED', label: tr(lang, 'statusCancelled') },
        { id: 'REFUNDED', label: tr(lang, 'statusRefunded') },
    ]

    const statusLabel = (s: string) =>
        ({
            PENDING_PAYMENT: tr(lang, 'statusPending'),
            PAID: tr(lang, 'statusPaid'),
            PROCESSING: tr(lang, 'statusProcessing'),
            SHIPPED: tr(lang, 'statusShipped'),
            DELIVERED: tr(lang, 'statusDelivered'),
            COMPLETED: tr(lang, 'statusCompleted'),
            CANCELLED: tr(lang, 'statusCancelled'),
            REFUNDED: tr(lang, 'statusRefunded'),
        })[s] || s

    // Tải danh sách đơn hàng từ API hoặc kho mock store
    const loadOrders = async () => {
        setLoading(true)
        try {
            const data = await ordersApi.getOrders({
                role: 'buyer',
                userId: user?.id,
                status: statusFilter,
                search: searchQuery,
            })
            setOrders(data)
        } catch {
            setOrders([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadOrders()
    }, [statusFilter, searchQuery])

    // Xử lý xác nhận hủy đơn hàng
    const handleConfirmCancel = async () => {
        if (!cancelOrderId) return
        try {
            await ordersApi.cancelOrder(cancelOrderId, cancelReason)
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
            })
            setCancelOrderId(null)
            setCancelReason('')
            await loadOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err.message ||
                    (lang === 'en' ? 'Failed to cancel order.' : 'Không thể hủy đơn hàng.'),
            })
        }
    }

    return (
        <BuyerLayout>
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-2xl font-bold text-gray-900">{tr(lang, 'orderHistory')}</h1>
                    <p className="text-sm text-gray-500 mt-1">
                        {lang === 'en'
                            ? 'Track, inspect and manage your placed purchases'
                            : 'Theo dõi, tra cứu và quản lý các đơn hàng bạn đã mua'}
                    </p>
                </div>
                <div className="w-full sm:w-72">
                    <Input
                        icon={<Search size={16} />}
                        placeholder={
                            lang === 'en'
                                ? 'Search order ID or item...'
                                : 'Tìm mã đơn hoặc tên sản phẩm...'
                        }
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        className="w-full"
                    />
                </div>
            </div>

            <div className="nexus-card">
                <Tabs tabs={STATUS_TABS} active={statusFilter} onChange={setStatusFilter} />
                <div className="p-4 sm:p-6">
                    {loading ? (
                        <div className="py-12 text-center text-gray-400">
                            <div className="inline-block animate-spin w-8 h-8 border-4 border-primary border-t-transparent rounded-full mb-2" />
                            <p>
                                {lang === 'en'
                                    ? 'Loading orders...'
                                    : 'Đang tải danh sách đơn hàng...'}
                            </p>
                        </div>
                    ) : orders.length === 0 ? (
                        <EmptyState
                            icon={<Package size={24} />}
                            title={tr(lang, 'noData')}
                            subtitle={
                                lang === 'en'
                                    ? 'No orders found matching the filter.'
                                    : 'Không tìm thấy đơn hàng nào phù hợp với bộ lọc.'
                            }
                        />
                    ) : (
                        <div className="space-y-5">
                            {orders.map((order) => {
                                const canCancel = ['PENDING_PAYMENT', 'PAID'].includes(order.status)
                                return (
                                    <div
                                        key={order.id}
                                        className="border border-border rounded-xl p-5 hover:border-primary/40 transition-colors bg-white"
                                    >
                                        <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-border/60 mb-3">
                                            <div className="flex items-center gap-3">
                                                <StatusBadge
                                                    status={order.status}
                                                    label={statusLabel(order.status)}
                                                />
                                                <span className="font-mono font-semibold text-sm text-gray-800">
                                                    {order.id}
                                                </span>
                                                <span className="text-xs text-gray-400">
                                                    {formatDate(order.createdAt, lang)}
                                                </span>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                {canCancel && (
                                                    <Button
                                                        variant="ghost"
                                                        size="sm"
                                                        className="text-danger hover:bg-danger/10"
                                                        onClick={() => setCancelOrderId(order.id)}
                                                    >
                                                        {tr(lang, 'cancelOrder')}
                                                    </Button>
                                                )}
                                                <Button
                                                    variant="outline"
                                                    size="sm"
                                                    onClick={() =>
                                                        navigate('order-detail', { id: order.id })
                                                    }
                                                >
                                                    {tr(lang, 'orderDetail')}{' '}
                                                    <ChevronRight size={14} />
                                                </Button>
                                            </div>
                                        </div>

                                        {/* Danh sách sản phẩm của đơn */}
                                        <div className="space-y-3">
                                            {order.items.map((item) => (
                                                <div
                                                    key={item.productId}
                                                    className="flex items-center justify-between gap-4"
                                                >
                                                    <div className="flex items-center gap-3">
                                                        <img
                                                            src={item.image}
                                                            alt=""
                                                            className="w-14 h-14 object-cover rounded-lg border border-border"
                                                        />
                                                        <div>
                                                            <p className="text-sm font-semibold text-gray-900 line-clamp-1">
                                                                {item.productName}
                                                            </p>
                                                            <p className="text-xs text-gray-500 mt-0.5">
                                                                ×{item.quantity} ·{' '}
                                                                {formatCurrency(
                                                                    item.unitPrice,
                                                                    lang,
                                                                )}
                                                            </p>
                                                        </div>
                                                    </div>
                                                    <span className="font-semibold text-sm text-gray-800 shrink-0">
                                                        {formatCurrency(item.subtotal, lang)}
                                                    </span>
                                                </div>
                                            ))}
                                        </div>

                                        <div className="flex items-center justify-between pt-3 mt-3 border-t border-border/60">
                                            <div className="text-xs text-gray-500">
                                                <span>
                                                    {lang === 'en'
                                                        ? `Sold by ${order.sellerName}`
                                                        : `Cung cấp bởi ${order.sellerName}`}
                                                </span>
                                                {order.shippingAddress && (
                                                    <span className="hidden md:inline ml-3 text-gray-400">
                                                        · {order.shippingAddress}
                                                    </span>
                                                )}
                                            </div>
                                            <div className="text-right">
                                                <span className="text-xs text-gray-500 mr-2">
                                                    {lang === 'en'
                                                        ? 'Total payable:'
                                                        : 'Tổng thanh toán:'}
                                                </span>
                                                <span className="font-bold text-base text-primary">
                                                    {formatCurrency(order.total, lang)}
                                                </span>
                                            </div>
                                        </div>
                                    </div>
                                )
                            })}
                        </div>
                    )}
                </div>
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

// ─── Order Detail Page ───
/**
 * Màn hình Chi tiết đơn hàng của Buyer
 *
 * Chức năng: Hiển thị đầy đủ thông tin một đơn hàng: tiến trình timeline, sản phẩm, địa chỉ,
 * thanh toán, trạng thái ký quỹ Escrow (Bảo vệ Buyer), vận chuyển tracking;
 * cho phép Buyer thực hiện các hành động: Hủy đơn, Xác nhận đã nhận hàng (giải ngân Escrow),
 * Mở khiếu nại (giữ tiền để Admin xử lý), và Đánh giá người bán.
 *
 * Luồng dữ liệu:
 * - Gọi `ordersApi.getOrderById(pageParams.id)` để lấy thông tin đơn hàng mới nhất từ API / Mock store.
 * - Các tương tác hành động gọi API tương ứng trong `ordersApi` và cập nhật trực tiếp dữ liệu.
 */
export function OrderDetailPage() {
    const { lang, pageParams, showToast } = useApp()
    const [order, setOrder] = useState<Order | null>(null)
    const [loading, setLoading] = useState(true)
    const [cancelOpen, setCancelOpen] = useState(false)
    const [reviewOpen, setReviewOpen] = useState(false)
    const [rating, setRating] = useState(5)
    const [comment, setComment] = useState('')
    const [reviewed, setReviewed] = useState(false)
    const [disputeOpen, setDisputeOpen] = useState(false)
    const [disputeReason, setDisputeReason] = useState('')

    // Nạp chi tiết đơn hàng
    const loadDetail = async () => {
        setLoading(true)
        try {
            const targetId = (pageParams?.id as string) || 'ORD-20240215-001'
            const data = await ordersApi.getOrderById(targetId)
            if (data) {
                setOrder(data)
                setReviewed(Boolean(data.reviewed))
            }
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadDetail()
    }, [pageParams?.id])

    if (loading || !order) {
        return (
            <BuyerLayout>
                <div className="py-20 text-center text-gray-500">
                    <div className="inline-block animate-spin w-8 h-8 border-4 border-primary border-t-transparent rounded-full mb-3" />
                    <p>
                        {lang === 'en'
                            ? 'Loading order details...'
                            : 'Đang tải chi tiết đơn hàng...'}
                    </p>
                </div>
            </BuyerLayout>
        )
    }

    const paymentStatus = getEffectivePaymentStatus(order)

    const statusLabel = (s: string) =>
        ({
            PENDING_PAYMENT: tr(lang, 'statusPending'),
            PAID: tr(lang, 'statusPaid'),
            PROCESSING: tr(lang, 'statusProcessing'),
            SHIPPED: tr(lang, 'statusShipped'),
            DELIVERED: tr(lang, 'statusDelivered'),
            COMPLETED: tr(lang, 'statusCompleted'),
            CANCELLED: tr(lang, 'statusCancelled'),
            REFUNDED: tr(lang, 'statusRefunded'),
        })[s] || s

    const canCancel = ['PENDING_PAYMENT', 'PAID'].includes(order.status)
    const protectionDays = order.protectionEndsAt
        ? Math.max(
              0,
              Math.ceil((new Date(order.protectionEndsAt).getTime() - Date.now()) / 86400000),
          )
        : 0
    const isProtectionActive = order.status === 'DELIVERED' && paymentStatus === 'protection_period'

    // Xác nhận đã nhận hàng -> chuyển sang completed và giải ngân tiền cho Seller
    const confirmDelivery = async () => {
        try {
            const updated = await ordersApi.confirmOrderDelivery(order.id)
            setOrder(updated)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Order confirmed. Payment has been released to the seller.'
                        : 'Đã xác nhận nhận hàng. Khoản tiền đã được giải ngân cho người bán.',
            })
        } catch {
            showToast({
                type: 'error',
                message:
                    lang === 'en' ? 'Failed to confirm delivery.' : 'Không thể xác nhận nhận hàng.',
            })
        }
    }

    // Mở khiếu nại đơn hàng -> chuyển tiền sang on_hold để Admin xử lý
    const submitDispute = async () => {
        if (!disputeReason.trim()) return
        try {
            const updated = await ordersApi.submitOrderDispute(order.id, disputeReason)
            setOrder(updated)
            setDisputeOpen(false)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Dispute submitted. Payment remains on hold for admin review.'
                        : 'Đã gửi khiếu nại. Khoản thanh toán tiếp tục được giữ để Admin xử lý.',
            })
        } catch {
            showToast({
                type: 'error',
                message: lang === 'en' ? 'Failed to submit dispute.' : 'Không thể gửi khiếu nại.',
            })
        }
    }

    // Hủy đơn hàng
    const handleCancelOrder = async () => {
        try {
            const updated = await ordersApi.cancelOrder(order.id, 'Buyer requested cancellation')
            setOrder(updated)
            setCancelOpen(false)
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
            })
        } catch {
            showToast({
                type: 'error',
                message: lang === 'en' ? 'Failed to cancel order.' : 'Không thể hủy đơn hàng.',
            })
        }
    }

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    {
                        label: lang === 'en' ? 'Order History' : 'Lịch sử đơn hàng',
                        page: 'order-history',
                    },
                    { label: order.id },
                ]}
            />
            <div className="mt-5 grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-6">
                <div className="space-y-5">
                    {/* Header đơn hàng */}
                    <div className="nexus-card p-5 flex flex-wrap items-center justify-between gap-4">
                        <div>
                            <div className="flex items-center gap-3 mb-1">
                                <StatusBadge
                                    status={order.status}
                                    label={statusLabel(order.status)}
                                />
                                <span className="font-mono font-semibold text-gray-800">
                                    {order.id}
                                </span>
                            </div>
                            <p className="text-sm text-gray-500">
                                {lang === 'en' ? 'Placed on' : 'Đặt lúc'}{' '}
                                {formatDate(order.createdAt, lang)}
                            </p>
                        </div>
                        <div className="flex gap-2">
                            {canCancel && (
                                <Button
                                    variant="danger"
                                    size="sm"
                                    onClick={() => setCancelOpen(true)}
                                >
                                    {tr(lang, 'cancelOrder')}
                                </Button>
                            )}
                            <Button variant="outline" size="sm">
                                <Download size={14} />
                                {tr(lang, 'downloadInvoice')}
                            </Button>
                        </div>
                    </div>

                    {/* Danh sách sản phẩm mua */}
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Purchased Items' : 'Sản phẩm đã mua'}
                        </h3>
                        <div className="divide-y divide-border/60">
                            {order.items.map((item) => (
                                <div
                                    key={item.productId}
                                    className="flex gap-4 py-3 first:pt-0 last:pb-0"
                                >
                                    <img
                                        src={item.image}
                                        alt=""
                                        className="w-16 h-16 object-cover rounded-xl border border-border shrink-0"
                                    />
                                    <div className="flex-1 min-w-0">
                                        <p className="font-semibold text-gray-900 line-clamp-1">
                                            {item.productName}
                                        </p>
                                        <p className="text-sm text-gray-500 mt-1">
                                            ×{item.quantity} ·{' '}
                                            {formatCurrency(item.unitPrice, lang)}
                                        </p>
                                    </div>
                                    <p className="font-bold text-primary shrink-0">
                                        {formatCurrency(item.subtotal, lang)}
                                    </p>
                                </div>
                            ))}
                        </div>

                        {/* Tổng kết chi phí */}
                        <div className="pt-4 border-t border-border mt-4 space-y-2 text-sm">
                            <div className="flex justify-between text-gray-600">
                                <span>{tr(lang, 'subtotal')}</span>
                                <span>{formatCurrency(order.subtotal, lang)}</span>
                            </div>
                            <div className="flex justify-between text-gray-600">
                                <span>{tr(lang, 'shipping')}</span>
                                <span>{formatCurrency(order.shipping, lang)}</span>
                            </div>
                            <div className="flex justify-between text-gray-600">
                                <span>{tr(lang, 'tax')}</span>
                                <span>{formatCurrency(order.tax, lang)}</span>
                            </div>
                            {order.discount > 0 && (
                                <div className="flex justify-between text-green-600">
                                    <span>{tr(lang, 'discount')}</span>
                                    <span>-{formatCurrency(order.discount, lang)}</span>
                                </div>
                            )}
                            <div className="flex justify-between font-bold text-base text-gray-900 border-t border-border pt-3 mt-1">
                                <span>{tr(lang, 'total')}</span>
                                <span className="text-primary text-lg">
                                    {formatCurrency(order.total, lang)}
                                </span>
                            </div>
                        </div>
                    </div>

                    {/* Timeline tiến trình đơn hàng */}
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en'
                                ? 'Order Status Timeline'
                                : 'Tiến trình trạng thái đơn hàng'}
                        </h3>
                        <OrderTimeline status={order.status} />
                    </div>

                    {/* Thời hạn bảo vệ người mua (Buyer Protection) */}
                    {isProtectionActive && (
                        <div className="nexus-card p-5 border-primary/40 bg-primary/5">
                            <div className="flex flex-col sm:flex-row items-start justify-between gap-6">
                                <div>
                                    <div className="flex items-center gap-2 mb-2">
                                        <Shield size={20} className="text-primary" />
                                        <h3 className="font-bold text-gray-900">
                                            {lang === 'en'
                                                ? 'Bảo vệ người mua 7 ngày'
                                                : 'Bảo vệ người mua & Giữ tiền an toàn trong 7 ngày'}
                                        </h3>
                                    </div>
                                    <p className="text-sm text-gray-600">
                                        {lang === 'en'
                                            ? `Payment is safely held by Nexus for ${protectionDays} more day(s). Confirm satisfactory goods to release payment, or submit a return/dispute.`
                                            : `Tiền của bạn đang được giữ an toàn tại Nexus thêm ${protectionDays} ngày. Vui lòng kiểm tra hàng và xác nhận hài lòng để thanh toán cho người bán, hoặc yêu cầu khiếu nại nếu hàng có vấn đề.`}
                                    </p>
                                    {order.protectionEndsAt && (
                                        <p className="text-xs text-gray-500 mt-2">
                                            {lang === 'en'
                                                ? 'Auto release at'
                                                : 'Tự động giải ngân lúc'}
                                            : {new Date(order.protectionEndsAt).toLocaleString()}
                                        </p>
                                    )}
                                </div>
                                <div className="flex sm:flex-col gap-2 shrink-0 w-full sm:w-auto">
                                    <Button
                                        variant="primary"
                                        size="sm"
                                        onClick={confirmDelivery}
                                        className="flex-1 sm:flex-initial"
                                    >
                                        {lang === 'en'
                                            ? 'Confirm Received'
                                            : 'Xác nhận đã nhận hàng'}
                                    </Button>
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() => setDisputeOpen(true)}
                                        className="flex-1 sm:flex-initial"
                                    >
                                        {lang === 'en'
                                            ? 'Return / Dispute'
                                            : 'Trả hàng / Khiếu nại'}
                                    </Button>
                                </div>
                            </div>
                        </div>
                    )}

                    {paymentStatus === 'on_hold' && (
                        <AlertBanner
                            type="warning"
                            message={
                                lang === 'en'
                                    ? `Payment is on hold while Admin reviews the dispute: "${order.disputeReason || 'Customer reported issue'}"`
                                    : `Khoản thanh toán đang được giữ lại để Admin thụ lý khiếu nại: "${order.disputeReason || 'Khách hàng báo cáo sự cố'}"`
                            }
                        />
                    )}

                    {paymentStatus === 'released' && (
                        <AlertBanner
                            type="success"
                            message={
                                lang === 'en'
                                    ? 'Order successfully completed. Payment has been released to the seller.'
                                    : 'Đơn hàng đã hoàn tất thành công. Tiền ký quỹ đã được chuyển vào số dư người bán.'
                            }
                        />
                    )}

                    {/* Theo dõi vận chuyển */}
                    {order.shipment && (
                        <div className="nexus-card p-5">
                            <div className="flex items-center justify-between mb-4">
                                <h3 className="font-bold flex items-center gap-2">
                                    <Truck size={18} className="text-primary" />
                                    {lang === 'en' ? 'Shipment Tracking' : 'Hành trình vận chuyển'}
                                </h3>
                                <div className="text-sm text-gray-500">
                                    {order.shipment.carrier} ·{' '}
                                    <span className="font-mono font-semibold">
                                        {order.shipment.trackingNumber}
                                    </span>
                                </div>
                            </div>
                            <div className="space-y-3">
                                {order.shipment.events.map((ev, i) => (
                                    <div key={i} className="flex gap-4">
                                        <div className="flex flex-col items-center">
                                            <div
                                                className={`w-3 h-3 rounded-full ${i === 0 ? 'bg-primary' : 'bg-gray-300'}`}
                                            />
                                            {i < order.shipment!.events.length - 1 && (
                                                <div className="w-0.5 h-8 bg-gray-200 mt-1" />
                                            )}
                                        </div>
                                        <div className="pb-3">
                                            <p className="text-sm font-semibold text-gray-800">
                                                {ev.description}
                                            </p>
                                            {ev.location && (
                                                <p className="text-xs text-gray-500">
                                                    {ev.location}
                                                </p>
                                            )}
                                            <p className="text-xs text-gray-400">
                                                {formatDate(ev.timestamp, lang)}
                                            </p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Đánh giá đơn hàng */}
                    {order.status === 'DELIVERED' && !reviewed && (
                        <div className="nexus-card p-5 border-primary/20 bg-primary-light/30">
                            <h3 className="font-bold mb-2">{tr(lang, 'leaveReview')}</h3>
                            <p className="text-sm text-gray-500 mb-3">
                                {lang === 'en'
                                    ? 'How was your purchase? Share your experience with others.'
                                    : 'Trải nghiệm mua sắm của bạn thế nào? Hãy chia sẻ đánh giá của bạn.'}
                            </p>
                            <Button variant="primary" size="sm" onClick={() => setReviewOpen(true)}>
                                {tr(lang, 'leaveReview')}
                            </Button>
                        </div>
                    )}
                    {reviewed && (
                        <AlertBanner type="success" message={tr(lang, 'alreadyReviewed')} />
                    )}
                </div>

                {/* Sidebar thông tin người bán, địa chỉ, thanh toán */}
                <div className="space-y-5">
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-3">{tr(lang, 'shippingAddress')}</h3>
                        <p className="text-sm text-gray-700 leading-relaxed">
                            {order.shippingAddress}
                        </p>
                    </div>

                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-3">{tr(lang, 'payment')}</h3>
                        <p className="text-sm text-gray-700">{order.paymentMethod}</p>
                        <div className="mt-4 pt-3 border-t border-border flex items-center justify-between">
                            <span className="text-xs text-gray-500">
                                {lang === 'en' ? 'Payment status' : 'Tình trạng thanh toán'}
                            </span>
                            <StatusBadge
                                status={paymentStatus}
                                label={
                                    {
                                        held: lang === 'en' ? 'Held by Nexus' : 'Nexus đang giữ',
                                        protection_period:
                                            lang === 'en'
                                                ? 'Buyer protection'
                                                : 'Đang bảo vệ Buyer',
                                        released: lang === 'en' ? 'Released' : 'Đã giải ngân',
                                        on_hold: lang === 'en' ? 'On hold' : 'Tạm giữ khiếu nại',
                                        refunded: lang === 'en' ? 'Refunded' : 'Đã hoàn tiền',
                                        partial_refund:
                                            lang === 'en' ? 'Partial refund' : 'Hoàn một phần',
                                    }[paymentStatus] || paymentStatus
                                }
                            />
                        </div>
                        {order.refundStatus !== 'none' && (
                            <div className="mt-2 flex items-center justify-between">
                                <span className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Refund progress' : 'Tiến trình hoàn tiền'}
                                </span>
                                <StatusBadge
                                    status={order.refundStatus}
                                    label={
                                        order.refundStatus === 'completed'
                                            ? tr(lang, 'refundProcessed')
                                            : tr(lang, 'refundInitiated')
                                    }
                                />
                            </div>
                        )}
                    </div>

                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-2">{tr(lang, 'seller')}</h3>
                        <p className="text-sm font-semibold text-gray-800">{order.sellerName}</p>
                        <p className="text-xs text-gray-400 mt-1">ID: {order.sellerId}</p>
                    </div>
                </div>
            </div>

            {/* Modal xác nhận hủy đơn */}
            <ConfirmModal
                open={cancelOpen}
                onClose={() => setCancelOpen(false)}
                onConfirm={handleCancelOrder}
                title={tr(lang, 'confirmCancel')}
                message={
                    lang === 'en'
                        ? 'Are you sure you want to cancel this order? This action cannot be undone.'
                        : 'Bạn có chắc chắn muốn hủy đơn hàng này? Thao tác này không thể hoàn tác.'
                }
                danger
            />

            {/* Modal đánh giá */}
            <Modal
                open={reviewOpen}
                onClose={() => setReviewOpen(false)}
                title={tr(lang, 'leaveReview')}
                footer={
                    <>
                        <Button variant="outline" onClick={() => setReviewOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setReviewed(true)
                                setReviewOpen(false)
                                showToast({ type: 'success', message: tr(lang, 'reviewSubmitted') })
                            }}
                        >
                            {lang === 'en' ? 'Submit Review' : 'Gửi đánh giá'}
                        </Button>
                    </>
                }
            >
                <div className="space-y-4">
                    <div>
                        <p className="text-sm font-semibold mb-2">
                            {lang === 'en' ? 'Your Rating' : 'Đánh giá của bạn'}
                        </p>
                        <StarRating rating={rating} interactive onChange={setRating} size={28} />
                    </div>
                    <div>
                        <label className="text-sm font-semibold mb-2 block">
                            {lang === 'en' ? 'Comment' : 'Nhận xét'}
                        </label>
                        <textarea
                            value={comment}
                            onChange={(e) => setComment(e.target.value)}
                            className="nexus-input min-h-[100px]"
                            placeholder={
                                lang === 'en'
                                    ? 'Tell us about your experience...'
                                    : 'Chia sẻ trải nghiệm của bạn...'
                            }
                        />
                    </div>
                </div>
            </Modal>

            {/* Modal gửi khiếu nại */}
            <Modal
                open={disputeOpen}
                onClose={() => setDisputeOpen(false)}
                title={
                    lang === 'en' ? 'Return or dispute request' : 'Yêu cầu trả hàng hoặc khiếu nại'
                }
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setDisputeOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            disabled={!disputeReason.trim()}
                            onClick={submitDispute}
                        >
                            {lang === 'en' ? 'Submit Request' : 'Gửi yêu cầu'}
                        </Button>
                    </>
                }
            >
                <AlertBanner
                    type="info"
                    message={
                        lang === 'en'
                            ? 'The payment will remain on hold until Admin resolves the dispute.'
                            : 'Khoản thanh toán sẽ tiếp tục được giữ cho đến khi Admin xử lý xong khiếu nại.'
                    }
                />
                <div className="mt-4">
                    <Textarea
                        label={tr(lang, 'reason')}
                        value={disputeReason}
                        onChange={(e) => setDisputeReason(e.target.value)}
                        rows={4}
                        placeholder={
                            lang === 'en'
                                ? 'Describe the issue with the item...'
                                : 'Mô tả vấn đề với sản phẩm...'
                        }
                    />
                </div>
            </Modal>
        </BuyerLayout>
    )
}

// ─── Shopee-Style Public Seller Shop Page ──────────────────────────────────────
export function SellerShopPage() {
    const { lang, pageParams, showToast } = useApp()
    const sellerId = (pageParams?.id as string) || 'seller-1'
    const sellerName = (pageParams?.sellerName as string) || 'TechZone Store'
    const [shopTab, setShopTab] = useState<'all' | 'popular' | 'auctions'>('all')
    const [isFollowing, setIsFollowing] = useState(false)

    const sellerProducts = PRODUCTS.filter(
        (p) => p.sellerId === sellerId || p.sellerName === sellerName,
    )
    const displayProducts = sellerProducts.length > 0 ? sellerProducts : PRODUCTS.slice(0, 8)
    const sellerAuctions = AUCTIONS.filter(
        (a) => a.sellerId === sellerId || a.sellerName === sellerName,
    )

    return (
        <BuyerLayout>
            <div className="max-w-[1440px] mx-auto space-y-6">
                <Breadcrumb
                    items={[
                        { label: lang === 'vi' ? 'Trang chủ' : 'Home', page: 'home' },
                        { label: sellerName },
                    ]}
                />

                {/* Shopee-style Store Header Banner */}
                <div className="nexus-card overflow-hidden border border-border/80 bg-white">
                    <div className="grid grid-cols-1 lg:grid-cols-[380px_1fr] p-6 gap-6 items-center bg-gradient-to-r from-gray-900 via-slate-800 to-indigo-950 text-white rounded-xl">
                        {/* Left Box: Avatar & Action Buttons */}
                        <div className="flex items-center gap-4 bg-white/10 backdrop-blur-md p-4 rounded-xl border border-white/15">
                            <div className="relative">
                                <div className="w-16 h-16 rounded-full bg-white text-gray-900 font-black text-2xl flex items-center justify-center shadow-lg border-2 border-white">
                                    {sellerName[0]}
                                </div>
                                <span className="absolute -bottom-1 -right-1 bg-red-600 text-white text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wider">
                                    Mall
                                </span>
                            </div>
                            <div className="flex-1 min-w-0">
                                <h2 className="font-bold text-base text-white truncate">
                                    {sellerName}
                                </h2>
                                <p className="text-xs text-gray-300 mt-0.5">
                                    {lang === 'vi' ? 'Online 5 phút trước' : 'Active 5m ago'}
                                </p>
                                <div className="flex items-center gap-2 mt-2.5">
                                    <button
                                        onClick={() => {
                                            setIsFollowing(!isFollowing)
                                            showToast({
                                                type: 'success',
                                                message: !isFollowing
                                                    ? lang === 'vi'
                                                        ? `Đã theo dõi ${sellerName}`
                                                        : `Followed ${sellerName}`
                                                    : lang === 'vi'
                                                      ? 'Đã bỏ theo dõi'
                                                      : 'Unfollowed',
                                            })
                                        }}
                                        className={`px-3 py-1 rounded-md text-xs font-semibold transition-all ${
                                            isFollowing
                                                ? 'bg-white/20 text-white border border-white/40'
                                                : 'bg-red-600 hover:bg-red-700 text-white shadow-sm'
                                        }`}
                                    >
                                        {isFollowing
                                            ? lang === 'vi'
                                                ? '✓ Đang Theo Dõi'
                                                : '✓ Following'
                                            : lang === 'vi'
                                              ? '+ Theo Dõi'
                                              : '+ Follow'}
                                    </button>
                                    <button
                                        onClick={() =>
                                            showToast({
                                                type: 'info',
                                                message:
                                                    lang === 'vi'
                                                        ? `Mở khung chat với ${sellerName}`
                                                        : `Chatting with ${sellerName}`,
                                            })
                                        }
                                        className="px-3 py-1 rounded-md text-xs font-semibold bg-white/20 hover:bg-white/30 text-white border border-white/30 transition-all"
                                    >
                                        💬 {lang === 'vi' ? 'Chat' : 'Chat'}
                                    </button>
                                </div>
                            </div>
                        </div>

                        {/* Right Box: Shopee Metrics Grid */}
                        <div className="grid grid-cols-2 md:grid-cols-3 gap-x-6 gap-y-3.5 text-xs">
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    📦 {lang === 'vi' ? 'Sản Phẩm:' : 'Products:'}
                                </span>
                                <span className="font-bold text-yellow-400 text-sm">
                                    {displayProducts.length}
                                </span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    👥 {lang === 'vi' ? 'Người Theo Dõi:' : 'Followers:'}
                                </span>
                                <span className="font-bold text-yellow-400 text-sm">3,1k</span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    ★ {lang === 'vi' ? 'Đánh Giá:' : 'Rating:'}
                                </span>
                                <span className="font-bold text-yellow-400 text-sm">
                                    4.9 (8.7k {lang === 'vi' ? 'Đánh Giá' : 'Reviews'})
                                </span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    ⚡ {lang === 'vi' ? 'Tỉ Lệ Phản Hồi:' : 'Response Rate:'}
                                </span>
                                <span className="font-bold text-green-400 text-sm">
                                    99% ({lang === 'vi' ? 'Trong Vài Giờ' : 'In Hours'})
                                </span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    🕒 {lang === 'vi' ? 'Tham Gia:' : 'Joined:'}
                                </span>
                                <span className="font-bold text-white text-sm">
                                    {lang === 'vi' ? '2 năm trước' : '2 years ago'}
                                </span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">
                                    📍 {lang === 'vi' ? 'Kho Hàng:' : 'Location:'}
                                </span>
                                <span className="font-bold text-white text-sm">
                                    {lang === 'vi' ? 'TP. Hồ Chí Minh' : 'HCMC, VN'}
                                </span>
                            </div>
                        </div>
                    </div>
                </div>

                {/* Tab Navigation for Shop Items */}
                <div className="flex items-center justify-between border-b border-border pb-3 flex-wrap gap-3">
                    <div className="flex items-center gap-2">
                        {[
                            {
                                id: 'all' as const,
                                label: lang === 'vi' ? 'TẤT CẢ SẢN PHẨM' : 'ALL PRODUCTS',
                                count: displayProducts.length,
                            },
                            {
                                id: 'popular' as const,
                                label: lang === 'vi' ? 'BÁN CHẠY NHẤT' : 'BEST SELLERS',
                                count: Math.min(4, displayProducts.length),
                            },
                            {
                                id: 'auctions' as const,
                                label: lang === 'vi' ? 'ĐANG ĐẤU GIÁ' : 'LIVE AUCTIONS',
                                count: sellerAuctions.length,
                            },
                        ].map((tab) => (
                            <button
                                key={tab.id}
                                onClick={() => setShopTab(tab.id)}
                                className={`px-4 py-2 text-xs font-bold rounded-lg transition-all ${
                                    shopTab === tab.id
                                        ? 'bg-primary text-white shadow-sm'
                                        : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                                }`}
                            >
                                {tab.label} ({tab.count})
                            </button>
                        ))}
                    </div>

                    <p className="text-xs text-gray-400">
                        {lang === 'vi'
                            ? `Đang hiển thị ${displayProducts.length} mặt hàng`
                            : `Showing ${displayProducts.length} items`}
                    </p>
                </div>

                {/* Product Cards Grid */}
                {shopTab === 'auctions' ? (
                    sellerAuctions.length > 0 ? (
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-5">
                            {sellerAuctions.map((auc) => (
                                <AuctionCard key={auc.id} auction={auc} />
                            ))}
                        </div>
                    ) : (
                        <div className="py-16 text-center bg-white rounded-xl border border-border/60">
                            <p className="text-gray-500 text-sm">
                                {lang === 'vi'
                                    ? 'Shop hiện chưa có phiên đấu giá nào đang diễn ra.'
                                    : 'No live auctions from this shop at the moment.'}
                            </p>
                        </div>
                    )
                ) : (
                    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-4 gap-5">
                        {(shopTab === 'popular'
                            ? displayProducts.slice(0, 4)
                            : displayProducts
                        ).map((p) => (
                            <ProductCard key={p.id} product={p} />
                        ))}
                    </div>
                )}
            </div>
        </BuyerLayout>
    )
}

/** Hộp thư đơn/thanh toán dùng API thật, thay cho thông báo và tùy chọn email/push mock. */
export { NotificationInbox as NotificationPage } from './NotificationInbox'
