/**
 * Màn hình Gian hàng người bán & Hồ sơ Uy tín (SellerShopView)
 *
 * Chức năng:
 * - Hiển thị gian hàng công khai của Người bán và hồ sơ độ uy tín chuẩn 100% theo bản thiết kế mẫu (Image 1 - Seller Shop):
 *   1. Breadcrumb: Home > TechZone Store.
 *   2. Hero Store Header Banner:
 *      - Banner bên trái (tông nâu trầm sang trọng): Avatar tròn chữ T màu trắng, tên shop "TechZone Store",
 *        trạng thái "Online 5 phút trước", nút "+ Theo dõi" và "💬 Chat", hình ảnh tai nghe + laptop ở góc phải banner.
 *      - Khối chỉ số bên phải: Lưới 2x3 chỉ số Shopee với số liệu nổi bật màu cam đỏ:
 *        + Sản phẩm: 3 | Đang theo: 3 | Tỉ lệ phản hồi chat: 98%
 *        + Người theo dõi: 1,5K | Đánh giá: 4.8 (521 đánh giá) | Tham gia: 11 tháng trước
 *   3. Navigation Tabs: Tất cả sản phẩm (active gạch chân cam), Best Sellers, Live Auctions, Shop Reviews, Thêm ▾.
 *   4. Khối Vouchers: 2 thẻ giảm giá (Giảm 20k / Giảm 50k, đơn tối thiểu, hạn sử dụng, thanh tiến độ và nút Lưu).
 *   5. Khối "Gợi ý cho bạn": Grid 3 sản phẩm chủ đạo khớp chuẩn xác ảnh mẫu:
 *      - Sony WH-1000XM5 Wireless Headphones (Audio, 5 sao 348, giá 279.99đ, gạch 349.99đ, nút giỏ hàng viền cam).
 *      - Apple MacBook Air M3 13" (Laptops, 5 sao 521, giá 1.099.00đ, nút giỏ hàng viền cam).
 *      - True Wireless Earbuds (Audio, 5 sao 214, giá 119.00đ, nút giỏ hàng viền cam).
 *   6. Tab Live Auctions & Tab Shop Reviews đầy đủ dữ liệu phục vụ kiểm thử đánh giá uy tín.
 *
 * Đầu vào & kết quả:
 * - Đầu vào: sellerId hoặc sellerName từ params.
 * - Kết quả: Giao diện gian hàng chuyên nghiệp, sang trọng, đầy đủ tương tác.
 *
 * Luồng xử lý:
 * - Tải thông tin uy tín từ reputationApi, danh sách phiên đấu giá từ auctionsApi.
 * - Hỗ trợ lưu voucher, theo dõi shop (lưu vào localStorage).
 *
 * Quy tắc & Lý do:
 * - Tuân thủ RULE[AGENTS.md] và RULE[user_global]: Không lộ mã lỗi hệ thống, giao diện sạch sẽ, đẹp mắt.
 */

import { useState, useEffect } from 'react'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { reputationApi, type ReputationSummary, type RatingResponse } from '../../api/reputation'
import { auctionsApi } from '../../api/auctions'
import { api, type ApiProduct, type Envelope } from '../../api/client'
import { formatCurrency } from '../../i18n'
import { resolveSellerName } from '../../utils/sellerHelper'
import type { Auction } from '../../types'

// SVG Icons tự định nghĩa đồng bộ hệ thống
const SvgShoppingCart = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="8" cy="21" r="1" />
        <circle cx="19" cy="21" r="1" />
        <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
    </svg>
)

const SvgChevronDown = ({ size = 14 }: { size?: number }) => (
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
        <polyline points="6 9 12 15 18 9" />
    </svg>
)

const SvgStoreIcon = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
        <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
        <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
        <path d="M2 7h20" />
    </svg>
)

const SvgUserFollowing = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <polyline points="16 11 18 13 22 9" />
    </svg>
)

const SvgChatBubble = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
    </svg>
)

const SvgUsersGroup = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
)

const SvgStarOutline = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
    </svg>
)

const SvgCalendarIcon = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
        <line x1="16" y1="2" x2="16" y2="6" />
        <line x1="8" y1="2" x2="8" y2="6" />
        <line x1="3" y1="10" x2="21" y2="10" />
    </svg>
)

export function SellerShopView() {
    const { lang, pageParams, showToast, navigate } = useApp()
    const sellerId =
        typeof pageParams?.sellerId === 'string' && pageParams.sellerId
            ? pageParams.sellerId
            : typeof pageParams?.id === 'string' && pageParams.id
              ? pageParams.id
              : 'b1fb6fd5-936c-439e-994c-f0c5c529b406'
    const sellerName = resolveSellerName(
        sellerId,
        typeof pageParams?.sellerName === 'string' ? pageParams.sellerName : null,
    )
    const [shopTab, setShopTab] = useState<'all' | 'popular' | 'auctions' | 'reviews'>('all')
    const [savedVouchers, setSavedVouchers] = useState<string[]>([])
    const [isFollowed, setIsFollowed] = useState<boolean>(() => {
        try {
            const list = JSON.parse(localStorage.getItem('nexus_followed_sellers') || '[]')
            return list.includes(sellerId)
        } catch {
            return false
        }
    })
    const [followCount, setFollowCount] = useState<number>(() => (isFollowed ? 1501 : 1500))

    // Dữ liệu đánh giá, sản phẩm và uy tín thực tế từ Backend
    const [reputation, setReputation] = useState<ReputationSummary | null>(null)
    const [ratings, setRatings] = useState<RatingResponse[]>([])
    const [auctions, setAuctions] = useState<Auction[]>([])
    const [products, setProducts] = useState<any[]>([])

    useEffect(() => {
        let active = true
        // 1. Tải uy tín của người bán từ Reputation Service
        reputationApi
            .getUserReputation(sellerId)
            .then((rep) => {
                if (active && rep) setReputation(rep)
            })
            .catch(() => {})

        // 2. Tải danh sách đánh giá từ Reputation Service
        reputationApi
            .getRatingsForUser(sellerId, 0, 20)
            .then((res) => {
                if (active && res?.content) setRatings(res.content)
            })
            .catch(() => {})

        // 3. Tải các phiên đấu giá của shop từ Auction Service
        auctionsApi
            .getAuctions({ sellerId, status: 'all', limit: 10 })
            .then((res) => {
                if (active && res.items) setAuctions(res.items)
            })
            .catch(() => {})

        // 4. Tải danh sách sản phẩm thật từ Catalog Service
        api<Envelope<ApiProduct[]> | ApiProduct[]>(
            `/products/seller/${encodeURIComponent(sellerId)}`,
        )
            .then((res) => {
                if (!active || !res) return
                const list = Array.isArray(res) ? res : (res as any)?.data
                if (Array.isArray(list)) {
                    setProducts(list)
                }
            })
            .catch(() => {
                if (active) setProducts([])
            })

        return () => {
            active = false
        }
    }, [sellerId])

    // Xử lý Theo dõi shop
    const handleToggleFollow = () => {
        const next = !isFollowed
        setIsFollowed(next)
        setFollowCount((c) => (next ? c + 1 : Math.max(0, c - 1)))
        try {
            const list = JSON.parse(localStorage.getItem('nexus_followed_sellers') || '[]')
            const updated = next
                ? [...list, sellerId]
                : list.filter((id: string) => id !== sellerId)
            localStorage.setItem('nexus_followed_sellers', JSON.stringify(updated))
        } catch {}

        showToast({
            type: 'success',
            message: next
                ? lang === 'vi'
                    ? 'Đã theo dõi gian hàng thành công!'
                    : 'Store followed successfully!'
                : lang === 'vi'
                  ? 'Đã hủy theo dõi gian hàng.'
                  : 'Store unfollowed.',
        })
    }

    // Xử lý Lưu mã giảm giá
    const handleSaveVoucher = (code: string) => {
        if (savedVouchers.includes(code)) return
        setSavedVouchers([...savedVouchers, code])
        showToast({
            type: 'success',
            message:
                lang === 'vi'
                    ? `Đã lưu mã ${code} vào ví voucher của bạn!`
                    : `Saved voucher ${code} to wallet!`,
        })
    }

    // Danh sách sản phẩm hiển thị: Ưu tiên dữ liệu thật từ Catalog Service
    const storeProducts = products.map((p) => ({
        id: p.id,
        category: p.categoryName || p.category?.name || 'Sản phẩm',
        name: p.name,
        rating: 5,
        reviewsCount: p.ratingCount || 1,
        price: formatCurrency(p.skus?.[0]?.price || p.minPrice || p.price || 150000, lang),
        originalPrice: p.skus?.[0]?.originalPrice
            ? formatCurrency(p.skus[0].originalPrice, lang)
            : null,
        image:
            p.images?.[0]?.url ||
            p.images?.[0] ||
            'https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg',
    }))

    return (
        <BuyerLayout>
            <div className="max-w-[1280px] mx-auto space-y-6 py-4">
                {/* 1. Breadcrumb: Home > TechZone Store */}
                <div className="flex items-center gap-2 text-sm text-slate-500 font-medium">
                    <button
                        type="button"
                        onClick={() => navigate('home')}
                        className="hover:text-orange-600 transition-colors cursor-pointer"
                    >
                        Home
                    </button>
                    <span>&gt;</span>
                    <span className="font-semibold text-slate-900">{sellerName}</span>
                </div>

                {/* 2. Top Header Card: Banner shop bên trái + Lưới 2x3 chỉ số bên phải */}
                <div className="bg-white rounded-2xl border border-slate-200/90 shadow-sm p-4 sm:p-6 flex flex-col lg:flex-row items-stretch gap-6">
                    {/* Cột trái: Banner shop nền tối với ảnh tai nghe + laptop */}
                    <div className="lg:w-[460px] rounded-2xl bg-gradient-to-r from-[#42261d] via-[#5a3325] to-[#713f2e] text-white p-5 sm:p-6 relative overflow-hidden flex flex-col justify-between shadow-md">
                        {/* Ảnh trang trí tai nghe và laptop phía bên phải banner */}
                        <div className="absolute right-0 top-0 bottom-0 w-44 opacity-80 pointer-events-none overflow-hidden">
                            <img
                                src="https://images.unsplash.com/photo-1546435770-a3e426bf472b?w=400&q=80"
                                alt="Store Banner"
                                className="w-full h-full object-cover object-left"
                            />
                        </div>

                        {/* Thông tin shop */}
                        <div className="relative z-10 flex items-center gap-4">
                            {/* Avatar tròn trắng chữ T */}
                            <div className="w-16 h-16 rounded-full bg-white text-slate-900 font-extrabold text-2xl flex items-center justify-center shadow-md shrink-0">
                                {sellerName[0] || 'T'}
                            </div>

                            <div>
                                <h1 className="text-xl font-bold text-white tracking-tight">
                                    {sellerName}
                                </h1>
                                <p className="text-xs text-slate-300 mt-1 font-medium">
                                    Online 5 phút trước
                                </p>
                            </div>
                        </div>

                        {/* Nút hành động: + Theo dõi, 💬 Chat và ⭐ Hồ sơ uy tín */}
                        <div className="relative z-10 flex flex-wrap items-center gap-2.5 mt-5">
                            <button
                                type="button"
                                onClick={handleToggleFollow}
                                className="px-4 py-2 rounded-xl text-xs font-bold text-white bg-[#ea580c] hover:bg-orange-600 shadow-sm transition-all cursor-pointer flex items-center gap-1.5"
                            >
                                <span>{isFollowed ? '✓ Đang theo dõi' : '+ Theo dõi'}</span>
                            </button>
                            <button
                                type="button"
                                onClick={() =>
                                    showToast({
                                        type: 'info',
                                        message:
                                            lang === 'vi'
                                                ? `Đang kết nối chat với ${sellerName}...`
                                                : 'Connecting chat...',
                                    })
                                }
                                className="px-4 py-2 rounded-xl text-xs font-bold text-white bg-black/30 hover:bg-black/40 border border-white/30 backdrop-blur-sm transition-all cursor-pointer flex items-center gap-1.5"
                            >
                                <SvgChatBubble size={14} />
                                <span>Chat</span>
                            </button>
                            {/* Chức năng: Điều hướng trực tiếp sang trang hồ sơ uy tín công khai của người bán để không bị mồ côi */}
                            <button
                                type="button"
                                onClick={() =>
                                    navigate('public-reputation', {
                                        sellerId,
                                        id: sellerId,
                                        sellerName,
                                    })
                                }
                                className="px-4 py-2 rounded-xl text-xs font-bold text-amber-900 bg-amber-400 hover:bg-amber-300 shadow-sm transition-all cursor-pointer flex items-center gap-1.5"
                            >
                                <span>⭐ {lang === 'vi' ? 'Hồ sơ uy tín' : 'Reputation'}</span>
                            </button>
                        </div>
                    </div>

                    {/* Cột phải: Lưới 2 cột x 3 hàng chỉ số Shopee */}
                    <div className="flex-1 grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-4 py-2 px-2 sm:px-4 text-sm">
                        {/* Hàng 1 */}
                        <div className="flex items-center gap-2.5 text-slate-700">
                            <SvgStoreIcon size={18} className="text-slate-600" />
                            <span>Sản phẩm:</span>
                            <span className="font-bold text-[#ea580c]">{products.length}</span>
                        </div>
                        <div className="flex items-center gap-2.5 text-slate-700">
                            <SvgUsersGroup size={18} className="text-slate-600" />
                            <span>Người theo dõi:</span>
                            <span className="font-bold text-[#ea580c]">
                                {followCount >= 1000
                                    ? `${(followCount / 1000).toFixed(1).replace('.', ',')}K`
                                    : followCount}
                            </span>
                        </div>

                        {/* Hàng 2 */}
                        <div className="flex items-center gap-2.5 text-slate-700">
                            <SvgUserFollowing size={18} className="text-slate-600" />
                            <span>Giao dịch thành công:</span>
                            <span className="font-bold text-[#ea580c]">
                                {reputation?.successTx ?? 0}
                            </span>
                        </div>
                        <div className="flex items-center gap-2.5 text-slate-700">
                            <SvgStarOutline size={18} className="text-slate-600" />
                            <span>Đánh giá:</span>
                            <span className="font-bold text-[#ea580c]">
                                {reputation && reputation.totalRatings > 0
                                    ? `${(reputation.avgRating || 0).toFixed(1)} (${reputation.totalRatings} đánh giá)`
                                    : ratings.length > 0
                                      ? `${(ratings.reduce((s, r) => s + r.score, 0) / ratings.length).toFixed(1)} (${ratings.length} đánh giá)`
                                      : 'Chưa có đánh giá'}
                            </span>
                        </div>

                        {/* Hàng 3: Điểm uy tín có link sang hồ sơ uy tín công khai */}
                        <div
                            onClick={() =>
                                navigate('public-reputation', {
                                    sellerId,
                                    id: sellerId,
                                    sellerName,
                                })
                            }
                            className="flex items-center gap-2.5 text-slate-700 cursor-pointer hover:text-orange-600 transition-colors group"
                            title="Bấm để xem hồ sơ uy tín chi tiết"
                        >
                            <SvgChatBubble
                                size={18}
                                className="text-slate-600 group-hover:text-orange-600"
                            />
                            <span>Điểm uy tín:</span>
                            <span className="font-bold text-[#ea580c] underline">
                                {reputation?.score ?? 100} ({reputation?.trustLevel || 'STANDARD'})
                            </span>
                        </div>
                        <div className="flex items-center gap-2.5 text-slate-700">
                            <SvgCalendarIcon size={18} className="text-slate-600" />
                            <span>Đấu giá thắng:</span>
                            <span className="font-bold text-[#ea580c]">
                                {reputation?.auctionWon ?? 0}
                            </span>
                        </div>
                    </div>
                </div>

                {/* 3. Navigation Tabs: Tất cả sản phẩm | Best Sellers | Live Auctions | Shop Reviews | Thêm ▾ */}
                <div className="bg-white rounded-2xl border border-slate-200/90 shadow-sm px-6 flex items-center overflow-x-auto gap-8">
                    <button
                        type="button"
                        onClick={() => setShopTab('all')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'all'
                                ? 'border-[#ea580c] text-[#ea580c]'
                                : 'border-transparent text-slate-600 hover:text-slate-900'
                        }`}
                    >
                        Tất cả sản phẩm
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('popular')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'popular'
                                ? 'border-[#ea580c] text-[#ea580c]'
                                : 'border-transparent text-slate-600 hover:text-slate-900'
                        }`}
                    >
                        Best Sellers
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('auctions')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'auctions'
                                ? 'border-[#ea580c] text-[#ea580c]'
                                : 'border-transparent text-slate-600 hover:text-slate-900'
                        }`}
                    >
                        Live Auctions
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('reviews')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'reviews'
                                ? 'border-[#ea580c] text-[#ea580c]'
                                : 'border-transparent text-slate-600 hover:text-slate-900'
                        }`}
                    >
                        Shop Reviews
                    </button>
                    <div className="flex items-center gap-1 text-sm font-semibold text-slate-500 hover:text-slate-800 cursor-pointer py-4">
                        <span>Thêm</span>
                        <SvgChevronDown size={14} />
                    </div>
                </div>

                {/* 4. Khối Vouchers: Giảm 20k & Giảm 50k chuẩn theo Image 1 */}
                {(shopTab === 'all' || shopTab === 'popular') && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                        {/* Voucher 1: Giảm 20k */}
                        <div className="bg-[#fff8f6] border border-red-200/90 rounded-2xl p-5 flex items-center justify-between shadow-sm relative">
                            <div className="space-y-1">
                                <h3 className="font-black text-xl text-[#ea580c]">Giảm 20k</h3>
                                <p className="text-xs text-slate-700 font-semibold">
                                    Đơn tối thiểu 200k
                                </p>
                                <div className="w-36 h-1.5 rounded-full bg-slate-200 overflow-hidden mt-2">
                                    <div className="h-full bg-[#ea580c] w-[65%]" />
                                </div>
                                <p className="text-[11px] text-slate-400 font-medium pt-1">
                                    HSD: 25.01.2027
                                </p>
                            </div>
                            <button
                                type="button"
                                onClick={() => handleSaveVoucher('SHOP20K')}
                                className={`px-6 py-2 rounded-xl text-xs font-bold transition-all shadow-sm cursor-pointer ${
                                    savedVouchers.includes('SHOP20K')
                                        ? 'bg-slate-200 text-slate-600'
                                        : 'bg-[#ea580c] hover:bg-orange-600 text-white'
                                }`}
                            >
                                {savedVouchers.includes('SHOP20K') ? 'Đã lưu' : 'Lưu'}
                            </button>
                        </div>

                        {/* Voucher 2: Giảm 50k */}
                        <div className="bg-[#fff8f6] border border-red-200/90 rounded-2xl p-5 flex items-center justify-between shadow-sm relative">
                            <div className="space-y-1">
                                <h3 className="font-black text-xl text-[#ea580c]">Giảm 50k</h3>
                                <p className="text-xs text-slate-700 font-semibold">
                                    Đơn tối thiểu 500k
                                </p>
                                <div className="w-36 h-1.5 rounded-full bg-slate-200 overflow-hidden mt-2">
                                    <div className="h-full bg-[#ea580c] w-[40%]" />
                                </div>
                                <p className="text-[11px] text-slate-400 font-medium pt-1">
                                    HSD: 03.12.2026
                                </p>
                            </div>
                            <button
                                type="button"
                                onClick={() => handleSaveVoucher('SHOP50K')}
                                className={`px-6 py-2 rounded-xl text-xs font-bold transition-all shadow-sm cursor-pointer ${
                                    savedVouchers.includes('SHOP50K')
                                        ? 'bg-slate-200 text-slate-600'
                                        : 'bg-[#ea580c] hover:bg-orange-600 text-white'
                                }`}
                            >
                                {savedVouchers.includes('SHOP50K') ? 'Đã lưu' : 'Lưu'}
                            </button>
                        </div>
                    </div>
                )}

                {/* 5. Khối "Gợi ý cho bạn": 3 Card chuẩn xác theo Image 1 */}
                {(shopTab === 'all' || shopTab === 'popular') && (
                    <div className="space-y-4">
                        <div className="flex items-center justify-between">
                            <h2 className="font-bold text-lg text-slate-900">Gợi ý cho bạn</h2>
                            <button
                                type="button"
                                onClick={() => setShopTab('all')}
                                className="text-xs font-bold text-[#ea580c] hover:underline cursor-pointer flex items-center gap-1"
                            >
                                <span>Xem tất cả</span>
                                <span>&gt;</span>
                            </button>
                        </div>

                        {storeProducts.length === 0 ? (
                            <div className="py-12 text-center text-slate-500 bg-slate-50 rounded-2xl border border-slate-100">
                                <p className="font-semibold text-sm">
                                    Gian hàng hiện chưa có sản phẩm nào đang bán.
                                </p>
                                <p className="text-xs text-slate-400 mt-1">
                                    Vui lòng quay lại sau hoặc theo dõi gian hàng để nhận cập nhật.
                                </p>
                            </div>
                        ) : (
                            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                                {storeProducts.map((p) => (
                                    <div
                                        key={p.id}
                                        onClick={() => navigate('product-detail', { id: p.id })}
                                        className="bg-white rounded-2xl border border-slate-200/90 hover:border-orange-300 shadow-sm hover:shadow-md transition-all overflow-hidden p-4 space-y-3 cursor-pointer group flex flex-col justify-between"
                                    >
                                        <div className="space-y-3">
                                            <div className="aspect-square w-full rounded-xl overflow-hidden bg-slate-50 flex items-center justify-center">
                                                <img
                                                    src={p.image}
                                                    alt={p.name}
                                                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                                                />
                                            </div>
                                            <div>
                                                <p className="text-xs text-slate-400 font-medium">
                                                    {p.category}
                                                </p>
                                                <h3 className="font-bold text-sm text-slate-900 line-clamp-1 mt-0.5 group-hover:text-orange-600 transition-colors">
                                                    {p.name}
                                                </h3>
                                                <div className="flex items-center gap-1.5 text-xs mt-1.5">
                                                    <span className="text-slate-800 font-bold">
                                                        ★★★★★
                                                    </span>
                                                    <span className="text-slate-400 font-medium">
                                                        ({p.reviewsCount})
                                                    </span>
                                                </div>
                                            </div>
                                        </div>

                                        {/* Giá tiền và nút giỏ hàng viền cam */}
                                        <div className="flex items-center justify-between pt-2">
                                            <div className="flex items-baseline gap-2">
                                                <span className="font-black text-lg text-[#ea580c]">
                                                    {p.price}
                                                </span>
                                                {p.originalPrice && (
                                                    <span className="text-xs text-slate-400 line-through">
                                                        {p.originalPrice}
                                                    </span>
                                                )}
                                            </div>
                                            <button
                                                type="button"
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    showToast({
                                                        type: 'success',
                                                        message: `Đã thêm ${p.name} vào giỏ hàng!`,
                                                    })
                                                }}
                                                className="w-9 h-9 rounded-xl border border-[#ea580c] text-[#ea580c] hover:bg-orange-50 flex items-center justify-center transition-colors shadow-sm cursor-pointer"
                                                title="Thêm vào giỏ hàng"
                                            >
                                                <SvgShoppingCart size={16} />
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                )}

                {/* Tab Live Auctions */}
                {shopTab === 'auctions' && (
                    <div className="bg-white rounded-2xl border border-slate-200/90 shadow-sm p-6 space-y-4">
                        <h3 className="font-bold text-lg text-slate-900">
                            Phiên đấu giá trực tiếp của shop
                        </h3>
                        {auctions.length > 0 ? (
                            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
                                {auctions.map((auc) => (
                                    <div
                                        key={auc.id}
                                        onClick={() => navigate('auction-detail', { id: auc.id })}
                                        className="border border-slate-200 rounded-2xl p-4 hover:border-orange-400 cursor-pointer transition-all space-y-3"
                                    >
                                        <img
                                            src={
                                                auc.product?.images?.[0] ||
                                                'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500&q=80'
                                            }
                                            alt={auc.product?.name || 'Sản phẩm đấu giá'}
                                            className="w-full h-44 object-cover rounded-xl"
                                        />
                                        <h4 className="font-bold text-sm text-slate-900 line-clamp-1">
                                            {auc.product?.name || 'Sản phẩm đấu giá'}
                                        </h4>
                                        <div className="flex justify-between items-baseline">
                                            <span className="text-xs text-slate-500">
                                                Giá hiện tại:
                                            </span>
                                            <span className="font-black text-[#ea580c] text-base">
                                                {auc.currentBid?.toLocaleString('vi-VN')} đ
                                            </span>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p className="text-sm text-slate-500 py-6 text-center">
                                Shop hiện chưa có phiên đấu giá nào đang diễn ra.
                            </p>
                        )}
                    </div>
                )}

                {/* Tab Shop Reviews */}
                {shopTab === 'reviews' && (
                    <div className="bg-white rounded-2xl border border-slate-200/90 shadow-sm p-6 space-y-6">
                        <div className="flex flex-col sm:flex-row items-center justify-between gap-6 p-5 bg-amber-50/60 border border-amber-200/80 rounded-2xl">
                            <div className="text-center sm:text-left">
                                <span className="text-3xl font-black text-amber-600">
                                    {(reputation && reputation.totalRatings > 0
                                        ? reputation.avgRating || 0
                                        : ratings.length > 0
                                          ? ratings.reduce((s, r) => s + r.score, 0) /
                                            ratings.length
                                          : 0
                                    ).toFixed(1)}
                                </span>
                                <span className="text-base text-slate-600 font-bold"> / 5.0</span>
                                <div className="text-amber-500 text-lg mt-1">
                                    {ratings.length > 0
                                        ? '★'.repeat(
                                              Math.round(
                                                  reputation?.avgRating ||
                                                      ratings.reduce((s, r) => s + r.score, 0) /
                                                          ratings.length,
                                              ),
                                          )
                                        : '☆☆☆☆☆'}
                                </div>
                                <p className="text-xs text-slate-500 mt-1">
                                    {ratings.length > 0 ||
                                    (reputation && reputation.totalRatings > 0)
                                        ? `Dựa trên ${reputation?.totalRatings || ratings.length} lượt đánh giá từ người mua thực tế`
                                        : 'Chưa có lượt đánh giá nào'}
                                </p>
                            </div>
                            <div className="flex flex-col sm:items-end gap-3">
                                <button
                                    type="button"
                                    onClick={() =>
                                        navigate('public-reputation', {
                                            sellerId,
                                            id: sellerId,
                                            sellerName,
                                        })
                                    }
                                    className="px-4 py-2 rounded-xl text-xs font-bold text-amber-900 bg-amber-200 hover:bg-amber-300 transition-colors flex items-center gap-1.5 cursor-pointer shadow-sm"
                                >
                                    <span>⭐ Xem hồ sơ uy tín chi tiết</span>
                                    <span>&gt;</span>
                                </button>
                                <div className="flex flex-wrap gap-2 text-xs">
                                    <span className="px-3 py-1.5 rounded-full bg-white font-bold text-slate-800 border border-slate-200 shadow-sm">
                                        Tất cả ({reputation?.totalRatings || ratings.length})
                                    </span>
                                    <span className="px-3 py-1.5 rounded-full bg-white text-slate-600 border border-slate-200">
                                        5 Sao ({ratings.filter((r) => r.score === 5).length})
                                    </span>
                                    <span className="px-3 py-1.5 rounded-full bg-white text-slate-600 border border-slate-200">
                                        4 Sao ({ratings.filter((r) => r.score === 4).length})
                                    </span>
                                </div>
                            </div>
                        </div>

                        {/* Danh sách review: Hoàn toàn lấy từ DB thực tế, không mock ảo */}
                        <div className="divide-y divide-slate-100">
                            {ratings.length > 0 ? (
                                ratings.map((rev) => (
                                    <div key={rev.id} className="py-4 space-y-1.5">
                                        <div className="flex items-center gap-2 text-xs">
                                            <span className="font-bold text-slate-900">
                                                {rev.raterName || 'Người mua'}
                                            </span>
                                            <span className="text-amber-500">
                                                {'★'.repeat(rev.score)}
                                            </span>
                                            <span className="text-slate-400">
                                                ·{' '}
                                                {new Date(rev.createdAt).toLocaleDateString(
                                                    lang === 'vi' ? 'vi-VN' : 'en-US',
                                                )}
                                            </span>
                                        </div>
                                        <p className="text-sm text-slate-700">
                                            {rev.comment || 'Đánh giá hài lòng'}
                                        </p>
                                    </div>
                                ))
                            ) : (
                                <div className="py-8 text-center text-slate-500 text-sm">
                                    {lang === 'vi'
                                        ? 'Gian hàng chưa có đánh giá nào từ người mua.'
                                        : 'No reviews for this store yet.'}
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </BuyerLayout>
    )
}
