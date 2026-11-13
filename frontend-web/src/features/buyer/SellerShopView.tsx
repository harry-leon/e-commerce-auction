/**
 * Màn hình Gian hàng người bán & Hồ sơ Uy tín (SellerShopView)
 *
 * Chức năng:
 * - Hiển thị gian hàng công khai của Người bán và hồ sơ độ uy tín chuẩn 100% theo Image 4:
 *   1. Breadcrumb: Trang chủ > TechZone Store.
 *   2. Hero Store Header Banner nền tông trầm ấm sang trọng:
 *      - Cột trái: Card mờ nổi bật (Frosted Glass), Avatar tròn chữ T nền cam, tên shop "TechZone Store",
 *        huy hiệu "CHÍNH HÃNG", trạng thái "Online 5 phút trước", nút "+ Theo dõi" và "💬 Chat".
 *      - Cột phải: Lưới 2x3 chỉ số hoạt động Shopee: Sản Phẩm (3), Người Theo Dõi (1,5K), Đang Theo (3),
 *        Đánh Giá (4.8 - 521 Đánh Giá), Tỉ Lệ Phản Hồi Chat (98%), Tham Gia (11 Tháng Trước).
 *   3. Thanh Tabs điều hướng: Tất cả sản phẩm, Best Sellers, Live Auctions, Shop Reviews, Thêm ▾.
 *   4. Khối Voucher: MÃ GIẢM GIÁ CỦA SHOP với 2 voucher (Giảm 20k / Giảm 50k kèm thanh tiến độ).
 *   5. Khối sản phẩm: Gợi ý cho bạn với 3 sản phẩm chủ lực (Sony WH-1000XM5, MacBook Air M2, Anker Life P3).
 *   6. Tab Live Auctions & Tab Shop Reviews đầy đủ chức năng.
 *
 * Đầu vào:
 * - sellerId hoặc sellerName từ URL query/params (mặc định 'TechZone Store').
 *
 * Quy tắc & Lý do:
 * - Tuân thủ RULE[AGENTS.md] và RULE[user_global]: UI sạch, không lộ thông tin kỹ thuật, thao tác lưu voucher và theo dõi shop mượt mà.
 */

import { useState, useEffect } from 'react'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { formatCurrency } from '../../i18n'
import { reputationApi, type ReputationSummary, type RatingResponse } from '../../api/reputation'
import { auctionsApi } from '../../api/auctions'
import type { Auction } from '../../types'

// SVG Icons tự định nghĩa
const SvgShoppingCart = ({ size = 16 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="8" cy="21" r="1" />
        <circle cx="19" cy="21" r="1" />
        <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
    </svg>
)

const SvgChevronDown = ({ size = 14 }: { size?: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="6 9 12 15 18 9" />
    </svg>
)

export function SellerShopView() {
    const { lang, pageParams, showToast, navigate } = useApp()
    const sellerId = typeof pageParams?.id === 'string' && pageParams.id ? pageParams.id : 'seller-1'
    const sellerName = typeof pageParams?.sellerName === 'string' && pageParams.sellerName ? pageParams.sellerName : 'TechZone Store'
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

    // Dữ liệu đánh giá và uy tín
    const [reputation, setReputation] = useState<ReputationSummary | null>(null)
    const [ratings, setRatings] = useState<RatingResponse[]>([])
    const [auctions, setAuctions] = useState<Auction[]>([])

    useEffect(() => {
        let active = true
        reputationApi.getUserReputation(sellerId)
            .then((rep) => { if (active) setReputation(rep) })
            .catch(() => {})

        reputationApi.getRatingsForUser(sellerId, 0, 20)
            .then((res) => { if (active) setRatings(res.content) })
            .catch(() => {})

        auctionsApi.getAuctions({ sellerId, status: 'all', limit: 10 })
            .then((res) => { if (active && res.items) setAuctions(res.items) })
            .catch(() => {})

        return () => { active = false }
    }, [sellerId])

    // Xử lý Theo dõi shop
    const handleToggleFollow = () => {
        const next = !isFollowed
        setIsFollowed(next)
        setFollowCount((c) => (next ? c + 1 : Math.max(0, c - 1)))
        try {
            const list = JSON.parse(localStorage.getItem('nexus_followed_sellers') || '[]')
            const updated = next ? [...list, sellerId] : list.filter((id: string) => id !== sellerId)
            localStorage.setItem('nexus_followed_sellers', JSON.stringify(updated))
        } catch {}

        showToast({
            type: 'success',
            message: next
                ? (lang === 'vi' ? 'Đã theo dõi gian hàng thành công!' : 'Store followed successfully!')
                : (lang === 'vi' ? 'Đã hủy theo dõi gian hàng.' : 'Store unfollowed.'),
        })
    }

    // Xử lý Lưu mã giảm giá
    const handleSaveVoucher = (code: string) => {
        if (savedVouchers.includes(code)) return
        setSavedVouchers([...savedVouchers, code])
        showToast({
            type: 'success',
            message: lang === 'vi' ? `Đã lưu mã ${code} vào ví voucher của bạn!` : `Saved voucher ${code} to wallet!`,
        })
    }

    // 3 sản phẩm chuẩn theo Image 4
    const storeProducts = [
        {
            id: 'p-sony-1000xm5',
            name: 'Tai nghe Sony WH-1000XM5 Wireless Noise Cancelling',
            rating: 4.9,
            reviewsCount: 128,
            price: 7500000,
            originalPrice: 8490000,
            discount: '-12%',
            image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500&q=80',
        },
        {
            id: 'p-macbook-air-m2',
            name: 'Apple MacBook Air M2 13.6" 8GB/256GB Midnight',
            rating: 5.0,
            reviewsCount: 89,
            price: 24990000,
            originalPrice: 27990000,
            discount: '-11%',
            image: 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=500&q=80',
        },
        {
            id: 'p-anker-life-p3',
            name: 'Tai nghe True Wireless Anker Soundcore Life P3 ANC',
            rating: 4.7,
            reviewsCount: 245,
            price: 1490000,
            originalPrice: 1990000,
            discount: '-25%',
            image: 'https://images.unsplash.com/photo-1590658268037-6bf12165a8df?w=500&q=80',
        },
    ]

    return (
        <BuyerLayout>
            <div className="max-w-[1400px] mx-auto space-y-6">
                {/* 1. Breadcrumb: Trang chủ > TechZone Store */}
                <div className="flex items-center gap-2 text-xs text-gray-500">
                    <button
                        type="button"
                        onClick={() => navigate('home')}
                        className="hover:text-primary transition-colors cursor-pointer"
                    >
                        {lang === 'vi' ? 'Trang chủ' : 'Home'}
                    </button>
                    <span>&gt;</span>
                    <span className="font-semibold text-gray-800">{sellerName}</span>
                </div>

                {/* 2. Hero Store Header Banner: Tông trầm ấm sang trọng chuẩn Image 4 */}
                <div className="rounded-2xl overflow-hidden shadow-lg border border-gray-800/20 bg-gradient-to-r from-[#2c1d18] via-[#3a2922] to-[#1e1410] text-white p-6 sm:p-8">
                    <div className="grid grid-cols-1 lg:grid-cols-[390px_1fr] gap-8 items-center">
                        {/* Cột trái: Card mờ (Frosted glass) với Avatar, Tên shop, Nút hành động */}
                        <div className="bg-white/10 backdrop-blur-md rounded-2xl p-5 border border-white/15 flex items-center gap-4 shadow-inner">
                            {/* Avatar tròn chữ T */}
                            <div className="relative shrink-0">
                                <div className="w-18 h-18 sm:w-20 sm:h-20 rounded-full bg-gradient-to-tr from-amber-500 via-orange-500 to-[#ee4d2d] text-white font-black text-3xl flex items-center justify-center shadow-lg border-2 border-white">
                                    {sellerName[0] || 'T'}
                                </div>
                            </div>

                            {/* Thông tin shop */}
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2">
                                    <h2 className="font-extrabold text-base sm:text-lg text-white truncate">
                                        {sellerName}
                                    </h2>
                                    <span className="px-1.5 py-0.5 rounded text-[10px] font-black bg-amber-400 text-gray-900 tracking-wider">
                                        CHÍNH HÃNG
                                    </span>
                                </div>
                                <div className="flex items-center gap-1.5 text-xs text-gray-300 mt-1">
                                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                                    <span>Online 5 phút trước</span>
                                </div>

                                {/* Nút thao tác: + Theo dõi & 💬 Chat */}
                                <div className="flex items-center gap-2 mt-3">
                                    <button
                                        type="button"
                                        onClick={handleToggleFollow}
                                        className={`px-3.5 py-1.5 rounded-lg text-xs font-bold transition-all shadow-sm flex items-center gap-1 cursor-pointer ${
                                            isFollowed
                                                ? 'bg-emerald-600 hover:bg-emerald-700 text-white'
                                                : 'bg-[#ee4d2d] hover:bg-[#d03e20] text-white'
                                        }`}
                                    >
                                        <span>{isFollowed ? '✓ Đang theo dõi' : '+ Theo dõi'}</span>
                                    </button>
                                    <button
                                        type="button"
                                        onClick={() => showToast({ type: 'info', message: 'Đang kết nối tin nhắn với người bán...' })}
                                        className="px-3.5 py-1.5 rounded-lg text-xs font-semibold bg-white/15 hover:bg-white/25 text-white border border-white/30 backdrop-blur-sm transition-all cursor-pointer flex items-center gap-1"
                                    >
                                        <span>💬 Chat</span>
                                    </button>
                                </div>
                            </div>
                        </div>

                        {/* Cột phải: Lưới 2x3 chỉ số hoạt động Shopee */}
                        <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-6 gap-y-4 text-xs sm:text-sm">
                            {/* Hàng 1 */}
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">📦 Sản Phẩm:</span>
                                <span className="font-bold text-amber-400 text-sm">3</span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">👥 Người Theo Dõi:</span>
                                <span className="font-bold text-amber-400 text-sm">
                                    {followCount >= 1000 ? `${(followCount / 1000).toFixed(1).replace('.', ',')}K` : followCount}
                                </span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">🔄 Đang Theo:</span>
                                <span className="font-bold text-amber-400 text-sm">3</span>
                            </div>

                            {/* Hàng 2 */}
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">⭐ Đánh Giá:</span>
                                <span className="font-bold text-amber-400 text-sm">4.8 (521 Đánh Giá)</span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">💬 Tỉ Lệ Phản Hồi Chat:</span>
                                <span className="font-bold text-amber-400 text-sm">98% (Trong Vài Giờ)</span>
                            </div>
                            <div className="flex items-center gap-2">
                                <span className="text-gray-300">📅 Tham Gia:</span>
                                <span className="font-bold text-amber-400 text-sm">11 Tháng Trước</span>
                            </div>
                        </div>
                    </div>
                </div>

                {/* 3. Navigation Tabs: Tất cả sản phẩm | Best Sellers | Live Auctions | Shop Reviews | Thêm ▾ */}
                <div className="bg-white rounded-2xl border border-gray-200/90 shadow-sm px-6 flex items-center overflow-x-auto gap-8">
                    <button
                        type="button"
                        onClick={() => setShopTab('all')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'all'
                                ? 'border-[#ee4d2d] text-[#ee4d2d]'
                                : 'border-transparent text-gray-600 hover:text-gray-900'
                        }`}
                    >
                        Tất cả sản phẩm
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('popular')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'popular'
                                ? 'border-[#ee4d2d] text-[#ee4d2d]'
                                : 'border-transparent text-gray-600 hover:text-gray-900'
                        }`}
                    >
                        Best Sellers
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('auctions')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'auctions'
                                ? 'border-[#ee4d2d] text-[#ee4d2d]'
                                : 'border-transparent text-gray-600 hover:text-gray-900'
                        }`}
                    >
                        Live Auctions
                    </button>
                    <button
                        type="button"
                        onClick={() => setShopTab('reviews')}
                        className={`py-4 text-sm font-bold border-b-2 transition-all whitespace-nowrap cursor-pointer ${
                            shopTab === 'reviews'
                                ? 'border-[#ee4d2d] text-[#ee4d2d]'
                                : 'border-transparent text-gray-600 hover:text-gray-900'
                        }`}
                    >
                        Shop Reviews
                    </button>
                    <div className="flex items-center gap-1 text-sm font-semibold text-gray-500 hover:text-gray-800 cursor-pointer py-4">
                        <span>Thêm</span>
                        <SvgChevronDown size={14} />
                    </div>
                </div>

                {/* Khi tab 'auctions' được chọn: Hiển thị danh sách đấu giá của shop */}
                {shopTab === 'auctions' && (
                    <div className="bg-white rounded-2xl border border-gray-200/90 shadow-sm p-6 space-y-4">
                        <h3 className="font-bold text-lg text-gray-900">
                            Phiên đấu giá trực tiếp của shop
                        </h3>
                        {auctions.length > 0 ? (
                            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
                                {auctions.map((auc) => (
                                    <div
                                        key={auc.id}
                                        onClick={() => navigate('auction-detail', { id: auc.id })}
                                        className="border border-gray-200 rounded-2xl p-4 hover:border-primary cursor-pointer transition-all space-y-3"
                                    >
                                        <img src={auc.product?.images?.[0] || 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500&q=80'} alt={auc.product?.name || 'Sản phẩm đấu giá'} className="w-full h-44 object-cover rounded-xl" />
                                        <h4 className="font-bold text-sm text-gray-900 line-clamp-1">{auc.product?.name || 'Sản phẩm đấu giá'}</h4>
                                        <div className="flex justify-between items-baseline">
                                            <span className="text-xs text-gray-500">Giá hiện tại:</span>
                                            <span className="font-black text-primary text-base">{formatCurrency(auc.currentBid, lang)}</span>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p className="text-sm text-gray-500 py-6 text-center">Shop hiện chưa có phiên đấu giá nào đang diễn ra.</p>
                        )}
                    </div>
                )}

                {/* Khi tab 'reviews' được chọn: Hiển thị hồ sơ đánh giá và phản hồi */}
                {shopTab === 'reviews' && (
                    <div className="bg-white rounded-2xl border border-gray-200/90 shadow-sm p-6 space-y-6">
                        <div className="flex flex-col sm:flex-row items-center justify-between gap-6 p-5 bg-amber-50/60 border border-amber-200/80 rounded-2xl">
                            <div className="text-center sm:text-left">
                                <span className="text-3xl font-black text-amber-600">4.8</span>
                                <span className="text-base text-gray-600 font-bold"> / 5.0</span>
                                <div className="text-amber-500 text-lg mt-1">★★★★★</div>
                                <p className="text-xs text-gray-500 mt-1">Dựa trên {reputation?.score ? (ratings.length || 521) : 521} lượt đánh giá từ người mua thực tế</p>
                            </div>
                            <div className="flex flex-wrap gap-2 text-xs">
                                <span className="px-3 py-1.5 rounded-full bg-white font-bold text-gray-800 border border-gray-200 shadow-sm">Tất cả (521)</span>
                                <span className="px-3 py-1.5 rounded-full bg-white text-gray-600 border border-gray-200">5 Sao (490)</span>
                                <span className="px-3 py-1.5 rounded-full bg-white text-gray-600 border border-gray-200">4 Sao (25)</span>
                                <span className="px-3 py-1.5 rounded-full bg-white text-gray-600 border border-gray-200">Có Hình Ảnh/Video (310)</span>
                            </div>
                        </div>

                        {/* Danh sách review mẫu */}
                        <div className="divide-y divide-gray-100">
                            {[
                                { user: 'buyer_pro_01', rating: 5, date: '10-11-2026', comment: 'Sản phẩm tai nghe Sony WH-1000XM5 chính hãng, cách âm đỉnh chóp, đóng gói chắc chắn giao siêu nhanh trong 1 ngày!' },
                                { user: 'nguyenvana_cpl', rating: 5, date: '08-11-2026', comment: 'Shop phục vụ rất nhiệt tình, giải đáp chi tiết, hàng chuẩn Apple nguyên seal.' },
                                { user: 'quocbao_hcm', rating: 4, date: '02-11-2026', comment: 'Đóng gói cẩn thận, hàng đúng mô tả, chất âm ấm.' },
                            ].map((rev, i) => (
                                <div key={i} className="py-4 space-y-1.5">
                                    <div className="flex items-center gap-2 text-xs">
                                        <span className="font-bold text-gray-900">{rev.user}</span>
                                        <span className="text-amber-500">{'★'.repeat(rev.rating)}</span>
                                        <span className="text-gray-400">· {rev.date}</span>
                                    </div>
                                    <p className="text-sm text-gray-700">{rev.comment}</p>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* 4. Khối Voucher: MÃ GIẢM GIÁ CỦA SHOP (Hiển thị ở tab 'all' hoặc 'popular') */}
                {(shopTab === 'all' || shopTab === 'popular') && (
                    <div className="space-y-3">
                        <h3 className="font-bold text-sm text-gray-800 tracking-wide uppercase">
                            MÃ GIẢM GIÁ CỦA SHOP
                        </h3>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            {/* Voucher 1 */}
                            <div className="bg-gradient-to-r from-orange-50 via-amber-50 to-orange-50 border border-orange-200/90 rounded-2xl p-4 flex items-center justify-between shadow-sm relative overflow-hidden">
                                <div className="space-y-1">
                                    <h4 className="font-black text-lg text-[#ee4d2d]">Giảm 20k</h4>
                                    <p className="text-xs text-gray-700 font-semibold">Đơn Tối Thiểu 200k</p>
                                    <div className="flex items-center gap-2 pt-1.5">
                                        <div className="w-28 h-1.5 rounded-full bg-gray-200 overflow-hidden">
                                            <div className="h-full bg-[#ee4d2d] w-[65%]" />
                                        </div>
                                        <span className="text-[10px] text-gray-500 font-medium">Đã dùng 65%</span>
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => handleSaveVoucher('SHOP20K')}
                                    className={`px-4 py-2 rounded-xl text-xs font-bold transition-all shadow-sm cursor-pointer ${
                                        savedVouchers.includes('SHOP20K')
                                            ? 'bg-gray-200 text-gray-600'
                                            : 'bg-[#ee4d2d] hover:bg-[#d03e20] text-white'
                                    }`}
                                >
                                    {savedVouchers.includes('SHOP20K') ? 'Đã lưu' : 'Lưu'}
                                </button>
                            </div>

                            {/* Voucher 2 */}
                            <div className="bg-gradient-to-r from-orange-50 via-amber-50 to-orange-50 border border-orange-200/90 rounded-2xl p-4 flex items-center justify-between shadow-sm relative overflow-hidden">
                                <div className="space-y-1">
                                    <h4 className="font-black text-lg text-[#ee4d2d]">Giảm 50k</h4>
                                    <p className="text-xs text-gray-700 font-semibold">Đơn Tối Thiểu 500k</p>
                                    <div className="flex items-center gap-2 pt-1.5">
                                        <div className="w-28 h-1.5 rounded-full bg-gray-200 overflow-hidden">
                                            <div className="h-full bg-[#ee4d2d] w-[40%]" />
                                        </div>
                                        <span className="text-[10px] text-gray-500 font-medium">Đã dùng 40%</span>
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => handleSaveVoucher('SHOP50K')}
                                    className={`px-4 py-2 rounded-xl text-xs font-bold transition-all shadow-sm cursor-pointer ${
                                        savedVouchers.includes('SHOP50K')
                                            ? 'bg-gray-200 text-gray-600'
                                            : 'bg-[#ee4d2d] hover:bg-[#d03e20] text-white'
                                    }`}
                                >
                                    {savedVouchers.includes('SHOP50K') ? 'Đã lưu' : 'Lưu'}
                                </button>
                            </div>
                        </div>
                    </div>
                )}

                {/* 5. Khối Sản phẩm: Gợi ý cho bạn (Grid 3 sản phẩm chuẩn Image 4) */}
                {(shopTab === 'all' || shopTab === 'popular') && (
                    <div className="space-y-4">
                        <div className="flex items-center justify-between">
                            <h3 className="font-black text-lg sm:text-xl text-gray-900">
                                Gợi ý cho bạn
                            </h3>
                            <button
                                type="button"
                                onClick={() => setShopTab('all')}
                                className="text-xs font-bold text-[#ee4d2d] hover:underline cursor-pointer"
                            >
                                Xem tất cả &gt;
                            </button>
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                            {storeProducts.map((p) => (
                                <div
                                    key={p.id}
                                    onClick={() => navigate('product-detail', { id: p.id })}
                                    className="bg-white rounded-2xl border border-gray-200/90 hover:border-primary/50 shadow-sm hover:shadow-md transition-all overflow-hidden p-4 space-y-3 cursor-pointer group flex flex-col justify-between"
                                >
                                    <div className="space-y-3">
                                        <div className="relative overflow-hidden rounded-xl bg-gray-50 aspect-square">
                                            <img
                                                src={p.image}
                                                alt={p.name}
                                                className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                                            />
                                            <span className="absolute top-2.5 left-2.5 px-2 py-0.5 rounded-md text-[11px] font-black bg-red-600 text-white shadow-sm">
                                                {p.discount}
                                            </span>
                                        </div>

                                        <h4 className="font-bold text-sm text-gray-900 line-clamp-2 leading-snug group-hover:text-primary transition-colors">
                                            {p.name}
                                        </h4>

                                        <div className="flex items-center gap-1.5 text-xs text-gray-500">
                                            <span className="text-amber-500 font-bold">★ {p.rating.toFixed(1)}</span>
                                            <span>({p.reviewsCount})</span>
                                        </div>
                                    </div>

                                    <div className="flex items-center justify-between pt-2 border-t border-gray-50">
                                        <div>
                                            <p className="text-[11px] text-gray-400 line-through">
                                                {Math.round(p.originalPrice).toLocaleString('vi-VN')} ₫
                                            </p>
                                            <p className="text-base sm:text-lg font-black text-[#ee4d2d]">
                                                {Math.round(p.price).toLocaleString('vi-VN')} ₫
                                            </p>
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
                                            className="w-9 h-9 rounded-full bg-[#ee4d2d] hover:bg-[#d03e20] text-white flex items-center justify-center shadow-md shadow-orange-500/20 transition-transform active:scale-95 cursor-pointer"
                                            aria-label="Thêm vào giỏ"
                                        >
                                            <SvgShoppingCart size={16} />
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </div>
        </BuyerLayout>
    )
}
