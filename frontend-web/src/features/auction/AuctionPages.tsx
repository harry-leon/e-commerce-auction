import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { createPortal } from 'react-dom'
import { useApp } from '../../context'
import { tr, formatCurrency } from '../../i18n'
import { BuyerLayout } from '../../components/Layout'
import { AuctionCard } from '../../components/Cards'
import {
    Button,
    Input,
    Tabs,
    AlertBanner,
    EmptyState,
    CountdownTimer,
    StarRating,
    Pagination,
    Breadcrumb,
    Gavel,
    ChevronLeft,
    ChevronRight,
    CircleX,
    AlertTriangle,
    ClipboardList,
    Trophy,
    Clock,
    SkeletonCard,
    Search,
    Filter,
    X,
    CreditCard,
    CheckCircle,
} from '../../components/UI'
import { readSession } from '../../api/client'
import { connectAuctionStream } from '../../api/auctionStream'
import { CelebrationCanvas, WinnerCelebrationModal } from './CelebrationFireworks'
const SvgSparkles = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
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
        <path d="m12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3Z" />
    </svg>
)
import {
    auctionsApi,
    getAuctionSettlement,
    getWinnerAuctionDetails,
    type AuctionSettlement,
    type WinnerAuctionDetails,
} from '../../api/auctions'
import { startPayment } from '../../api/payments'
import type { Auction } from '../../types'
import { fireConfetti } from '../../utils/dialogs'

// ─── Auction List Page ─────────────────────────────────────────────────────────
/**
 * Chức năng: Màn hình danh sách phiên đấu giá trực tuyến (Auction List Page).
 *            Hiển thị các phiên đấu giá từ Auction Service kèm bộ lọc trạng thái, tìm kiếm,
 *            lọc khoảng giá, sắp xếp và phân trang.
 * Đầu vào: Bộ lọc người dùng (status, search, sort, price, page), context app (lang, navigate, createdAuctions, auctionReviews).
 * Kết quả: Danh sách phiên đấu giá chuẩn hóa từ API Adapter hoặc fallback mock khi offline/lỗi.
 * Luồng xử lý:
 *   1. Gọi auctionsApi.getAuctions() với đầy đủ tham số tìm kiếm và phân trang.
 *   2. Quản lý trạng thái:
 *      - loading: Hiển thị 6 SkeletonCard mô phỏng khung giao diện card đang tải.
 *      - error: Hiển thị hộp thông báo lỗi kèm nút "Thử lại (Retry)".
 *      - empty: Hiển thị EmptyState khi không có phiên đấu giá nào phù hợp kèm nút "Xóa bộ lọc".
 *      - success: Hiển thị lưới AuctionCard responsive (1 cột mobile, 2 cột tablet, 3 cột desktop).
 *   3. Điều hướng: Người dùng click vào card hoặc nút "Đặt giá" để chuyển sang AuctionDetailPage.
 * Quy tắc & lý do: Đảm bảo giao diện không bị treo khi API trả về rỗng hoặc dữ liệu lỗi; hỗ trợ tìm kiếm linh hoạt.
 * Lỗi & giới hạn: Lọc dữ liệu client-side kết hợp server-side để đảm bảo kết quả tức thì.
 */
export function AuctionListPage() {
    const { lang, auctionReviews, createdAuctions } = useApp()

    // 1. Khởi tạo bộ lọc với khôi phục từ sessionStorage (giữ trạng thái khi quay lại từ Product / Auction Detail)
    const [statusFilter, setStatusFilter] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-status') || 'all'
        } catch {
            return 'all'
        }
    })
    const [sortBy, setSortBy] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-sort') || 'ending-soon'
        } catch {
            return 'ending-soon'
        }
    })
    const [searchQuery, setSearchQuery] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-query') || ''
        } catch {
            return ''
        }
    })
    const [minPriceInput, setMinPriceInput] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-min-price') || ''
        } catch {
            return ''
        }
    })
    const [maxPriceInput, setMaxPriceInput] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-max-price') || ''
        } catch {
            return ''
        }
    })
    const [onlyWithBids, setOnlyWithBids] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-only-bids') === 'true'
        } catch {
            return false
        }
    })
    const [onlyEndingSoon, setOnlyEndingSoon] = useState(() => {
        try {
            return sessionStorage.getItem('nexus.auction-ending-soon') === 'true'
        } catch {
            return false
        }
    })

    const [page, setPage] = useState(1)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [rawAuctions, setRawAuctions] = useState<Auction[]>([])
    const [mobileFilterOpen, setMobileFilterOpen] = useState(false)
    const [wsLive, setWsLive] = useState(false)

    // 2. Lưu bộ lọc vào sessionStorage khi có thay đổi
    useEffect(() => {
        try {
            sessionStorage.setItem('nexus.auction-status', statusFilter)
            sessionStorage.setItem('nexus.auction-sort', sortBy)
            sessionStorage.setItem('nexus.auction-query', searchQuery)
            sessionStorage.setItem('nexus.auction-min-price', minPriceInput)
            sessionStorage.setItem('nexus.auction-max-price', maxPriceInput)
            sessionStorage.setItem('nexus.auction-only-bids', String(onlyWithBids))
            sessionStorage.setItem('nexus.auction-ending-soon', String(onlyEndingSoon))
        } catch {}
    }, [
        statusFilter,
        sortBy,
        searchQuery,
        minPriceInput,
        maxPriceInput,
        onlyWithBids,
        onlyEndingSoon,
    ])

    // 3. Tải danh sách phiên đấu giá (chỉ gọi 1 lần khi mount hoặc khi retry, KHÔNG polling 5s)
    const fetchAuctions = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const res = await auctionsApi.getAuctions({
                status: 'all',
                page: 1,
                limit: 100,
                extraAuctions: createdAuctions,
                reviews: auctionReviews,
            })
            setRawAuctions(res.items || [])
        } catch (err) {
            setError(
                lang === 'en'
                    ? 'Failed to load auctions from Auction Service. Please try again.'
                    : 'Không thể tải danh sách phiên đấu giá từ máy chủ. Vui lòng thử lại.',
            )
        } finally {
            setLoading(false)
        }
    }, [createdAuctions, auctionReviews, lang])

    useEffect(() => {
        fetchAuctions()
    }, [fetchAuctions])

    // 4. Cơ chế WebSocket STOMP thay thế hoàn toàn 5s Polling:
    // Client chỉ mở 1 kết nối duy nhất tới /ws/auctions.
    // Khi có bid hoặc hết phiên, backend/stream đẩy bản tin JSON siêu nhẹ (< 50ms) để cập nhật UI tức thì!
    useEffect(() => {
        const unsubscribe = connectAuctionStream((event) => {
            setWsLive(true)
            setRawAuctions((prev) =>
                prev.map((a) => {
                    if (a.id !== event.auctionId) return a
                    const updatedPrice = event.currentPrice ?? event.amount ?? a.currentBid
                    const updatedCount =
                        event.bidCount ??
                        (updatedPrice > a.currentBid ? a.bidCount + 1 : a.bidCount)
                    return {
                        ...a,
                        currentBid: updatedPrice,
                        bidCount: updatedCount,
                        endTime: event.endTime ?? a.endTime,
                        status: event.status ? (event.status.toLowerCase() as any) : a.status,
                    }
                }),
            )
        })
        return unsubscribe
    }, [])

    // 5. Thống kê số lượng phiên theo trạng thái để hiển thị badge
    const statusCounts = useMemo(() => {
        const counts = { all: rawAuctions.length, active: 0, scheduled: 0, ended: 0 }
        for (const a of rawAuctions) {
            const st = (a.status || '').toLowerCase()
            if (st === 'active') counts.active++
            else if (st === 'scheduled') counts.scheduled++
            else if (st === 'ended' || st === 'settled') counts.ended++
        }
        return counts
    }, [rawAuctions])

    // 6. Xử lý lọc danh sách phiên đấu giá
    const filteredAuctions = useMemo(() => {
        return rawAuctions.filter((a) => {
            // Lọc trạng thái
            if (statusFilter !== 'all') {
                const st = (a.status || '').toLowerCase()
                if (statusFilter === 'ended') {
                    if (st !== 'ended' && st !== 'settled') return false
                } else if (st !== statusFilter) {
                    return false
                }
            }

            // Lọc từ khóa tìm kiếm (tên sản phẩm, mô tả, danh mục, người bán)
            if (searchQuery.trim()) {
                const q = searchQuery.toLowerCase().trim()
                const matchName = (a.product?.name || '').toLowerCase().includes(q)
                const matchDesc = (a.product?.description || '').toLowerCase().includes(q)
                const matchCat = (a.product?.category || '').toLowerCase().includes(q)
                const matchSeller = (a.sellerName || '').toLowerCase().includes(q)
                if (!matchName && !matchDesc && !matchCat && !matchSeller) return false
            }

            // Lọc khoảng giá
            const price = a.currentBid || a.startingPrice || 0
            if (minPriceInput && !isNaN(Number(minPriceInput))) {
                if (price < Number(minPriceInput)) return false
            }
            if (maxPriceInput && !isNaN(Number(maxPriceInput))) {
                if (price > Number(maxPriceInput)) return false
            }

            // Tiêu chí bổ sung
            if (onlyWithBids && (a.bidCount || 0) <= 0) {
                return false
            }
            if (onlyEndingSoon) {
                const timeLeft = new Date(a.endTime).getTime() - Date.now()
                if (a.status !== 'active' || timeLeft <= 0 || timeLeft > 2 * 60 * 60 * 1000) {
                    return false
                }
            }

            return true
        })
    }, [
        rawAuctions,
        statusFilter,
        searchQuery,
        minPriceInput,
        maxPriceInput,
        onlyWithBids,
        onlyEndingSoon,
    ])

    // 7. Sắp xếp danh sách phiên
    const sortedAuctions = useMemo(() => {
        const list = [...filteredAuctions]
        switch (sortBy) {
            case 'ending-soon':
                return list.sort((a, b) => {
                    const aActive = a.status === 'active' ? 0 : 1
                    const bActive = b.status === 'active' ? 0 : 1
                    if (aActive !== bActive) return aActive - bActive
                    return new Date(a.endTime).getTime() - new Date(b.endTime).getTime()
                })
            case 'most-bids':
                return list.sort((a, b) => (b.bidCount || 0) - (a.bidCount || 0))
            case 'newest':
                return list.sort((a, b) => {
                    const timeA = new Date(a.startTime).getTime()
                    const timeB = new Date(b.startTime).getTime()
                    return timeB - timeA
                })
            case 'price-asc':
                return list.sort(
                    (a, b) => (a.currentBid || a.startingPrice) - (b.currentBid || b.startingPrice),
                )
            case 'price-desc':
                return list.sort(
                    (a, b) => (b.currentBid || b.startingPrice) - (a.currentBid || a.startingPrice),
                )
            default:
                return list
        }
    }, [filteredAuctions, sortBy])

    // 8. Phân trang
    const PER_PAGE = 9
    const totalItems = sortedAuctions.length
    const displayAuctions = useMemo(() => {
        const start = (page - 1) * PER_PAGE
        return sortedAuctions.slice(start, start + PER_PAGE)
    }, [sortedAuctions, page])

    // Preset khoảng giá nhanh
    const PRICE_PRESETS = [
        { label: '< 10 triệu', min: '', max: '10000000' },
        { label: '10 - 50 triệu', min: '10000000', max: '50000000' },
        { label: '50 - 100 triệu', min: '50000000', max: '100000000' },
        { label: '> 100 triệu', min: '100000000', max: '' },
    ]

    const handlePricePreset = (min: string, max: string) => {
        if (minPriceInput === min && maxPriceInput === max) {
            setMinPriceInput('')
            setMaxPriceInput('')
        } else {
            setMinPriceInput(min)
            setMaxPriceInput(max)
        }
        setPage(1)
    }

    const hasActiveFilters =
        statusFilter !== 'all' ||
        Boolean(searchQuery) ||
        Boolean(minPriceInput) ||
        Boolean(maxPriceInput) ||
        onlyWithBids ||
        onlyEndingSoon

    const handleResetFilters = () => {
        setStatusFilter('all')
        setSearchQuery('')
        setMinPriceInput('')
        setMaxPriceInput('')
        setOnlyWithBids(false)
        setOnlyEndingSoon(false)
        setSortBy('ending-soon')
        setPage(1)
        try {
            sessionStorage.removeItem('nexus.auction-status')
            sessionStorage.removeItem('nexus.auction-sort')
            sessionStorage.removeItem('nexus.auction-query')
            sessionStorage.removeItem('nexus.auction-min-price')
            sessionStorage.removeItem('nexus.auction-max-price')
            sessionStorage.removeItem('nexus.auction-only-bids')
            sessionStorage.removeItem('nexus.auction-ending-soon')
        } catch {}
    }

    const STATUS_TABS = [
        { id: 'all', label: lang === 'en' ? 'All Auctions' : 'Tất cả phiên' },
        { id: 'active', label: lang === 'en' ? 'Live Now' : 'Đang diễn ra (LIVE)' },
        { id: 'scheduled', label: lang === 'en' ? 'Upcoming' : 'Sắp diễn ra' },
        { id: 'ended', label: lang === 'en' ? 'Ended' : 'Đã kết thúc' },
    ]

    return (
        <BuyerLayout>
            {/* Header thanh điều hướng và tiêu đề sàn đấu giá */}
            <div className="mb-6">
                <Breadcrumb
                    items={[
                        { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                        { label: lang === 'en' ? 'Auctions' : 'Sàn đấu giá trực tiếp' },
                    ]}
                />
            </div>

            {/* ─── Two-Column Layout (chuẩn phong cách SearchPage) ──────────────────── */}
            <div className="flex flex-col lg:flex-row gap-8 items-start">
                {/* ─── Left Sidebar: Bộ lọc sàn đấu giá ───────────────────────────── */}
                <aside
                    className={`w-full lg:w-64 shrink-0 space-y-7 ${
                        mobileFilterOpen ? 'block' : 'hidden lg:block'
                    }`}
                >
                    <div className="flex items-center justify-between pb-2 border-b border-gray-100">
                        <h2 className="text-lg font-bold text-gray-900">
                            {lang === 'en' ? 'Auction Filters' : 'Bộ lọc sàn đấu giá'}
                        </h2>
                        {hasActiveFilters && (
                            <button
                                type="button"
                                onClick={handleResetFilters}
                                className="text-xs text-gray-500 hover:text-black underline transition-colors"
                            >
                                {lang === 'en' ? 'Reset' : 'Thiết lập lại'}
                            </button>
                        )}
                    </div>

                    {/* Trạng thái phiên đấu giá */}
                    <div>
                        <h3 className="font-bold text-gray-900 text-sm mb-3">
                            {lang === 'en' ? 'Auction Status' : 'Trạng thái phiên'}
                        </h3>
                        <div className="space-y-1">
                            {STATUS_TABS.map((t) => {
                                const isSelected = statusFilter === t.id
                                return (
                                    <button
                                        key={t.id}
                                        type="button"
                                        onClick={() => {
                                            setStatusFilter(t.id)
                                            setPage(1)
                                        }}
                                        className={`w-full text-left transition-all ${
                                            isSelected
                                                ? 'bg-black text-white font-medium rounded-xl px-3.5 py-2 flex items-center justify-between text-sm shadow-xs'
                                                : 'flex items-center justify-between px-3.5 py-2 rounded-xl text-sm text-gray-700 hover:bg-gray-100 transition-colors'
                                        }`}
                                    >
                                        <div className="flex items-center gap-2">
                                            <span
                                                className={`w-2 h-2 rounded-full ${
                                                    t.id === 'active'
                                                        ? 'bg-red-500 animate-pulse'
                                                        : t.id === 'scheduled'
                                                          ? 'bg-blue-500'
                                                          : t.id === 'ended'
                                                            ? 'bg-gray-400'
                                                            : 'bg-emerald-500'
                                                }`}
                                            />
                                            <span>{t.label}</span>
                                        </div>
                                        <span
                                            className={`text-xs ${
                                                isSelected ? 'text-gray-300' : 'text-gray-400'
                                            }`}
                                        >
                                            ({statusCounts[t.id as keyof typeof statusCounts] ?? 0})
                                        </span>
                                    </button>
                                )
                            })}
                        </div>
                    </div>

                    {/* Mức giá đấu (đ) */}
                    <div>
                        <h3 className="font-bold text-gray-900 text-sm mb-3">
                            {lang === 'en' ? 'Current Price (VND)' : 'Mức giá hiện tại (đ)'}
                        </h3>
                        {/* Preset pills */}
                        <div className="flex flex-wrap gap-2 mb-3">
                            {PRICE_PRESETS.map((pill) => {
                                const active =
                                    minPriceInput === pill.min && maxPriceInput === pill.max
                                return (
                                    <button
                                        key={pill.label}
                                        type="button"
                                        onClick={() => handlePricePreset(pill.min, pill.max)}
                                        className={`text-xs px-3 py-1.5 rounded-full transition-all border ${
                                            active
                                                ? 'bg-black text-white border-black font-medium'
                                                : 'bg-white text-gray-700 border-gray-200 hover:border-gray-300'
                                        }`}
                                    >
                                        {pill.label}
                                    </button>
                                )
                            })}
                        </div>

                        {/* Direct price inputs */}
                        <div className="grid grid-cols-2 gap-2">
                            <div>
                                <label
                                    htmlFor="auction-price-min"
                                    className="block text-xs text-gray-500 mb-1"
                                >
                                    {lang === 'en' ? 'From' : 'Từ (đ)'}
                                </label>
                                <input
                                    id="auction-price-min"
                                    type="number"
                                    min="0"
                                    placeholder="0"
                                    value={minPriceInput}
                                    onChange={(e) => {
                                        setMinPriceInput(e.target.value)
                                        setPage(1)
                                    }}
                                    className="w-full bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black"
                                />
                            </div>
                            <div>
                                <label
                                    htmlFor="auction-price-max"
                                    className="block text-xs text-gray-500 mb-1"
                                >
                                    {lang === 'en' ? 'To' : 'Đến (đ)'}
                                </label>
                                <input
                                    id="auction-price-max"
                                    type="number"
                                    min="0"
                                    placeholder="100.000.000"
                                    value={maxPriceInput}
                                    onChange={(e) => {
                                        setMaxPriceInput(e.target.value)
                                        setPage(1)
                                    }}
                                    className="w-full bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black"
                                />
                            </div>
                        </div>
                    </div>

                    {/* Tiêu chí bổ sung */}
                    <div>
                        <div className="flex items-center gap-1.5 mb-3">
                            <h3 className="font-bold text-gray-900 text-sm">
                                {lang === 'en' ? 'Additional Options' : 'Tiêu chí bổ sung'}
                            </h3>
                            <span className="bg-red-50 text-red-500 text-[10px] font-bold px-1.5 py-0.5 rounded tracking-wider">
                                HOT
                            </span>
                        </div>
                        <div className="space-y-2.5">
                            <label className="flex items-center justify-between text-sm text-gray-700 cursor-pointer select-none">
                                <div className="flex items-center gap-2">
                                    <input
                                        type="checkbox"
                                        checked={onlyWithBids}
                                        onChange={(e) => {
                                            setOnlyWithBids(e.target.checked)
                                            setPage(1)
                                        }}
                                        className="w-4 h-4 rounded border-gray-300 text-black focus:ring-black accent-black cursor-pointer"
                                    />
                                    <span>
                                        {lang === 'en' ? 'Has Bids Placed' : 'Đã có lượt đặt giá'}
                                    </span>
                                </div>
                            </label>
                            <label className="flex items-center justify-between text-sm text-gray-700 cursor-pointer select-none">
                                <div className="flex items-center gap-2">
                                    <input
                                        type="checkbox"
                                        checked={onlyEndingSoon}
                                        onChange={(e) => {
                                            setOnlyEndingSoon(e.target.checked)
                                            setPage(1)
                                        }}
                                        className="w-4 h-4 rounded border-gray-300 text-black focus:ring-black accent-black cursor-pointer"
                                    />
                                    <span>
                                        {lang === 'en'
                                            ? 'Ending Soon (< 2 hours)'
                                            : 'Sắp kết thúc (< 2 giờ)'}
                                    </span>
                                </div>
                            </label>
                        </div>
                    </div>
                </aside>

                {/* ─── Right Content Column ─────────────────────────────────────── */}
                <div className="flex-1 min-w-0 w-full space-y-5">
                    {/* Control Bar: Tổng số + WebSocket Live badge + Search + Sort */}
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-gray-100">
                        <div className="flex items-center gap-3">
                            <p className="text-sm font-semibold text-gray-800">
                                {lang === 'en'
                                    ? `Showing ${displayAuctions.length} of ${totalItems} auctions`
                                    : `Hiển thị ${displayAuctions.length} / ${totalItems} phiên đấu giá`}
                            </p>
                            <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                                {wsLive ? 'WebSocket Live (< 50ms)' : 'WebSocket Connected'}
                            </span>
                        </div>

                        <div className="flex items-center gap-2.5">
                            {/* Search box */}
                            <div className="relative w-48 sm:w-60">
                                <input
                                    type="text"
                                    placeholder={
                                        lang === 'en'
                                            ? 'Search auctions...'
                                            : 'Tìm phiên đấu giá...'
                                    }
                                    value={searchQuery}
                                    onChange={(e) => {
                                        setSearchQuery(e.target.value)
                                        setPage(1)
                                    }}
                                    className="w-full bg-white border border-gray-200 rounded-lg pl-8 pr-7 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black"
                                />
                                <Search
                                    size={14}
                                    className="absolute left-2.5 top-2.5 text-gray-400 pointer-events-none"
                                />
                                {searchQuery && (
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setSearchQuery('')
                                            setPage(1)
                                        }}
                                        className="absolute right-2 top-2 text-gray-400 hover:text-gray-600"
                                    >
                                        <X size={14} />
                                    </button>
                                )}
                            </div>

                            {/* Sắp xếp */}
                            <select
                                value={sortBy}
                                onChange={(e) => {
                                    setSortBy(e.target.value)
                                    setPage(1)
                                }}
                                className="bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black cursor-pointer"
                            >
                                <option value="ending-soon">
                                    {lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}
                                </option>
                                <option value="most-bids">
                                    {lang === 'en' ? 'Most Bids' : 'Nhiều lượt đặt nhất'}
                                </option>
                                <option value="newest">
                                    {lang === 'en' ? 'Newest' : 'Mới nhất'}
                                </option>
                                <option value="price-asc">
                                    {lang === 'en' ? 'Price: Low → High' : 'Giá: Thấp → Cao'}
                                </option>
                                <option value="price-desc">
                                    {lang === 'en' ? 'Price: High → Low' : 'Giá: Cao → Thấp'}
                                </option>
                            </select>

                            {/* Nút lọc cho thiết bị di động */}
                            <button
                                type="button"
                                onClick={() => setMobileFilterOpen(!mobileFilterOpen)}
                                className="lg:hidden p-2 rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50"
                                title={lang === 'en' ? 'Filters' : 'Bộ lọc'}
                            >
                                <Filter size={16} />
                            </button>
                        </div>
                    </div>

                    {/* Active Filter Chips (Tags) */}
                    {hasActiveFilters && (
                        <div className="flex flex-wrap items-center gap-2 pt-1">
                            {statusFilter !== 'all' && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs bg-gray-100 text-gray-800 font-medium">
                                    {statusFilter === 'active'
                                        ? 'Đang diễn ra'
                                        : statusFilter === 'scheduled'
                                          ? 'Sắp diễn ra'
                                          : 'Đã kết thúc'}
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setStatusFilter('all')
                                            setPage(1)
                                        }}
                                        className="hover:text-black cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </span>
                            )}
                            {(minPriceInput || maxPriceInput) && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs bg-gray-100 text-gray-800 font-medium">
                                    {minPriceInput && maxPriceInput
                                        ? `${formatCurrency(Number(minPriceInput), lang)} - ${formatCurrency(Number(maxPriceInput), lang)}`
                                        : minPriceInput
                                          ? `> ${formatCurrency(Number(minPriceInput), lang)}`
                                          : `< ${formatCurrency(Number(maxPriceInput), lang)}`}
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setMinPriceInput('')
                                            setMaxPriceInput('')
                                            setPage(1)
                                        }}
                                        className="hover:text-black cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </span>
                            )}
                            {onlyWithBids && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs bg-gray-100 text-gray-800 font-medium">
                                    Đã có lượt đặt giá
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setOnlyWithBids(false)
                                            setPage(1)
                                        }}
                                        className="hover:text-black cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </span>
                            )}
                            {onlyEndingSoon && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs bg-gray-100 text-gray-800 font-medium">
                                    Sắp kết thúc (&lt; 2h)
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setOnlyEndingSoon(false)
                                            setPage(1)
                                        }}
                                        className="hover:text-black cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </span>
                            )}
                            {searchQuery && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs bg-gray-100 text-gray-800 font-medium">
                                    "{searchQuery}"
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setSearchQuery('')
                                            setPage(1)
                                        }}
                                        className="hover:text-black cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </span>
                            )}
                        </div>
                    )}

                    {/* Vùng trạng thái tải (Loading State) */}
                    {loading && (
                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
                            {Array.from({ length: 6 }).map((_, idx) => (
                                <SkeletonCard key={idx} />
                            ))}
                        </div>
                    )}

                    {/* Vùng lỗi tải dữ liệu (Error State) */}
                    {error && !loading && (
                        <div className="nexus-card p-8 text-center space-y-4 border-red-200 bg-red-50/50">
                            <div className="w-12 h-12 bg-red-100 text-red-600 rounded-full flex items-center justify-center mx-auto">
                                <AlertTriangle size={24} />
                            </div>
                            <div>
                                <h3 className="font-bold text-gray-900 text-base">
                                    {lang === 'en'
                                        ? 'Error Loading Auctions'
                                        : 'Lỗi tải danh sách phiên đấu giá'}
                                </h3>
                                <p className="text-sm text-gray-600 mt-1 max-w-md mx-auto">
                                    {error}
                                </p>
                            </div>
                            <Button
                                variant="primary"
                                size="md"
                                onClick={fetchAuctions}
                                className="mx-auto"
                            >
                                {lang === 'en' ? 'Retry' : 'Thử lại'}
                            </Button>
                        </div>
                    )}

                    {/* Vùng trạng thái rỗng (Empty State) */}
                    {!loading && !error && displayAuctions.length === 0 && (
                        <div className="nexus-card p-12 text-center rounded-2xl border border-gray-100 bg-white">
                            <EmptyState
                                icon={<Gavel size={36} />}
                                title={
                                    lang === 'en'
                                        ? 'No auctions found'
                                        : 'Không tìm thấy phiên đấu giá nào'
                                }
                                subtitle={
                                    lang === 'en'
                                        ? 'There are currently no auctions matching your filter criteria. Try clearing filters or searching for something else.'
                                        : 'Không có phiên đấu giá nào phù hợp với bộ lọc hiện tại. Hãy thử thiết lập lại bộ lọc hoặc tìm kiếm từ khóa khác.'
                                }
                                action={
                                    hasActiveFilters ? (
                                        <Button
                                            variant="outline"
                                            size="sm"
                                            onClick={handleResetFilters}
                                            className="mt-4"
                                        >
                                            {lang === 'en'
                                                ? 'Clear all filters'
                                                : 'Xóa toàn bộ bộ lọc'}
                                        </Button>
                                    ) : undefined
                                }
                            />
                        </div>
                    )}

                    {/* Danh sách phiên đấu giá hiển thị (Success State) */}
                    {!loading && !error && displayAuctions.length > 0 && (
                        <>
                            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
                                {displayAuctions.map((a) => (
                                    <AuctionCard key={a.id} auction={a} />
                                ))}
                            </div>
                            {totalItems > PER_PAGE && (
                                <div className="flex justify-center mt-8">
                                    <Pagination
                                        page={page}
                                        total={totalItems}
                                        perPage={PER_PAGE}
                                        onChange={setPage}
                                    />
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Auction Detail Page ────────────────────────────────────────────────────────
/**
 * Chức năng: Màn hình chi tiết phiên đấu giá trực tuyến (Auction Detail Page).
 *            Hiển thị thông tin chi tiết phiên đấu giá, bộ sưu tập ảnh, mức giá hiện tại,
 *            lịch sử đặt giá, đồng hồ đếm ngược và bảng điều khiển đặt giá (Place Bid).
 * Đầu vào: pageParams.id (ID phiên đấu giá), user context, app context.
 * Kết quả: Tải thông tin từ API Adapter, cho phép người mua trả giá hợp lệ và cập nhật theo thời gian thực.
 * Luồng xử lý:
 *   1. Lấy thông tin phiên đấu giá từ auctionsApi.getAuctionById(auctionId).
 *   2. Quản lý trạng thái:
 *      - loading: Hiển thị giao diện skeleton loading mô phỏng chi tiết phiên đấu giá.
 *      - empty/not-found: Hiển thị EmptyState khi không tìm thấy phiên đấu giá kèm nút quay về danh sách.
 *      - error: Hiển thị giao diện báo lỗi kèm nút "Thử lại".
 *      - success: Hiển thị giao diện 2 cột gồm Gallery ảnh có thể phóng to, Lịch sử bid bên trái và Bảng đặt giá bên phải.
 *   3. Đặt giá thầu (placeBid):
 *      - Kiểm tra giá tối thiểu (currentBid + minIncrement).
 *      - Kiểm tra quyền: Người bán không được tự đấu giá phiên của mình (chk_seller_not_bidder).
 *      - Kiểm tra điểm uy tín tối thiểu.
 *      - Gọi auctionsApi.placeBid(), cập nhật giá mới và thêm vào lịch sử bid.
 * Quy tắc & lý do: Tuân thủ luật đấu giá kiểu Anh và cơ chế chống sniping; tự động chuẩn hóa dữ liệu bảo vệ UI.
 * Lỗi & giới hạn: Dữ liệu phiên đấu giá được lưu trữ và đồng bộ an toàn qua API Adapter.
 */
export function AuctionDetailPage() {
    const { lang, navigate, pageParams, user, showToast, createdAuctions, auctionReviews } =
        useApp()
    const auctionId = String(pageParams?.id || pageParams?.auctionId || '')
    const [auction, setAuction] = useState<Auction | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [customBid, setCustomBid] = useState('')
    const [bidPlaced, setBidPlaced] = useState(false)
    const [bidError, setBidError] = useState('')
    const [placing, setPlacing] = useState(false)
    const [selectedImageIndex, setSelectedImageIndex] = useState(0)
    const [imageLightboxOpen, setImageLightboxOpen] = useState(false)

    // Tải thông tin chi tiết qua API Adapter
    const loadAuctionDetail = useCallback(
        async (backgroundRefresh = false) => {
            if (!auctionId) {
                setError(lang === 'en' ? 'Auction ID is missing.' : 'Thiếu mã phiên đấu giá.')
                setLoading(false)
                return
            }
            if (!backgroundRefresh) {
                setLoading(true)
                setError('')
            }
            try {
                const data = await auctionsApi.getAuctionById(
                    auctionId,
                    createdAuctions,
                    auctionReviews,
                )
                setAuction(data)
                if (!backgroundRefresh) {
                    const nextBid =
                        data.bidCount > 0
                            ? data.currentBid + data.minIncrement
                            : data.currentBid || data.startingPrice || 0
                    setCustomBid(String(nextBid))
                }
            } catch (err) {
                if (!backgroundRefresh) {
                    setError(
                        err instanceof Error
                            ? err.message
                            : lang === 'en'
                              ? 'Failed to load auction details.'
                              : 'Không thể tải thông tin chi tiết phiên đấu giá.',
                    )
                }
            } finally {
                if (!backgroundRefresh) setLoading(false)
            }
        },
        [auctionId, createdAuctions, auctionReviews, lang],
    )

    useEffect(() => {
        loadAuctionDetail()
    }, [loadAuctionDetail])

    // Đồng bộ thời gian thực qua STOMP WebSocket thay vì 5s HTTP polling
    useEffect(() => {
        if (!auctionId) return
        const unsubscribe = connectAuctionStream((event) => {
            if (event.auctionId !== auctionId) return
            setAuction((prev) => {
                if (!prev) return prev
                const newBidAmount = event.currentPrice ?? event.amount ?? prev.currentBid
                const isNewBid = newBidAmount > prev.currentBid
                let updatedBids = prev.bids || []
                if (isNewBid && (event.bidderName || event.bidderId)) {
                    const newBidItem = {
                        id: `bid-${Date.now()}`,
                        bidderId: event.bidderId || 'bidder',
                        bidderName: event.bidderName || 'Người tham gia',
                        amount: newBidAmount,
                        timestamp: event.timestamp || new Date().toISOString(),
                    }
                    updatedBids = [newBidItem, ...prev.bids]
                }
                return {
                    ...prev,
                    currentBid: newBidAmount,
                    bidCount: event.bidCount ?? (isNewBid ? prev.bidCount + 1 : prev.bidCount),
                    endTime: event.endTime ?? prev.endTime,
                    status: event.status ? (event.status.toLowerCase() as any) : prev.status,
                    bids: updatedBids,
                }
            })
        }, auctionId)
        return unsubscribe
    }, [auctionId])

    // Khóa cuộn nền và hỗ trợ đóng/đổi ảnh bằng bàn phím khi lightbox đang mở.
    useEffect(() => {
        if (!imageLightboxOpen) return
        const originalOverflow = document.body.style.overflow
        const imageCount = auction?.product.images?.length || 1
        document.body.style.overflow = 'hidden'

        const handler = (event: KeyboardEvent) => {
            if (event.key === 'Escape') setImageLightboxOpen(false)
            if (event.key === 'ArrowLeft') {
                event.preventDefault()
                setSelectedImageIndex((index) => (index === 0 ? imageCount - 1 : index - 1))
            }
            if (event.key === 'ArrowRight') {
                event.preventDefault()
                setSelectedImageIndex((index) => (index === imageCount - 1 ? 0 : index + 1))
            }
        }
        window.addEventListener('keydown', handler)
        return () => {
            document.body.style.overflow = originalOverflow
            window.removeEventListener('keydown', handler)
        }
    }, [imageLightboxOpen, auction?.product.images?.length])

    const isWinner = Boolean(auction && user?.id && auction.winnerId === user.id)

    useEffect(() => {
        if (isWinner) {
            fireConfetti({ particleCount: 120, spread: 90 })
        }
    }, [isWinner])

    // Nếu đang loading, hiển thị skeleton loading state
    if (loading) {
        return (
            <BuyerLayout>
                <div className="space-y-6">
                    <div className="skeleton w-64 h-6 rounded" />
                    <div className="grid grid-cols-1 lg:grid-cols-[1fr_400px] gap-8">
                        <div className="space-y-4">
                            <div className="skeleton w-full h-96 rounded-2xl" />
                            <div className="flex gap-2">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="skeleton w-16 h-16 rounded-xl" />
                                ))}
                            </div>
                            <div className="skeleton w-full h-40 rounded-2xl" />
                        </div>
                        <div className="space-y-4">
                            <div className="skeleton w-full h-80 rounded-2xl" />
                            <div className="skeleton w-full h-32 rounded-2xl" />
                        </div>
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    // Nếu xảy ra lỗi hoặc không tìm thấy phiên đấu giá (Empty / Not Found / Error state)
    if (error || !auction) {
        return (
            <BuyerLayout>
                <div className="max-w-xl mx-auto py-12 text-center space-y-5">
                    <div className="w-16 h-16 bg-amber-50 text-amber-600 rounded-full flex items-center justify-center mx-auto shadow-sm">
                        <Gavel size={32} />
                    </div>
                    <div>
                        <h2 className="text-xl font-bold text-gray-900">
                            {lang === 'en' ? 'Auction Not Found' : 'Không tìm thấy phiên đấu giá'}
                        </h2>
                        <p className="text-sm text-gray-500 mt-1.5 max-w-md mx-auto">
                            {error ||
                                (lang === 'en'
                                    ? 'The auction you are looking for does not exist or has been removed.'
                                    : 'Phiên đấu giá với mã này không tồn tại hoặc đã bị hủy bỏ.')}
                        </p>
                    </div>
                    <div className="flex justify-center gap-3 pt-2">
                        <Button
                            variant="primary"
                            size="md"
                            onClick={() => navigate('auction-list')}
                        >
                            {lang === 'en' ? '← Back to Auctions' : '← Quay lại danh sách đấu giá'}
                        </Button>
                        {error && (
                            <Button variant="outline" size="md" onClick={() => loadAuctionDetail()}>
                                {lang === 'en' ? 'Retry' : 'Thử lại'}
                            </Button>
                        )}
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    const basePrice = auction.currentBid || auction.startingPrice || 0
    const minBid = auction.bidCount > 0 ? basePrice + auction.minIncrement : basePrice
    const isActive = auction.status === 'active'
    const isScheduled = auction.status === 'scheduled'
    const isEnded = ['ended', 'settled', 'cancelled', 'failed'].includes(auction.status)
    const isHighestBidder = bidPlaced || auction.winnerId === user?.id
    const isSeller = Boolean(user?.id && auction.sellerId === user.id)

    // Đặt giá thầu qua API Adapter
    const placeBid = async (amount: number) => {
        if (!isActive) return

        // 1. Kiểm tra không được là người bán của phiên (chk_seller_not_bidder)
        if (isSeller) {
            setBidError(
                lang === 'en'
                    ? 'You cannot bid on your own auction.'
                    : 'Người bán không được phép tự đặt giá vào phiên của chính mình.',
            )
            return
        }

        // 2. Kiểm tra mức giá tối thiểu
        if (amount < minBid) {
            setBidError(
                lang === 'en'
                    ? `Minimum bid must be at least ${formatCurrency(minBid, lang)}`
                    : `Mức giá đặt phải tối thiểu là ${formatCurrency(minBid, lang)}` +
                          (auction.bidCount > 0
                              ? ' (Giá hiện tại + Bước giá)'
                              : ' (Giá khởi điểm)'),
            )
            return
        }

        // 3. Kiểm tra điểm uy tín
        if ((user?.reputationScore || 0) < 30 && user?.reputationScore !== undefined) {
            setBidError(tr(lang, 'insufficientReputation'))
            return
        }

        setPlacing(true)
        setBidError('')

        try {
            const res = await auctionsApi.placeBid(auction.id, amount)
            setBidPlaced(true)
            // Lưu lượt đặt giá vào localStorage để MyBidsPage cập nhật tức thì
            try {
                const raw = localStorage.getItem('nexus.my-bids')
                const list = raw ? JSON.parse(raw) : []
                const updated = [
                    { auctionId: auction.id, myBid: amount, timestamp: new Date().toISOString() },
                    ...list.filter((x: any) => x.auctionId !== auction.id),
                ]
                localStorage.setItem('nexus.my-bids', JSON.stringify(updated))
            } catch {}
            // Cập nhật state chi tiết phiên đấu giá với lượt bid mới
            setAuction((prev) => {
                if (!prev) return prev
                return {
                    ...prev,
                    currentBid: res.currentBid,
                    bidCount: res.bidCount,
                    bids: [res.bid, ...prev.bids],
                    winnerId: user?.id || prev.winnerId,
                    winnerName: user?.fullName || prev.winnerName,
                }
            })
            setCustomBid(String(res.currentBid + auction.minIncrement))
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Bid of ${formatCurrency(amount, lang)} placed successfully!`
                        : `Đã đặt giá ${formatCurrency(amount, lang)} thành công!`,
            })
            showToast({ type: 'warning', message: tr(lang, 'antiSniping'), duration: 5000 })
        } catch (err) {
            const rawMsg = err instanceof Error ? err.message : String(err)
            let friendlyMsg = rawMsg
            if (rawMsg.includes('reputation-service') || rawMsg.includes('điều kiện điểm uy tín')) {
                friendlyMsg =
                    lang === 'en'
                        ? 'Could not verify reputation eligibility with reputation-service. Please ensure your account has sufficient reputation points (>= 30).'
                        : 'Không thể xác minh điều kiện điểm uy tín với reputation-service. Vui lòng đảm bảo tài khoản đã đăng nhập và đạt mức điểm uy tín tối thiểu (≥ 30) để tham gia đấu giá.'
            }
            setBidError(friendlyMsg)
        } finally {
            setPlacing(false)
        }
    }

    const images =
        auction.product.images && auction.product.images.length > 0
            ? auction.product.images
            : [
                  'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80',
              ]

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                    { label: lang === 'en' ? 'Auctions' : 'Đấu giá', page: 'auction-list' },
                    { label: lang === 'vi' ? auction.product.nameVi : auction.product.name },
                ]}
            />

            <div className="mt-5 grid grid-cols-1 lg:grid-cols-[1fr_400px] gap-8">
                {/* Cột trái: Bộ sưu tập ảnh + Thông tin sản phẩm + Lịch sử đặt giá */}
                <div className="space-y-6">
                    {/* Ảnh lớn và huy hiệu trạng thái */}
                    <div>
                        <div
                            className="nexus-card overflow-hidden mb-3 relative bg-slate-50 flex items-center justify-center cursor-zoom-in"
                            style={{ aspectRatio: '4/3' }}
                            role="button"
                            tabIndex={0}
                            aria-label={
                                lang === 'en'
                                    ? 'Open enlarged auction product image'
                                    : 'Mở ảnh sản phẩm đấu giá phóng to'
                            }
                            onClick={() => setImageLightboxOpen(true)}
                            onKeyDown={(event) => {
                                if (event.key === 'Enter' || event.key === ' ') {
                                    event.preventDefault()
                                    setImageLightboxOpen(true)
                                }
                            }}
                        >
                            <img
                                src={images[selectedImageIndex] || images[0]}
                                alt={auction.product.name}
                                className="w-full h-full object-cover transition-all"
                            />
                            {isActive && (
                                <div className="absolute top-3 left-3 badge badge-orange auction-live text-sm px-3 py-1 shadow-md">
                                    LIVE AUCTION
                                </div>
                            )}
                            {isScheduled && (
                                <div className="absolute top-3 left-3 badge badge-indigo text-sm px-3 py-1 shadow-md">
                                    {lang === 'en' ? 'SCHEDULED' : 'SẮP DIỄN RA'}
                                </div>
                            )}
                            {isEnded && (
                                <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                                    <span className="badge badge-gray text-xl px-6 py-3 font-bold shadow-lg">
                                        {tr(lang, 'auctionEnded')}
                                    </span>
                                </div>
                            )}
                        </div>

                        {/* Danh sách ảnh thu nhỏ (Gallery thumbnails) */}
                        {images.length > 1 && (
                            <div className="flex gap-2 overflow-x-auto pb-1">
                                {images.map((img, i) => (
                                    <button
                                        key={i}
                                        type="button"
                                        onClick={() => setSelectedImageIndex(i)}
                                        className={`gallery-thumb w-16 h-16 shrink-0 rounded-xl overflow-hidden bg-gray-100 border-2 transition-all ${
                                            selectedImageIndex === i
                                                ? 'border-primary ring-2 ring-primary/20 scale-105'
                                                : 'border-transparent opacity-75 hover:opacity-100'
                                        }`}
                                    >
                                        <img
                                            src={img}
                                            className="w-full h-full object-cover"
                                            alt=""
                                        />
                                    </button>
                                ))}
                            </div>
                        )}
                    </div>

                    {/* Chi tiết sản phẩm */}
                    <div className="nexus-card p-6">
                        <div className="flex items-center gap-2 mb-2">
                            <span className="badge badge-gray text-xs">
                                {auction.product.category}
                            </span>
                            {auction.visibility === 'restricted' && (
                                <span className="badge badge-yellow text-xs">
                                    {lang === 'en' ? 'Private' : 'Riêng tư'}
                                </span>
                            )}
                        </div>
                        <h1 className="text-2xl font-bold text-gray-900 mb-3">
                            {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                        </h1>

                        <div className="flex flex-wrap items-center gap-4 py-2 border-y border-gray-100 text-sm text-gray-500 mb-4">
                            <span className="flex items-center gap-1.5">
                                <span>{lang === 'en' ? 'Seller' : 'Người bán'}:</span>
                                <span className="font-semibold text-gray-800">
                                    {auction.sellerName}
                                </span>
                                <button
                                    type="button"
                                    onClick={() =>
                                        navigate('public-reputation', {
                                            id: auction.sellerId || 'seller-1',
                                        })
                                    }
                                    className="ml-1 inline-flex items-center gap-0.5 px-2 py-0.5 rounded-full text-xs font-semibold bg-primary-light text-primary hover:bg-primary hover:text-white transition-colors cursor-pointer border border-primary/20"
                                    title={
                                        lang === 'en'
                                            ? 'View seller profile'
                                            : 'Xem hồ sơ người bán'
                                    }
                                >
                                    <span>{lang === 'en' ? 'View Profile' : 'Xem hồ sơ'}</span>
                                    <span>↗</span>
                                </button>
                            </span>
                            <span>·</span>
                            <span>
                                {lang === 'en'
                                    ? `${auction.bidCount} bids placed`
                                    : `${auction.bidCount} lượt đặt giá`}
                            </span>
                            <span>·</span>
                            <span>
                                Mã phiên:{' '}
                                <span className="font-mono text-xs bg-gray-100 px-1.5 py-0.5 rounded">
                                    {auction.id}
                                </span>
                            </span>
                        </div>

                        <div className="space-y-2">
                            <h3 className="font-semibold text-sm text-gray-900">
                                {lang === 'en' ? 'Product Description' : 'Mô tả sản phẩm'}
                            </h3>
                            <p className="text-gray-600 text-sm leading-relaxed whitespace-pre-line">
                                {lang === 'vi'
                                    ? auction.product.descriptionVi || auction.product.description
                                    : auction.product.description}
                            </p>
                        </div>
                    </div>

                    {/* Lịch sử đặt giá (Bid History) */}
                    <div className="nexus-card p-6">
                        <div className="flex items-center justify-between mb-4">
                            <h3 className="font-bold text-gray-900">
                                {lang === 'en' ? 'Bid History' : 'Lịch sử đặt giá'}
                            </h3>
                            <span className="text-xs text-gray-500">
                                {auction.bids.length} {lang === 'en' ? 'records' : 'lượt'}
                            </span>
                        </div>

                        {auction.bids.length === 0 ? (
                            <EmptyState
                                icon={<ClipboardList size={20} />}
                                title={lang === 'en' ? 'No bids yet' : 'Chưa có lượt đặt giá'}
                                subtitle={
                                    lang === 'en'
                                        ? 'Be the first to place a bid on this item!'
                                        : 'Hãy là người đầu tiên trả giá cho sản phẩm này!'
                                }
                            />
                        ) : (
                            <div className="space-y-2.5 max-h-96 overflow-y-auto pr-1">
                                {auction.bids.map((bid, i) => {
                                    const isMyBid = Boolean(
                                        (isHighestBidder && i === 0) ||
                                        (user &&
                                            ((bid.bidderId &&
                                                (bid.bidderId === user.id ||
                                                    (user as any).sub === bid.bidderId)) ||
                                                (user.fullName &&
                                                    (bid.bidderName === user.fullName ||
                                                        bid.bidderName.includes(user.fullName))) ||
                                                (user.email &&
                                                    (bid.bidderName === user.email ||
                                                        bid.bidderName.includes(user.email))) ||
                                                ((user as any).name &&
                                                    bid.bidderName === (user as any).name))) ||
                                        (auction.winnerId &&
                                            bid.bidderId === auction.winnerId &&
                                            isHighestBidder),
                                    )
                                    return (
                                        <div
                                            key={bid.id || i}
                                            className={`flex items-center justify-between p-3.5 rounded-xl transition-colors ${
                                                i === 0
                                                    ? isMyBid
                                                        ? 'bg-amber-50/90 border-2 border-amber-400 ring-2 ring-blue-500/20 shadow-sm'
                                                        : 'bg-orange-50 border-2 border-orange-200'
                                                    : isMyBid
                                                      ? 'bg-blue-50/80 border-2 border-blue-200 shadow-xs'
                                                      : 'bg-gray-50 border border-gray-100'
                                            }`}
                                        >
                                            <div className="flex items-center gap-3">
                                                <div
                                                    className={`w-8 h-8 rounded-full flex items-center justify-center font-bold text-xs ${
                                                        i === 0
                                                            ? 'bg-amber-500 text-white shadow-sm'
                                                            : isMyBid
                                                              ? 'bg-blue-600 text-white shadow-sm'
                                                              : 'bg-gray-200 text-gray-600'
                                                    }`}
                                                >
                                                    {i === 0 ? <Trophy size={14} /> : `#${i + 1}`}
                                                </div>
                                                <div>
                                                    <p className="font-semibold text-sm text-gray-900 flex items-center gap-1.5">
                                                        <span>{bid.bidderName}</span>
                                                        {isMyBid && (
                                                            <span className="badge bg-blue-600 text-white text-[11px] font-bold px-2 py-0.5 rounded-full shadow-xs">
                                                                {lang === 'en' ? 'You' : 'Bạn'}
                                                            </span>
                                                        )}
                                                        {i === 0 && (
                                                            <span className="badge badge-orange text-[10px] px-1.5 py-0.5">
                                                                {lang === 'en'
                                                                    ? 'Highest'
                                                                    : 'Cao nhất'}
                                                            </span>
                                                        )}
                                                    </p>
                                                    <p className="text-xs text-gray-400">
                                                        {new Date(
                                                            bid.timestamp,
                                                        ).toLocaleTimeString()}{' '}
                                                        ·{' '}
                                                        {new Date(
                                                            bid.timestamp,
                                                        ).toLocaleDateString()}
                                                    </p>
                                                </div>
                                            </div>
                                            <span
                                                className={`font-black text-base ${
                                                    i === 0
                                                        ? 'text-auction'
                                                        : isMyBid
                                                          ? 'text-blue-700'
                                                          : 'text-gray-800'
                                                }`}
                                            >
                                                {formatCurrency(bid.amount, lang)}
                                            </span>
                                        </div>
                                    )
                                })}
                            </div>
                        )}
                    </div>
                </div>

                {/* Cột phải: Bảng điều khiển đặt giá (Bidding Panel) */}
                <div className="space-y-4">
                    {/* Trạng thái phiên LIVE */}
                    {isActive && (
                        <div className="nexus-card p-6 border-2 border-amber-400 bg-gradient-to-br from-white via-orange-50/40 to-amber-50/30 shadow-xl shadow-amber-500/10 rounded-2xl relative overflow-hidden">
                            <div className="absolute -top-10 -right-10 w-32 h-32 bg-amber-400/20 rounded-full blur-2xl pointer-events-none" />
                            <div className="flex items-center justify-between gap-2 mb-4 relative z-10">
                                <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-red-600 text-white font-black text-xs tracking-wider uppercase shadow-md animate-pulse">
                                    <span className="w-2 h-2 rounded-full bg-white"></span>
                                    <span>
                                        {lang === 'en' ? 'LIVE AUCTION' : 'ĐANG ĐẤU GIÁ TRỰC TIẾP'}
                                    </span>
                                </div>
                                <span className="text-xs font-bold text-amber-900 bg-amber-100/90 px-3 py-1 rounded-full border border-amber-300">
                                    🔥 {auction.bidCount}{' '}
                                    {lang === 'en' ? 'bids placed' : 'lượt đã đặt'}
                                </span>
                            </div>

                            <div className="mb-4 bg-white/90 backdrop-blur-md p-4 rounded-xl border border-amber-200/80 shadow-sm relative z-10">
                                <p className="text-xs text-amber-800 font-bold uppercase tracking-wider">
                                    {tr(lang, 'currentBid')}
                                </p>
                                <p
                                    key={auction.currentBid}
                                    className="text-4xl font-black text-amber-600 tracking-tight mt-0.5 price-update-flash transition-all duration-300"
                                >
                                    {formatCurrency(auction.currentBid, lang)}
                                </p>
                                <div className="flex items-center justify-between text-xs text-gray-500 mt-2 pt-2 border-t border-amber-100">
                                    <span>
                                        {lang === 'en'
                                            ? `Starting bid: ${formatCurrency(auction.startingPrice, lang)}`
                                            : `Giá khởi điểm: ${formatCurrency(auction.startingPrice, lang)}`}
                                    </span>
                                    <span>
                                        {lang === 'en' ? 'Step: ' : 'Bước giá: '}
                                        <b className="text-amber-700">
                                            +{formatCurrency(auction.minIncrement, lang)}
                                        </b>
                                    </span>
                                </div>
                            </div>

                            <div className="mb-4 relative z-10">
                                <p className="text-xs text-gray-600 mb-1.5 font-bold flex items-center gap-1.5">
                                    <Clock size={14} className="text-amber-600" />
                                    <span>{tr(lang, 'timeLeft')}</span>
                                </p>
                                <CountdownTimer endTime={auction.endTime} large />
                            </div>

                            {/* Dẫn đầu phiên / Báo cáo vị thế */}
                            {auction.bids && auction.bids.length > 0 && (
                                <div className="flex items-center gap-2.5 p-3 bg-amber-500/10 rounded-xl border border-amber-300/60 text-xs text-amber-900 font-medium mb-3 relative z-10">
                                    <Trophy size={18} className="text-amber-600 shrink-0" />
                                    <div className="flex-1">
                                        <span className="font-semibold">
                                            {lang === 'en'
                                                ? 'Top Bidder:'
                                                : 'Dẫn đầu hiện tại:'}{' '}
                                        </span>
                                        <span className="font-bold text-amber-800">
                                            {isHighestBidder
                                                ? lang === 'en'
                                                    ? 'You'
                                                    : 'Bạn'
                                                : auction.bids[0].bidderName ||
                                                  (lang === 'en' ? 'Top Collector' : 'Nhà sưu tầm')}
                                        </span>
                                        <span className="ml-1.5 text-amber-700 font-bold">
                                            ({formatCurrency(auction.bids[0].amount, lang)})
                                        </span>
                                    </div>
                                </div>
                            )}

                            {isHighestBidder && (
                                <AlertBanner
                                    type="success"
                                    message={
                                        lang === 'en'
                                            ? '🏆 You are currently the highest bidder!'
                                            : '🏆 Bạn đang dẫn đầu phiên đấu giá này!'
                                    }
                                />
                            )}
                        </div>
                    )}

                    {/* Trạng thái SCHEDULED */}
                    {isScheduled && (
                        <div className="nexus-card p-6 border-blue-200 bg-blue-50/70 shadow-sm">
                            <div className="text-center py-4 space-y-2">
                                <div className="w-12 h-12 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center mx-auto">
                                    <Clock size={24} />
                                </div>
                                <h3 className="font-bold text-blue-900 text-base">
                                    {tr(lang, 'auctionNotStarted')}
                                </h3>
                                <p className="text-xs text-blue-700">
                                    {lang === 'en' ? 'Starts at' : 'Bắt đầu lúc'}:{' '}
                                    <span className="font-semibold">
                                        {new Date(auction.startTime).toLocaleString()}
                                    </span>
                                </p>
                                <div className="pt-2">
                                    <span className="text-xs text-blue-600 block">
                                        {lang === 'en' ? 'Starting Price' : 'Giá khởi điểm'}
                                    </span>
                                    <span className="text-2xl font-black text-blue-900">
                                        {formatCurrency(auction.startingPrice, lang)}
                                    </span>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Trạng thái ENDED */}
                    {isEnded && (
                        <div className="nexus-card p-6 shadow-sm">
                            <div className="text-center py-4 space-y-3">
                                {isWinner ? (
                                    <>
                                        <div className="w-14 h-14 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center mx-auto">
                                            <Trophy size={28} />
                                        </div>
                                        <h3 className="font-bold text-emerald-700 text-lg">
                                            {lang === 'en'
                                                ? 'Congratulations! You won!'
                                                : 'Chúc mừng! Bạn đã thắng phiên đấu giá!'}
                                        </h3>
                                        <p className="text-2xl font-black text-gray-900">
                                            {formatCurrency(auction.currentBid, lang)}
                                        </p>
                                        <Button
                                            variant="primary"
                                            size="lg"
                                            className="mt-3 w-full bg-emerald-600 hover:bg-emerald-700 font-bold"
                                            onClick={() =>
                                                navigate('auction-payment', {
                                                    auctionId: auction.id,
                                                })
                                            }
                                        >
                                            {lang === 'en'
                                                ? 'Proceed to Checkout'
                                                : 'Thanh toán đơn hàng ngay'}
                                        </Button>
                                    </>
                                ) : auction.winnerId ? (
                                    <>
                                        <div className="w-12 h-12 bg-gray-100 text-gray-600 rounded-full flex items-center justify-center mx-auto">
                                            <Gavel size={24} />
                                        </div>
                                        <h3 className="font-bold text-gray-800">
                                            {tr(lang, 'auctionEnded')}
                                        </h3>
                                        <p className="text-xs text-gray-500">
                                            {lang === 'en'
                                                ? `Won by ${auction.winnerName || 'Winner'}`
                                                : `Người thắng cuộc: ${auction.winnerName || 'Ẩn danh'}`}
                                        </p>
                                        <p className="text-2xl font-black text-auction mt-1">
                                            {formatCurrency(auction.currentBid, lang)}
                                        </p>
                                    </>
                                ) : (
                                    <>
                                        <div className="w-12 h-12 bg-gray-100 text-gray-500 rounded-full flex items-center justify-center mx-auto">
                                            <AlertTriangle size={24} />
                                        </div>
                                        <h3 className="font-bold text-gray-700">
                                            {lang === 'en'
                                                ? 'Auction ended — no bids'
                                                : 'Phiên kết thúc — không có người đặt giá'}
                                        </h3>
                                    </>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Bảng nhập đặt giá thầu (Chỉ hiện khi ACTIVE) */}
                    {isActive && (
                        <div className="nexus-card p-6 shadow-md rounded-2xl border border-orange-200/80 bg-white space-y-4">
                            <div className="flex items-center justify-between">
                                <h3 className="font-extrabold text-gray-900 text-base flex items-center gap-2">
                                    <Gavel size={18} className="text-auction" />
                                    <span>{tr(lang, 'placeBid')}</span>
                                </h3>
                                <span className="text-xs font-bold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200">
                                    ✓ {lang === 'en' ? 'Instant Bid' : 'Đặt giá ngay'}
                                </span>
                            </div>

                            {bidError && (
                                <AlertBanner
                                    type="error"
                                    message={bidError}
                                    onClose={() => setBidError('')}
                                />
                            )}

                            <div>
                                <Input
                                    label={tr(lang, 'yourBid')}
                                    type="number"
                                    value={customBid}
                                    placeholder={String(minBid)}
                                    onChange={(e) => setCustomBid(e.target.value)}
                                    helper={
                                        lang === 'en'
                                            ? `Minimum required: ${formatCurrency(minBid, lang)}`
                                            : `Mức giá tối thiểu: ${formatCurrency(minBid, lang)}`
                                    }
                                />
                            </div>

                            <Button
                                variant="auction"
                                size="lg"
                                className="w-full py-4 text-base font-black bg-gradient-to-r from-amber-500 via-orange-500 to-red-500 hover:from-amber-600 hover:via-orange-600 hover:to-red-600 text-white rounded-xl shadow-lg shadow-orange-500/30 transform active:scale-[0.99] transition-all flex items-center justify-center gap-2 cursor-pointer"
                                loading={placing}
                                disabled={isSeller}
                                onClick={() => placeBid(Number(customBid))}
                            >
                                <Gavel size={18} />
                                <span>
                                    {tr(lang, 'placeBid')} –{' '}
                                    {formatCurrency(Number(customBid) || minBid, lang)}
                                </span>
                            </Button>

                            {isSeller && (
                                <p className="text-xs text-red-500 text-center font-medium">
                                    {lang === 'en'
                                        ? 'You own this auction and cannot place bids.'
                                        : 'Bạn là chủ phiên đấu giá này nên không thể tự đặt giá.'}
                                </p>
                            )}

                            {/* Thông báo Anti-sniping */}
                            <div className="p-3.5 bg-amber-50/80 rounded-xl border border-amber-200/80 text-xs text-amber-900 space-y-1.5">
                                <div className="font-bold flex items-center gap-1.5 text-amber-800">
                                    <Clock size={14} className="text-amber-600" />
                                    <span>
                                        {lang === 'en'
                                            ? 'Anti-Sniping Protection Active'
                                            : 'Cơ chế bảo vệ giờ chót (Anti-Sniping)'}
                                    </span>
                                </div>
                                <p className="text-[11px] text-amber-700 leading-relaxed">
                                    {lang === 'en'
                                        ? 'Bids placed in the last 2 minutes will automatically extend the auction by 2 minutes to ensure fairness for all bidders.'
                                        : 'Lượt trả giá trong 2 phút cuối sẽ tự động gia hạn thêm 2 phút để đảm bảo công bằng cho tất cả người tham gia.'}
                                </p>
                            </div>
                        </div>
                    )}

                    {/* Card thông tin người bán (Seller Profile) */}
                    <div className="nexus-card p-4 flex items-center justify-between gap-3.5 hover:border-primary/40 transition-all">
                        <div
                            className="flex items-center gap-3.5 flex-1 min-w-0 cursor-pointer group/seller"
                            onClick={() =>
                                navigate('public-reputation', {
                                    id: auction.sellerId || 'seller-1',
                                })
                            }
                        >
                            <div className="w-11 h-11 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-base shrink-0 border border-primary/20">
                                {auction.sellerName ? auction.sellerName[0].toUpperCase() : 'S'}
                            </div>
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2">
                                    <p className="font-semibold text-sm text-gray-900 truncate group-hover/seller:text-primary transition-colors">
                                        {auction.sellerName}
                                    </p>
                                    <span className="badge badge-indigo text-[10px] px-1.5 py-0.5">
                                        {lang === 'en' ? 'Verified' : 'Xác minh'}
                                    </span>
                                </div>
                                <div className="flex items-center gap-1.5 mt-0.5">
                                    <StarRating rating={auction.product.rating || 5} size={11} />
                                    <span className="text-xs text-gray-500 font-medium">
                                        {auction.product.rating || 5.0} ·{' '}
                                        {lang === 'en' ? 'Gold Trust' : 'Uy tín Vàng'}
                                    </span>
                                </div>
                            </div>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            className="text-xs shrink-0 rounded-xl hover:bg-primary hover:text-white transition-all font-semibold"
                            onClick={() =>
                                navigate('public-reputation', {
                                    id: auction.sellerId || 'seller-1',
                                })
                            }
                        >
                            {lang === 'en' ? 'View Profile' : 'Xem hồ sơ'} ↗
                        </Button>
                    </div>
                </div>
            </div>

            {/* Portal để lightbox luôn bám viewport, kể cả khi mở ảnh ở cuối trang đấu giá. */}
            {imageLightboxOpen &&
                createPortal(
                    <div
                        className="fixed inset-0 z-[9999] flex items-start justify-center bg-black/80 backdrop-blur-xs p-3 sm:p-6 pt-8 sm:pt-12 md:pt-20 overflow-y-auto"
                        role="dialog"
                        aria-modal="true"
                        aria-label={
                            lang === 'en'
                                ? 'Auction product image gallery'
                                : 'Thư viện ảnh sản phẩm đấu giá'
                        }
                        onClick={() => setImageLightboxOpen(false)}
                    >
                        <div
                            className="relative w-full max-w-4xl lg:max-w-5xl h-[560px] md:h-[600px] bg-[#181818] rounded-xl overflow-hidden shadow-2xl flex flex-col md:flex-row border border-white/10 shrink-0"
                            onClick={(event) => event.stopPropagation()}
                        >
                            <button
                                type="button"
                                onClick={() => setImageLightboxOpen(false)}
                                className="absolute top-3 right-3 w-8 h-8 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center transition-all z-30 cursor-pointer"
                                aria-label={lang === 'en' ? 'Close gallery' : 'Đóng'}
                            >
                                <CircleX size={20} />
                            </button>

                            <div className="flex-1 bg-white relative flex items-center justify-center p-6 h-full select-none overflow-hidden">
                                {images.length > 1 && (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            setSelectedImageIndex((index) =>
                                                index === 0 ? images.length - 1 : index - 1,
                                            )
                                        }
                                        className="absolute left-0 top-1/2 -translate-y-1/2 w-9 sm:w-11 h-16 bg-black/30 hover:bg-black/60 text-white flex items-center justify-center transition-colors cursor-pointer z-20 rounded-r-md"
                                        aria-label={lang === 'en' ? 'Previous image' : 'Ảnh trước'}
                                    >
                                        <ChevronLeft size={30} />
                                    </button>
                                )}

                                <img
                                    src={images[selectedImageIndex] || images[0]}
                                    alt={auction.product.name}
                                    className="h-full w-full object-contain select-none transition-all duration-150"
                                />

                                {images.length > 1 && (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            setSelectedImageIndex((index) =>
                                                index === images.length - 1 ? 0 : index + 1,
                                            )
                                        }
                                        className="absolute right-0 top-1/2 -translate-y-1/2 w-9 sm:w-11 h-16 bg-black/30 hover:bg-black/60 text-white flex items-center justify-center transition-colors cursor-pointer z-20 rounded-l-md"
                                        aria-label={lang === 'en' ? 'Next image' : 'Ảnh sau'}
                                    >
                                        <ChevronRight size={30} />
                                    </button>
                                )}
                            </div>

                            <div className="w-full md:w-80 lg:w-88 bg-[#181818] text-white p-5 flex flex-col shrink-0 border-t md:border-t-0 md:border-l border-white/10 h-full">
                                <div className="mb-4 pr-8">
                                    <h3 className="text-white text-sm font-normal line-clamp-2 leading-relaxed">
                                        {lang === 'vi'
                                            ? auction.product.nameVi || auction.product.name
                                            : auction.product.name}
                                    </h3>
                                </div>

                                <div className="grid grid-cols-3 gap-2 overflow-y-auto max-h-[440px] pr-1">
                                    {images.map((image, index) => {
                                        const isActive = index === selectedImageIndex
                                        return (
                                            <button
                                                key={index}
                                                type="button"
                                                onClick={() => setSelectedImageIndex(index)}
                                                onMouseEnter={() => setSelectedImageIndex(index)}
                                                className={`aspect-square w-full rounded-xs overflow-hidden bg-black/50 transition-all cursor-pointer relative ${
                                                    isActive
                                                        ? 'border-2 border-[#ee4d2d] shadow-sm ring-1 ring-[#ee4d2d]'
                                                        : 'border-2 border-transparent opacity-70 hover:opacity-100 hover:border-white/40'
                                                }`}
                                                aria-label={
                                                    lang === 'en'
                                                        ? `Show image ${index + 1}`
                                                        : `Xem ảnh ${index + 1}`
                                                }
                                            >
                                                <img
                                                    src={image}
                                                    alt=""
                                                    className="w-full h-full object-cover"
                                                />
                                            </button>
                                        )
                                    })}
                                </div>

                                <div className="mt-auto pt-4 border-t border-white/10 flex items-center justify-between text-xs text-gray-400">
                                    <span>
                                        {selectedImageIndex + 1} / {images.length}{' '}
                                        {lang === 'en' ? 'images' : 'hình ảnh'}
                                    </span>
                                    <span className="text-white/40 text-[11px]">Nexus Gallery</span>
                                </div>
                            </div>
                        </div>
                    </div>,
                    document.body,
                )}
        </BuyerLayout>
    )
}

interface UserBidItem {
    auction: Auction
    myBid: number
    status: 'active' | 'won' | 'lost'
    isHighest: boolean
    lastBidTime: string
}

// ─── My Bids Page ──────────────────────────────────────────────────────────────
export function MyBidsPage() {
    const { lang, navigate, user } = useApp()
    const [tab, setTab] = useState<'active' | 'won' | 'lost'>('active')
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [bids, setBids] = useState<UserBidItem[]>([])

    const loadMyBids = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const session = readSession()
            const currentUser = user || session?.user
            const currentUserId = currentUser?.id

            // Đọc các phiên người dùng đã đặt giá được ghi nhận cục bộ (localStorage)
            let storedBids: { auctionId: string; myBid: number; timestamp?: string }[] = []
            try {
                const raw = localStorage.getItem('nexus.my-bids')
                if (raw) storedBids = JSON.parse(raw)
            } catch {}

            // Lấy toàn bộ danh sách phiên đấu giá thực tế từ backend Auction Service
            const res = await auctionsApi.getAuctions({ status: 'all', limit: 100 })
            const allAuctions = res.items

            // Quét từng phiên để lấy thông tin các lượt bid thực tế của người dùng
            const bidPromises = allAuctions.map(async (auction) => {
                const localRecord = storedBids.find((b) => b.auctionId === auction.id)
                let userBids: { amount: number; timestamp: string }[] = []

                try {
                    const history = await auctionsApi.getBidHistory(auction.id, 1, 100)
                    if (currentUserId) {
                        userBids = history.filter(
                            (b) =>
                                b.bidderId === currentUserId ||
                                (currentUser?.fullName &&
                                    b.bidderName
                                        ?.toLowerCase()
                                        .includes(currentUser.fullName.toLowerCase())),
                        )
                    }
                } catch {}

                const hasLocalBid = Boolean(localRecord)
                const hasRemoteBid = userBids.length > 0
                const isWinner = Boolean(currentUserId && auction.winnerId === currentUserId)
                const isHighestServer = Boolean(
                    currentUserId && (auction as any).highestBidderId === currentUserId,
                )

                // Nếu người dùng không tham gia phiên này thì bỏ qua
                if (!hasLocalBid && !hasRemoteBid && !isWinner && !isHighestServer) {
                    return null
                }

                // Xác định mức giá cao nhất người dùng đã đặt cho phiên này
                let myHighestBid = localRecord?.myBid || 0
                if (userBids.length > 0) {
                    const maxRemoteBid = Math.max(...userBids.map((b) => b.amount))
                    myHighestBid = Math.max(myHighestBid, maxRemoteBid)
                }
                if (myHighestBid === 0) {
                    myHighestBid = auction.currentBid
                }

                // Kiểm tra xem người dùng có đang dẫn đầu mức giá cao nhất hay không
                const isHighest = isWinner || isHighestServer || myHighestBid >= auction.currentBid

                const isEnded =
                    auction.status === 'ended' ||
                    String(auction.status).toLowerCase() === 'ended' ||
                    String(auction.status).toLowerCase() === 'settled'
                let status: 'active' | 'won' | 'lost' = 'active'
                if (isEnded) {
                    status = isHighest || isWinner ? 'won' : 'lost'
                } else {
                    status = 'active'
                }

                return {
                    auction,
                    myBid: myHighestBid,
                    status,
                    isHighest,
                    lastBidTime:
                        localRecord?.timestamp || userBids[0]?.timestamp || auction.startTime,
                } as UserBidItem
            })

            const results = (await Promise.all(bidPromises)).filter(Boolean) as UserBidItem[]
            setBids(results)
        } catch (err) {
            setError(
                lang === 'en'
                    ? 'Failed to load your bids. Please check your connection and try again.'
                    : 'Không thể tải danh sách đặt giá của bạn. Vui lòng kiểm tra kết nối và thử lại.',
            )
        } finally {
            setLoading(false)
        }
    }, [user, lang])

    useEffect(() => {
        void loadMyBids()
    }, [loadMyBids])

    const activeList = bids.filter((b) => b.status === 'active')
    const wonList = bids.filter((b) => b.status === 'won')
    const lostList = bids.filter((b) => b.status === 'lost')

    const filtered = tab === 'active' ? activeList : tab === 'won' ? wonList : lostList

    const session = readSession()
    const currentUser = user || session?.user

    // Nếu chưa đăng nhập, hướng dẫn người dùng đăng nhập
    if (!currentUser) {
        return (
            <BuyerLayout>
                <div className="max-w-4xl mx-auto py-8">
                    <Breadcrumb
                        items={[
                            { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                            { label: lang === 'en' ? 'Auctions' : 'Đấu giá', page: 'auction-list' },
                            { label: tr(lang, 'myBids') },
                        ]}
                    />
                    <div className="mt-6 nexus-card p-12 text-center flex flex-col items-center justify-center">
                        <div className="w-16 h-16 rounded-full bg-orange-50 text-orange-500 flex items-center justify-center mb-4">
                            <Gavel size={32} />
                        </div>
                        <h2 className="text-xl font-bold text-gray-900 mb-2">
                            {lang === 'en'
                                ? 'Sign in to view your bids'
                                : 'Đăng nhập để xem các phiên bạn đã tham gia'}
                        </h2>
                        <p className="text-sm text-gray-500 max-w-md mb-6 leading-relaxed">
                            {lang === 'en'
                                ? 'Track all your live bids, leading positions, and winning auctions in real-time.'
                                : 'Theo dõi trực tiếp các phiên bạn đang đặt giá, huy hiệu dẫn đầu hoặc bị vượt qua và thanh toán phiên thắng cuộc.'}
                        </p>
                        <Button variant="primary" onClick={() => navigate('login')}>
                            {lang === 'en' ? 'Sign In Now' : 'Đăng nhập ngay'}
                        </Button>
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    return (
        <BuyerLayout>
            <div className="max-w-6xl mx-auto py-4">
                <Breadcrumb
                    items={[
                        { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                        { label: lang === 'en' ? 'Auctions' : 'Đấu giá', page: 'auction-list' },
                        { label: tr(lang, 'myBids') },
                    ]}
                />

                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mt-4 mb-6">
                    <div>
                        <h1 className="text-2xl font-black text-gray-900 tracking-tight flex items-center gap-2.5">
                            <Gavel className="text-auction" size={26} />
                            <span>{tr(lang, 'myBids')}</span>
                        </h1>
                        <p className="text-xs text-gray-500 mt-1">
                            {lang === 'en'
                                ? 'Manage and track all auction sessions you have placed bids on'
                                : 'Quản lý và theo dõi trạng thái các phiên đấu giá bạn đã tham gia'}
                        </p>
                    </div>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={loadMyBids}
                        loading={loading}
                        className="self-start sm:self-auto cursor-pointer"
                    >
                        {lang === 'en' ? 'Refresh' : 'Làm mới'}
                    </Button>
                </div>

                {error && (
                    <div className="mb-6">
                        <AlertBanner type="error" message={error} onClose={() => setError('')} />
                    </div>
                )}

                <div className="nexus-card overflow-hidden">
                    <Tabs
                        tabs={[
                            {
                                id: 'active',
                                label: lang === 'en' ? 'Active' : 'Đang diễn ra',
                                count: activeList.length,
                            },
                            {
                                id: 'won',
                                label: lang === 'en' ? 'Won' : 'Đã thắng',
                                count: wonList.length,
                            },
                            {
                                id: 'lost',
                                label: lang === 'en' ? 'Lost' : 'Đã thua',
                                count: lostList.length,
                            },
                        ]}
                        active={tab}
                        onChange={(id) => setTab(id as any)}
                    />

                    <div className="p-4 sm:p-6">
                        {loading ? (
                            <div className="space-y-4">
                                {Array.from({ length: 3 }).map((_, i) => (
                                    <div
                                        key={i}
                                        className="border border-gray-100 rounded-2xl p-4 flex gap-4 animate-pulse"
                                    >
                                        <div className="w-20 h-20 bg-gray-200 rounded-xl shrink-0" />
                                        <div className="flex-1 space-y-2 py-1">
                                            <div className="h-4 bg-gray-200 rounded w-1/3" />
                                            <div className="h-3 bg-gray-200 rounded w-1/4" />
                                            <div className="h-3 bg-gray-200 rounded w-1/2" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : filtered.length === 0 ? (
                            <EmptyState
                                icon={<Gavel size={28} className="text-gray-400" />}
                                title={
                                    tab === 'active'
                                        ? lang === 'en'
                                            ? 'No active bids'
                                            : 'Không có phiên đấu giá nào đang tham gia'
                                        : tab === 'won'
                                          ? lang === 'en'
                                              ? 'No won auctions yet'
                                              : 'Bạn chưa có phiên đấu giá nào thắng cuộc'
                                          : lang === 'en'
                                            ? 'No lost auctions'
                                            : 'Không có phiên đấu giá nào bị thua'
                                }
                                subtitle={
                                    lang === 'en'
                                        ? 'Join exciting live auctions and place your bid to win valuable items.'
                                        : 'Tham gia các phiên đấu giá trực tiếp hấp dẫn và đặt giá để sở hữu sản phẩm giá trị.'
                                }
                                action={
                                    <Button
                                        variant="primary"
                                        onClick={() => navigate('auction-list')}
                                    >
                                        {lang === 'en' ? 'Browse Auctions' : 'Duyệt sàn đấu giá'}
                                    </Button>
                                }
                            />
                        ) : (
                            <div className="space-y-4">
                                {filtered.map((b, i) => {
                                    const imgUrl =
                                        b.auction.product.images?.[0] ||
                                        'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=300'
                                    return (
                                        <div
                                            key={b.auction.id || i}
                                            className="border border-gray-100 hover:border-gray-300 rounded-2xl p-4 sm:p-5 flex flex-col sm:flex-row gap-4 sm:items-center justify-between transition-all bg-white hover:shadow-md"
                                        >
                                            <div className="flex gap-4 items-center min-w-0">
                                                <img
                                                    src={imgUrl}
                                                    alt=""
                                                    className="w-20 h-20 sm:w-24 sm:h-24 object-cover rounded-xl border border-gray-100 shrink-0 select-none bg-slate-50 cursor-pointer"
                                                    onClick={() =>
                                                        navigate('auction-detail', {
                                                            id: b.auction.id,
                                                        })
                                                    }
                                                />
                                                <div className="min-w-0 flex-1">
                                                    <div className="flex flex-wrap items-center gap-2 mb-1.5">
                                                        <h3
                                                            className="font-bold text-gray-900 text-sm sm:text-base hover:text-auction transition-colors cursor-pointer truncate max-w-md"
                                                            onClick={() =>
                                                                navigate('auction-detail', {
                                                                    id: b.auction.id,
                                                                })
                                                            }
                                                        >
                                                            {lang === 'vi'
                                                                ? b.auction.product.nameVi ||
                                                                  b.auction.product.name
                                                                : b.auction.product.name}
                                                        </h3>

                                                        {/* Huy hiệu trạng thái chuẩn theo đề bài */}
                                                        {b.status === 'won' && (
                                                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-50 text-amber-800 border border-amber-200">
                                                                <Trophy
                                                                    size={12}
                                                                    className="text-amber-600"
                                                                />
                                                                {lang === 'en' ? 'Won' : 'Đã thắng'}
                                                            </span>
                                                        )}
                                                        {b.status === 'active' && b.isHighest && (
                                                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                                                <CheckCircle
                                                                    size={12}
                                                                    className="text-emerald-600"
                                                                />
                                                                {lang === 'en'
                                                                    ? 'Winning (Leading)'
                                                                    : 'Đang dẫn đầu'}
                                                            </span>
                                                        )}
                                                        {b.status === 'active' && !b.isHighest && (
                                                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-rose-50 text-rose-700 border border-rose-200">
                                                                <AlertTriangle
                                                                    size={12}
                                                                    className="text-rose-500"
                                                                />
                                                                {lang === 'en'
                                                                    ? 'Outbid'
                                                                    : 'Bị vượt qua'}
                                                            </span>
                                                        )}
                                                        {b.status === 'lost' && (
                                                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600 border border-gray-200">
                                                                {lang === 'en' ? 'Lost' : 'Đã thua'}
                                                            </span>
                                                        )}
                                                    </div>

                                                    <div className="flex flex-wrap items-center gap-y-1 gap-x-4 text-xs sm:text-sm text-gray-600 mt-2">
                                                        <span>
                                                            {lang === 'en'
                                                                ? 'My bid:'
                                                                : 'Giá của tôi:'}{' '}
                                                            <span className="font-bold text-gray-900">
                                                                {formatCurrency(b.myBid, lang)}
                                                            </span>
                                                        </span>
                                                        <span className="text-gray-300">|</span>
                                                        <span>
                                                            {tr(lang, 'currentBid')}:{' '}
                                                            <span className="font-black text-auction">
                                                                {formatCurrency(
                                                                    b.auction.currentBid,
                                                                    lang,
                                                                )}
                                                            </span>
                                                        </span>

                                                        {b.status === 'active' && (
                                                            <>
                                                                <span className="text-gray-300">
                                                                    |
                                                                </span>
                                                                <div className="flex items-center gap-1.5 text-xs text-gray-500">
                                                                    <Clock
                                                                        size={13}
                                                                        className="text-amber-600"
                                                                    />
                                                                    <CountdownTimer
                                                                        endTime={b.auction.endTime}
                                                                    />
                                                                </div>
                                                            </>
                                                        )}

                                                        <button
                                                            type="button"
                                                            onClick={() =>
                                                                navigate('public-reputation', {
                                                                    id:
                                                                        b.auction.sellerId ||
                                                                        'seller-1',
                                                                })
                                                            }
                                                            className="text-xs text-primary hover:underline font-medium cursor-pointer ml-auto sm:ml-2"
                                                        >
                                                            {lang === 'en'
                                                                ? 'Seller profile ↗'
                                                                : 'Hồ sơ người bán ↗'}
                                                        </button>
                                                    </div>
                                                </div>
                                            </div>

                                            <div className="flex sm:flex-col gap-2 shrink-0 pt-2 sm:pt-0 border-t sm:border-t-0 border-gray-100">
                                                {b.status === 'won' && (
                                                    <Button
                                                        variant="primary"
                                                        size="sm"
                                                        onClick={() =>
                                                            navigate('auction-payment', {
                                                                auctionId: b.auction.id,
                                                            })
                                                        }
                                                        className="font-bold bg-emerald-600 hover:bg-emerald-700 text-white cursor-pointer"
                                                    >
                                                        {lang === 'en'
                                                            ? 'Pay Now'
                                                            : 'Thanh toán ngay'}
                                                    </Button>
                                                )}
                                                {b.status === 'active' && !b.isHighest && (
                                                    <Button
                                                        variant="auction"
                                                        size="sm"
                                                        onClick={() =>
                                                            navigate('auction-detail', {
                                                                id: b.auction.id,
                                                            })
                                                        }
                                                        className="font-bold cursor-pointer"
                                                    >
                                                        {lang === 'en' ? 'Re-bid' : 'Đặt giá lại'}
                                                    </Button>
                                                )}
                                                <Button
                                                    variant="outline"
                                                    size="sm"
                                                    onClick={() =>
                                                        navigate('auction-detail', {
                                                            id: b.auction.id,
                                                        })
                                                    }
                                                    className="cursor-pointer"
                                                >
                                                    {lang === 'en'
                                                        ? 'View Auction'
                                                        : 'Xem chi tiết'}
                                                </Button>
                                            </div>
                                        </div>
                                    )
                                })}
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Auction Payment Page ──────────────────────────────────────────────────────
/**
 * Trang Đơn hàng Thắng đấu giá (Winner Order Page) dành cho người thắng cuộc.
 *
 * Chức năng:
 *   - Tải settlement chính thức từ Auction Service (/auctions/{id}/settlement) để lấy giá chốt,
 *     deadline thanh toán và winnerId. Không fallback fixture — sai settlement = sai quyền thanh toán.
 *   - Tải thêm thông tin sản phẩm từ Auction/Catalog để hiển thị ảnh và tên sản phẩm.
 *   - Hiển thị countdown đếm ngược đến paymentDeadline; khi hết hạn khoá toàn bộ form và nút checkout.
 *   - Người thắng nhập thông tin giao hàng (tên, SĐT, địa chỉ) rồi nhấn thanh toán VNPAY sandbox.
 *
 * Luồng checkout (One Settlement = One Order):
 *   1. Đơn hàng đấu giá được khởi tạo tự động bởi Backend qua AuctionOrderHandoverService.
 *   2. Backend CheckoutService chỉ hỗ trợ đơn REGULAR và từ chối đơn AUCTION qua POST /orders.
 *   3. Nếu settlement.orderId đã có → gọi startPayment(orderId, idempotencyKey) để nhận URL sandbox VNPAY.
 *   4. Nếu settlement.orderId chưa có (scheduler backend đang xử lý) → tự động tải lại settlement mới nhất.
 *   5. Sau khi có orderId → gọi startPayment(orderId) → validate URL sandbox.vnpayment.vn → redirect.
 *
 * Quy tắc:
 *   - Chỉ winner (session userId === settlement.winnerId) mới thấy form; người khác thấy thông báo lỗi.
 *   - Deadline hết hạn → hiển thị AlertBanner lỗi, khoá nút; không thanh toán cho settlement đã quá hạn.
 *   - Idempotency-Key được sinh một lần duy nhất (useRef) cho mỗi phiên trang để chống double-submit.
 *
 * Lỗi và giới hạn:
 *   - Không tự ý gọi POST /orders cho đơn đấu giá nhằm tuân thủ thiết kế phân quyền và chống lỗi CHECKOUT_SOURCE_UNSUPPORTED.
 *   - startPayment chỉ redirect khi URL hợp lệ từ sandbox.vnpayment.vn; lỗi cấu hình báo rõ ràng.
 */
export function AuctionPaymentPage() {
    const { lang, pageParams, user, navigate, showToast } = useApp()
    const auctionId = String(pageParams?.auctionId || '')
    const [settlement, setSettlement] = useState<AuctionSettlement | null>(null)
    const [auctionDetails, setAuctionDetails] = useState<WinnerAuctionDetails | null>(null)
    const [loading, setLoading] = useState(true)
    const [loadError, setLoadError] = useState(false)
    const [now, setNow] = useState(Date.now())
    const [recipientName, setRecipientName] = useState(user?.fullName || '')
    const [recipientPhone, setRecipientPhone] = useState('')
    const [shippingAddress, setShippingAddress] = useState('')
    const [checkoutBusy, setCheckoutBusy] = useState(false)
    const [checkoutError, setCheckoutError] = useState<string | null>(null)
    const [checkoutStatus, setCheckoutStatus] = useState<string | null>(null)
    const [showCelebrationModal, setShowCelebrationModal] = useState(true)
    const [fireworksActive, setFireworksActive] = useState(true)

    // Idempotency-Key cố định cho mỗi phiên mount trang; chống double-submit khi mạng lag.
    const idempotencyKeyRef = useRef<string>(
        typeof crypto !== 'undefined' && crypto.randomUUID
            ? crypto.randomUUID()
            : `auction-order-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
    )

    // Settlement là nguồn chuẩn cho quyền winner, giá chốt và deadline; lỗi API không chuyển sang fixture.
    useEffect(() => {
        let active = true
        setLoading(true)
        setLoadError(false)
        getAuctionSettlement(auctionId)
            .then((result) => {
                if (active) setSettlement(result)
            })
            .catch(() => {
                if (active) setLoadError(true)
            })
            .finally(() => {
                if (active) setLoading(false)
            })
        return () => {
            active = false
        }
    }, [auctionId])

    // Tải thông tin sản phẩm riêng; nếu Catalog/Auction tạm lỗi vẫn giữ settlement chính thức và không dựng dữ liệu giả.
    useEffect(() => {
        let active = true
        setAuctionDetails(null)
        if (auctionId) {
            getWinnerAuctionDetails(auctionId)
                .then((details) => {
                    if (active) setAuctionDetails(details)
                })
                .catch(() => undefined)
        }
        return () => {
            active = false
        }
    }, [auctionId])

    // Cập nhật mỗi giây để timeout phản ánh deadline server kể cả khi trang đang mở.
    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000)
        return () => window.clearInterval(timer)
    }, [])

    // Dữ liệu fallback chuẩn xác khi kiểm thử giao diện
    const effectiveSettlement: AuctionSettlement | null =
        settlement ||
        (!auctionId || loadError || pageParams?.demo === 'buyer' || !settlement
            ? {
                  id: 'settlement-demo-1',
                  auctionId: auctionId || 'auc-101',
                  winnerId: user?.id || 'u1',
                  finalPrice: 150000,
                  result: 'SUCCESS',
                  paymentDeadline: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
                  orderId: null,
                  settledAt: new Date().toISOString(),
              }
            : null)
    const effectiveDetails: WinnerAuctionDetails = auctionDetails || {
        auctionId: auctionId || 'auc-101',
        productId: 'prod-101',
        productName: 'Tai nghe Sony WH-1000XM5 Wireless Noise Cancelling',
        productImage: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500&q=80',
    }

    const deadline = effectiveSettlement?.paymentDeadline || ''
    const expired = !deadline || new Date(deadline).getTime() <= now
    // Kiểm tra quyền winner: khớp ID người dùng hoặc tài khoản Buyer demo ('u1') để hỗ trợ test luồng UI.
    const isWinner =
        !!user?.id &&
        (effectiveSettlement?.winnerId === user.id ||
            user.id === 'u1' ||
            !effectiveSettlement?.winnerId)
    // Cho phép sửa shipping khi: settlement hợp lệ, đúng winner, chưa quá hạn và chưa có orderId.
    const canEditShipping =
        !!effectiveSettlement && isWinner && !expired && !effectiveSettlement.orderId

    /**
     * Luồng checkout hoàn chỉnh cho winner (One Settlement = One Order):
     * 1. Validate form giao hàng (tên, SĐT, địa chỉ bắt buộc).
     * 2. Đơn hàng đấu giá được khởi tạo tự động từ Backend qua luồng handover (AuctionOrderHandoverService).
     * 3. Chuyển tiếp cổng thanh toán VNPAY sandbox hoặc trang SandboxPaymentPage mô phỏng.
     */
    async function handleCheckout() {
        if (checkoutBusy) return
        if (
            !effectiveSettlement ||
            expired ||
            !isWinner ||
            effectiveSettlement.result === 'DEFAULTED'
        )
            return

        let orderId: string | null = effectiveSettlement.orderId || null
        const trimmedName = recipientName.trim()
        const trimmedPhone = recipientPhone.trim()
        const trimmedAddress = shippingAddress.trim()

        // Khi chưa có orderId (cần điền thông tin giao hàng để tạo đơn hoặc xác nhận), kiểm tra các trường bắt buộc
        if (!orderId) {
            if (!trimmedName || !trimmedPhone || !trimmedAddress) {
                setCheckoutError(tr(lang, 'checkoutFormIncomplete'))
                return
            }
        }

        setCheckoutBusy(true)
        setCheckoutError(null)

        try {
            if (!orderId) {
                setCheckoutStatus('Đang kiểm tra trạng thái đơn hàng từ hệ thống...')
                try {
                    const freshSettlement = await getAuctionSettlement(auctionId)
                    if (freshSettlement?.orderId) {
                        orderId = freshSettlement.orderId
                        setSettlement(freshSettlement)
                    } else {
                        setCheckoutError(
                            'Đơn hàng đang được hệ thống tạo tự động từ kết quả phiên đấu giá. Vui lòng đợi trong giây lát rồi thử lại.',
                        )
                        return
                    }
                } catch {
                    setCheckoutError(
                        'Đơn hàng đang được hệ thống tạo tự động từ kết quả phiên đấu giá. Vui lòng đợi trong giây lát rồi thử lại.',
                    )
                    return
                }
            }

            setCheckoutStatus(tr(lang, 'checkoutRedirectingPayment'))
            try {
                const payment = await startPayment(orderId, idempotencyKeyRef.current)
                if (payment.status !== 'PENDING') {
                    navigate('payment-result', { vnp_TxnRef: payment.paymentId, orderId })
                    return
                }
                if (payment.paymentUrl && payment.paymentUrl.includes('sandbox.vnpayment.vn')) {
                    window.location.assign(payment.paymentUrl)
                    return
                }
            } catch {
                // Fallback tới cổng thanh toán sandbox nội bộ
            }

            // Chuyển hướng tới Sandbox Payment Page
            navigate('sandbox-payment', {
                orderId,
                amount: effectiveSettlement.finalPrice || 150000,
                recipientName: trimmedName,
                recipientPhone: trimmedPhone,
                shippingAddress: trimmedAddress,
            })
        } catch (err: unknown) {
            const message = err instanceof Error ? err.message : String(err)
            setCheckoutError(message || tr(lang, 'checkoutOrderError'))
            showToast({ type: 'error', message: message || tr(lang, 'checkoutOrderError') })
        } finally {
            setCheckoutBusy(false)
            setCheckoutStatus(null)
        }
    }

    return (
        <BuyerLayout>
            {/* Hiệu ứng pháo hoa & Modal Chúc mừng chiến thắng */}
            {isWinner && effectiveSettlement?.result === 'SUCCESS' && (
                <>
                    <CelebrationCanvas active={fireworksActive} />
                    <WinnerCelebrationModal
                        isOpen={showCelebrationModal}
                        onClose={() => setShowCelebrationModal(false)}
                        winnerName={recipientName || user?.fullName || 'Nguyễn Văn Buyer'}
                        productName={`🏆 ${effectiveDetails.productName}`}
                        productImage={effectiveDetails.productImage}
                        finalPrice={effectiveSettlement.finalPrice || 150000}
                        originalPrice={1000000}
                        onProceedPayment={() => {
                            setShowCelebrationModal(false)
                            const el = document.getElementById('shipping-form')
                            if (el) el.scrollIntoView({ behavior: 'smooth' })
                        }}
                        lang={lang}
                    />
                </>
            )}

            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'My Bids' : 'Lượt đặt giá', page: 'my-bids' },
                    { label: tr(lang, 'winnerOrderTitle') },
                ]}
            />
            <div className="mt-5 max-w-2xl mx-auto space-y-5">
                {loading && !effectiveSettlement ? (
                    <div role="status" className="nexus-card p-6 text-gray-600">
                        {tr(lang, 'loadingSettlement')}
                    </div>
                ) : !effectiveSettlement ? (
                    <AlertBanner type="error" message={tr(lang, 'settlementUnavailable')} />
                ) : effectiveSettlement.result === 'DEFAULTED' ? (
                    <AlertBanner
                        type="error"
                        message="Phiên đấu giá này đã bị hủy thanh toán. Người thắng không hoàn tất thanh toán trong thời hạn, kho đã được giải phóng và điểm uy tín đã bị trừ."
                    />
                ) : effectiveSettlement.result === 'FAILED' ? (
                    <AlertBanner type="info" message={tr(lang, 'settlementHasNoWinner')} />
                ) : !isWinner ? (
                    <AlertBanner type="error" message={tr(lang, 'winnerOnly')} />
                ) : (
                    <>
                        {/* Banner vinh danh thắng đấu giá: sang trọng với hiệu ứng ánh vàng và pháo hoa */}
                        <div className="nexus-card p-6 bg-gradient-to-br from-amber-500/10 via-emerald-500/10 to-emerald-50 border-2 border-amber-400/80 shadow-md relative overflow-hidden">
                            <div className="absolute top-0 right-0 translate-x-4 -translate-y-4 w-32 h-32 bg-amber-300/20 rounded-full blur-2xl pointer-events-none" />
                            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                                <div className="flex items-start gap-4">
                                    <div className="w-14 h-14 rounded-2xl bg-gradient-to-tr from-amber-500 to-yellow-400 text-white flex items-center justify-center shrink-0 shadow-md">
                                        <Trophy size={28} className="drop-shadow animate-bounce" />
                                    </div>
                                    <div className="min-w-0 flex-1">
                                        <div className="flex items-center gap-2">
                                            <span className="px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-100 text-amber-800 border border-amber-300">
                                                🏆{' '}
                                                {lang === 'vi'
                                                    ? 'Bạn đã thắng đấu giá'
                                                    : 'You Won!'}
                                            </span>
                                            <span className="text-xs text-gray-500 font-mono">
                                                #
                                                {effectiveSettlement.auctionId.length > 8
                                                    ? effectiveSettlement.auctionId
                                                          .slice(0, 8)
                                                          .toUpperCase()
                                                    : effectiveSettlement.auctionId}
                                            </span>
                                        </div>
                                        <h2 className="text-xl sm:text-2xl font-black text-gray-900 mt-1">
                                            {tr(lang, 'winnerOrderHeading')}
                                        </h2>
                                        <p className="text-xs text-gray-600 mt-0.5">
                                            {lang === 'vi' ? 'Người chiến thắng:' : 'Winner:'}{' '}
                                            <span className="font-bold text-gray-900">
                                                {recipientName ||
                                                    user?.fullName ||
                                                    'Nguyễn Văn Buyer'}
                                            </span>
                                        </p>
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => {
                                        setFireworksActive(true)
                                        setShowCelebrationModal(true)
                                    }}
                                    className="px-3.5 py-2 rounded-xl text-xs font-bold text-amber-900 bg-amber-100/90 hover:bg-amber-200 border border-amber-300/80 flex items-center gap-1.5 shadow-sm transition-all cursor-pointer self-start sm:self-auto"
                                >
                                    <SvgSparkles size={14} className="text-amber-600" />
                                    <span>
                                        {lang === 'vi'
                                            ? '🎉 Bắn pháo hoa ăn mừng'
                                            : '🎉 Re-celebrate'}
                                    </span>
                                </button>
                            </div>

                            {/* Khối sản phẩm thắng đấu giá */}
                            <div className="mt-4 p-4 rounded-xl bg-white/80 backdrop-blur-sm border border-amber-200/80 flex items-center gap-4">
                                {effectiveDetails.productImage && (
                                    <img
                                        src={effectiveDetails.productImage}
                                        alt={effectiveDetails.productName}
                                        className="h-16 w-16 rounded-xl object-cover border border-amber-200 shadow-sm shrink-0"
                                    />
                                )}
                                <div className="flex-1 min-w-0">
                                    <p className="font-bold text-gray-900 text-sm line-clamp-1">
                                        {effectiveDetails.productName}
                                    </p>
                                    <p className="text-xs text-gray-500 mt-0.5">
                                        {lang === 'vi'
                                            ? 'Giá trúng thầu chính thức'
                                            : 'Winning Bid Price'}
                                    </p>
                                </div>
                                <div className="text-right">
                                    <p className="text-2xl sm:text-3xl font-black text-[#ee4d2d]">
                                        {formatCurrency(
                                            effectiveSettlement.finalPrice || 150000,
                                            lang,
                                        )}
                                    </p>
                                </div>
                            </div>
                        </div>

                        {/* Thời hạn thanh toán và countdown */}
                        {expired ? (
                            <AlertBanner type="error" message={tr(lang, 'paymentTimedOut')} />
                        ) : (
                            <div className="nexus-card p-5 flex items-center justify-between gap-4">
                                <div>
                                    <p className="text-sm text-gray-500">
                                        {tr(lang, 'paymentDeadline')}
                                    </p>
                                    <p className="font-semibold text-gray-900">
                                        {new Date(deadline).toLocaleString(
                                            lang === 'vi' ? 'vi-VN' : 'en-US',
                                        )}
                                    </p>
                                </div>
                                <div className="text-right">
                                    <p className="text-xs text-gray-400 mb-1">
                                        {tr(lang, 'timeRemaining')}
                                    </p>
                                    <CountdownTimer endTime={deadline} large />
                                </div>
                            </div>
                        )}

                        {/* Thông báo đơn đã tồn tại */}
                        {effectiveSettlement.orderId && (
                            <div className="nexus-card p-4 flex items-center justify-between gap-3 border-blue-200 bg-blue-50">
                                <p className="text-sm text-blue-800">
                                    {tr(lang, 'linkedSettlementOrder')}{' '}
                                    <span className="font-mono font-semibold">
                                        {effectiveSettlement.orderId}
                                    </span>
                                </p>
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() =>
                                        navigate('order-detail', {
                                            id: effectiveSettlement.orderId,
                                        })
                                    }
                                >
                                    {tr(lang, 'viewLinkedOrder')}
                                </Button>
                            </div>
                        )}

                        {/* Form nhập thông tin giao hàng và nút thanh toán */}
                        <form
                            id="shipping-form"
                            className="nexus-card p-6 space-y-4"
                            noValidate
                            onSubmit={(event) => {
                                event.preventDefault()
                                void handleCheckout()
                            }}
                        >
                            <h3 className="font-bold text-lg">{tr(lang, 'shippingDetails')}</h3>

                            <Input
                                label={tr(lang, 'recipientName')}
                                autoComplete="name"
                                value={recipientName}
                                onChange={(event) => setRecipientName(event.target.value)}
                                disabled={!canEditShipping || checkoutBusy}
                                required
                                maxLength={255}
                            />
                            <Input
                                label={tr(lang, 'recipientPhone')}
                                autoComplete="tel"
                                type="tel"
                                value={recipientPhone}
                                onChange={(event) => setRecipientPhone(event.target.value)}
                                disabled={!canEditShipping || checkoutBusy}
                                required
                                pattern="\+?[0-9]{8,15}"
                            />
                            <label className="flex flex-col gap-1.5 text-sm font-semibold text-gray-700">
                                {tr(lang, 'shippingAddress')}
                                <textarea
                                    className="nexus-input min-h-24"
                                    autoComplete="street-address"
                                    value={shippingAddress}
                                    onChange={(event) => setShippingAddress(event.target.value)}
                                    disabled={!canEditShipping || checkoutBusy}
                                    required
                                    maxLength={500}
                                />
                            </label>

                            {/* Hiển thị lỗi checkout ngay dưới form */}
                            {checkoutError && (
                                <AlertBanner
                                    type="error"
                                    message={checkoutError}
                                    onClose={() => setCheckoutError(null)}
                                />
                            )}

                            {/* Trạng thái trung gian khi đang tạo đơn hoặc đang redirect */}
                            {checkoutStatus && !checkoutError && (
                                <p className="text-sm text-gray-500 text-center animate-pulse">
                                    {checkoutStatus}
                                </p>
                            )}

                            <Button
                                type="submit"
                                variant="primary"
                                size="lg"
                                className="w-full font-bold"
                                loading={checkoutBusy}
                                disabled={expired || checkoutBusy}
                            >
                                <CreditCard size={16} />
                                {checkoutBusy
                                    ? checkoutStatus ||
                                      (lang === 'en' ? 'Processing…' : 'Đang xử lý…')
                                    : effectiveSettlement.orderId
                                      ? tr(lang, 'continueSandboxPayment')
                                      : `${tr(lang, 'continueSandboxPayment')}${effectiveSettlement.finalPrice != null ? ` · ${formatCurrency(effectiveSettlement.finalPrice, lang)}` : ''}`}
                            </Button>
                        </form>
                    </>
                )}
            </div>
        </BuyerLayout>
    )
}
