import { useEffect, useState } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
import { BuyerLayout } from '../../components/Layout'
import { ProductCard } from '../../components/Cards'
import { AuctionCard } from '../../components/Cards'
import { Button, Input, Textarea, Select, NumberStepper, Tabs, Modal, ConfirmModal, AlertBanner, EmptyState, StarRating, StatusBadge, OrderTimeline, ReputationBar, ProgressBar, Toggle, Checkbox, Breadcrumb, Pagination, Download, Gavel, Search, Filter, ChevronRight, AlertTriangle, CheckCircle, Truck, Package, ShoppingCart, CreditCard, Settings2, Gift, Star, CircleX, Shield } from '../../components/UI'
import { PRODUCTS, AUCTIONS, ORDERS, CATEGORIES, NOTIFICATIONS } from '../../mockData'
import { CATEGORY_ICONS } from '../../components/Layout'
import { getEffectivePaymentStatus } from '../../payment'
import type { OrderStatus } from '../../types'

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
    .map(auction => ({ ...auction, status: auctionReviews[auction.id]?.status || auction.status }))
    .filter(auction => auction?.status === 'active' && auction.product?.images?.[0])
  const [heroIndex, setHeroIndex] = useState(0)
  const heroAuction = activeAuctions.length > 0
    ? activeAuctions[heroIndex % activeAuctions.length]
    : null

  useEffect(() => {
    setHeroIndex(index => activeAuctions.length > 0 ? index % activeAuctions.length : 0)
    if (activeAuctions.length < 2) return
    const timer = window.setInterval(() => {
      setHeroIndex(index => (index + 1) % activeAuctions.length)
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
            src={auction.product.images[0].replace(/w=\d+/, 'w=1800').replace(/h=\d+/, 'h=720')}
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
              {lang === 'en' ? <>Buy, Sell &<br /><span className="text-gray-300">Bid Smart</span></> : <>Mua, Bán &<br /><span className="text-gray-300">Đấu giá thông minh</span></>}
            </h1>
            <p className="text-white/80 text-lg mb-8 max-w-md">{lang === 'en' ? 'The trusted Vietnamese marketplace for shopping and live auctions.' : 'Sàn thương mại điện tử Việt Nam uy tín cho mua sắm và đấu giá trực tiếp.'}</p>
            <div className="flex gap-3">
              <Button variant="primary" size="lg" className="bg-white text-gray-900 hover:bg-gray-100" onClick={() => navigate('search')}>{lang === 'en' ? 'Shop Now' : 'Mua sắm ngay'}</Button>
              <Button variant="ghost" size="lg" className="border-white/40 text-white hover:bg-white/10 border" onClick={() => heroAuction ? navigate('auction-detail', { id: heroAuction.id }) : navigate('auction-list')}>
                <Gavel size={16} />{lang === 'en' ? 'View This Auction' : 'Xem phiên đấu giá'}
              </Button>
            </div>

            {heroAuction && (
              <div className="mt-8 pt-5 border-t border-white/25 flex items-end gap-8 max-w-xl">
                <div className="min-w-0">
                  <p className="text-xs uppercase tracking-wider text-white/60 mb-1">{lang === 'en' ? 'Live now' : 'Đang diễn ra'}</p>
                  <p className="font-semibold text-white truncate">{lang === 'vi' ? heroAuction.product?.nameVi : heroAuction.product?.name}</p>
                </div>
                <div className="shrink-0">
                  <p className="text-xs text-white/60 mb-1">{tr(lang, 'currentBid')}</p>
                  <p className="font-bold text-white">{formatCurrency(getAuctionCurrentBid(heroAuction), lang)}</p>
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
                aria-label={lang === 'en' ? `Show auction ${index + 1}` : `Hiển thị phiên đấu giá ${index + 1}`}
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
            <h2 className="text-2xl font-bold text-gray-900">{lang === 'en' ? 'Shop by Category' : 'Mua theo danh mục'}</h2>
          </div>
          <div className="grid grid-cols-6 gap-4">
            {CATEGORIES.map(cat => {
              const Icon = CATEGORY_ICONS[cat.id]
              return (
                <button key={cat.id} onClick={() => navigate('search', { category: cat.id })}
                  className="nexus-card p-5 flex flex-col items-center gap-3 hover:border-primary hover:shadow-md transition-all group cursor-pointer">
                  <div className="w-14 h-14 rounded-xl bg-gray-100 flex items-center justify-center text-gray-500">
                    {Icon && <Icon width="28" height="28" strokeWidth="1.4" />}
                  </div>
                  <span className="text-sm font-semibold text-gray-700 group-hover:text-primary text-center leading-tight">{lang === 'vi' ? cat.nameVi : cat.name}</span>
                  <span className="text-xs text-gray-400">{(cat.productCount ?? 0).toLocaleString()} {lang === 'en' ? 'items' : 'sản phẩm'}</span>
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
                <h2 className="text-2xl font-bold text-gray-900">{tr(lang, 'liveAuctions')}</h2>
                <span className="badge badge-orange auction-live text-sm px-3 py-1">{lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}</span>
              </div>
              <Button variant="outline" size="sm" onClick={() => navigate('auction-list')}>{tr(lang, 'viewAll')} →</Button>
            </div>
            <div className="grid grid-cols-4 gap-5">
              {activeAuctions.map(a => <AuctionCard key={a.id} auction={a} />)}
            </div>
          </section>
        )}

        {/* Popular Products */}
        <section>
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-2xl font-bold text-gray-900">{tr(lang, 'popularProducts')}</h2>
            <Button variant="outline" size="sm" onClick={() => navigate('search')}>{tr(lang, 'viewAll')} →</Button>
          </div>
          <div className="grid grid-cols-4 gap-5">
            {PRODUCTS.slice(0, 4).map(p => <ProductCard key={p.id} product={p} />)}
          </div>
        </section>

        {/* New Arrivals */}
        <section>
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-2xl font-bold text-gray-900">{tr(lang, 'newArrivals')}</h2>
            <Button variant="outline" size="sm" onClick={() => navigate('search')}>{tr(lang, 'viewAll')} →</Button>
          </div>
          <div className="grid grid-cols-4 gap-5">
            {PRODUCTS.slice(2, 6).map(p => <ProductCard key={p.id} product={p} />)}
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
  const filtered = PRODUCTS.filter(p =>
    (!q || p.name.toLowerCase().includes(q.toLowerCase()) || p.nameVi.toLowerCase().includes(q.toLowerCase())) &&
    (!category || p.categoryId === category || p.categoryId.startsWith(`${category}-`)) &&
    (region === 'all' || p.location === region)
  )
  return (
    <BuyerLayout>
      <Breadcrumb items={[{ label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' }, { label: lang === 'en' ? 'Search Results' : 'Kết quả tìm kiếm' }]} />
      <div className="mt-4 flex gap-6">
        {/* Sidebar filters */}
        <aside className="w-56 shrink-0 space-y-5">
          <div className="nexus-card p-4">
            <h3 className="font-bold text-sm text-gray-900 mb-3">{tr(lang, 'category')}</h3>
            <div className="space-y-1">
              {CATEGORIES.map(cat => {
                const Icon = CATEGORY_ICONS[cat.id]
                return (
                  <button key={cat.id} className="flex items-center gap-2 w-full px-2 py-1.5 text-sm rounded-lg hover:bg-gray-50 text-gray-700 hover:text-primary transition-colors">
                    {Icon && <Icon className="shrink-0 text-gray-400" />}
                    <span>{lang === 'vi' ? cat.nameVi : cat.name}</span>
                    {cat.productCount && <span className="ml-auto text-xs text-gray-400">{cat.productCount}</span>}
                  </button>
                )
              })}
            </div>
          </div>
          <div className="nexus-card p-4">
            <Select
              label={lang === 'en' ? 'Region' : 'Khu vực'}
              value={region}
              onChange={event => { setRegion(event.target.value); setCurrentPage(1) }}
              options={BUYER_REGIONS.map(item => ({
                value: item.id,
                label: `${lang === 'vi' ? item.vi : item.en} (${item.id === 'all' ? PRODUCTS.length : PRODUCTS.filter(product => product.location === item.id).length})`,
              }))}
            />
          </div>
          <div className="nexus-card p-4">
            <h3 className="font-bold text-sm text-gray-900 mb-3">{lang === 'en' ? 'Price Range' : 'Khoảng giá'}</h3>
            <div className="flex gap-2 items-center">
              <Input type="number" placeholder={lang === 'en' ? 'Min' : 'Thấp nhất'} value={priceMin} onChange={e => setPriceMin(e.target.value)} className="text-xs py-1.5" />
              <span className="text-gray-400">-</span>
              <Input type="number" placeholder={lang === 'en' ? 'Max' : 'Cao nhất'} value={priceMax} onChange={e => setPriceMax(e.target.value)} className="text-xs py-1.5" />
            </div>
          </div>
          <div className="nexus-card p-4">
            <h3 className="font-bold text-sm text-gray-900 mb-3">{lang === 'en' ? 'Listing Type' : 'Loại'}</h3>
            <div className="space-y-2">
              {[['all', lang === 'en' ? 'All' : 'Tất cả'], ['buy-now', lang === 'en' ? 'Buy Now' : 'Mua ngay'], ['auction', lang === 'en' ? 'Auction' : 'Đấu giá']].map(([v, l]) => (
                <button key={v} onClick={() => setType(v as any)}
                  className={`flex items-center gap-2 w-full px-2 py-1.5 text-sm rounded-lg ${type === v ? 'bg-primary-light text-primary' : 'hover:bg-gray-50 text-gray-700'}`}>
                  <span className={`w-3.5 h-3.5 rounded-full border-2 flex items-center justify-center ${type === v ? 'border-primary' : 'border-gray-300'}`}>
                    {type === v && <span className="w-1.5 h-1.5 rounded-full bg-primary" />}
                  </span>{l}
                </button>
              ))}
            </div>
          </div>
        </aside>

        {/* Results */}
        <div className="flex-1">
          <div className="flex items-center justify-between mb-4">
            <p className="text-sm text-gray-600">{lang === 'en' ? `${filtered.length} results` : `${filtered.length} kết quả`} {q && `"${q}"`}</p>
            <div className="flex items-center gap-2">
              <span className="text-sm text-gray-500">{tr(lang, 'sort')}:</span>
              <select value={sortBy} onChange={e => setSortBy(e.target.value)} className="nexus-input py-1.5 text-sm w-auto pr-8">
                <option value="relevance">{lang === 'en' ? 'Relevance' : 'Phù hợp nhất'}</option>
                <option value="price-asc">{lang === 'en' ? 'Price: Low to High' : 'Giá: Thấp đến Cao'}</option>
                <option value="price-desc">{lang === 'en' ? 'Price: High to Low' : 'Giá: Cao đến Thấp'}</option>
                <option value="newest">{lang === 'en' ? 'Newest' : 'Mới nhất'}</option>
                <option value="ending-soon">{lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}</option>
              </select>
            </div>
          </div>
          {filtered.length === 0 ? (
            <EmptyState icon={<Search size={20} />} title={lang === 'en' ? 'No results found' : 'Không tìm thấy kết quả'}
              subtitle={lang === 'en' ? `We couldn't find anything for "${q}"` : `Không tìm thấy kết quả cho "${q}"`}
              action={<Button variant="primary" onClick={() => navigate('home')}>{lang === 'en' ? 'Browse All Products' : 'Duyệt tất cả sản phẩm'}</Button>} />
          ) : (
            <>
              <div className="grid grid-cols-3 gap-5">
                {filtered.map(p => <ProductCard key={p.id} product={p} />)}
              </div>
              <div className="flex justify-center mt-8"><Pagination page={currentPage} total={filtered.length * 3} perPage={9} onChange={setCurrentPage} /></div>
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
  const product = PRODUCTS.find(p => p.id === pageParams?.id) || PRODUCTS[0]
  // BR-CFG-09: Maximum images per product MAX_PRODUCT_IMAGES = 5
  const MAX_PRODUCT_IMAGES = 5
  const productImages = (product.images || []).slice(0, MAX_PRODUCT_IMAGES)

  const [activeImg, setActiveImg] = useState(0)
  const [qty, setQty] = useState(1)
  const [activeTab, setActiveTab] = useState('description')
  const categoryRoot = product.categoryId.split('-')[0]
  const relatedProducts = PRODUCTS.filter(candidate =>
    candidate.id !== product.id &&
    candidate.status === 'active' &&
    candidate.categoryId.split('-')[0] === categoryRoot
  ).slice(0, 4)

  useEffect(() => {
    setActiveImg(0)
    setQty(1)
    setActiveTab('description')
  }, [product.id])

  const addToCart = () => {
    setCartCount(cartCount + qty)
    showToast({ type: 'success', message: lang === 'en' ? `Added ${qty}x to cart!` : `Đã thêm ${qty} sản phẩm vào giỏ hàng!` })
  }

  const TABS = [
    { id: 'description', label: tr(lang, 'description') },
    { id: 'reviews', label: `${tr(lang, 'reviews')} (${product.reviewCount})` },
    { id: 'specifications', label: tr(lang, 'specifications') },
  ]

  const REVIEWS = [
    { name: 'Nguyen Thi Thu', rating: 5, date: '2024-01-20', comment: lang === 'en' ? 'Excellent product! Great sound quality and noise cancellation works perfectly.' : 'Sản phẩm xuất sắc! Chất lượng âm thanh tốt và chống ồn hoạt động hoàn hảo.', helpful: 24 },
    { name: 'Tran Van Minh', rating: 4, date: '2024-01-15', comment: lang === 'en' ? 'Very good headphones. Battery life is impressive. Highly recommended.' : 'Tai nghe rất tốt. Thời lượng pin ấn tượng. Rất đáng mua.', helpful: 18 },
  ]

  return (
    <BuyerLayout>
      <Breadcrumb items={[{ label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' }, { label: product.category, page: 'search' }, { label: lang === 'vi' ? product.nameVi : product.name }]} />
      <div className="mt-5 grid grid-cols-[1fr_1.2fr] gap-10">
        {/* Gallery - BR-CFG-09: MAX_PRODUCT_IMAGES = 5 */}
        <div>
          <div className="nexus-card overflow-hidden mb-3 bg-gray-50 relative group" style={{ aspectRatio: '1/1' }}>
            <img src={productImages[activeImg] ?? productImages[0]} alt={product.name} className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105" />
            <div className="absolute bottom-3 right-3 bg-black/60 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-semibold flex items-center gap-1.5">
              <span>{productImages.length > 0 ? activeImg + 1 : 0} / {productImages.length}</span>
              <span className="text-gray-300 text-[10px]">(Max {MAX_PRODUCT_IMAGES})</span>
            </div>
          </div>
          <div className="flex gap-2">
            {productImages.map((img, i) => (
              <button key={i} onClick={() => setActiveImg(i)} className={`gallery-thumb w-16 h-16 overflow-hidden bg-gray-100 rounded-lg border-2 transition-all ${i === activeImg ? 'border-primary ring-2 ring-primary/20 scale-105' : 'border-transparent opacity-75 hover:opacity-100'}`}>
                <img src={img} alt="" className="w-full h-full object-cover" />
              </button>
            ))}
          </div>
        </div>

        {/* Product info */}
        <div>
          <div className="flex items-center gap-2 mb-2 flex-wrap">
            <span className="text-sm font-semibold text-primary bg-primary/10 px-2.5 py-0.5 rounded-full">{product.category}</span>
            {/* BR-CFG-08: Product Statuses (DRAFT, ACTIVE, INACTIVE) */}
            {product.status === 'active' ? (
              product.stock > 0 ? <span className="badge badge-green">{tr(lang, 'inStock')}</span> : <span className="badge badge-red">{tr(lang, 'outOfStock')}</span>
            ) : product.status === 'draft' ? (
              <span className="badge badge-gray">{tr(lang, 'draft')}</span>
            ) : (
              <span className="badge badge-slate">{tr(lang, 'inactive')}</span>
            )}
            {product.enableAuction && (
              <span className="badge badge-indigo">{lang === 'en' ? 'Auction Eligible' : 'Hỗ trợ đấu giá'}</span>
            )}
          </div>
          <h1 className="text-3xl font-bold text-gray-900 mb-3 leading-tight">{lang === 'vi' ? product.nameVi : product.name}</h1>
          <div className="flex items-center gap-3 mb-5">
            <StarRating rating={product.rating} />
            <span className="text-sm text-gray-600 font-medium">{product.rating} ({product.reviewCount} {lang === 'en' ? 'reviews' : 'đánh giá'})</span>
          </div>

          <div className="flex items-baseline gap-3 mb-6">
            <span className="text-4xl font-black text-primary">{formatCurrency(product.price, lang)}</span>
            {product.originalPrice && <>
              <span className="text-xl text-gray-400 line-through">{formatCurrency(product.originalPrice, lang)}</span>
              <span className="badge badge-red">-{Math.round((1 - product.price / product.originalPrice) * 100)}%</span>
            </>}
          </div>

          <div className="nexus-card p-5 bg-gray-50 mb-6 space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-gray-700">{tr(lang, 'quantity')}</span>
              {product.stock > 0 ? <NumberStepper value={qty} min={1} max={product.stock} onChange={setQty} /> : null}
            </div>
            <div className="text-xs text-gray-500">{product.stock > 0 ? `${product.stock} ${lang === 'en' ? 'units available' : 'sản phẩm còn hàng'}` : tr(lang, 'outOfStock')}</div>
            <div className="flex gap-3">
              <Button variant="secondary" size="lg" className="flex-1" onClick={addToCart} disabled={product.stock === 0}>{tr(lang, 'addToCart')}</Button>
              <Button variant="primary" size="lg" className="flex-1" onClick={() => navigate('checkout')} disabled={product.stock === 0}>{tr(lang, 'buyNow')}</Button>
            </div>
          </div>

          {/* Seller info */}
          <div className="nexus-card p-4 flex items-center gap-4 mb-5">
            <div className="w-12 h-12 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-lg">{product.sellerName[0]}</div>
            <div className="flex-1">
              <p className="font-semibold text-gray-900">{product.sellerName}</p>
              <div className="flex items-center gap-2 mt-0.5">
                <StarRating rating={product.sellerRating} size={12} />
                <span className="text-xs text-gray-500">{product.sellerRating}</span>
                <span className={`badge ${product.sellerTrust === 'gold' ? 'badge-yellow' : product.sellerTrust === 'platinum' ? 'badge-indigo' : 'badge-slate'}`}>{product.sellerTrust.charAt(0).toUpperCase() + product.sellerTrust.slice(1)}</span>
              </div>
            </div>
            <Button variant="outline" size="sm" onClick={() => navigate('public-reputation', { id: product.sellerId })}>{lang === 'en' ? 'View Profile' : 'Xem hồ sơ'}</Button>
          </div>

          {/* Shipping estimate */}
          <div className="p-4 bg-primary-light rounded-xl text-sm">
            <div>
              <span className="font-semibold text-primary">{tr(lang, 'shippingEstimate')}:</span>
              <span className="text-gray-700 ml-2">GHN – {lang === 'en' ? '3-5 days, $3.99' : '3-5 ngày, 50.000₫'} | GHTK – {lang === 'en' ? '2-3 days, $5.99' : '2-3 ngày, 80.000₫'}</span>
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
              <p className="mt-4">{lang === 'en' ? `SKU: ${product.sku} • Weight: ${product.weight}kg` : `Mã SKU: ${product.sku} • Khối lượng: ${product.weight}kg`}</p>
            </div>
          )}
          {activeTab === 'reviews' && (
            <div className="space-y-5">
              {REVIEWS.map((r, i) => (
                <div key={i} className="border-b border-border pb-5 last:border-0">
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 bg-gray-200 rounded-full flex items-center justify-center font-bold text-sm">{r.name[0]}</div>
                      <div><p className="font-semibold text-sm">{r.name}</p><StarRating rating={r.rating} size={12} /></div>
                    </div>
                    <span className="text-xs text-gray-400">{formatDate(r.date, lang)}</span>
                  </div>
                  <p className="text-sm text-gray-700">{r.comment}</p>
                  <p className="text-xs text-gray-400 mt-2">{lang === 'en' ? `${r.helpful} found helpful` : `${r.helpful} người thấy hữu ích`}</p>
                </div>
              ))}
            </div>
          )}
          {activeTab === 'specifications' && (
            <div className="grid grid-cols-2 gap-3">
              {[['SKU', product.sku], ['Weight', `${product.weight} kg`], ['Category', product.category], ['Status', 'In Stock']].map(([k, v]) => (
                <div key={k} className="flex items-center p-3 bg-gray-50 rounded-lg">
                  <span className="text-sm font-semibold text-gray-500 w-28">{k}</span>
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
              <h2 className="text-2xl font-bold text-gray-900">{lang === 'en' ? 'Related products' : 'Sản phẩm liên quan'}</h2>
              <p className="text-sm text-gray-500 mt-1">{lang === 'en' ? `More products related to ${product.category}` : `Các sản phẩm khác liên quan đến ${product.category}`}</p>
            </div>
            <Button variant="outline" size="sm" onClick={() => navigate('search', { category: product.categoryId })}>{lang === 'en' ? 'View category' : 'Xem danh mục'}</Button>
          </div>
          <div className="grid grid-cols-4 gap-5">
            {relatedProducts.map(relatedProduct => <ProductCard key={relatedProduct.id} product={relatedProduct} />)}
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
    if (voucher === 'NEXUS10') { setAppliedVoucher(voucher); setVoucherError(''); showToast({ type: 'success', message: tr(lang, 'voucherApplied') }) }
    else { setVoucherError(tr(lang, 'invalidVoucher')) }
  }

  const updateQty = (idx: number, qty: number) => {
    if (qty === 0) { setItems(prev => prev.filter((_, i) => i !== idx)); setCartCount(Math.max(0, items.length - 1)) }
    else setItems(prev => prev.map((it, i) => i === idx ? { ...it, qty } : it))
  }

  if (items.length === 0) return (
    <BuyerLayout>
      <EmptyState icon={<ShoppingCart size={20} />} title={tr(lang, 'emptyCart')} subtitle={lang === 'en' ? "You haven't added anything to your cart yet." : "Bạn chưa thêm bất cứ thứ gì vào giỏ hàng."} action={<Button variant="primary" onClick={() => navigate('home')}>{lang === 'en' ? 'Start Shopping' : 'Bắt đầu mua sắm'}</Button>} />
    </BuyerLayout>
  )

  return (
    <BuyerLayout>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">{tr(lang, 'cart')} <span className="text-gray-400 font-normal text-lg">({items.length})</span></h1>
      {subtotal < 100 && (
        <AlertBanner type="info" message={lang === 'en' ? `Add $${(100 - subtotal).toFixed(2)} more to get free shipping!` : `Thêm ${formatCurrency(100 - subtotal, lang)} để được miễn phí vận chuyển!`} />
      )}
      {subtotal < 100 && <div className="mt-3 mb-5"><ProgressBar value={subtotal} max={100} /></div>}
      <div className="grid grid-cols-[1fr_360px] gap-8 mt-4">
        <div className="space-y-3">
          {items.map((item, idx) => (
            <div key={item.id + idx} className="nexus-card p-4 flex gap-4">
              <img src={item.images[0]} alt={item.name} className="w-20 h-20 object-cover rounded-xl bg-gray-100 shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-gray-900 line-clamp-2">{lang === 'vi' ? item.nameVi : item.name}</p>
                <p className="text-sm text-gray-500 mt-1">{item.category}</p>
                <div className="flex items-center justify-between mt-3">
                  <NumberStepper value={item.qty} min={0} max={item.stock} onChange={qty => updateQty(idx, qty)} />
                  <span className="text-lg font-bold text-primary">{formatCurrency(item.price * item.qty, lang)}</span>
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
                [tr(lang, 'shipping'), shipping === 0 ? tr(lang, 'freeShipping') : formatCurrency(shipping, lang)],
                [tr(lang, 'tax'), formatCurrency(tax, lang)],
                ...(discount > 0 ? [[tr(lang, 'discount'), `-${formatCurrency(discount, lang)}`]] : []),
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between text-gray-600">
                  <span>{k}</span><span className={String(v).startsWith('-') ? 'text-success font-semibold' : ''}>{v}</span>
                </div>
              ))}
              <div className="border-t border-border pt-3 flex justify-between font-bold text-base text-gray-900">
                <span>{tr(lang, 'total')}</span><span className="text-primary">{formatCurrency(total, lang)}</span>
              </div>
            </div>
          </div>

          <div className="nexus-card p-4">
            <label className="text-sm font-semibold text-gray-700 mb-2 block">{tr(lang, 'voucher')}</label>
            <div className="flex gap-2">
              <input value={voucher} onChange={e => setVoucher(e.target.value)} placeholder="NEXUS10" className="nexus-input flex-1 py-2" />
              <Button variant="outline" onClick={applyVoucher}>{tr(lang, 'applyVoucher')}</Button>
            </div>
            {voucherError && <p className="text-xs text-danger mt-1.5">{voucherError}</p>}
            {appliedVoucher && <p className="text-xs text-success mt-1.5 flex items-center gap-1"><CheckCircle size={12} />{tr(lang, 'voucherApplied')}</p>}
          </div>

          <Button variant="primary" size="lg" className="w-full" onClick={() => navigate('checkout')}>{tr(lang, 'checkout')} →</Button>
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

  const STEPS = [tr(lang, 'shippingAddress'), tr(lang, 'shippingMethod'), tr(lang, 'payment'), tr(lang, 'confirmation')]

  const placeOrder = async () => {
    setLoading(true)
    await new Promise(r => setTimeout(r, 1500))
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
            <span className={`checkout-step ${i < step - 1 ? 'done' : i === step - 1 ? 'current' : 'pending'}`}>
              <span className="checkout-step-num">{i < step - 1 ? <CheckCircle size={13} /> : i + 1}</span>
              <span className="text-xs ml-1.5">{s}</span>
            </span>
            {i < STEPS.length - 1 && <span className={`checkout-connector mx-2 ${i < step - 1 ? 'done' : ''}`} />}
          </span>
        ))}
      </div>

      <div className="grid grid-cols-[1fr_340px] gap-8">
        <div className="nexus-card p-6">
          {/* Countdown */}
          <div className="flex items-center gap-2 mb-5 p-3 bg-warning-light rounded-xl text-sm text-yellow-800">
            <AlertTriangle size={16} className="text-gray-500" />
            {lang === 'en' ? 'Complete your order within 15:00 to reserve your items' : 'Hoàn tất đơn hàng trong 15:00 để giữ sản phẩm'}
            <span className="ml-auto font-mono font-bold text-warning">14:23</span>
          </div>

          {step === 1 && (
            <div className="space-y-4">
              <h2 className="font-bold text-gray-900 text-lg mb-3">{tr(lang, 'shippingAddress')}</h2>
              <div className="grid grid-cols-2 gap-4">
                <Input label={lang === 'en' ? 'Full Name' : 'Họ và tên'} defaultValue="Minh Tran" />
                <Input label={lang === 'en' ? 'Phone' : 'Số điện thoại'} defaultValue="0901234567" />
              </div>
              <Input label={lang === 'en' ? 'Street Address' : 'Địa chỉ'} defaultValue="123 Nguyen Hue Street" />
              <div className="grid grid-cols-3 gap-4">
                <Input label={lang === 'en' ? 'City' : 'Thành phố'} defaultValue="Ho Chi Minh City" />
                <Input label={lang === 'en' ? 'District' : 'Quận/Huyện'} defaultValue="District 1" />
                <Input label={lang === 'en' ? 'Ward' : 'Phường/Xã'} defaultValue="Ben Nghe" />
              </div>
              <Button variant="primary" size="lg" onClick={() => setStep(2)}>{tr(lang, 'next')} →</Button>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-4">
              <h2 className="font-bold text-gray-900 text-lg mb-3">{tr(lang, 'shippingMethod')}</h2>
              {[
                { id: 'GHN', name: 'GHN Express', time: lang === 'en' ? '3-5 business days' : '3-5 ngày làm việc', price: 3.99, priceVi: '55.000₫' },
                { id: 'GHTK', name: 'GHTK Standard', time: lang === 'en' ? '5-7 business days' : '5-7 ngày làm việc', price: 2.49, priceVi: '35.000₫' },
              ].map(m => (
                <label key={m.id} className={`flex items-center gap-4 p-4 border-2 rounded-xl cursor-pointer transition-colors ${shipping === m.id ? 'border-primary bg-primary-light' : 'border-border hover:border-gray-300'}`}>
                  <input type="radio" className="w-4 h-4 accent-primary" checked={shipping === m.id as any} onChange={() => setShipping(m.id as 'GHN' | 'GHTK')} />
                  <Truck size={20} className={shipping === m.id ? 'text-primary' : 'text-gray-400'} />
                  <div className="flex-1"><p className="font-semibold">{m.name}</p><p className="text-sm text-gray-500">{tr(lang, 'deliveryEstimate')}: {m.time}</p></div>
                  <span className="font-bold text-primary">{lang === 'vi' ? m.priceVi : `$${m.price}`}</span>
                </label>
              ))}
              <div className="flex gap-3"><Button variant="outline" size="lg" onClick={() => setStep(1)}>{tr(lang, 'back')}</Button><Button variant="primary" size="lg" onClick={() => setStep(3)}>{tr(lang, 'next')} →</Button></div>
            </div>
          )}

          {step === 3 && (
            <div className="space-y-4">
              <h2 className="font-bold text-gray-900 text-lg mb-3">{tr(lang, 'payment')}</h2>
              <div className="p-3 bg-primary-light rounded-xl flex items-center gap-2 text-sm text-primary mb-3">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="1" y="4" width="22" height="16" rx="2" ry="2" /><line x1="1" y1="10" x2="23" y2="10" /></svg>
                {lang === 'en' ? 'Powered by Stripe — your payment is secure' : 'Bảo mật bởi Stripe — thanh toán của bạn được bảo vệ'}
              </div>
              <Input label={tr(lang, 'cardNumber')} placeholder="4242 4242 4242 4242" />
              <Input label={tr(lang, 'cardHolder')} placeholder="MINH TRAN" />
              <div className="grid grid-cols-2 gap-4">
                <Input label={tr(lang, 'expiryDate')} placeholder="MM/YY" />
                <Input label={tr(lang, 'cvv')} placeholder="123" type="password" />
              </div>
              <div className="flex gap-3"><Button variant="outline" size="lg" onClick={() => setStep(2)}>{tr(lang, 'back')}</Button><Button variant="primary" size="lg" onClick={() => setStep(4)}>{tr(lang, 'next')} →</Button></div>
            </div>
          )}

          {step === 4 && (
            <div className="space-y-4">
              <h2 className="font-bold text-gray-900 text-lg mb-3">{tr(lang, 'confirmation')}</h2>
              <div className="space-y-2 text-sm">
                {[
                  [lang === 'en' ? 'Ship to' : 'Giao đến', '123 Nguyen Hue, District 1, HCM'],
                  [tr(lang, 'shippingMethod'), shipping === 'GHN' ? 'GHN Express' : 'GHTK Standard'],
                  [tr(lang, 'payment'), 'Visa ending 4242'],
                  [tr(lang, 'total'), formatCurrency(329.97, lang)],
                ].map(([k, v]) => (
                  <div key={k} className="flex justify-between p-3 bg-gray-50 rounded-lg">
                    <span className="text-gray-500">{k}</span><span className="font-semibold">{v}</span>
                  </div>
                ))}
              </div>
              <div className="flex gap-3"><Button variant="outline" size="lg" onClick={() => setStep(3)}>{tr(lang, 'back')}</Button><Button variant="primary" size="lg" loading={loading} onClick={placeOrder} className="flex-1">{tr(lang, 'placeOrder')}</Button></div>
            </div>
          )}
        </div>

        {/* Order summary sidebar */}
        <div className="nexus-card p-5 h-fit">
          <h3 className="font-bold mb-4">{tr(lang, 'orderSummary')}</h3>
          <div className="space-y-3 mb-4">
            {[PRODUCTS[0], PRODUCTS[2]].map(p => (
              <div key={p.id} className="flex gap-3">
                <img src={p.images[0]} alt="" className="w-12 h-12 object-cover rounded-lg" />
                <div className="flex-1 min-w-0"><p className="text-xs font-semibold line-clamp-1">{lang === 'vi' ? p.nameVi : p.name}</p><p className="text-sm font-bold text-primary mt-0.5">{formatCurrency(p.price, lang)}</p></div>
              </div>
            ))}
          </div>
          <div className="border-t border-border pt-3 space-y-2 text-sm">
            {[[tr(lang, 'subtotal'), '$429.98'], [tr(lang, 'shipping'), '$3.99'], [tr(lang, 'tax'), '$34.40'], [tr(lang, 'total'), '$468.37']].map(([k, v], i) => (
              <div key={k} className={`flex justify-between ${i === 3 ? 'font-bold text-base border-t border-border pt-2 mt-2 text-primary' : 'text-gray-600'}`}><span>{k}</span><span>{v}</span></div>
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
        {status === 'success' && <>
          <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-5"><CheckCircle size={40} className="text-gray-600" /></div>
          <h1 className="text-2xl font-bold text-gray-900 mb-2">{tr(lang, 'orderPlaced')}</h1>
          <p className="text-gray-500 mb-2">{lang === 'en' ? 'Order #ORD-20240301-004 confirmed.' : 'Đơn hàng #ORD-20240301-004 đã được xác nhận.'}</p>
          <p className="text-sm text-gray-500 mb-2">{lang === 'en' ? 'Payment received and securely held by Nexus until the order is completed.' : 'Thanh toán thành công và đang được Nexus giữ an toàn cho đến khi đơn hàng hoàn tất.'}</p>
          <p className="text-sm text-gray-400 mb-8">{lang === 'en' ? "You'll receive a confirmation email shortly." : 'Bạn sẽ nhận được email xác nhận sớm.'}</p>
          <div className="flex gap-3 justify-center">
            <Button variant="outline" onClick={() => navigate('home')}>{lang === 'en' ? 'Continue Shopping' : 'Tiếp tục mua sắm'}</Button>
            <Button variant="primary" onClick={() => navigate('order-history')}>{tr(lang, 'orderHistory')}</Button>
          </div>
        </>}
        {status === 'failed' && <>
          <div className="w-20 h-20 bg-danger-light rounded-full flex items-center justify-center mx-auto mb-5">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#DC2626" strokeWidth="2.5" strokeLinecap="round"><circle cx="12" cy="12" r="10" /><line x1="15" y1="9" x2="9" y2="15" /><line x1="9" y1="9" x2="15" y2="15" /></svg>
          </div>
          <h1 className="text-2xl font-bold text-gray-900 mb-2">{tr(lang, 'paymentFailed')}</h1>
          {retries < 3 ? <>
            <p className="text-sm text-gray-400 mb-1">{lang === 'en' ? `Attempt ${retries + 1} of 3` : `Lần thử ${retries + 1} / 3`}</p>
            <p className="text-sm text-gray-500 mb-8">{tr(lang, 'retryLimit')}</p>
            <div className="flex gap-3 justify-center">
              <Button variant="outline" onClick={() => navigate('cart')}>{tr(lang, 'cancel')}</Button>
              <Button variant="primary" onClick={() => setRetries(r => r + 1)}>{tr(lang, 'tryAgain')}</Button>
            </div>
          </> : <><p className="text-gray-500 mb-8">{lang === 'en' ? 'Maximum retries reached. Please contact support.' : 'Đã hết số lần thử. Vui lòng liên hệ hỗ trợ.'}</p><Button variant="outline" onClick={() => navigate('home')}>{lang === 'en' ? 'Back to Home' : 'Về trang chủ'}</Button></>}
        </>}
        {status === 'pending' && <>
          <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-5"><AlertTriangle size={40} className="text-gray-600" /></div>
          <h1 className="text-2xl font-bold mb-2">{lang === 'en' ? 'Payment Pending' : 'Đang xử lý thanh toán'}</h1>
          <p className="text-gray-500 mb-8">{tr(lang, 'paymentPending')}</p>
          <Button variant="outline" onClick={() => navigate('order-history')}>{tr(lang, 'orderHistory')}</Button>
        </>}
      </div>
    </BuyerLayout>
  )
}

// ─── Order History Page ─────────────────────────────────────────────────────────
export function OrderHistoryPage() {
  const { lang, navigate } = useApp()
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [loading, setLoading] = useState(false)

  const STATUS_TABS = [
    { id: 'all', label: tr(lang, 'all') },
    { id: 'pending', label: tr(lang, 'statusPending') },
    { id: 'processing', label: tr(lang, 'statusProcessing') },
    { id: 'shipped', label: tr(lang, 'statusShipped') },
    { id: 'completed', label: tr(lang, 'statusCompleted') },
    { id: 'cancelled', label: tr(lang, 'statusCancelled') },
  ]

  const filtered = statusFilter === 'all' ? ORDERS : ORDERS.filter(o => o.status === statusFilter)

  const statusLabel = (s: string) => ({
    pending: tr(lang, 'statusPending'), paid: tr(lang, 'statusPaid'), processing: tr(lang, 'statusProcessing'),
    shipped: tr(lang, 'statusShipped'), delivered: tr(lang, 'statusDelivered'), completed: tr(lang, 'statusCompleted'),
    cancelled: tr(lang, 'statusCancelled'), refunded: tr(lang, 'statusRefunded'),
  }[s] || s)

  return (
    <BuyerLayout>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{tr(lang, 'orderHistory')}</h1>
      </div>
      <div className="nexus-card">
        <Tabs tabs={STATUS_TABS} active={statusFilter} onChange={setStatusFilter} />
        <div className="p-4">
          {filtered.length === 0 ? (
            <EmptyState icon={<Package size={20} />} title={tr(lang, 'noData')} subtitle={lang === 'en' ? 'No orders in this status.' : 'Không có đơn hàng nào.'} />
          ) : filtered.map(order => (
            <div key={order.id} className="border-b border-border last:border-0 py-5 first:pt-0">
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-3">
                  <StatusBadge status={order.status} label={statusLabel(order.status)} />
                  <span className="font-mono text-sm text-gray-600">{order.id}</span>
                  <span className="text-xs text-gray-400">{formatDate(order.createdAt, lang)}</span>
                </div>
                <Button variant="ghost" size="sm" onClick={() => navigate('order-detail', { id: order.id })}>{tr(lang, 'orderDetail')} <ChevronRight size={14} /></Button>
              </div>
              <div className="flex gap-4">
                {order.items.map(item => (
                  <div key={item.productId} className="flex items-center gap-3">
                    <img src={item.image} alt="" className="w-14 h-14 object-cover rounded-lg" />
                    <div><p className="text-sm font-semibold">{item.productName}</p><p className="text-xs text-gray-400">×{item.quantity} · {formatCurrency(item.subtotal, lang)}</p></div>
                  </div>
                ))}
              </div>
              <div className="flex items-center justify-between mt-3">
                <span className="text-sm text-gray-500">{lang === 'en' ? `Sold by ${order.sellerName}` : `Bởi ${order.sellerName}`}</span>
                <span className="font-bold text-primary">{lang === 'en' ? 'Total: ' : 'Tổng: '}{formatCurrency(order.total, lang)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </BuyerLayout>
  )
}

// ─── Order Detail Page ─────────────────────────────────────────────────────────
export function OrderDetailPage() {
  const { lang, navigate, pageParams, showToast } = useApp()
  const order = ORDERS.find(o => o.id === pageParams?.id) || ORDERS[0]
  const [cancelOpen, setCancelOpen] = useState(false)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [reviewed, setReviewed] = useState(false)
  const [paymentStatus, setPaymentStatus] = useState(getEffectivePaymentStatus(order))
  const [disputeOpen, setDisputeOpen] = useState(false)
  const [disputeReason, setDisputeReason] = useState('')

  const statusLabel = (s: string) => ({
    pending: tr(lang, 'statusPending'), paid: tr(lang, 'statusPaid'), processing: tr(lang, 'statusProcessing'),
    shipped: tr(lang, 'statusShipped'), delivered: tr(lang, 'statusDelivered'), completed: tr(lang, 'statusCompleted'),
    cancelled: tr(lang, 'statusCancelled'), refunded: tr(lang, 'statusRefunded'),
  }[s] || s)

  const canCancel = ['pending', 'paid'].includes(order.status)
  const protectionDays = order.protectionEndsAt ? Math.max(0, Math.ceil((new Date(order.protectionEndsAt).getTime() - Date.now()) / 86400000)) : 0
  const isProtectionActive = order.status === 'delivered' && paymentStatus === 'protection_period'

  const confirmDelivery = () => {
    setPaymentStatus('released')
    showToast({ type: 'success', message: lang === 'en' ? 'Order confirmed. Payment has been released to the seller.' : 'Đã xác nhận đơn hàng. Tiền đã được chuyển vào số dư người bán.' })
  }

  const submitDispute = () => {
    if (!disputeReason.trim()) return
    setPaymentStatus('on_hold')
    setDisputeOpen(false)
    showToast({ type: 'success', message: lang === 'en' ? 'Dispute submitted. Payment remains on hold for admin review.' : 'Đã gửi khiếu nại. Khoản thanh toán tiếp tục được giữ để Admin xử lý.' })
  }

  return (
    <BuyerLayout>
      <Breadcrumb items={[{ label: lang === 'en' ? 'Order History' : 'Lịch sử đơn hàng', page: 'order-history' }, { label: order.id }]} />
      <div className="mt-5 grid grid-cols-[1fr_360px] gap-6">
        <div className="space-y-5">
          {/* Header */}
          <div className="nexus-card p-5 flex items-center justify-between">
            <div>
              <div className="flex items-center gap-3 mb-1">
                <StatusBadge status={order.status} label={statusLabel(order.status)} />
                <span className="font-mono font-semibold text-gray-800">{order.id}</span>
              </div>
              <p className="text-sm text-gray-500">{lang === 'en' ? 'Placed on' : 'Đặt lúc'} {formatDate(order.createdAt, lang)}</p>
            </div>
            <div className="flex gap-2">
              {canCancel && <Button variant="danger" size="sm" onClick={() => setCancelOpen(true)}>{tr(lang, 'cancelOrder')}</Button>}
              <Button variant="outline" size="sm"><Download size={14} />{tr(lang, 'downloadInvoice')}</Button>
            </div>
          </div>

          {/* Items */}
          <div className="nexus-card p-5">
            <h3 className="font-bold mb-4">{lang === 'en' ? 'Items' : 'Sản phẩm'}</h3>
            {order.items.map(item => (
              <div key={item.productId} className="flex gap-4 py-3 border-b border-border last:border-0">
                <img src={item.image} alt="" className="w-16 h-16 object-cover rounded-xl" />
                <div className="flex-1"><p className="font-semibold">{item.productName}</p><p className="text-sm text-gray-500">×{item.quantity} · {formatCurrency(item.unitPrice, lang)}</p></div>
                <p className="font-bold text-primary">{formatCurrency(item.subtotal, lang)}</p>
              </div>
            ))}
            <div className="pt-3 space-y-1.5 text-sm">
              {[[tr(lang, 'subtotal'), order.subtotal], [tr(lang, 'shipping'), order.shipping], [tr(lang, 'tax'), order.tax], ...(order.discount > 0 ? [[tr(lang, 'discount'), -order.discount]] : [])].map(([k, v]) => (
                <div key={String(k)} className="flex justify-between text-gray-600"><span>{k}</span><span>{typeof v === 'number' && v < 0 ? `-${formatCurrency(-v, lang)}` : formatCurrency(Number(v), lang)}</span></div>
              ))}
              <div className="flex justify-between font-bold text-base text-gray-900 border-t border-border pt-2 mt-1"><span>{tr(lang, 'total')}</span><span className="text-primary">{formatCurrency(order.total, lang)}</span></div>
            </div>
          </div>

          {/* Order timeline */}
          <div className="nexus-card p-5">
            <h3 className="font-bold mb-4">{lang === 'en' ? 'Order Status' : 'Trạng thái đơn hàng'}</h3>
            <OrderTimeline status={order.status} />
          </div>

          {isProtectionActive && (
            <div className="nexus-card p-5 border-primary/30">
              <div className="flex items-start justify-between gap-6">
                <div>
                  <div className="flex items-center gap-2 mb-2">
                    <Shield size={18} className="text-gray-500" />
                    <h3 className="font-bold">{lang === 'en' ? '7-day buyer protection' : 'Bảo vệ người mua trong 7 ngày'}</h3>
                  </div>
                  <p className="text-sm text-gray-600">
                    {lang === 'en'
                      ? `Payment is held by Nexus for ${protectionDays} more day(s). Confirm the item or submit a return/dispute before the protection period ends.`
                      : `Nexus tiếp tục giữ tiền trong ${protectionDays} ngày. Hãy xác nhận hàng hoặc gửi yêu cầu trả hàng/khiếu nại trước khi thời gian bảo vệ kết thúc.`}
                  </p>
                  {order.protectionEndsAt && <p className="text-xs text-gray-400 mt-2">{lang === 'en' ? 'Protection ends' : 'Kết thúc bảo vệ'}: {new Date(order.protectionEndsAt).toLocaleString()}</p>}
                </div>
                <div className="flex flex-col gap-2 shrink-0">
                  <Button variant="primary" size="sm" onClick={confirmDelivery}>{lang === 'en' ? 'Confirm Item' : 'Xác nhận hàng'}</Button>
                  <Button variant="outline" size="sm" onClick={() => setDisputeOpen(true)}>{lang === 'en' ? 'Return / Dispute' : 'Trả hàng / Khiếu nại'}</Button>
                </div>
              </div>
            </div>
          )}

          {paymentStatus === 'on_hold' && (
            <AlertBanner type="warning" message={lang === 'en' ? 'Payment is on hold while Admin reviews the dispute.' : 'Khoản thanh toán đang được giữ trong khi Admin xử lý khiếu nại.'} />
          )}

          {paymentStatus === 'released' && (
            <AlertBanner type="success" message={lang === 'en' ? 'Order completed. Payment has been released to the seller balance.' : 'Đơn hàng đã hoàn tất. Tiền đã được chuyển vào số dư người bán.'} />
          )}

          {/* Shipment tracking */}
          {order.shipment && (
            <div className="nexus-card p-5">
              <div className="flex items-center justify-between mb-4">
                <h3 className="font-bold">{lang === 'en' ? 'Shipment Tracking' : 'Theo dõi vận chuyển'}</h3>
                <div className="text-sm text-gray-500">{order.shipment.carrier} · <span className="font-mono">{order.shipment.trackingNumber}</span></div>
              </div>
              <div className="space-y-3">
                {order.shipment.events.map((ev, i) => (
                  <div key={i} className="flex gap-4">
                    <div className="flex flex-col items-center">
                      <div className={`w-3 h-3 rounded-full ${i === 0 ? 'bg-primary' : 'bg-gray-300'}`} />
                      {i < order.shipment!.events.length - 1 && <div className="w-0.5 h-8 bg-gray-200 mt-1" />}
                    </div>
                    <div className="pb-3">
                      <p className="text-sm font-semibold text-gray-800">{ev.description}</p>
                      {ev.location && <p className="text-xs text-gray-500">{ev.location}</p>}
                      <p className="text-xs text-gray-400">{formatDate(ev.timestamp, lang)}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Review */}
          {order.status === 'delivered' && !reviewed && (
            <div className="nexus-card p-5 border-primary/20 bg-primary-light/30">
              <h3 className="font-bold mb-2">{tr(lang, 'leaveReview')}</h3>
              <p className="text-sm text-gray-500 mb-3">{lang === 'en' ? 'How was your purchase?' : 'Trải nghiệm mua hàng của bạn như thế nào?'}</p>
              <Button variant="primary" size="sm" onClick={() => setReviewOpen(true)}>{tr(lang, 'leaveReview')}</Button>
            </div>
          )}
          {reviewed && <AlertBanner type="success" message={tr(lang, 'alreadyReviewed')} />}
        </div>

        <div className="space-y-4">
          <div className="nexus-card p-5">
            <h3 className="font-bold mb-3">{tr(lang, 'shippingAddress')}</h3>
            <p className="text-sm text-gray-700">{order.shippingAddress}</p>
          </div>
          <div className="nexus-card p-5">
            <h3 className="font-bold mb-3">{tr(lang, 'payment')}</h3>
            <p className="text-sm text-gray-700">{order.paymentMethod}</p>
            <div className="mt-3 flex items-center justify-between">
              <span className="text-xs text-gray-500">{lang === 'en' ? 'Escrow status' : 'Trạng thái tiền giữ hộ'}</span>
              <StatusBadge status={paymentStatus} label={{
                held: lang === 'en' ? 'Held by Nexus' : 'Nexus đang giữ',
                protection_period: lang === 'en' ? 'Buyer protection' : 'Đang bảo vệ Buyer',
                released: lang === 'en' ? 'Released' : 'Đã giải ngân',
                on_hold: lang === 'en' ? 'On hold' : 'Tạm giữ',
                refunded: lang === 'en' ? 'Refunded' : 'Đã hoàn tiền',
                partial_refund: lang === 'en' ? 'Partial refund' : 'Hoàn một phần',
              }[paymentStatus]} />
            </div>
            {order.refundStatus !== 'none' && <div className="mt-3"><StatusBadge status={order.refundStatus} label={order.refundStatus === 'completed' ? tr(lang, 'refundProcessed') : tr(lang, 'refundInitiated')} /></div>}
          </div>
          <div className="nexus-card p-5">
            <h3 className="font-bold mb-2">{tr(lang, 'seller')}</h3>
            <p className="text-sm font-semibold">{order.sellerName}</p>
          </div>
        </div>
      </div>

      <ConfirmModal open={cancelOpen} onClose={() => setCancelOpen(false)} onConfirm={() => showToast({ type: 'success', message: lang === 'en' ? 'Order cancelled.' : 'Đã hủy đơn hàng.' })}
        title={tr(lang, 'confirmCancel')} message={lang === 'en' ? 'Are you sure you want to cancel this order? This cannot be undone.' : 'Bạn có chắc muốn hủy đơn hàng này? Hành động này không thể hoàn tác.'} danger />

      <Modal open={reviewOpen} onClose={() => setReviewOpen(false)} title={tr(lang, 'leaveReview')}
        footer={<><Button variant="outline" onClick={() => setReviewOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setReviewed(true); setReviewOpen(false); showToast({ type: 'success', message: tr(lang, 'reviewSubmitted') }) }}>{lang === 'en' ? 'Submit Review' : 'Gửi đánh giá'}</Button></>}>
        <div className="space-y-4">
          <div><p className="text-sm font-semibold mb-2">{lang === 'en' ? 'Your Rating' : 'Đánh giá của bạn'}</p><StarRating rating={rating} interactive onChange={setRating} size={28} /></div>
          <div><label className="text-sm font-semibold mb-2 block">{lang === 'en' ? 'Comment' : 'Nhận xét'}</label><textarea value={comment} onChange={e => setComment(e.target.value)} className="nexus-input min-h-[100px]" placeholder={lang === 'en' ? 'Tell us about your experience...' : 'Chia sẻ trải nghiệm của bạn...'} /></div>
        </div>
      </Modal>

      <Modal open={disputeOpen} onClose={() => setDisputeOpen(false)} title={lang === 'en' ? 'Return or dispute request' : 'Yêu cầu trả hàng hoặc khiếu nại'} size="sm"
        footer={<><Button variant="outline" onClick={() => setDisputeOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" disabled={!disputeReason.trim()} onClick={submitDispute}>{lang === 'en' ? 'Submit Request' : 'Gửi yêu cầu'}</Button></>}>
        <AlertBanner type="info" message={lang === 'en' ? 'The payment will remain on hold until Admin resolves the dispute.' : 'Khoản thanh toán sẽ tiếp tục được giữ cho đến khi Admin xử lý xong khiếu nại.'} />
        <div className="mt-4">
          <Textarea label={tr(lang, 'reason')} value={disputeReason} onChange={e => setDisputeReason(e.target.value)} rows={4} placeholder={lang === 'en' ? 'Describe the issue with the item...' : 'Mô tả vấn đề với sản phẩm...'} />
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

  const save = async () => { setSaving(true); await new Promise(r => setTimeout(r, 700)); setSaving(false); showToast({ type: 'success', message: lang === 'en' ? 'Profile updated!' : 'Đã cập nhật hồ sơ!' }) }

  if (!user) return null
  return (
    <BuyerLayout>
      <div className="max-w-3xl mx-auto">
        <div className="flex items-center gap-5 mb-6">
          <img src={user.avatar} alt="" className="w-20 h-20 rounded-2xl object-cover shadow" />
          <div>
            <h1 className="text-2xl font-bold">{user.fullName}</h1>
            <p className="text-gray-500 text-sm">{user.email}</p>
            <div className="flex items-center gap-2 mt-1">
              <span className={`badge ${user.trustLevel === 'gold' ? 'badge-yellow' : user.trustLevel === 'platinum' ? 'badge-indigo' : 'badge-slate'}`}>{user.trustLevel.charAt(0).toUpperCase() + user.trustLevel.slice(1)}</span>
              <span className="text-xs text-gray-500">· {user.reputationScore} pts</span>
            </div>
          </div>
        </div>
        <div className="nexus-card">
          <Tabs tabs={[{ id: 'info', label: lang === 'en' ? 'Personal Info' : 'Thông tin cá nhân' }, { id: 'password', label: lang === 'en' ? 'Change Password' : 'Đổi mật khẩu' }]} active={activeTab} onChange={setActiveTab} />
          <div className="p-6">
            {activeTab === 'info' && (
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <Input label={tr(lang, 'fullName')} defaultValue={user.fullName} />
                  <Input label={tr(lang, 'email')} type="email" defaultValue={user.email} />
                  <Input label={tr(lang, 'phone')} defaultValue={user.phone} />
                  <Input label={tr(lang, 'idNumber')} defaultValue={user.idNumber} />
                  <Input label={tr(lang, 'dateOfBirth')} type="date" defaultValue={user.dateOfBirth} />
                </div>
                <Input label={tr(lang, 'address')} defaultValue={user.address} />
                <Button variant="primary" loading={saving} onClick={save}>{tr(lang, 'save')}</Button>
              </div>
            )}
            {activeTab === 'password' && (
              <div className="space-y-4 max-w-sm">
                <Input label={lang === 'en' ? 'Current Password' : 'Mật khẩu hiện tại'} type="password" placeholder="••••••••" />
                <Input label={tr(lang, 'newPassword')} type="password" placeholder="••••••••" />
                <Input label={tr(lang, 'confirmPassword')} type="password" placeholder="••••••••" />
                <Button variant="primary" loading={saving} onClick={save}>{lang === 'en' ? 'Update Password' : 'Cập nhật mật khẩu'}</Button>
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
    { from: 'TechZone Store', score: 5, comment: lang === 'en' ? 'Excellent buyer! Fast payment.' : 'Người mua tuyệt vời! Thanh toán nhanh.', date: '2024-02-18' },
    { from: 'SportsPro VN', score: 4, comment: lang === 'en' ? 'Good communication, smooth transaction.' : 'Giao tiếp tốt, giao dịch suôn sẻ.', date: '2024-01-25' },
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
                { icon: <CheckCircle size={18} />, label: tr(lang, 'successfulTransactions'), value: 24, className: 'text-success' },
                { icon: <CircleX size={18} />, label: tr(lang, 'failedTransactions'), value: 1, className: 'text-danger' },
              ].map(item => (
                <div key={item.label} className="text-center p-3 bg-gray-50 rounded-xl">
                  <div className="text-gray-500 flex justify-center mb-1">{item.icon}</div>
                  <p className={`text-2xl font-black ${item.className}`}>{item.value}</p>
                  <p className="text-xs text-gray-500">{item.label}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="nexus-card p-5">
          <h3 className="font-bold mb-4">{lang === 'en' ? 'Ratings Received' : 'Đánh giá nhận được'}</h3>
          {RATINGS.map((r, i) => (
            <div key={i} className="border-b border-border last:border-0 py-4">
              <div className="flex items-center justify-between mb-2">
                <span className="font-semibold text-sm">{r.from}</span>
                <div className="flex items-center gap-2"><StarRating rating={r.score} size={14} /><span className="text-xs text-gray-400">{formatDate(r.date, lang)}</span></div>
              </div>
              <p className="text-sm text-gray-600">{r.comment}</p>
            </div>
          ))}
        </div>
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
    const Icon = type === 'order' ? Package : type === 'auction' ? Gavel : type === 'payment' ? CreditCard : type === 'promotion' ? Gift : type === 'reputation' ? Star : Settings2
    return <Icon size={18} />
  }

  return (
    <BuyerLayout>
      <div className="max-w-3xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold">{tr(lang, 'notificationCenter')}</h1>
          <Button variant="ghost" size="sm" onClick={() => setNotifications(prev => prev.map(n => ({ ...n, read: true })))}>{tr(lang, 'markAllRead')}</Button>
        </div>
        <div className="nexus-card">
          <Tabs tabs={[{ id: 'history', label: lang === 'en' ? 'Notifications' : 'Thông báo', count: notifications.filter(n => !n.read).length }, { id: 'preferences', label: tr(lang, 'notifPreferences') }]} active={activeTab} onChange={setActiveTab} />
          <div className="p-0">
            {activeTab === 'history' && (
              <div>
                {notifications.map(n => (
                  <div key={n.id} onClick={() => setNotifications(prev => prev.map(x => x.id === n.id ? { ...x, read: true } : x))}
                    className={`flex gap-4 p-4 border-b border-border last:border-0 cursor-pointer transition-colors ${!n.read ? 'bg-primary-light/40' : 'hover:bg-gray-50'}`}>
                    <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-gray-100 text-gray-500">{notificationIcon(n.type)}</div>
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <p className={`text-sm font-semibold ${!n.read ? 'text-gray-900' : 'text-gray-700'}`}>{lang === 'vi' ? n.titleVi : n.title}</p>
                        {!n.read && <span className="w-2 h-2 rounded-full bg-primary" />}
                      </div>
                      <p className="text-xs text-gray-500 mt-0.5">{lang === 'vi' ? n.bodyVi : n.body}</p>
                      <p className="text-xs text-gray-400 mt-1">{new Date(n.timestamp).toLocaleString()}</p>
                    </div>
                  </div>
                ))}
              </div>
            )}
            {activeTab === 'preferences' && (
              <div className="p-5">
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead><tr className="border-b border-border"><th className="text-left py-3 text-sm text-gray-500">Type</th><th className="text-center py-3 text-sm text-gray-500">{tr(lang, 'emailChannel')}</th><th className="text-center py-3 text-sm text-gray-500">{tr(lang, 'pushChannel')}</th></tr></thead>
                    <tbody>
                      {[
                        { type: lang === 'en' ? 'Order Updates' : 'Cập nhật đơn hàng', locked: true },
                        { type: lang === 'en' ? 'Payment Notifications' : 'Thông báo thanh toán', locked: true },
                        { type: lang === 'en' ? 'Auction Alerts' : 'Thông báo đấu giá', locked: false },
                        { type: lang === 'en' ? 'Promotions' : 'Khuyến mãi', locked: false },
                        { type: lang === 'en' ? 'System News' : 'Tin tức hệ thống', locked: false },
                      ].map(item => (
                        <tr key={item.type} className="border-b border-border last:border-0">
                          <td className="py-3 text-sm font-medium flex items-center gap-2">{item.type}{item.locked && <span className="badge badge-gray text-[10px]">{tr(lang, 'alwaysOn')}</span>}</td>
                          <td className="text-center py-3"><Toggle checked={true} onChange={() => {}} disabled={item.locked} /></td>
                          <td className="text-center py-3"><Toggle checked={!item.locked} onChange={() => {}} disabled={item.locked} /></td>
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
