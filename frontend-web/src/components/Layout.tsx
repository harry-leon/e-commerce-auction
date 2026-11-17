import { useEffect, useState, type ReactNode, type SVGProps } from 'react'
import { useApp } from '../context'
import type { Page } from '../types'
import { tr } from '../i18n'
import {
    LanguageSwitcher,
    Bell,
    ShoppingCart,
    Search,
    User2,
    ChevronDown,
    LogOut,
    Package,
    Gavel,
    Star,
    BarChart2,
    Users,
    Tag,
    Settings2,
    Shield,
    Truck,
    Activity,
    FileText,
    Server,
    Warehouse,
    HeartHandshake,
    CreditCard,
} from './UI'
import { NOTIFICATIONS } from '../mockData'
// Giữ cả phiên đăng nhập cho menu quản trị và tìm kiếm/lọc tồn kho từ nhánh dev.
import { api, errorMessage, type Envelope, type ApiCategory, type ApiProduct } from '../api/client'
import { useSessionStore } from '../features/auth/sessionStore'
import { getBatchStock } from '../api/inventory'
import { filterInStockProducts } from '../features/catalog/catalog'
import { matchesSearchKeyword, scoreProductSearch } from '../utils/searchMatcher'

// Simple monochrome SVG icons per category
function IconElectronics(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <rect x="2" y="3" width="20" height="14" rx="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
        </svg>
    )
}
function IconFashion(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <path d="M20.38 3.46L16 2a4 4 0 01-8 0L3.62 3.46a2 2 0 00-1.34 2.23l.58 3.57a1 1 0 00.99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 002-2V10h2.15a1 1 0 00.99-.84l.58-3.57a2 2 0 00-1.34-2.23z" />
        </svg>
    )
}
function IconHome(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" />
            <polyline points="9 22 9 12 15 12 15 22" />
        </svg>
    )
}
function IconSports(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <circle cx="12" cy="12" r="10" />
            <path d="M4.93 4.93l4.24 4.24M14.83 14.83l4.24 4.24M12 2v4M12 18v4M2 12h4M18 12h4" />
        </svg>
    )
}
function IconBooks(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <path d="M4 19.5A2.5 2.5 0 016.5 17H20" />
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" />
        </svg>
    )
}
function IconCollectibles(p: SVGProps<SVGSVGElement>) {
    return (
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            width="16"
            height="16"
            {...p}
        >
            <path d="M12 2L2 7l10 5 10-5-10-5z" />
            <path d="M2 17l10 5 10-5" />
            <path d="M2 12l10 5 10-5" />
        </svg>
    )
}

/**
 * Bản đồ icon danh mục: hỗ trợ cả mã demo cũ (c1-c6) và UUID thật từ Catalog Service trong PostgreSQL.
 * Giúp PublicHeader và HomePage luôn hiển thị đúng icon tương ứng cho từng nhóm ngành hàng.
 */
export const CATEGORY_ICONS: Record<string, (p: SVGProps<SVGSVGElement>) => React.JSX.Element> = {
    c1: IconElectronics,
    c2: IconFashion,
    c3: IconHome,
    c4: IconSports,
    c5: IconBooks,
    c6: IconCollectibles,
    '00000000-0000-0000-0000-000000000001': IconElectronics,
    '11111111-1111-1111-1111-111111111111': IconElectronics,
    '22222222-2222-2222-2222-222222222222': IconFashion,
    '33333333-3333-3333-3333-333333333333': IconHome,
    '44444444-4444-4444-4444-444444444444': IconSports,
    '55555555-5555-5555-5555-555555555555': IconBooks,
    '66666666-6666-6666-6666-666666666666': IconCollectibles,
}

// ─── Public Header ─────────────────────────────────────────────────────────────
export function PublicHeader() {
    // Danh mục trên thanh điều hướng lấy UUID thật từ Catalog; ID mock sẽ khiến bộ lọc backend không hợp lệ.
    const [categories, setCategories] = useState<ApiCategory[]>([])
    useEffect(() => {
        const controller = new AbortController()
        // Không thay lỗi mạng bằng danh mục demo; SearchPage có hiển thị lỗi tải catalog riêng.
        api<Envelope<ApiCategory[]> | ApiCategory[]>('/categories', { signal: controller.signal })
            .then((response) => {
                const list = Array.isArray((response as any)?.data)
                    ? (response as any).data
                    : Array.isArray(response)
                      ? response
                      : []
                setCategories(list)
            })
            .catch(() => setCategories([]))
        return () => controller.abort()
    }, [])
    // Session quyết định menu được phép hiện; pageParams giữ từ khóa tìm kiếm khi điều hướng từ Discovery.
    const { lang, navigate, user, setUser, cartCount, pageParams } = useApp()
    const sessionReady = useSessionStore((state) => state.sessionReady)
    const [searchQ, setSearchQ] = useState(typeof pageParams?.q === 'string' ? pageParams.q : '')
    const [suggestions, setSuggestions] = useState<ApiProduct[]>([])
    const [suggestionsOpen, setSuggestionsOpen] = useState(false)
    const [suggestionsLoading, setSuggestionsLoading] = useState(false)
    const [suggestionsError, setSuggestionsError] = useState('')
    const [userOpen, setUserOpen] = useState(false)
    const [notifOpen, setNotifOpen] = useState(false)

    // Đồng bộ từ khóa từ URL để thanh tìm kiếm không bị xóa khi chuyển sang trang kết quả.
    useEffect(() => {
        setSearchQ(typeof pageParams?.q === 'string' ? pageParams.q : '')
    }, [pageParams?.q])

    // Debounce truy vấn, lọc tên/mô tả/SKU theo đúng từ khóa rồi chỉ giữ SKU còn hàng.
    useEffect(() => {
        const query = searchQ.trim()
        if (!query) {
            setSuggestions([])
            setSuggestionsLoading(false)
            setSuggestionsError('')
            return
        }

        const controller = new AbortController()
        const timer = setTimeout(() => {
            setSuggestionsLoading(true)
            setSuggestionsError('')
            api<Envelope<ApiProduct[]>>(
                `/products?q=${encodeURIComponent(query)}&page=0&size=12&sort=name,asc`,
                { signal: controller.signal },
            )
                .then(async (response) => {
                    let products = Array.isArray(response?.data) ? response.data : []

                    // Nếu backend trả về rỗng (ví dụ gõ tiếng Việt không dấu "den led" hoặc từ đồng nghĩa),
                    // tự động tải danh mục sản phẩm để đối soát từ khóa thông minh phía client
                    if (products.length === 0) {
                        try {
                            const fallbackRes = await api<Envelope<ApiProduct[]>>(
                                `/products?page=0&size=50&sort=name,asc`,
                                { signal: controller.signal },
                            )
                            if (Array.isArray(fallbackRes?.data)) {
                                products = fallbackRes.data
                            }
                        } catch {}
                    }

                    // Sử dụng bộ so khớp từ khóa tối ưu (đa từ, không dấu tiếng Việt, đảo từ và xếp hạng)
                    const scoredProducts = products
                        .map((product) => {
                            const searchableText = [
                                product.name,
                                (product as any).nameVi,
                                product.description,
                                (product as any).descriptionVi,
                                ...(product.skus || []).map((sku) => sku.skuCode || ''),
                            ]
                                .filter(Boolean)
                                .join(' ')

                            const isMatch = matchesSearchKeyword(searchableText, query)
                            const score = isMatch ? scoreProductSearch(product, query) : 0
                            return { product, score }
                        })
                        .filter(({ score }) => score > 0)
                        .sort((a, b) => b.score - a.score)
                        .slice(0, 6)
                        .map(({ product }) => product)

                    const skuIds = scoredProducts.flatMap((product) =>
                        (product.skus || [])
                            .filter((sku) => sku.status === 'ACTIVE')
                            .map((sku) => sku.id),
                    )
                    if (skuIds.length === 0) return []
                    const stocks = await getBatchStock({ skuIds }, controller.signal)
                    return filterInStockProducts(
                        scoredProducts,
                        Object.fromEntries(
                            stocks.map((stock) => [stock.skuId, stock.availableQty]),
                        ),
                    )
                })
                .then((products) => {
                    if (!controller.signal.aborted) setSuggestions(products)
                })
                .catch((cause) => {
                    if (!controller.signal.aborted) setSuggestionsError(errorMessage(cause))
                })
                .finally(() => {
                    if (!controller.signal.aborted) setSuggestionsLoading(false)
                })
        }, 250)

        return () => {
            clearTimeout(timer)
            controller.abort()
        }
    }, [searchQ])

    const doSearch = (query = searchQ) => {
        const normalizedQuery = query.trim()
        if (normalizedQuery) {
            setSuggestionsOpen(false)
            setSearchQ(normalizedQuery)
            navigate('search', { q: normalizedQuery })
        }
    }
    const showSuggestions =
        suggestionsOpen && !suggestionsLoading && !suggestionsError && suggestions.length > 0

    return (
        /**
         * Chức năng: Header công khai phong cách Shopee (Shopee Orange theme #ee4d2d).
         * Gồm đường kẻ gradient cam nổi bật ở đỉnh, Logo thương hiệu Nexus, thanh tìm kiếm bo tròn và giỏ hàng.
         */
        <header className="bg-white border-b border-orange-100/60 sticky top-0 z-40 shadow-xs">
            {/* Đường viền cam nhận diện thương hiệu đặc trưng kiểu Shopee */}
            <div className="h-1 bg-gradient-to-r from-[#ee4d2d] via-[#f53d2d] to-[#ff7337]" />
            {/* Top bar */}
            <div className="max-w-[1440px] mx-auto px-8 h-16 flex items-center gap-6">
                {/* Logo Nexus phong cách cam Shopee */}
                <button
                    onClick={() => navigate('home')}
                    className="flex items-center gap-2 shrink-0 group"
                >
                    <div className="w-8 h-8 bg-primary rounded-lg flex items-center justify-center shadow-xs group-hover:scale-105 transition-transform">
                        <Gavel size={16} className="text-white" />
                    </div>
                    <span className="text-xl font-black text-gray-900 tracking-tight">
                        Nexus<span className="text-primary font-bold">.</span>
                    </span>
                </button>

                {/* Search */}
                <div
                    className="relative flex-1 max-w-2xl"
                    onBlur={(event) => {
                        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
                            setSuggestionsOpen(false)
                        }
                    }}
                >
                    <div className="flex border border-border rounded-xl overflow-hidden focus-within:ring-2 focus-within:ring-primary/30 focus-within:border-primary">
                        <input
                            value={searchQ}
                            onChange={(e) => {
                                const value = e.target.value
                                setSearchQ(value)
                                setSuggestions([])
                                setSuggestionsError('')
                                setSuggestionsLoading(value.trim().length > 0)
                                setSuggestionsOpen(value.trim().length > 0)
                            }}
                            onKeyDown={(e) => e.key === 'Enter' && doSearch()}
                            aria-autocomplete="list"
                            aria-expanded={showSuggestions}
                            aria-controls="product-search-suggestions"
                            placeholder={
                                lang === 'en'
                                    ? 'Search products, auctions...'
                                    : 'Tìm sản phẩm, phiên đấu giá...'
                            }
                            className="flex-1 px-4 py-2.5 text-sm outline-none text-gray-800 placeholder-gray-400"
                        />
                        <button
                            type="button"
                            onClick={() => doSearch()}
                            aria-label={lang === 'en' ? 'Search' : 'Tìm kiếm'}
                            className="px-4 bg-primary text-white hover:bg-primary-dark transition-colors"
                        >
                            <Search size={16} />
                        </button>
                    </div>
                    {showSuggestions && (
                        <div
                            id="product-search-suggestions"
                            role="listbox"
                            className="absolute left-0 right-0 top-full z-50 mt-1 max-h-80 overflow-y-auto rounded-xl border border-gray-200 bg-white py-2 shadow-lg"
                        >
                            {suggestions.map((product) => (
                                <button
                                    key={product.id}
                                    type="button"
                                    role="option"
                                    aria-selected={false}
                                    className="block w-full px-4 py-2 text-left text-sm text-gray-800 hover:bg-orange-50"
                                    onMouseDown={(event) => event.preventDefault()}
                                    onClick={() => doSearch(product.name)}
                                >
                                    {product.name}
                                </button>
                            ))}
                        </div>
                    )}
                    {suggestionsOpen && suggestionsError && (
                        <p role="alert" className="mt-1 text-xs text-red-600">
                            {suggestionsError}
                        </p>
                    )}
                </div>

                {/* Right controls */}
                <div className="flex items-center gap-2 shrink-0 ml-auto">
                    {/* Notifications */}
                    {sessionReady && user && (
                        <div
                            className="relative"
                            onMouseEnter={() => setNotifOpen(true)}
                            onMouseLeave={() => setNotifOpen(false)}
                        >
                            <button
                                onClick={() => navigate('notifications')}
                                className="relative p-2 rounded-lg text-gray-600 hover:bg-gray-100 transition-colors"
                            >
                                <Bell size={20} />
                                {0 > 0 && (
                                    <span className="absolute top-1 right-1 w-4 h-4 bg-gray-800 text-white text-[10px] font-bold rounded-full flex items-center justify-center">
                                        0
                                    </span>
                                )}
                            </button>
                            {notifOpen && (
                                <div className="absolute right-0 top-full pt-2 w-96 z-50">
                                    <div className="bg-white border border-border rounded-xl shadow-lg overflow-hidden">
                                        <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                                            <p className="font-semibold text-sm text-gray-900">
                                                {lang === 'en' ? 'Notifications' : 'Thông báo'}
                                            </p>
                                            <span className="text-xs text-gray-400">
                                                {
                                                    NOTIFICATIONS.filter(
                                                        (notification) => !notification.read,
                                                    ).length
                                                }{' '}
                                                {lang === 'en' ? 'unread' : 'chưa đọc'}
                                            </span>
                                        </div>
                                        <div className="max-h-80 overflow-y-auto">
                                            {NOTIFICATIONS.slice(0, 4).map((notification) => (
                                                <button
                                                    key={notification.id}
                                                    onClick={() => navigate('notifications')}
                                                    className={`w-full flex items-start gap-3 px-4 py-3 text-left border-b border-border last:border-0 hover:bg-gray-50 ${notification.read ? '' : 'bg-gray-50/70'}`}
                                                >
                                                    <span className="w-8 h-8 rounded-lg bg-gray-100 text-gray-500 flex items-center justify-center shrink-0">
                                                        <Bell size={14} />
                                                    </span>
                                                    <span className="flex-1 min-w-0">
                                                        <span className="flex items-center gap-2">
                                                            <span className="text-sm font-semibold text-gray-800 truncate">
                                                                {lang === 'vi'
                                                                    ? notification.titleVi
                                                                    : notification.title}
                                                            </span>
                                                            {!notification.read && (
                                                                <span className="w-1.5 h-1.5 rounded-full bg-gray-600 shrink-0" />
                                                            )}
                                                        </span>
                                                        <span className="block text-xs text-gray-500 mt-0.5 line-clamp-2">
                                                            {lang === 'vi'
                                                                ? notification.bodyVi
                                                                : notification.body}
                                                        </span>
                                                        <span className="block text-[11px] text-gray-400 mt-1">
                                                            {new Date(
                                                                notification.timestamp,
                                                            ).toLocaleString()}
                                                        </span>
                                                    </span>
                                                </button>
                                            ))}
                                        </div>
                                        <button
                                            onClick={() => navigate('notifications')}
                                            className="w-full px-4 py-3 text-sm font-semibold text-gray-700 hover:bg-gray-50"
                                        >
                                            {lang === 'en'
                                                ? 'View all notifications'
                                                : 'Xem tất cả thông báo'}
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    )}

                    {/* Cart */}
                    <button
                        onClick={() => navigate('cart')}
                        className="relative p-2 rounded-xl text-gray-600 hover:bg-gray-100 hover:text-primary transition-all duration-200 active:scale-95"
                        aria-label="Shopping Cart"
                    >
                        <ShoppingCart size={20} />
                        {cartCount > 0 && (
                            <span
                                key={cartCount}
                                className="cart-count-bounce absolute -top-1 -right-1 min-w-[20px] h-5 px-1 bg-primary text-white text-[11px] font-bold rounded-full flex items-center justify-center shadow-md ring-2 ring-white"
                            >
                                {cartCount}
                            </span>
                        )}
                    </button>

                    {/* User menu */}
                    {!sessionReady ? (
                        <div className="w-36 h-10" aria-hidden="true" />
                    ) : user ? (
                        <div className="relative">
                            <button
                                onClick={() => setUserOpen((o) => !o)}
                                className="flex items-center gap-2 p-1.5 rounded-xl hover:bg-gray-100 transition-colors"
                            >
                                <img
                                    src={user.avatar}
                                    alt={user.fullName}
                                    className="w-8 h-8 rounded-full object-cover ring-1 ring-gray-200"
                                />
                                <span className="text-sm font-semibold text-gray-700 max-w-[100px] truncate">
                                    {user.fullName}
                                </span>
                                <ChevronDown size={14} className="text-gray-400" />
                            </button>
                            {userOpen && (
                                <div className="absolute right-0 top-full mt-2 bg-white border border-gray-100 rounded-2xl shadow-xl py-2 w-56 z-50 animate-in fade-in zoom-in-95 duration-150">
                                    <div className="px-4 py-2 border-b border-gray-100 mb-1">
                                        <p className="font-semibold text-sm text-gray-900">{user.fullName}</p>
                                        <p className="text-xs text-gray-500 truncate">{user.email}</p>
                                    </div>
                                    {[
                                        {
                                            icon: <User2 size={14} />,
                                            label: tr(lang, 'profile'),
                                            page: (user.role === 'admin'
                                                ? 'admin-profile'
                                                : user.role === 'seller'
                                                  ? 'seller-profile'
                                                  : user.role === 'support'
                                                    ? 'support-profile'
                                                    : 'profile') as Page,
                                        },
                                        {
                                            icon: <Package size={14} />,
                                            label: tr(lang, 'myOrders'),
                                            page: 'order-history' as const,
                                        },
                                        {
                                            icon: <Gavel size={14} />,
                                            label: tr(lang, 'myBids'),
                                            page: 'my-bids' as const,
                                        },
                                        {
                                            icon: <Star size={14} />,
                                            label: tr(lang, 'myReputation'),
                                            page: 'my-reputation' as const,
                                        },
                                    ].map((item) => (
                                        <button
                                            key={item.page}
                                            onClick={() => {
                                                navigate(item.page)
                                                setUserOpen(false)
                                            }}
                                            className="flex items-center gap-3 w-full px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 hover:text-primary transition-colors"
                                        >
                                            <span className="text-gray-400">{item.icon}</span>
                                            {item.label}
                                        </button>
                                    ))}
                                    <div className="border-t border-gray-100 mt-1 pt-1">
                                        <button
                                            onClick={async () => {
                                                // Chỉ rời trang sau khi server xóa cookie; lỗi cho phép thử lại.
                                                try {
                                                    await setUser(null)
                                                } catch {
                                                    window.alert(
                                                        'Đăng xuất thất bại, vui lòng thử lại.',
                                                    )
                                                    return
                                                }
                                                navigate('login')
                                                setUserOpen(false)
                                            }}
                                            className="flex items-center gap-3 w-full px-4 py-2 text-sm text-danger hover:bg-danger-light transition-colors"
                                        >
                                            <LogOut size={14} />
                                            {tr(lang, 'logout')}
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    ) : (
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => navigate('login')}
                                className="nexus-btn nexus-btn-outline nexus-btn-sm"
                            >
                                {tr(lang, 'login')}
                            </button>
                            <button
                                onClick={() => navigate('register')}
                                className="nexus-btn nexus-btn-primary nexus-btn-sm"
                            >
                                {tr(lang, 'register')}
                            </button>
                        </div>
                    )}

                    <LanguageSwitcher />
                </div>
            </div>

            {/* Category nav */}
            <div className="border-t border-gray-100">
                <div className="max-w-[1440px] mx-auto px-8 flex items-center gap-1 py-1.5 overflow-x-auto">
                    {(categories || []).map((cat) => {
                        const Icon = CATEGORY_ICONS[cat.id]
                        return (
                            <button
                                key={cat.id}
                                onClick={() => navigate('search', { category: cat.id })}
                                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-medium text-gray-600 hover:text-primary hover:bg-primary-light transition-all duration-200 active:scale-95 whitespace-nowrap"
                            >
                                {Icon && <Icon className="text-gray-500" />}
                                {lang === 'vi' ? cat.nameVi : cat.name}
                            </button>
                        )
                    })}
                    <button
                        onClick={() => navigate('auction-list')}
                        className="flex items-center gap-2 px-3.5 py-1.5 rounded-xl text-sm text-auction font-bold bg-orange-50 hover:bg-orange-100/80 border border-orange-200/60 shadow-xs transition-all duration-200 active:scale-95 whitespace-nowrap ml-auto"
                    >
                        <span className="live-indicator" />
                        <Gavel size={14} />
                        {lang === 'en' ? 'Live Auctions' : 'Đấu giá trực tiếp'}
                    </button>
                </div>
            </div>
        </header>
    )
}

// ─── Footer ────────────────────────────────────────────────────────────────────
export function Footer() {
    const { lang, navigate } = useApp()
    return (
        <footer className="bg-gray-900 text-gray-400 mt-16">
            <div className="max-w-[1440px] mx-auto px-8 py-12 grid grid-cols-4 gap-8">
                <div>
                    <div className="flex items-center gap-2 mb-4">
                        <div className="w-7 h-7 bg-primary rounded-lg flex items-center justify-center">
                            <Gavel size={14} className="text-white" />
                        </div>
                        <span className="text-white font-black text-lg">Nexus</span>
                    </div>
                    <p className="text-sm leading-relaxed">
                        {lang === 'en'
                            ? 'The trusted marketplace for buying, selling, and bidding on products.'
                            : 'Sàn thương mại đáng tin cậy để mua, bán và đấu giá sản phẩm.'}
                    </p>
                </div>
                {[
                    {
                        title: lang === 'en' ? 'Buy' : 'Mua sắm',
                        links: [
                            {
                                l: lang === 'en' ? 'Browse Products' : 'Duyệt sản phẩm',
                                p: 'search' as const,
                            },
                            {
                                l: lang === 'en' ? 'Live Auctions' : 'Đấu giá',
                                p: 'auction-list' as const,
                            },
                            {
                                l: lang === 'en' ? 'My Orders' : 'Đơn hàng',
                                p: 'order-history' as const,
                            },
                        ],
                    },
                    {
                        title: lang === 'en' ? 'Sell' : 'Bán hàng',
                        links: [
                            {
                                l: lang === 'en' ? 'Seller Dashboard' : 'Bảng điều khiển',
                                p: 'seller-dashboard' as const,
                            },
                            {
                                l: lang === 'en' ? 'List a Product' : 'Đăng sản phẩm',
                                p: 'seller-create-product' as const,
                            },
                        ],
                    },
                    {
                        title: lang === 'en' ? 'Support' : 'Hỗ trợ',
                        links: [
                            {
                                l: lang === 'en' ? 'Help Center' : 'Trung tâm trợ giúp',
                                p: 'home' as const,
                            },
                            { l: lang === 'en' ? 'Contact Us' : 'Liên hệ', p: 'home' as const },
                        ],
                    },
                ].map((col) => (
                    <div key={col.title}>
                        <h4 className="text-white font-semibold text-sm mb-3">{col.title}</h4>
                        <ul className="space-y-2">
                            {col.links.map((lnk) => (
                                <li key={lnk.l}>
                                    <button
                                        onClick={() => navigate(lnk.p)}
                                        className="text-sm hover:text-white transition-colors"
                                    >
                                        {lnk.l}
                                    </button>
                                </li>
                            ))}
                        </ul>
                    </div>
                ))}
            </div>
            <div className="border-t border-gray-800">
                <div className="max-w-[1440px] mx-auto px-8 py-4 flex items-center justify-between">
                    <p className="text-xs text-gray-600">
                        © 2024 Nexus.{' '}
                        {lang === 'en' ? 'All rights reserved.' : 'Bảo lưu mọi quyền.'}
                    </p>
                    <LanguageSwitcher />
                </div>
            </div>
        </footer>
    )
}

// ─── Buyer Layout ─────────────────────────────────────────────────────────────
export function BuyerLayout({ children, noPad }: { children: ReactNode; noPad?: boolean }) {
    return (
        <div className="min-h-screen bg-page flex flex-col">
            <PublicHeader />
            <main className={`flex-1 ${noPad ? '' : 'max-w-[1440px] mx-auto px-8 py-8 w-full'}`}>
                {children}
            </main>
            <Footer />
        </div>
    )
}

// ─── Seller Layout ─────────────────────────────────────────────────────────────
/**
 * Chức năng: Danh sách menu điều hướng cho cổng Người bán (Seller Portal).
 * Quy tắc & lý do: Đã loại bỏ mục 'seller-profile' khỏi danh sách điều hướng chính,
 * chuyển lối vào Profile xuống nút tài khoản người dùng 'Seller Nexus' ở chân sidebar (đồng bộ với Admin).
 */
const SELLER_NAV = [
    {
        id: 'seller-dashboard',
        label: 'Dashboard',
        labelVi: 'Bảng điều khiển',
        icon: <BarChart2 size={16} />,
    },
    { id: 'seller-products', label: 'Products', labelVi: 'Sản phẩm', icon: <Package size={16} /> },
    { id: 'seller-auctions', label: 'Auctions', labelVi: 'Đấu giá', icon: <Gavel size={16} /> },
    { id: 'seller-orders', label: 'Orders', labelVi: 'Đơn hàng', icon: <Tag size={16} /> },
    { id: 'seller-balance', label: 'Balance', labelVi: 'Số dư', icon: <CreditCard size={16} /> },
    {
        id: 'seller-inventory',
        label: 'Inventory',
        labelVi: 'Kho hàng',
        icon: <Warehouse size={16} />,
    },
    { id: 'seller-reputation', label: 'Reputation', labelVi: 'Uy tín', icon: <Star size={16} /> },
    {
        id: 'seller-notifications',
        label: 'Notifications',
        labelVi: 'Thông báo',
        icon: <Bell size={16} />,
    },
]

/**
 * Chức năng: Khung bố cục chuẩn cho Seller Portal đồng bộ 100% với cấu trúc viewport của AdminLayout.
 * Đầu vào: children (nội dung trang Seller), title (tiêu đề trang).
 * Luồng & Quy tắc:
 *   - Khung ngoài h-screen w-screen flex overflow-hidden bám cố định viewport.
 *   - Sidebar cố định chiều rộng w-56 h-full min-h-0 với nền #0F172A, thanh cuộn độc lập min-h-0 overflow-y-auto.
 *   - Lối vào Profile được gắn trực tiếp vào khối thẻ thông tin tài khoản 'Seller Nexus' ở chân sidebar.
 *   - Đã loại bỏ hoàn toàn nút 'Buyer Storefront' (Trang mua hàng) theo yêu cầu.
 *   - Main container độc lập min-h-0 overflow-y-auto overflow-x-hidden.
 */
export function SellerLayout({ children, title }: { children: ReactNode; title: string }) {
    const { lang, page, navigate, user, setUser } = useApp()
    const [notifOpen, setNotifOpen] = useState(false)

    useEffect(() => {
        if (title && typeof document !== 'undefined') {
            document.title = `${title} | Nexus`
        }
    }, [title])
    return (
        <div className="h-screen w-screen flex overflow-hidden bg-page">
            {/* Sidebar Seller cố định chiều rộng w-56 đồng bộ AdminLayout */}
            <aside
                className="w-56 h-full min-h-0 flex flex-col shrink-0"
                style={{ background: '#0F172A' }}
            >
                <div className="p-5 border-b border-white/10">
                    <button onClick={() => navigate('seller-dashboard')} className="flex items-center gap-2">
                        <div className="w-7 h-7 bg-primary rounded-lg flex items-center justify-center">
                            <Gavel size={14} className="text-white" />
                        </div>
                        <span className="text-white font-black text-lg">Nexus</span>
                    </button>
                    <p className="text-xs text-gray-400 mt-1">
                        {lang === 'en' ? 'Seller Portal' : 'Cổng người bán'}
                    </p>
                </div>
                <nav className="flex-1 min-h-0 p-3 overflow-y-auto space-y-1">
                    {SELLER_NAV.map((item) => (
                        <button
                            key={item.id}
                            onClick={() => navigate(item.id as any)}
                            className={`sidebar-link w-full ${page === item.id ? 'active' : ''}`}
                        >
                            {item.icon}
                            <span>{lang === 'vi' ? item.labelVi : item.label}</span>
                        </button>
                    ))}
                </nav>
                <div className="p-3 border-t border-white/10">
                    {user && (
                        // Chức năng: Bấm vào thẻ Seller Nexus để truy cập trang Profile (Hồ sơ người bán)
                        <button
                            type="button"
                            aria-label={lang === 'en' ? 'Open Seller Nexus profile' : 'Mở hồ sơ Seller Nexus'}
                            onClick={() => navigate('seller-profile')}
                            className="sidebar-link w-full flex items-center gap-2 px-3 py-2 mb-2 text-left"
                        >
                            <img
                                src={user.avatar}
                                alt=""
                                className="w-7 h-7 rounded-full object-cover"
                            />
                            <div className="flex-1 min-w-0">
                                <p className="text-gray-200 text-xs font-semibold truncate">
                                    {user.fullName || 'Seller Nexus'}
                                </p>
                                <p className="text-gray-500 text-[11px] truncate">
                                    {lang === 'en' ? 'Seller' : 'Người bán'}
                                </p>
                            </div>
                        </button>
                    )}
                    {/* Bỏ mục 'Buyer Storefront', chỉ giữ nút Đăng xuất */}
                    <button
                        onClick={() => {
                            setUser(null)
                            navigate('login')
                        }}
                        className="sidebar-link w-full text-red-400 hover:text-red-300 hover:bg-red-900/20"
                    >
                        <LogOut size={14} />
                        {lang === 'en' ? 'Log Out' : 'Đăng xuất'}
                    </button>
                </div>
            </aside>
            {/* Main view container bám chuẩn viewport với thanh cuộn dọc riêng */}
            <div className="flex-1 flex flex-col min-w-0 min-h-0">
                {/* Topbar */}
                <div className="h-14 bg-white border-b border-border px-8 flex items-center justify-between shrink-0">
                    <h1 className="text-lg font-bold text-gray-900">{title}</h1>
                    <div className="flex items-center gap-3">
                        <LanguageSwitcher />
                        <div
                            className="relative"
                            onMouseEnter={() => setNotifOpen(true)}
                            onMouseLeave={() => setNotifOpen(false)}
                        >
                            <button
                                onClick={() => navigate('seller-notifications')}
                                className="relative p-2 rounded-lg text-gray-500 hover:bg-gray-100"
                            >
                                <Bell size={18} />
                                <span className="absolute top-1 right-1 w-3.5 h-3.5 bg-gray-800 text-white text-[9px] font-bold rounded-full flex items-center justify-center">
                                    {
                                        NOTIFICATIONS.filter((notification) => !notification.read)
                                            .length
                                    }
                                </span>
                            </button>
                            {notifOpen && (
                                <div className="absolute right-0 top-full pt-2 w-96 z-50">
                                    <div className="bg-white border border-border rounded-xl shadow-lg overflow-hidden">
                                        <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                                            <p className="font-semibold text-sm text-gray-900">
                                                {lang === 'en'
                                                    ? 'Seller notifications'
                                                    : 'Thông báo người bán'}
                                            </p>
                                            <span className="text-xs text-gray-400">
                                                {
                                                    NOTIFICATIONS.filter(
                                                        (notification) => !notification.read,
                                                    ).length
                                                }{' '}
                                                {lang === 'en' ? 'unread' : 'chưa đọc'}
                                            </span>
                                        </div>
                                        <div className="max-h-80 overflow-y-auto">
                                            {NOTIFICATIONS.slice(0, 4).map((notification) => (
                                                <button
                                                    key={notification.id}
                                                    onClick={() => navigate('seller-notifications')}
                                                    className={`w-full flex items-start gap-3 px-4 py-3 text-left border-b border-border last:border-0 hover:bg-gray-50 ${notification.read ? '' : 'bg-gray-50/70'}`}
                                                >
                                                    <span className="w-8 h-8 rounded-lg bg-gray-100 text-gray-500 flex items-center justify-center shrink-0">
                                                        <Bell size={14} />
                                                    </span>
                                                    <span className="flex-1 min-w-0">
                                                        <span className="flex items-center gap-2">
                                                            <span className="text-sm font-semibold text-gray-800 truncate">
                                                                {lang === 'vi'
                                                                    ? notification.titleVi
                                                                    : notification.title}
                                                            </span>
                                                            {!notification.read && (
                                                                <span className="w-1.5 h-1.5 rounded-full bg-gray-600 shrink-0" />
                                                            )}
                                                        </span>
                                                        <span className="block text-xs text-gray-500 mt-0.5 line-clamp-2">
                                                            {lang === 'vi'
                                                                ? notification.bodyVi
                                                                : notification.body}
                                                        </span>
                                                        <span className="block text-[11px] text-gray-400 mt-1">
                                                            {new Date(
                                                                notification.timestamp,
                                                            ).toLocaleString()}
                                                        </span>
                                                    </span>
                                                </button>
                                            ))}
                                        </div>
                                        <button
                                            onClick={() => navigate('seller-notifications')}
                                            className="w-full px-4 py-3 text-sm font-semibold text-gray-700 hover:bg-gray-50"
                                        >
                                            {lang === 'en'
                                                ? 'View all notifications'
                                                : 'Xem tất cả thông báo'}
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
                <main className="flex-1 min-h-0 p-8 overflow-y-auto overflow-x-hidden">{children}</main>
            </div>
        </div>
    )
}

// ─── Admin Layout ──────────────────────────────────────────────────────────────
const ADMIN_NAV = [
    {
        id: 'admin-dashboard',
        label: 'Dashboard',
        labelVi: 'Tổng quan',
        icon: <BarChart2 size={15} />,
        group: 'main',
    },
    {
        id: 'admin-users',
        label: 'Users',
        labelVi: 'Người dùng',
        icon: <Users size={15} />,
        group: 'main',
    },
    {
        id: 'admin-roles',
        label: 'Roles',
        labelVi: 'Vai trò',
        icon: <Shield size={15} />,
        group: 'main',
    },
    {
        id: 'admin-categories',
        label: 'Categories',
        labelVi: 'Danh mục',
        icon: <Tag size={15} />,
        group: 'catalog',
    },
    {
        id: 'admin-products',
        label: 'Products',
        labelVi: 'Sản phẩm',
        icon: <Package size={15} />,
        group: 'catalog',
    },
    {
        id: 'admin-orders',
        label: 'Orders',
        labelVi: 'Đơn hàng',
        icon: <Tag size={15} />,
        group: 'ops',
    },
    {
        id: 'admin-auctions',
        label: 'Auctions',
        labelVi: 'Đấu giá',
        icon: <Gavel size={15} />,
        group: 'ops',
    },
    {
        id: 'admin-warehouses',
        label: 'Warehouses',
        labelVi: 'Kho hàng',
        icon: <Warehouse size={15} />,
        group: 'ops',
    },
    {
        id: 'admin-shipments',
        label: 'Shipments',
        labelVi: 'Vận chuyển',
        icon: <Truck size={15} />,
        group: 'ops',
    },
    {
        id: 'admin-reputation',
        label: 'Reputation',
        labelVi: 'Uy tín',
        icon: <Star size={15} />,
        group: 'ops',
    },
    {
        id: 'admin-event-logs',
        label: 'Event Logs',
        labelVi: 'Nhật ký sự kiện',
        icon: <Activity size={15} />,
        group: 'system',
    },
    {
        id: 'admin-audit-logs',
        label: 'Audit Logs',
        labelVi: 'Nhật ký kiểm tra',
        icon: <FileText size={15} />,
        group: 'system',
    },
    {
        id: 'admin-system-config',
        label: 'Config',
        labelVi: 'Cấu hình',
        icon: <Settings2 size={15} />,
        group: 'system',
    },
    {
        id: 'admin-system-health',
        label: 'System Health',
        labelVi: 'Trạng thái hệ thống',
        icon: <Server size={15} />,
        group: 'system',
    },
]

const GROUP_LABELS: Record<string, [string, string]> = {
    main: ['Management', 'Quản lý'],
    catalog: ['Catalog', 'Danh mục'],
    ops: ['Operations', 'Vận hành'],
    system: ['System', 'Hệ thống'],
}

export function AdminLayout({ children, title }: { children: ReactNode; title: string }) {
    const { lang, page, navigate, user, setUser } = useApp()
    const groups = ['main', 'catalog', 'ops', 'system']

    // BR-IAM-05: RBAC check - Only ADMIN role can access Admin pages
    if (!user || user.role !== 'admin') {
        return (
            <div className="min-h-screen bg-page flex items-center justify-center p-6">
                <div className="nexus-card max-w-md w-full p-8 text-center space-y-4 shadow-xl border border-red-100">
                    <div className="w-16 h-16 bg-red-50 text-danger rounded-2xl flex items-center justify-center mx-auto shadow-sm">
                        <Shield size={32} />
                    </div>
                    <div>
                        <span className="badge badge-red uppercase tracking-wider text-[11px] font-bold">
                            BR-IAM-05 Access Denied
                        </span>
                        <h2 className="text-xl font-bold text-gray-900 mt-2">
                            {lang === 'en'
                                ? '403 - Administrator Access Required'
                                : '403 - Yêu cầu quyền Quản trị viên'}
                        </h2>
                        <p className="text-sm text-gray-500 mt-2 leading-relaxed">
                            {lang === 'en'
                                ? `Your current role (${user?.role || 'Guest'}) does not have permission to access the Admin Console and Category Management. Please sign in with an Administrator account.`
                                : `Vai trò hiện tại của bạn (${user?.role || 'Khách'}) không có quyền truy cập Bảng quản trị & Quản lý danh mục. Vui lòng đăng nhập với tài khoản Quản trị viên.`}
                        </p>
                    </div>
                    <div className="pt-2 flex flex-col gap-2">
                        <button
                            onClick={() => navigate('home')}
                            className="nexus-btn nexus-btn-primary w-full py-2.5 font-semibold"
                        >
                            {lang === 'en' ? 'Return to Home Store' : 'Quay về Cửa hàng'}
                        </button>
                        <button
                            onClick={() => navigate('login')}
                            className="nexus-btn nexus-btn-ghost w-full text-sm text-gray-500 hover:text-gray-800"
                        >
                            {lang === 'en' ? 'Switch Account / Login' : 'Đổi tài khoản / Đăng nhập'}
                        </button>
                    </div>
                </div>
            </div>
        )
    }

    return (
        // Khung Admin bám viewport; sidebar và vùng nội dung tự cuộn dọc thay vì kéo dài trang ngoài.
        <div className="h-screen w-screen flex overflow-hidden bg-page">
            <aside
                className="w-56 h-full min-h-0 flex flex-col shrink-0"
                style={{ background: '#0F172A' }}
            >
                <div className="p-5 border-b border-white/10">
                    {/* Logo trong Admin luôn đưa quản trị viên về Dashboard, không chuyển sang storefront Buyer. */}
                    <button
                        onClick={() => navigate('admin-dashboard')}
                        className="flex items-center gap-2"
                    >
                        <div className="w-7 h-7 bg-primary rounded-lg flex items-center justify-center">
                            <Shield size={14} className="text-white" />
                        </div>
                        <span className="text-white font-black text-lg">Nexus</span>
                    </button>
                </div>
                <nav className="flex-1 min-h-0 p-3 overflow-y-auto space-y-1">
                    {groups.map((g) => {
                        const items = ADMIN_NAV.filter((n) => n.group === g)
                        return (
                            <div key={g}>
                                <p className="px-3 py-1.5 text-[10px] font-bold text-gray-600 uppercase tracking-widest">
                                    {lang === 'vi' ? GROUP_LABELS[g][1] : GROUP_LABELS[g][0]}
                                </p>
                                {items.map((item) => (
                                    <button
                                        key={item.id}
                                        onClick={() => navigate(item.id as any)}
                                        className={`admin-sidebar-link w-full ${page === item.id || (item.id === 'admin-profile' && page === 'profile') ? 'active' : ''}`}
                                    >
                                        {item.icon}
                                        <span>{lang === 'vi' ? item.labelVi : item.label}</span>
                                    </button>
                                ))}
                            </div>
                        )
                    })}
                </nav>
                <div className="p-3 border-t border-white/10">
                    {user && (
                        // Đặt lối vào hồ sơ tại khu vực tài khoản để menu quản lý không có mục Profile riêng.
                        <button
                            type="button"
                            aria-label={
                                lang === 'en' ? 'Open Admin Nexus profile' : 'Mở hồ sơ Admin Nexus'
                            }
                            onClick={() => navigate('admin-profile')}
                            className="admin-sidebar-link w-full flex items-center gap-2 px-3 py-2 mb-2 text-left"
                        >
                            <img
                                src={user.avatar}
                                alt=""
                                className="w-7 h-7 rounded-full object-cover"
                            />
                            <div className="flex-1 min-w-0">
                                <p className="text-gray-200 text-xs font-semibold truncate">
                                    {user.fullName}
                                </p>
                                <p className="text-gray-500 text-[11px] truncate">Admin</p>
                            </div>
                        </button>
                    )}
                    {/* Bỏ mục chuyển sang Trang mua hàng (Buyer) theo yêu cầu, Admin chỉ giữ nút Đăng xuất */}
                    <button
                        onClick={() => {
                            setUser(null)
                            navigate('login')
                        }}
                        className="admin-sidebar-link w-full text-red-400 hover:text-red-300 hover:bg-red-900/20 w-full"
                    >
                        <LogOut size={14} />
                        {lang === 'en' ? 'Log Out' : 'Đăng xuất'}
                    </button>
                </div>
            </aside>
            <div className="flex-1 flex flex-col min-w-0 min-h-0">
                <div className="h-14 bg-white border-b border-border px-8 flex items-center justify-between shrink-0">
                    <h1 className="text-lg font-bold text-gray-900">{title}</h1>
                    <div className="flex items-center gap-3">
                        <LanguageSwitcher />
                    </div>
                </div>
                <main className="flex-1 min-h-0 p-8 overflow-y-auto overflow-x-hidden">
                    {children}
                </main>
            </div>
        </div>
    )
}

// ─── Support Layout ────────────────────────────────────────────────────────────
export function SupportLayout({ children, title }: { children: ReactNode; title: string }) {
    const { lang, page, navigate, setUser } = useApp()
    const SUPPORT_NAV = [
        {
            id: 'support-dashboard',
            label: 'Ticket Queue',
            labelVi: 'Hàng đợi',
            icon: <HeartHandshake size={16} />,
        },
        {
            id: 'support-order-lookup',
            label: 'Lookup Order',
            labelVi: 'Tra cứu đơn',
            icon: <Tag size={16} />,
        },
        {
            id: 'support-user-lookup',
            label: 'Lookup User',
            labelVi: 'Tra cứu người dùng',
            icon: <Users size={16} />,
        },
        { id: 'support-profile', label: 'Profile', labelVi: 'Hồ sơ', icon: <User2 size={16} /> },
    ]
    return (
        <div className="min-h-screen flex bg-page">
            <aside
                className="w-56 min-h-screen flex flex-col shrink-0"
                style={{ background: '#134E4A' }}
            >
                <div className="p-5 border-b border-white/10">
                    <div className="flex items-center gap-2">
                        <div className="w-7 h-7 bg-teal-500 rounded-lg flex items-center justify-center">
                            <HeartHandshake size={14} className="text-white" />
                        </div>
                        <span className="text-white font-black text-lg">Nexus</span>
                    </div>
                    <p className="text-xs text-teal-300 mt-1">Support Staff</p>
                </div>
                <nav className="flex-1 p-3 space-y-0.5">
                    {SUPPORT_NAV.map((item) => (
                        <button
                            key={item.id}
                            onClick={() => navigate(item.id as any)}
                            className={`sidebar-link w-full ${page === item.id || (item.id === 'support-profile' && page === 'profile') ? 'active' : ''}`}
                        >
                            {item.icon}
                            <span>{lang === 'vi' ? item.labelVi : item.label}</span>
                        </button>
                    ))}
                </nav>
                <div className="p-3 border-t border-white/10">
                    {/* Chức năng: Cho phép Support chuyển nhanh sang trang mua hàng của Buyer */}
                    <button
                        onClick={() => navigate('home')}
                        className="sidebar-link w-full text-indigo-300 hover:text-white hover:bg-white/10 mb-1"
                    >
                        <ShoppingCart size={16} />
                        <span>{lang === 'en' ? 'Buyer Storefront' : 'Trang mua hàng (Buyer)'}</span>
                    </button>
                    <button
                        onClick={() => {
                            setUser(null)
                            navigate('login')
                        }}
                        className="sidebar-link w-full text-red-400"
                    >
                        <LogOut size={14} />
                        {lang === 'en' ? 'Log Out' : 'Đăng xuất'}
                    </button>
                </div>
            </aside>
            <div className="flex-1 flex flex-col">
                <div className="h-14 bg-white border-b border-border px-8 flex items-center justify-between shrink-0">
                    <h1 className="text-lg font-bold text-gray-900">{title}</h1>
                    <LanguageSwitcher />
                </div>
                <main className="flex-1 p-8 overflow-auto">{children}</main>
            </div>
        </div>
    )
}
