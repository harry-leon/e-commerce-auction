import { useEffect, useState } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
import { BuyerLayout } from '../../components/Layout'
import { ProductCard } from '../../components/Cards'
import { AuctionCard } from '../../components/Cards'
import {
    Button,
    Input,
    Textarea,
    Select,
    NumberStepper,
    Tabs,
    Modal,
    ConfirmModal,
    AlertBanner,
    EmptyState,
    StarRating,
    StatusBadge,
    OrderTimeline,
    ReputationBar,
    ProgressBar,
    Toggle,
    Checkbox,
    Breadcrumb,
    Pagination,
    Download,
    Gavel,
    Search,
    Filter,
    ChevronLeft,
    ChevronRight,
    AlertTriangle,
    CheckCircle,
    Truck,
    Package,
    ShoppingCart,
    CreditCard,
    Settings2,
    Gift,
    Star,
    CircleX,
    Shield,
} from '../../components/UI'
import { PRODUCTS, AUCTIONS, ORDERS, CATEGORIES, NOTIFICATIONS } from '../../mockData'
import { CATEGORY_ICONS } from '../../components/Layout'
import { getEffectivePaymentStatus } from '../../payment'
import type { Order, OrderStatus } from '../../types'
import { ordersApi } from '../../api/orders'
import { useLoad } from '../../hooks/useLoad'
import type { ApiCategory, ApiProduct, Envelope } from '../../api/client'

function getAuctionCurrentBid(auction: unknown) {
    if (!auction || typeof auction !== 'object') return 0
    const value = Reflect.get(auction, 'currentBid')
    return typeof value === 'number' ? value : 0
}

const BUYER_REGIONS = [
    { id: 'all', en: 'All regions', vi: 'Tất cả khu vực' },
    { id: 'ha-noi', en: 'Ha Noi', vi: 'Hà Nội' },
    { id: 'hue', en: 'Hue', vi: 'Huế' },
    { id: 'hai-phong', en: 'Hai Phong', vi: 'Hải Phòng' },
    { id: 'da-nang', en: 'Da Nang', vi: 'Đà Nẵng' },
    { id: 'can-tho', en: 'Can Tho', vi: 'Cần Thơ' },
    { id: 'ho-chi-minh', en: 'Ho Chi Minh City', vi: 'TP. Hồ Chí Minh' },
    { id: 'lai-chau', en: 'Lai Chau', vi: 'Lai Châu' },
    { id: 'dien-bien', en: 'Dien Bien', vi: 'Điện Biên' },
    { id: 'son-la', en: 'Son La', vi: 'Sơn La' },
    { id: 'lang-son', en: 'Lang Son', vi: 'Lạng Sơn' },
    { id: 'cao-bang', en: 'Cao Bang', vi: 'Cao Bằng' },
    { id: 'tuyen-quang', en: 'Tuyen Quang', vi: 'Tuyên Quang' },
    { id: 'lao-cai', en: 'Lao Cai', vi: 'Lào Cai' },
    { id: 'thai-nguyen', en: 'Thai Nguyen', vi: 'Thái Nguyên' },
    { id: 'phu-tho', en: 'Phu Tho', vi: 'Phú Thọ' },
    { id: 'bac-ninh', en: 'Bac Ninh', vi: 'Bắc Ninh' },
    { id: 'hung-yen', en: 'Hung Yen', vi: 'Hưng Yên' },
    { id: 'ninh-binh', en: 'Ninh Binh', vi: 'Ninh Bình' },
    { id: 'quang-ninh', en: 'Quang Ninh', vi: 'Quảng Ninh' },
    { id: 'thanh-hoa', en: 'Thanh Hoa', vi: 'Thanh Hóa' },
    { id: 'nghe-an', en: 'Nghe An', vi: 'Nghệ An' },
    { id: 'ha-tinh', en: 'Ha Tinh', vi: 'Hà Tĩnh' },
    { id: 'quang-tri', en: 'Quang Tri', vi: 'Quảng Trị' },
    { id: 'quang-ngai', en: 'Quang Ngai', vi: 'Quảng Ngãi' },
    { id: 'gia-lai', en: 'Gia Lai', vi: 'Gia Lai' },
    { id: 'khanh-hoa', en: 'Khanh Hoa', vi: 'Khánh Hòa' },
    { id: 'lam-dong', en: 'Lam Dong', vi: 'Lâm Đồng' },
    { id: 'dak-lak', en: 'Dak Lak', vi: 'Đắk Lắk' },
    { id: 'dong-nai', en: 'Dong Nai', vi: 'Đồng Nai' },
    { id: 'tay-ninh', en: 'Tay Ninh', vi: 'Tây Ninh' },
    { id: 'vinh-long', en: 'Vinh Long', vi: 'Vĩnh Long' },
    { id: 'dong-thap', en: 'Dong Thap', vi: 'Đồng Tháp' },
    { id: 'ca-mau', en: 'Ca Mau', vi: 'Cà Mau' },
    { id: 'an-giang', en: 'An Giang', vi: 'An Giang' },
    { id: 'overseas', en: 'Overseas', vi: 'Nước ngoài' },
]

// ─── Home Page ─────────────────────────────────────────────────────────────────
export function HomePage() {
    const { lang, navigate, createdAuctions, auctionReviews } = useApp()
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

    // Chuyển đổi danh mục backend sang định dạng hiển thị, fallback về CATEGORIES nếu chưa có
    const liveCategories =
        catData?.data && catData.data.length > 0
            ? catData.data.map((c) => ({
                id: c.id,
                name: c.name,
                nameVi: c.nameVi,
                productCount: c.productCount,
            }))
            : CATEGORIES

    // Chuyển đổi sản phẩm backend sang định dạng hiển thị UI, fallback về PRODUCTS nếu chưa có
    const liveProducts =
        prodData?.data && prodData.data.length > 0
            ? prodData.data.map((p) => {
                const fallback = PRODUCTS.find((x) => x.id === p.id) || PRODUCTS[0]
                return {
                    ...fallback,
                    id: p.id,
                    name: p.name,
                    nameVi: p.name,
                    categoryId: p.categoryId,
                    price: p.skus?.[0]?.price ?? fallback.price,
                    originalPrice: p.skus?.[0]?.originalPrice ?? fallback.originalPrice,
                    sku: p.skus?.[0]?.skuCode ?? fallback.sku,
                    images:
                        p.images && p.images.length > 0
                            ? p.images.map((img) => img.url)
                            : fallback.images,
                    description: p.description || fallback.description,
                    descriptionVi: p.description || fallback.descriptionVi,
                    enableAuction: p.auctionEnabled ?? fallback.enableAuction,
                }
            })
            : PRODUCTS

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
            {/* Hero Banner */}
            <section className="relative min-h-[520px] overflow-hidden bg-gray-950 text-white">
                {activeAuctions.map((auction, index) => (
                    <img
                        key={auction.id}
                        src={auction.product.images[0]
                            .replace(/w=\d+/, 'w=1800')
                            .replace(/h=\d+/, 'h=720')}
                        alt=""
                        aria-hidden={index !== heroIndex}
                        className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-1000 ease-in-out ${index === heroIndex ? 'opacity-100' : 'opacity-0'}`}
                    />
                ))}
                <div className="absolute inset-0 bg-black/60" />

                <div className="relative z-10 max-w-[1440px] min-h-[520px] mx-auto px-8 py-14 flex items-center">
                    <div className="max-w-2xl">
                        <div className="inline-flex items-center gap-2 border border-white/30 rounded-full px-4 py-1.5 text-sm font-semibold mb-5">
                            <span className="w-2 h-2 bg-white rounded-full" />
                            {lang === 'en'
                                ? `${activeAuctions.length} live auctions in progress`
                                : `${activeAuctions.length} phiên đấu giá đang diễn ra`}
                        </div>
                        <h1 className="text-5xl font-black leading-tight mb-5">
                            {lang === 'en' ? (
                                <>
                                    Buy, Sell &<br />
                                    <span className="text-gray-300">Bid Smart</span>
                                </>
                            ) : (
                                <>
                                    Mua, Bán &<br />
                                    <span className="text-gray-300">Đấu giá thông minh</span>
                                </>
                            )}
                        </h1>
                        <p className="text-white/80 text-lg mb-8 max-w-md">
                            {lang === 'en'
                                ? 'The trusted Vietnamese marketplace for shopping and live auctions.'
                                : 'Sàn thương mại điện tử Việt Nam uy tín cho mua sắm và đấu giá trực tiếp.'}
                        </p>
                        <div className="flex gap-3">
                            <Button
                                variant="primary"
                                size="lg"
                                className="bg-white text-gray-900 hover:bg-gray-100"
                                onClick={() => navigate('search')}
                            >
                                {lang === 'en' ? 'Shop Now' : 'Mua sắm ngay'}
                            </Button>
                            <Button
                                variant="ghost"
                                size="lg"
                                className="border-white/40 text-white hover:bg-white/10 border"
                                onClick={() =>
                                    heroAuction
                                        ? navigate('auction-detail', { id: heroAuction.id })
                                        : navigate('auction-list')
                                }
                            >
                                <Gavel size={16} />
                                {lang === 'en' ? 'View This Auction' : 'Xem phiên đấu giá'}
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
                                    <div className="w-14 h-14 rounded-xl bg-gray-100 flex items-center justify-center text-gray-500">
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

                {/* Popular Products */}
                <section>
                    <div className="flex items-center justify-between mb-5">
                        <h2 className="text-2xl font-bold text-gray-900">
                            {tr(lang, 'popularProducts')}
                        </h2>
                        <Button variant="outline" size="sm" onClick={() => navigate('search')}>
                            {tr(lang, 'viewAll')} →
                        </Button>
                    </div>
                    <div className="grid grid-cols-4 gap-5">
                        {liveProducts.slice(0, 4).map((p) => (
                            <ProductCard key={p.id} product={p} />
                        ))}
                    </div>
                </section>

                {/* New Arrivals */}
                <section>
                    <div className="flex items-center justify-between mb-5">
                        <h2 className="text-2xl font-bold text-gray-900">
                            {tr(lang, 'newArrivals')}
                        </h2>
                        <Button variant="outline" size="sm" onClick={() => navigate('search')}>
                            {tr(lang, 'viewAll')} →
                        </Button>
                    </div>
                    <div className="grid grid-cols-4 gap-5">
                        {liveProducts.slice(2, 6).map((p) => (
                            <ProductCard key={p.id} product={p} />
                        ))}
                    </div>
                </section>
            </div>
        </BuyerLayout>
    )
}

// ─── Search Results Page ────────────────────────────────────────────────────────
export function SearchPage() {
    const { lang, navigate, pageParams } = useApp()
    const [sortBy, setSortBy] = useState('relevance')
    const [priceMin, setPriceMin] = useState('')
    const [priceMax, setPriceMax] = useState('')
    const [type, setType] = useState<'all' | 'buy-now' | 'auction'>('all')
    const [currentPage, setCurrentPage] = useState(1)
    const q = (pageParams?.q as string) || ''
    const category = (pageParams?.category as string) || ''
    const [region, setRegion] = useState((pageParams?.region as string) || 'all')
    const filtered = PRODUCTS.filter(
        (p) =>
            (!q ||
                p.name.toLowerCase().includes(q.toLowerCase()) ||
                p.nameVi.toLowerCase().includes(q.toLowerCase())) &&
            (!category || p.categoryId === category || p.categoryId.startsWith(`${category}-`)) &&
            (region === 'all' || p.location === region),
    )
    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                    { label: lang === 'en' ? 'Search Results' : 'Kết quả tìm kiếm' },
                ]}
            />
            <div className="mt-4 flex gap-6">
                {/* Sidebar filters */}
                <aside className="w-56 shrink-0 space-y-5">
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm text-gray-900 mb-3">
                            {tr(lang, 'category')}
                        </h3>
                        <div className="space-y-1">
                            {CATEGORIES.map((cat) => {
                                const Icon = CATEGORY_ICONS[cat.id]
                                return (
                                    <button
                                        key={cat.id}
                                        className="flex items-center gap-2 w-full px-2 py-1.5 text-sm rounded-lg hover:bg-gray-50 text-gray-700 hover:text-primary transition-colors"
                                    >
                                        {Icon && <Icon className="shrink-0 text-gray-400" />}
                                        <span>{lang === 'vi' ? cat.nameVi : cat.name}</span>
                                        {cat.productCount && (
                                            <span className="ml-auto text-xs text-gray-400">
                                                {cat.productCount}
                                            </span>
                                        )}
                                    </button>
                                )
                            })}
                        </div>
                    </div>
                    <div className="nexus-card p-4">
                        <Select
                            label={lang === 'en' ? 'Region' : 'Khu vực'}
                            value={region}
                            onChange={(event) => {
                                setRegion(event.target.value)
                                setCurrentPage(1)
                            }}
                            options={BUYER_REGIONS.map((item) => ({
                                value: item.id,
                                label: `${lang === 'vi' ? item.vi : item.en} (${item.id === 'all' ? PRODUCTS.length : PRODUCTS.filter((product) => product.location === item.id).length})`,
                            }))}
                        />
                    </div>
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm text-gray-900 mb-3">
                            {lang === 'en' ? 'Price Range' : 'Khoảng giá'}
                        </h3>
                        <div className="flex gap-2 items-center">
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Min' : 'Thấp nhất'}
                                value={priceMin}
                                onChange={(e) => setPriceMin(e.target.value)}
                                className="text-xs py-1.5"
                            />
                            <span className="text-gray-400">-</span>
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Max' : 'Cao nhất'}
                                value={priceMax}
                                onChange={(e) => setPriceMax(e.target.value)}
                                className="text-xs py-1.5"
                            />
                        </div>
                    </div>
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm text-gray-900 mb-3">
                            {lang === 'en' ? 'Listing Type' : 'Loại'}
                        </h3>
                        <div className="space-y-2">
                            {[
                                ['all', lang === 'en' ? 'All' : 'Tất cả'],
                                ['buy-now', lang === 'en' ? 'Buy Now' : 'Mua ngay'],
                                ['auction', lang === 'en' ? 'Auction' : 'Đấu giá'],
                            ].map(([v, l]) => (
                                <button
                                    key={v}
                                    onClick={() => setType(v as any)}
                                    className={`flex items-center gap-2 w-full px-2 py-1.5 text-sm rounded-lg ${type === v ? 'bg-primary-light text-primary' : 'hover:bg-gray-50 text-gray-700'}`}
                                >
                                    <span
                                        className={`w-3.5 h-3.5 rounded-full border-2 flex items-center justify-center ${type === v ? 'border-primary' : 'border-gray-300'}`}
                                    >
                                        {type === v && (
                                            <span className="w-1.5 h-1.5 rounded-full bg-primary" />
                                        )}
                                    </span>
                                    {l}
                                </button>
                            ))}
                        </div>
                    </div>
                </aside>

                {/* Results */}
                <div className="flex-1">
                    <div className="flex items-center justify-between mb-4">
                        <p className="text-sm text-gray-600">
                            {lang === 'en'
                                ? `${filtered.length} results`
                                : `${filtered.length} kết quả`}{' '}
                            {q && `"${q}"`}
                        </p>
                        <div className="flex items-center gap-2">
                            <span className="text-sm text-gray-500">{tr(lang, 'sort')}:</span>
                            <select
                                value={sortBy}
                                onChange={(e) => setSortBy(e.target.value)}
                                className="nexus-input py-1.5 text-sm w-auto pr-8"
                            >
                                <option value="relevance">
                                    {lang === 'en' ? 'Relevance' : 'Phù hợp nhất'}
                                </option>
                                <option value="price-asc">
                                    {lang === 'en' ? 'Price: Low to High' : 'Giá: Thấp đến Cao'}
                                </option>
                                <option value="price-desc">
                                    {lang === 'en' ? 'Price: High to Low' : 'Giá: Cao đến Thấp'}
                                </option>
                                <option value="newest">
                                    {lang === 'en' ? 'Newest' : 'Mới nhất'}
                                </option>
                                <option value="ending-soon">
                                    {lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}
                                </option>
                            </select>
                        </div>
                    </div>
                    {filtered.length === 0 ? (
                        <EmptyState
                            icon={<Search size={20} />}
                            title={lang === 'en' ? 'No results found' : 'Không tìm thấy kết quả'}
                            subtitle={
                                lang === 'en'
                                    ? `We couldn't find anything for "${q}"`
                                    : `Không tìm thấy kết quả cho "${q}"`
                            }
                            action={
                                <Button variant="primary" onClick={() => navigate('home')}>
                                    {lang === 'en'
                                        ? 'Browse All Products'
                                        : 'Duyệt tất cả sản phẩm'}
                                </Button>
                            }
                        />
                    ) : (
                        <>
                            <div className="grid grid-cols-3 gap-5">
                                {filtered.map((p) => (
                                    <ProductCard key={p.id} product={p} />
                                ))}
                            </div>
                            <div className="flex justify-center mt-8">
                                <Pagination
                                    page={currentPage}
                                    total={filtered.length * 3}
                                    perPage={9}
                                    onChange={setCurrentPage}
                                />
                            </div>
                        </>
                    )}
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Product Detail Page ────────────────────────────────────────────────────────
export function ProductDetailPage() {
    const { lang, navigate, pageParams, setCartCount, cartCount, showToast } = useApp()
    const product = PRODUCTS.find((p) => p.id === pageParams?.id) || PRODUCTS[0]
    // Maximum images per product MAX_PRODUCT_IMAGES = 5
    const MAX_PRODUCT_IMAGES = 5
    const productImages = (product.images || []).slice(0, MAX_PRODUCT_IMAGES)

    const [activeImg, setActiveImg] = useState(0)
    const [lightboxOpen, setLightboxOpen] = useState(false)
    const [qty, setQty] = useState(1)
    const [activeTab, setActiveTab] = useState('description')
    const categoryRoot = product.categoryId.split('-')[0]
    const relatedProducts = PRODUCTS.filter(
        (candidate) =>
            candidate.id !== product.id &&
            candidate.status === 'active' &&
            candidate.categoryId.split('-')[0] === categoryRoot,
    ).slice(0, 4)

    useEffect(() => {
        setActiveImg(0)
        setQty(1)
        setActiveTab('description')
        setLightboxOpen(false)
    }, [product.id])

    // Close lightbox on Escape key
    useEffect(() => {
        if (!lightboxOpen) return
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'Escape') setLightboxOpen(false)
            if (e.key === 'ArrowLeft')
                setActiveImg((prev) => (prev === 0 ? productImages.length - 1 : prev - 1))
            if (e.key === 'ArrowRight')
                setActiveImg((prev) => (prev === productImages.length - 1 ? 0 : prev + 1))
        }
        window.addEventListener('keydown', handler)
        return () => window.removeEventListener('keydown', handler)
    }, [lightboxOpen, productImages.length])

    const addToCart = () => {
        setCartCount(cartCount + qty)
        showToast({
            type: 'success',
            message:
                lang === 'en' ? `Added ${qty}x to cart!` : `Đã thêm ${qty} sản phẩm vào giỏ hàng!`,
        })
    }

    const TABS = [
        { id: 'description', label: tr(lang, 'description') },
        { id: 'reviews', label: `${tr(lang, 'reviews')} (${product.reviewCount})` },
        { id: 'specifications', label: tr(lang, 'specifications') },
    ]

    const REVIEWS = [
        {
            name: 'Nguyen Thi Thu',
            rating: 5,
            date: '2024-01-20',
            comment:
                lang === 'en'
                    ? 'Excellent product! Great sound quality and noise cancellation works perfectly.'
                    : 'Sản phẩm xuất sắc! Chất lượng âm thanh tốt và chống ồn hoạt động hoàn hảo.',
            helpful: 24,
        },
        {
            name: 'Tran Van Minh',
            rating: 4,
            date: '2024-01-15',
            comment:
                lang === 'en'
                    ? 'Very good headphones. Battery life is impressive. Highly recommended.'
                    : 'Tai nghe rất tốt. Thời lượng pin ấn tượng. Rất đáng mua.',
            helpful: 18,
        },
    ]

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                    { label: product.category, page: 'search' },
                    { label: lang === 'vi' ? product.nameVi : product.name },
                ]}
            />
            <div className="mt-5 grid grid-cols-[1fr_1.2fr] gap-10">
                {/* Product Gallery Carousel */}
                <div>
                    {/* Main Large Image with Prev / Next Buttons */}
                    <div
                        className="nexus-card overflow-hidden mb-3 bg-gray-50 relative group"
                        style={{ aspectRatio: '1/1' }}
                    >
                        {/* Clickable main image → opens lightbox */}
                        <img
                            src={productImages[activeImg] ?? productImages[0]}
                            alt={product.name}
                            onClick={() => setLightboxOpen(true)}
                            className="w-full h-full object-cover transition-all duration-300 group-hover:scale-105 cursor-zoom-in"
                        />

                        {/* Zoom hint */}
                        <div className="absolute top-3 right-3 bg-black/50 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-medium flex items-center gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none">
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                            >
                                <circle cx="11" cy="11" r="8" />
                                <line x1="21" y1="21" x2="16.65" y2="16.65" />
                                <line x1="11" y1="8" x2="11" y2="14" />
                                <line x1="8" y1="11" x2="14" y2="11" />
                            </svg>
                            {lang === 'en' ? 'Click to zoom' : 'Nhấn để phóng to'}
                        </div>

                        {/* Prev Image Button (<) */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }}
                                aria-label="Previous image"
                                className="absolute left-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronLeft size={20} />
                            </button>
                        )}

                        {/* Next Image Button (>) */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }}
                                aria-label="Next image"
                                className="absolute right-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronRight size={20} />
                            </button>
                        )}

                        {/* Counter badge */}
                        <div className="absolute bottom-3 right-3 bg-black/60 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-semibold flex items-center gap-1.5 pointer-events-none">
                            <span>
                                {productImages.length > 0 ? activeImg + 1 : 0} /{' '}
                                {productImages.length}
                            </span>
                        </div>
                    </div>

                    {/* ── Lightbox Modal ── */}
                    {lightboxOpen && (
                        <div
                            className="fixed inset-0 z-[9999] flex items-center justify-center"
                            style={{ background: 'rgba(0,0,0,0.92)' }}
                            onClick={() => setLightboxOpen(false)}
                        >
                            {/* Close button */}
                            <button
                                type="button"
                                onClick={() => setLightboxOpen(false)}
                                className="absolute top-4 right-4 w-10 h-10 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all z-10 cursor-pointer"
                                aria-label="Close"
                            >
                                <CircleX size={22} />
                            </button>

                            {/* Prev button */}
                            {productImages.length > 1 && (
                                <button
                                    type="button"
                                    onClick={(e) => {
                                        e.stopPropagation()
                                        setActiveImg((prev) =>
                                            prev === 0 ? productImages.length - 1 : prev - 1,
                                        )
                                    }}
                                    className="absolute left-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                                    aria-label="Previous"
                                >
                                    <ChevronLeft size={28} />
                                </button>
                            )}

                            {/* Main lightbox image */}
                            <div
                                className="flex flex-col items-center gap-5 px-20 w-full max-h-screen"
                                onClick={(e) => e.stopPropagation()}
                            >
                                <img
                                    src={productImages[activeImg]}
                                    alt={product.name}
                                    className="max-h-[75vh] max-w-[80vw] object-contain rounded-xl shadow-2xl select-none"
                                    style={{ transition: 'opacity 0.2s' }}
                                />

                                {/* Counter */}
                                <p className="text-white/60 text-sm font-medium">
                                    {activeImg + 1} / {productImages.length}
                                </p>

                                {/* Thumbnails strip */}
                                {productImages.length > 1 && (
                                    <div className="flex gap-3">
                                        {productImages.map((img, i) => (
                                            <button
                                                key={i}
                                                type="button"
                                                onClick={() => setActiveImg(i)}
                                                className={`w-16 h-16 shrink-0 rounded-lg overflow-hidden border-2 transition-all cursor-pointer ${i === activeImg
                                                    ? 'border-white scale-110'
                                                    : 'border-white/25 opacity-50 hover:opacity-80'
                                                    }`}
                                            >
                                                <img
                                                    src={img}
                                                    alt=""
                                                    className="w-full h-full object-cover"
                                                />
                                            </button>
                                        ))}
                                    </div>
                                )}
                            </div>

                            {/* Next button */}
                            {productImages.length > 1 && (
                                <button
                                    type="button"
                                    onClick={(e) => {
                                        e.stopPropagation()
                                        setActiveImg((prev) =>
                                            prev === productImages.length - 1 ? 0 : prev + 1,
                                        )
                                    }}
                                    className="absolute right-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                                    aria-label="Next"
                                >
                                    <ChevronRight size={28} />
                                </button>
                            )}
                        </div>
                    )}

                    {/* Thumbnails row with navigation buttons */}
                    <div className="flex items-center gap-2">
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh trước"
                            >
                                <ChevronLeft size={16} />
                            </button>
                        )}

                        <div className="flex gap-2 overflow-x-auto py-0.5 flex-1">
                            {productImages.map((img, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => setActiveImg(i)}
                                    className={`gallery-thumb w-16 h-16 shrink-0 overflow-hidden bg-gray-100 rounded-lg border-2 transition-all cursor-pointer ${i === activeImg
                                        ? 'border-primary ring-2 ring-primary/20 scale-105'
                                        : 'border-transparent opacity-70 hover:opacity-100'
                                        }`}
                                >
                                    <img src={img} alt="" className="w-full h-full object-cover" />
                                </button>
                            ))}
                        </div>

                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh tiếp theo"
                            >
                                <ChevronRight size={16} />
                            </button>
                        )}
                    </div>
                </div>

                {/* Product info */}
                <div>
                    <div className="flex items-center gap-2 mb-2 flex-wrap">
                        <span className="text-sm font-semibold text-primary bg-primary/10 px-2.5 py-0.5 rounded-full">
                            {product.category}
                        </span>
                        {product.status === 'active' ? (
                            product.stock > 0 ? (
                                <span className="badge badge-green">{tr(lang, 'inStock')}</span>
                            ) : (
                                <span className="badge badge-red">{tr(lang, 'outOfStock')}</span>
                            )
                        ) : product.status === 'draft' ? (
                            <span className="badge badge-gray">{tr(lang, 'draft')}</span>
                        ) : (
                            <span className="badge badge-slate">{tr(lang, 'inactive')}</span>
                        )}
                        {product.enableAuction && (
                            <span className="badge badge-indigo">
                                {lang === 'en' ? 'Auction Eligible' : 'Hỗ trợ đấu giá'}
                            </span>
                        )}
                    </div>
                    <h1 className="text-3xl font-bold text-gray-900 mb-3 leading-tight">
                        {lang === 'vi' ? product.nameVi : product.name}
                    </h1>
                    <div className="flex items-center gap-3 mb-5">
                        <StarRating rating={product.rating} />
                        <span className="text-sm text-gray-600 font-medium">
                            {product.rating} ({product.reviewCount}{' '}
                            {lang === 'en' ? 'reviews' : 'đánh giá'})
                        </span>
                    </div>

                    <div className="flex items-baseline gap-3 mb-6">
                        <span className="text-4xl font-black text-primary">
                            {formatCurrency(product.price, lang)}
                        </span>
                        {product.originalPrice && (
                            <>
                                <span className="text-xl text-gray-400 line-through">
                                    {formatCurrency(product.originalPrice, lang)}
                                </span>
                                <span className="badge badge-red">
                                    -{Math.round((1 - product.price / product.originalPrice) * 100)}
                                    %
                                </span>
                            </>
                        )}
                    </div>

                    <div className="nexus-card p-5 bg-gray-50 mb-6 space-y-4">
                        <div className="flex items-center justify-between">
                            <span className="text-sm font-semibold text-gray-700">
                                {tr(lang, 'quantity')}
                            </span>
                            {product.stock > 0 ? (
                                <NumberStepper
                                    value={qty}
                                    min={1}
                                    max={product.stock}
                                    onChange={setQty}
                                />
                            ) : null}
                        </div>
                        <div className="text-xs text-gray-500">
                            {product.stock > 0
                                ? `${product.stock} ${lang === 'en' ? 'units available' : 'sản phẩm còn hàng'}`
                                : tr(lang, 'outOfStock')}
                        </div>
                        <div className="flex gap-3">
                            <Button
                                variant="secondary"
                                size="lg"
                                className="flex-1"
                                onClick={addToCart}
                                disabled={product.stock === 0}
                            >
                                {tr(lang, 'addToCart')}
                            </Button>
                            <Button
                                variant="primary"
                                size="lg"
                                className="flex-1"
                                onClick={() => navigate('checkout')}
                                disabled={product.stock === 0}
                            >
                                {tr(lang, 'buyNow')}
                            </Button>
                        </div>
                    </div>

                    {/* Seller info */}
                    <div className="nexus-card p-4 flex items-center gap-4 mb-5">
                        <div className="w-12 h-12 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-lg">
                            {product.sellerName[0]}
                        </div>
                        <div className="flex-1">
                            <p className="font-semibold text-gray-900">{product.sellerName}</p>
                            <div className="flex items-center gap-2 mt-0.5">
                                <StarRating rating={product.sellerRating} size={12} />
                                <span className="text-xs text-gray-500">
                                    {product.sellerRating}
                                </span>
                                <span
                                    className={`badge ${product.sellerTrust === 'gold' ? 'badge-yellow' : product.sellerTrust === 'platinum' ? 'badge-indigo' : 'badge-slate'}`}
                                >
                                    {product.sellerTrust.charAt(0).toUpperCase() +
                                        product.sellerTrust.slice(1)}
                                </span>
                            </div>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => navigate('public-reputation', { id: product.sellerId })}
                        >
                            {lang === 'en' ? 'View Profile' : 'Xem hồ sơ'}
                        </Button>
                    </div>

                    {/* Shipping estimate */}
                    <div className="p-4 bg-primary-light rounded-xl text-sm">
                        <div>
                            <span className="font-semibold text-primary">
                                {tr(lang, 'shippingEstimate')}:
                            </span>
                            <span className="text-gray-700 ml-2">
                                GHN – {lang === 'en' ? '3-5 days, $3.99' : '3-5 ngày, 50.000₫'} |
                                GHTK – {lang === 'en' ? '2-3 days, $5.99' : '2-3 ngày, 80.000₫'}
                            </span>
                        </div>
                    </div>
                </div>
            </div>

            {/* Tabs */}
            <div className="mt-10 nexus-card">
                <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
                <div className="p-6">
                    {activeTab === 'description' && (
                        <div className="prose prose-sm max-w-none text-gray-700 leading-relaxed">
                            <p>{lang === 'vi' ? product.descriptionVi : product.description}</p>
                            <p className="mt-4">
                                {lang === 'en'
                                    ? `SKU: ${product.sku} • Weight: ${product.weight}kg`
                                    : `Mã SKU: ${product.sku} • Khối lượng: ${product.weight}kg`}
                            </p>
                        </div>
                    )}
                    {activeTab === 'reviews' && (
                        <div className="space-y-5">
                            {REVIEWS.map((r, i) => (
                                <div key={i} className="border-b border-border pb-5 last:border-0">
                                    <div className="flex items-center justify-between mb-2">
                                        <div className="flex items-center gap-3">
                                            <div className="w-9 h-9 bg-gray-200 rounded-full flex items-center justify-center font-bold text-sm">
                                                {r.name[0]}
                                            </div>
                                            <div>
                                                <p className="font-semibold text-sm">{r.name}</p>
                                                <StarRating rating={r.rating} size={12} />
                                            </div>
                                        </div>
                                        <span className="text-xs text-gray-400">
                                            {formatDate(r.date, lang)}
                                        </span>
                                    </div>
                                    <p className="text-sm text-gray-700">{r.comment}</p>
                                    <p className="text-xs text-gray-400 mt-2">
                                        {lang === 'en'
                                            ? `${r.helpful} found helpful`
                                            : `${r.helpful} người thấy hữu ích`}
                                    </p>
                                </div>
                            ))}
                        </div>
                    )}
                    {activeTab === 'specifications' && (
                        <div className="grid grid-cols-2 gap-3">
                            {[
                                ['SKU', product.sku],
                                ['Weight', `${product.weight} kg`],
                                ['Category', product.category],
                                ['Status', 'In Stock'],
                            ].map(([k, v]) => (
                                <div
                                    key={k}
                                    className="flex items-center p-3 bg-gray-50 rounded-lg"
                                >
                                    <span className="text-sm font-semibold text-gray-500 w-28">
                                        {k}
                                    </span>
                                    <span className="text-sm text-gray-800">{v}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>

            {relatedProducts.length > 0 && (
                <section className="mt-12">
                    <div className="flex items-end justify-between mb-5">
                        <div>
                            <h2 className="text-2xl font-bold text-gray-900">
                                {lang === 'en' ? 'Related products' : 'Sản phẩm liên quan'}
                            </h2>
                            <p className="text-sm text-gray-500 mt-1">
                                {lang === 'en'
                                    ? `More products related to ${product.category}`
                                    : `Các sản phẩm khác liên quan đến ${product.category}`}
                            </p>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => navigate('search', { category: product.categoryId })}
                        >
                            {lang === 'en' ? 'View category' : 'Xem danh mục'}
                        </Button>
                    </div>
                    <div className="grid grid-cols-4 gap-5">
                        {relatedProducts.map((relatedProduct) => (
                            <ProductCard key={relatedProduct.id} product={relatedProduct} />
                        ))}
                    </div>
                </section>
            )}
        </BuyerLayout>
    )
}

// ─── Cart Page ─────────────────────────────────────────────────────────────────
export function CartPage() {
    const { lang, navigate, setCartCount, showToast } = useApp()
    const [items, setItems] = useState([
        { ...PRODUCTS[0], qty: 1 },
        { ...PRODUCTS[2], qty: 2 },
        { ...PRODUCTS[4], qty: 1 },
    ])
    const [voucher, setVoucher] = useState('')
    const [appliedVoucher, setAppliedVoucher] = useState<string | null>(null)
    const [voucherError, setVoucherError] = useState('')

    const subtotal = items.reduce((s, i) => s + i.price * i.qty, 0)
    const discount = appliedVoucher ? subtotal * 0.1 : 0
    const shipping = subtotal >= 100 ? 0 : 8.99
    const tax = (subtotal - discount) * 0.08
    const total = subtotal - discount + shipping + tax

    const applyVoucher = () => {
        if (voucher === 'NEXUS10') {
            setAppliedVoucher(voucher)
            setVoucherError('')
            showToast({ type: 'success', message: tr(lang, 'voucherApplied') })
        } else {
            setVoucherError(tr(lang, 'invalidVoucher'))
        }
    }

    const updateQty = (idx: number, qty: number) => {
        if (qty === 0) {
            setItems((prev) => prev.filter((_, i) => i !== idx))
            setCartCount(Math.max(0, items.length - 1))
        } else setItems((prev) => prev.map((it, i) => (i === idx ? { ...it, qty } : it)))
    }

    if (items.length === 0)
        return (
            <BuyerLayout>
                <EmptyState
                    icon={<ShoppingCart size={20} />}
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

    return (
        <BuyerLayout>
            <h1 className="text-2xl font-bold text-gray-900 mb-6">
                {tr(lang, 'cart')}{' '}
                <span className="text-gray-400 font-normal text-lg">({items.length})</span>
            </h1>
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
                <div className="space-y-3">
                    {items.map((item, idx) => (
                        <div key={item.id + idx} className="nexus-card p-4 flex gap-4">
                            <img
                                src={item.images[0]}
                                alt={item.name}
                                className="w-20 h-20 object-cover rounded-xl bg-gray-100 shrink-0"
                            />
                            <div className="flex-1 min-w-0">
                                <p className="font-semibold text-gray-900 line-clamp-2">
                                    {lang === 'vi' ? item.nameVi : item.name}
                                </p>
                                <p className="text-sm text-gray-500 mt-1">{item.category}</p>
                                <div className="flex items-center justify-between mt-3">
                                    <NumberStepper
                                        value={item.qty}
                                        min={0}
                                        max={item.stock}
                                        onChange={(qty) => updateQty(idx, qty)}
                                    />
                                    <span className="text-lg font-bold text-primary">
                                        {formatCurrency(item.price * item.qty, lang)}
                                    </span>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>

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
        </BuyerLayout>
    )
}

// ─── Checkout Page ─────────────────────────────────────────────────────────────
export function CheckoutPage() {
    const { lang, navigate, showToast } = useApp()
    const [step, setStep] = useState(1)
    const [shipping, setShipping] = useState<'GHN' | 'GHTK'>('GHN')
    const [loading, setLoading] = useState(false)

    const STEPS = [
        tr(lang, 'shippingAddress'),
        tr(lang, 'shippingMethod'),
        tr(lang, 'payment'),
        tr(lang, 'confirmation'),
    ]

    const placeOrder = async () => {
        setLoading(true)
        await new Promise((r) => setTimeout(r, 1500))
        setLoading(false)
        showToast({ type: 'success', message: tr(lang, 'orderPlaced') })
        navigate('payment-result', { status: 'success' })
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

                    {step === 1 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'shippingAddress')}
                            </h2>
                            <div className="grid grid-cols-2 gap-4">
                                <Input
                                    label={lang === 'en' ? 'Full Name' : 'Họ và tên'}
                                    defaultValue="Minh Tran"
                                />
                                <Input
                                    label={lang === 'en' ? 'Phone' : 'Số điện thoại'}
                                    defaultValue="0901234567"
                                />
                            </div>
                            <Input
                                label={lang === 'en' ? 'Street Address' : 'Địa chỉ'}
                                defaultValue="123 Nguyen Hue Street"
                            />
                            <div className="grid grid-cols-3 gap-4">
                                <Input
                                    label={lang === 'en' ? 'City' : 'Thành phố'}
                                    defaultValue="Ho Chi Minh City"
                                />
                                <Input
                                    label={lang === 'en' ? 'District' : 'Quận/Huyện'}
                                    defaultValue="District 1"
                                />
                                <Input
                                    label={lang === 'en' ? 'Ward' : 'Phường/Xã'}
                                    defaultValue="Ben Nghe"
                                />
                            </div>
                            <Button variant="primary" size="lg" onClick={() => setStep(2)}>
                                {tr(lang, 'next')} →
                            </Button>
                        </div>
                    )}

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

                    {step === 3 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'payment')}
                            </h2>
                            <div className="p-3 bg-primary-light rounded-xl flex items-center gap-2 text-sm text-primary mb-3">
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                >
                                    <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
                                    <line x1="1" y1="10" x2="23" y2="10" />
                                </svg>
                                {lang === 'en'
                                    ? 'Powered by Stripe — your payment is secure'
                                    : 'Bảo mật bởi Stripe — thanh toán của bạn được bảo vệ'}
                            </div>
                            <Input
                                label={tr(lang, 'cardNumber')}
                                placeholder="4242 4242 4242 4242"
                            />
                            <Input label={tr(lang, 'cardHolder')} placeholder="MINH TRAN" />
                            <div className="grid grid-cols-2 gap-4">
                                <Input label={tr(lang, 'expiryDate')} placeholder="MM/YY" />
                                <Input label={tr(lang, 'cvv')} placeholder="123" type="password" />
                            </div>
                            <div className="flex gap-3">
                                <Button variant="outline" size="lg" onClick={() => setStep(2)}>
                                    {tr(lang, 'back')}
                                </Button>
                                <Button variant="primary" size="lg" onClick={() => setStep(4)}>
                                    {tr(lang, 'next')} →
                                </Button>
                            </div>
                        </div>
                    )}

                    {step === 4 && (
                        <div className="space-y-4">
                            <h2 className="font-bold text-gray-900 text-lg mb-3">
                                {tr(lang, 'confirmation')}
                            </h2>
                            <div className="space-y-2 text-sm">
                                {[
                                    [
                                        lang === 'en' ? 'Ship to' : 'Giao đến',
                                        '123 Nguyen Hue, District 1, HCM',
                                    ],
                                    [
                                        tr(lang, 'shippingMethod'),
                                        shipping === 'GHN' ? 'GHN Express' : 'GHTK Standard',
                                    ],
                                    [tr(lang, 'payment'), 'Visa ending 4242'],
                                    [tr(lang, 'total'), formatCurrency(329.97, lang)],
                                ].map(([k, v]) => (
                                    <div
                                        key={k}
                                        className="flex justify-between p-3 bg-gray-50 rounded-lg"
                                    >
                                        <span className="text-gray-500">{k}</span>
                                        <span className="font-semibold">{v}</span>
                                    </div>
                                ))}
                            </div>
                            <div className="flex gap-3">
                                <Button variant="outline" size="lg" onClick={() => setStep(3)}>
                                    {tr(lang, 'back')}
                                </Button>
                                <Button
                                    variant="primary"
                                    size="lg"
                                    loading={loading}
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
                    <div className="space-y-3 mb-4">
                        {[PRODUCTS[0], PRODUCTS[2]].map((p) => (
                            <div key={p.id} className="flex gap-3">
                                <img
                                    src={p.images[0]}
                                    alt=""
                                    className="w-12 h-12 object-cover rounded-lg"
                                />
                                <div className="flex-1 min-w-0">
                                    <p className="text-xs font-semibold line-clamp-1">
                                        {lang === 'vi' ? p.nameVi : p.name}
                                    </p>
                                    <p className="text-sm font-bold text-primary mt-0.5">
                                        {formatCurrency(p.price, lang)}
                                    </p>
                                </div>
                            </div>
                        ))}
                    </div>
                    <div className="border-t border-border pt-3 space-y-2 text-sm">
                        {[
                            [tr(lang, 'subtotal'), '$429.98'],
                            [tr(lang, 'shipping'), '$3.99'],
                            [tr(lang, 'tax'), '$34.40'],
                            [tr(lang, 'total'), '$468.37'],
                        ].map(([k, v], i) => (
                            <div
                                key={k}
                                className={`flex justify-between ${i === 3 ? 'font-bold text-base border-t border-border pt-2 mt-2 text-primary' : 'text-gray-600'}`}
                            >
                                <span>{k}</span>
                                <span>{v}</span>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Payment Result Page ───────────────────────────────────────────────────────
export function PaymentResultPage() {
    const { lang, navigate, pageParams } = useApp()
    const status = (pageParams?.status as string) || 'success'
    const [retries, setRetries] = useState(0)
    return (
        <BuyerLayout>
            <div className="max-w-lg mx-auto text-center py-12">
                {status === 'success' && (
                    <>
                        <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-5">
                            <CheckCircle size={40} className="text-gray-600" />
                        </div>
                        <h1 className="text-2xl font-bold text-gray-900 mb-2">
                            {tr(lang, 'orderPlaced')}
                        </h1>
                        <p className="text-gray-500 mb-2">
                            {lang === 'en'
                                ? 'Order #ORD-20240301-004 confirmed.'
                                : 'Đơn hàng #ORD-20240301-004 đã được xác nhận.'}
                        </p>
                        <p className="text-sm text-gray-500 mb-2">
                            {lang === 'en'
                                ? 'Payment received and securely held by Nexus until the order is completed.'
                                : 'Thanh toán thành công và đang được Nexus giữ an toàn cho đến khi đơn hàng hoàn tất.'}
                        </p>
                        <p className="text-sm text-gray-400 mb-8">
                            {lang === 'en'
                                ? "You'll receive a confirmation email shortly."
                                : 'Bạn sẽ nhận được email xác nhận sớm.'}
                        </p>
                        <div className="flex gap-3 justify-center">
                            <Button variant="outline" onClick={() => navigate('home')}>
                                {lang === 'en' ? 'Continue Shopping' : 'Tiếp tục mua sắm'}
                            </Button>
                            <Button variant="primary" onClick={() => navigate('order-history')}>
                                {tr(lang, 'orderHistory')}
                            </Button>
                        </div>
                    </>
                )}
                {status === 'failed' && (
                    <>
                        <div className="w-20 h-20 bg-danger-light rounded-full flex items-center justify-center mx-auto mb-5">
                            <svg
                                width="40"
                                height="40"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="#DC2626"
                                strokeWidth="2.5"
                                strokeLinecap="round"
                            >
                                <circle cx="12" cy="12" r="10" />
                                <line x1="15" y1="9" x2="9" y2="15" />
                                <line x1="9" y1="9" x2="15" y2="15" />
                            </svg>
                        </div>
                        <h1 className="text-2xl font-bold text-gray-900 mb-2">
                            {tr(lang, 'paymentFailed')}
                        </h1>
                        {retries < 3 ? (
                            <>
                                <p className="text-sm text-gray-400 mb-1">
                                    {lang === 'en'
                                        ? `Attempt ${retries + 1} of 3`
                                        : `Lần thử ${retries + 1} / 3`}
                                </p>
                                <p className="text-sm text-gray-500 mb-8">
                                    {tr(lang, 'retryLimit')}
                                </p>
                                <div className="flex gap-3 justify-center">
                                    <Button variant="outline" onClick={() => navigate('cart')}>
                                        {tr(lang, 'cancel')}
                                    </Button>
                                    <Button
                                        variant="primary"
                                        onClick={() => setRetries((r) => r + 1)}
                                    >
                                        {tr(lang, 'tryAgain')}
                                    </Button>
                                </div>
                            </>
                        ) : (
                            <>
                                <p className="text-gray-500 mb-8">
                                    {lang === 'en'
                                        ? 'Maximum retries reached. Please contact support.'
                                        : 'Đã hết số lần thử. Vui lòng liên hệ hỗ trợ.'}
                                </p>
                                <Button variant="outline" onClick={() => navigate('home')}>
                                    {lang === 'en' ? 'Back to Home' : 'Về trang chủ'}
                                </Button>
                            </>
                        )}
                    </>
                )}
                {status === 'pending' && (
                    <>
                        <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-5">
                            <AlertTriangle size={40} className="text-gray-600" />
                        </div>
                        <h1 className="text-2xl font-bold mb-2">
                            {lang === 'en' ? 'Payment Pending' : 'Đang xử lý thanh toán'}
                        </h1>
                        <p className="text-gray-500 mb-8">{tr(lang, 'paymentPending')}</p>
                        <Button variant="outline" onClick={() => navigate('order-history')}>
                            {tr(lang, 'orderHistory')}
                        </Button>
                    </>
                )}
            </div>
        </BuyerLayout>
    )
}

// ─── Order History Page ─────────────────────────────────────────────────────────
/**
 * Màn hình Lịch sử đơn hàng của Buyer (SRS 3.2.1 & ORDER-02).
 * 
 * Chức năng: Hiển thị danh sách các đơn hàng đã mua của Buyer theo phân loại trạng thái,
 * cho phép tìm kiếm theo mã đơn hoặc sản phẩm, hủy nhanh đơn chưa xử lý, và điều hướng vào xem chi tiết.
 * 
 * Đầu vào: Bộ lọc tab trạng thái (all, pending, paid, processing, shipped, delivered, completed, cancelled, refunded)
 * và chuỗi từ khóa tìm kiếm.
 * 
 * Luồng xử lý:
 * 1. Gọi `ordersApi.getOrders({ role: 'buyer', status, search })`.
 * 2. Ưu tiên lấy từ REST API/DB; nếu không có kết nối thì fallback dữ liệu mock JSON trong localStorage.
 * 3. Hỗ trợ thao tác hủy đơn trực tiếp (`ordersApi.cancelOrder`) với popup xác nhận và reload danh sách.
 */
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
        { id: 'pending', label: tr(lang, 'statusPending') },
        { id: 'paid', label: tr(lang, 'statusPaid') },
        { id: 'processing', label: tr(lang, 'statusProcessing') },
        { id: 'shipped', label: tr(lang, 'statusShipped') },
        { id: 'delivered', label: tr(lang, 'statusDelivered') },
        { id: 'completed', label: tr(lang, 'statusCompleted') },
        { id: 'cancelled', label: tr(lang, 'statusCancelled') },
        { id: 'refunded', label: tr(lang, 'statusRefunded') },
    ]

    const statusLabel = (s: string) =>
        ({
            pending: tr(lang, 'statusPending'),
            paid: tr(lang, 'statusPaid'),
            processing: tr(lang, 'statusProcessing'),
            shipped: tr(lang, 'statusShipped'),
            delivered: tr(lang, 'statusDelivered'),
            completed: tr(lang, 'statusCompleted'),
            cancelled: tr(lang, 'statusCancelled'),
            refunded: tr(lang, 'statusRefunded'),
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
                message: lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
            })
            setCancelOrderId(null)
            setCancelReason('')
            await loadOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message: err.message || (lang === 'en' ? 'Failed to cancel order.' : 'Không thể hủy đơn hàng.'),
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
                            <p>{lang === 'en' ? 'Loading orders...' : 'Đang tải danh sách đơn hàng...'}</p>
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
                                const canCancel = ['pending', 'paid'].includes(order.status)
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
                                                    onClick={() => navigate('order-detail', { id: order.id })}
                                                >
                                                    {tr(lang, 'orderDetail')} <ChevronRight size={14} />
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
                                                                ×{item.quantity} · {formatCurrency(item.unitPrice, lang)}
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
                                                    {lang === 'en' ? 'Total payable:' : 'Tổng thanh toán:'}
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
                        placeholder={lang === 'en' ? 'Tell us why you are cancelling...' : 'Nhập lý do hủy đơn...'}
                        rows={3}
                    />
                </div>
            </Modal>
        </BuyerLayout>
    )
}

// ─── Order Detail Page ─────────────────────────────────────────────────────────
/**
 * Màn hình Chi tiết đơn hàng của Buyer (SRS 3.2.1 & ORDER-02, ORDER-03, PAYMENT-01).
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
    const { lang, navigate, pageParams, showToast } = useApp()
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
                    <p>{lang === 'en' ? 'Loading order details...' : 'Đang tải chi tiết đơn hàng...'}</p>
                </div>
            </BuyerLayout>
        )
    }

    const paymentStatus = getEffectivePaymentStatus(order)

    const statusLabel = (s: string) =>
        ({
            pending: tr(lang, 'statusPending'),
            paid: tr(lang, 'statusPaid'),
            processing: tr(lang, 'statusProcessing'),
            shipped: tr(lang, 'statusShipped'),
            delivered: tr(lang, 'statusDelivered'),
            completed: tr(lang, 'statusCompleted'),
            cancelled: tr(lang, 'statusCancelled'),
            refunded: tr(lang, 'statusRefunded'),
        })[s] || s

    const canCancel = ['pending', 'paid'].includes(order.status)
    const protectionDays = order.protectionEndsAt
        ? Math.max(
            0,
            Math.ceil((new Date(order.protectionEndsAt).getTime() - Date.now()) / 86400000),
        )
        : 0
    const isProtectionActive = order.status === 'delivered' && paymentStatus === 'protection_period'

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
                message: lang === 'en' ? 'Failed to confirm delivery.' : 'Không thể xác nhận nhận hàng.',
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
                message: lang === 'en' ? 'Order cancelled successfully.' : 'Đã hủy đơn hàng thành công.',
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
                        <h3 className="font-bold mb-4">{lang === 'en' ? 'Purchased Items' : 'Sản phẩm đã mua'}</h3>
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
                                        <p className="font-semibold text-gray-900 line-clamp-1">{item.productName}</p>
                                        <p className="text-sm text-gray-500 mt-1">
                                            ×{item.quantity} · {formatCurrency(item.unitPrice, lang)}
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
                            {lang === 'en' ? 'Order Status Timeline' : 'Tiến trình trạng thái đơn hàng'}
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
                                                ? '7-day buyer protection escrow'
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
                                            {lang === 'en' ? 'Auto release at' : 'Tự động giải ngân lúc'}:{' '}
                                            {new Date(order.protectionEndsAt).toLocaleString()}
                                        </p>
                                    )}
                                </div>
                                <div className="flex sm:flex-col gap-2 shrink-0 w-full sm:w-auto">
                                    <Button variant="primary" size="sm" onClick={confirmDelivery} className="flex-1 sm:flex-initial">
                                        {lang === 'en' ? 'Confirm Received' : 'Xác nhận đã nhận hàng'}
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
                    {order.status === 'delivered' && !reviewed && (
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
                        <p className="text-sm text-gray-700 leading-relaxed">{order.shippingAddress}</p>
                    </div>

                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-3">{tr(lang, 'payment')}</h3>
                        <p className="text-sm text-gray-700">{order.paymentMethod}</p>
                        <div className="mt-4 pt-3 border-t border-border flex items-center justify-between">
                            <span className="text-xs text-gray-500">
                                {lang === 'en' ? 'Escrow status' : 'Trạng thái ký quỹ Escrow'}
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

// ─── Profile Page ──────────────────────────────────────────────────────────────
export function ProfilePage() {
    const { lang, user, showToast } = useApp()
    const [activeTab, setActiveTab] = useState('info')
    const [saving, setSaving] = useState(false)

    const save = async () => {
        setSaving(true)
        await new Promise((r) => setTimeout(r, 700))
        setSaving(false)
        showToast({
            type: 'success',
            message: lang === 'en' ? 'Profile updated!' : 'Đã cập nhật hồ sơ!',
        })
    }

    if (!user) return null
    return (
        <BuyerLayout>
            <div className="max-w-3xl mx-auto">
                <div className="flex items-center gap-5 mb-6">
                    <img
                        src={user.avatar}
                        alt=""
                        className="w-20 h-20 rounded-2xl object-cover shadow"
                    />
                    <div>
                        <h1 className="text-2xl font-bold">{user.fullName}</h1>
                        <p className="text-gray-500 text-sm">{user.email}</p>
                        <div className="flex items-center gap-2 mt-1">
                            <span
                                className={`badge ${user.trustLevel === 'gold' ? 'badge-yellow' : user.trustLevel === 'platinum' ? 'badge-indigo' : 'badge-slate'}`}
                            >
                                {user.trustLevel.charAt(0).toUpperCase() + user.trustLevel.slice(1)}
                            </span>
                            <span className="text-xs text-gray-500">
                                · {user.reputationScore} pts
                            </span>
                        </div>
                    </div>
                </div>
                <div className="nexus-card">
                    <Tabs
                        tabs={[
                            {
                                id: 'info',
                                label: lang === 'en' ? 'Personal Info' : 'Thông tin cá nhân',
                            },
                            {
                                id: 'password',
                                label: lang === 'en' ? 'Change Password' : 'Đổi mật khẩu',
                            },
                        ]}
                        active={activeTab}
                        onChange={setActiveTab}
                    />
                    <div className="p-6">
                        {activeTab === 'info' && (
                            <div className="space-y-4">
                                <div className="grid grid-cols-2 gap-4">
                                    <Input
                                        label={tr(lang, 'fullName')}
                                        defaultValue={user.fullName}
                                    />
                                    <Input
                                        label={tr(lang, 'email')}
                                        type="email"
                                        defaultValue={user.email}
                                    />
                                    <Input label={tr(lang, 'phone')} defaultValue={user.phone} />
                                    <Input
                                        label={tr(lang, 'idNumber')}
                                        defaultValue={user.idNumber}
                                    />
                                    <Input
                                        label={tr(lang, 'dateOfBirth')}
                                        type="date"
                                        defaultValue={user.dateOfBirth}
                                    />
                                </div>
                                <Input label={tr(lang, 'address')} defaultValue={user.address} />
                                <Button variant="primary" loading={saving} onClick={save}>
                                    {tr(lang, 'save')}
                                </Button>
                            </div>
                        )}
                        {activeTab === 'password' && (
                            <div className="space-y-4 max-w-sm">
                                <Input
                                    label={lang === 'en' ? 'Current Password' : 'Mật khẩu hiện tại'}
                                    type="password"
                                    placeholder="••••••••"
                                />
                                <Input
                                    label={tr(lang, 'newPassword')}
                                    type="password"
                                    placeholder="••••••••"
                                />
                                <Input
                                    label={tr(lang, 'confirmPassword')}
                                    type="password"
                                    placeholder="••••••••"
                                />
                                <Button variant="primary" loading={saving} onClick={save}>
                                    {lang === 'en' ? 'Update Password' : 'Cập nhật mật khẩu'}
                                </Button>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Reputation Page ────────────────────────────────────────────────────────────
export function ReputationPage() {
    const { lang, user } = useApp()
    if (!user) return null
    const RATINGS = [
        {
            from: 'TechZone Store',
            score: 5,
            comment:
                lang === 'en'
                    ? 'Excellent buyer! Fast payment.'
                    : 'Người mua tuyệt vời! Thanh toán nhanh.',
            date: '2024-02-18',
        },
        {
            from: 'SportsPro VN',
            score: 4,
            comment:
                lang === 'en'
                    ? 'Good communication, smooth transaction.'
                    : 'Giao tiếp tốt, giao dịch suôn sẻ.',
            date: '2024-01-25',
        },
    ]
    return (
        <BuyerLayout>
            <div className="max-w-3xl mx-auto">
                <h1 className="text-2xl font-bold mb-6">{tr(lang, 'myReputation')}</h1>
                <div className="grid grid-cols-[1fr_1fr] gap-5 mb-6">
                    <div className="nexus-card p-6">
                        <h3 className="font-bold mb-4">{tr(lang, 'reputationScore')}</h3>
                        <ReputationBar score={user.reputationScore} />
                    </div>
                    <div className="nexus-card p-6">
                        <h3 className="font-bold mb-4">{tr(lang, 'transactionHistory')}</h3>
                        <div className="grid grid-cols-2 gap-4">
                            {[
                                {
                                    icon: <CheckCircle size={18} />,
                                    label: tr(lang, 'successfulTransactions'),
                                    value: 24,
                                    className: 'text-success',
                                },
                                {
                                    icon: <CircleX size={18} />,
                                    label: tr(lang, 'failedTransactions'),
                                    value: 1,
                                    className: 'text-danger',
                                },
                            ].map((item) => (
                                <div
                                    key={item.label}
                                    className="text-center p-3 bg-gray-50 rounded-xl"
                                >
                                    <div className="text-gray-500 flex justify-center mb-1">
                                        {item.icon}
                                    </div>
                                    <p className={`text-2xl font-black ${item.className}`}>
                                        {item.value}
                                    </p>
                                    <p className="text-xs text-gray-500">{item.label}</p>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>
                <div className="nexus-card p-5">
                    <h3 className="font-bold mb-4">
                        {lang === 'en' ? 'Ratings Received' : 'Đánh giá nhận được'}
                    </h3>
                    {RATINGS.map((r, i) => (
                        <div key={i} className="border-b border-border last:border-0 py-4">
                            <div className="flex items-center justify-between mb-2">
                                <span className="font-semibold text-sm">{r.from}</span>
                                <div className="flex items-center gap-2">
                                    <StarRating rating={r.score} size={14} />
                                    <span className="text-xs text-gray-400">
                                        {formatDate(r.date, lang)}
                                    </span>
                                </div>
                            </div>
                            <p className="text-sm text-gray-600">{r.comment}</p>
                        </div>
                    ))}
                </div>
            </div>
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
                                        className={`px-3 py-1 rounded-md text-xs font-semibold transition-all ${isFollowing
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
                                className={`px-4 py-2 text-xs font-bold rounded-lg transition-all ${shopTab === tab.id
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

// ─── Notification Center ───────────────────────────────────────────────────────
export function NotificationPage() {
    const { lang } = useApp()
    const [activeTab, setActiveTab] = useState('history')
    const [notifications, setNotifications] = useState(NOTIFICATIONS)

    const notificationIcon = (type: string) => {
        const Icon =
            type === 'order'
                ? Package
                : type === 'auction'
                    ? Gavel
                    : type === 'payment'
                        ? CreditCard
                        : type === 'promotion'
                            ? Gift
                            : type === 'reputation'
                                ? Star
                                : Settings2
        return <Icon size={18} />
    }

    return (
        <BuyerLayout>
            <div className="max-w-3xl mx-auto">
                <div className="flex items-center justify-between mb-6">
                    <h1 className="text-2xl font-bold">{tr(lang, 'notificationCenter')}</h1>
                    <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                            setNotifications((prev) => prev.map((n) => ({ ...n, read: true })))
                        }
                    >
                        {tr(lang, 'markAllRead')}
                    </Button>
                </div>
                <div className="nexus-card">
                    <Tabs
                        tabs={[
                            {
                                id: 'history',
                                label: lang === 'en' ? 'Notifications' : 'Thông báo',
                                count: notifications.filter((n) => !n.read).length,
                            },
                            { id: 'preferences', label: tr(lang, 'notifPreferences') },
                        ]}
                        active={activeTab}
                        onChange={setActiveTab}
                    />
                    <div className="p-0">
                        {activeTab === 'history' && (
                            <div>
                                {notifications.map((n) => (
                                    <div
                                        key={n.id}
                                        onClick={() =>
                                            setNotifications((prev) =>
                                                prev.map((x) =>
                                                    x.id === n.id ? { ...x, read: true } : x,
                                                ),
                                            )
                                        }
                                        className={`flex gap-4 p-4 border-b border-border last:border-0 cursor-pointer transition-colors ${!n.read ? 'bg-primary-light/40' : 'hover:bg-gray-50'}`}
                                    >
                                        <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-gray-100 text-gray-500">
                                            {notificationIcon(n.type)}
                                        </div>
                                        <div className="flex-1">
                                            <div className="flex items-center gap-2">
                                                <p
                                                    className={`text-sm font-semibold ${!n.read ? 'text-gray-900' : 'text-gray-700'}`}
                                                >
                                                    {lang === 'vi' ? n.titleVi : n.title}
                                                </p>
                                                {!n.read && (
                                                    <span className="w-2 h-2 rounded-full bg-primary" />
                                                )}
                                            </div>
                                            <p className="text-xs text-gray-500 mt-0.5">
                                                {lang === 'vi' ? n.bodyVi : n.body}
                                            </p>
                                            <p className="text-xs text-gray-400 mt-1">
                                                {new Date(n.timestamp).toLocaleString()}
                                            </p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                        {activeTab === 'preferences' && (
                            <div className="p-5">
                                <div className="overflow-x-auto">
                                    <table className="w-full">
                                        <thead>
                                            <tr className="border-b border-border">
                                                <th className="text-left py-3 text-sm text-gray-500">
                                                    Type
                                                </th>
                                                <th className="text-center py-3 text-sm text-gray-500">
                                                    {tr(lang, 'emailChannel')}
                                                </th>
                                                <th className="text-center py-3 text-sm text-gray-500">
                                                    {tr(lang, 'pushChannel')}
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {[
                                                {
                                                    type:
                                                        lang === 'en'
                                                            ? 'Order Updates'
                                                            : 'Cập nhật đơn hàng',
                                                    locked: true,
                                                },
                                                {
                                                    type:
                                                        lang === 'en'
                                                            ? 'Payment Notifications'
                                                            : 'Thông báo thanh toán',
                                                    locked: true,
                                                },
                                                {
                                                    type:
                                                        lang === 'en'
                                                            ? 'Auction Alerts'
                                                            : 'Thông báo đấu giá',
                                                    locked: false,
                                                },
                                                {
                                                    type:
                                                        lang === 'en' ? 'Promotions' : 'Khuyến mãi',
                                                    locked: false,
                                                },
                                                {
                                                    type:
                                                        lang === 'en'
                                                            ? 'System News'
                                                            : 'Tin tức hệ thống',
                                                    locked: false,
                                                },
                                            ].map((item) => (
                                                <tr
                                                    key={item.type}
                                                    className="border-b border-border last:border-0"
                                                >
                                                    <td className="py-3 text-sm font-medium flex items-center gap-2">
                                                        {item.type}
                                                        {item.locked && (
                                                            <span className="badge badge-gray text-[10px]">
                                                                {tr(lang, 'alwaysOn')}
                                                            </span>
                                                        )}
                                                    </td>
                                                    <td className="text-center py-3">
                                                        <Toggle
                                                            checked={true}
                                                            onChange={() => { }}
                                                            disabled={item.locked}
                                                        />
                                                    </td>
                                                    <td className="text-center py-3">
                                                        <Toggle
                                                            checked={!item.locked}
                                                            onChange={() => { }}
                                                            disabled={item.locked}
                                                        />
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}
