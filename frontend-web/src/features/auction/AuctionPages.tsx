import { useState, useEffect, useCallback, useMemo } from 'react'
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
    AlertTriangle,
    ClipboardList,
    Trophy,
    Clock,
    SkeletonCard,
    Search,
    Filter,
    X,
    CircleX,
    CheckCircle,
    Shield,
    TrendingUp,
} from '../../components/UI'
import { AUCTIONS } from '../../mockData'
import { auctionsApi } from '../../api/auctions'
import { readLocalMockOrders, writeLocalMockOrders } from '../../api/orders'
import type { Auction, Order } from '../../types'

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
    const [statusFilter, setStatusFilter] = useState('all')
    const [sortBy, setSortBy] = useState('ending-soon')
    const [searchQuery, setSearchQuery] = useState('')
    const [minPriceInput, setMinPriceInput] = useState('')
    const [maxPriceInput, setMaxPriceInput] = useState('')
    const [appliedMinPrice, setAppliedMinPrice] = useState<number | undefined>(undefined)
    const [appliedMaxPrice, setAppliedMaxPrice] = useState<number | undefined>(undefined)
    const [page, setPage] = useState(1)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [auctions, setAuctions] = useState<Auction[]>([])
    const [totalItems, setTotalItems] = useState(0)
    const [mobileFilterOpen, setMobileFilterOpen] = useState(false)

    // Tải danh sách phiên đấu giá qua API Adapter
    const fetchAuctions = useCallback(async () => {
        setLoading(true)
        setError('')
        try {
            const res = await auctionsApi.getAuctions({
                status: statusFilter,
                search: searchQuery,
                sortBy,
                minPrice: appliedMinPrice,
                maxPrice: appliedMaxPrice,
                page,
                limit: 9,
                extraAuctions: createdAuctions,
                reviews: auctionReviews,
            })
            setAuctions(res.items)
            setTotalItems(res.total)
        } catch (err) {
            setError(
                lang === 'en'
                    ? 'Failed to load auctions from Auction Service. Please try again.'
                    : 'Không thể tải danh sách phiên đấu giá từ máy chủ. Vui lòng thử lại.',
            )
        } finally {
            setLoading(false)
        }
    }, [statusFilter, searchQuery, sortBy, appliedMinPrice, appliedMaxPrice, page, createdAuctions, auctionReviews, lang])

    useEffect(() => {
        fetchAuctions()
        // Đồng bộ lại danh sách để phiên vừa đến startTime chuyển tab từ Scheduled sang Active.
        const refreshTimer = window.setInterval(fetchAuctions, 5000)
        return () => window.clearInterval(refreshTimer)
    }, [fetchAuctions])

    // Xử lý áp dụng khoảng giá
    const handleApplyPrice = () => {
        const min = minPriceInput ? parseFloat(minPriceInput) : undefined
        const max = maxPriceInput ? parseFloat(maxPriceInput) : undefined
        setAppliedMinPrice(min)
        setAppliedMaxPrice(max)
        setPage(1)
    }

    // Xóa bộ lọc
    const handleClearFilters = () => {
        setStatusFilter('all')
        setSearchQuery('')
        setMinPriceInput('')
        setMaxPriceInput('')
        setAppliedMinPrice(undefined)
        setAppliedMaxPrice(undefined)
        setPage(1)
    }

    const STATUS_TABS = [
        { id: 'all', label: tr(lang, 'all') },
        { id: 'active', label: tr(lang, 'statusActive') },
        { id: 'scheduled', label: tr(lang, 'statusScheduled') },
        { id: 'ended', label: tr(lang, 'statusEnded') },
    ]

    const liveCount = auctions.filter((a) => a.status === 'active').length

    return (
        <BuyerLayout>
            {/* Header thanh tiêu đề và tìm kiếm */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-2xl font-bold text-gray-900">{tr(lang, 'auctions')}</h1>
                    <p className="text-gray-500 text-sm mt-1">
                        {lang === 'en'
                            ? `${liveCount} live auctions right now`
                            : `${liveCount} phiên đấu giá đang diễn ra`}
                    </p>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                    {/* Ô tìm kiếm nhanh */}
                    <div className="relative flex-1 sm:w-64">
                        <Input
                            placeholder={lang === 'en' ? 'Search auctions...' : 'Tìm phiên đấu giá...'}
                            value={searchQuery}
                            onChange={(e) => {
                                setSearchQuery(e.target.value)
                                setPage(1)
                            }}
                            className="text-sm py-2 pl-9 pr-3 w-full"
                        />
                        <div className="absolute left-3 top-2.5 text-gray-400 pointer-events-none">
                            <Search size={16} />
                        </div>
                    </div>

                    {/* Sắp xếp */}
                    <select
                        value={sortBy}
                        onChange={(e) => {
                            setSortBy(e.target.value)
                            setPage(1)
                        }}
                        className="nexus-input text-sm py-2 w-auto pr-8"
                    >
                        <option value="ending-soon">
                            {lang === 'en' ? 'Ending Soon' : 'Sắp kết thúc'}
                        </option>
                        <option value="most-bids">
                            {lang === 'en' ? 'Most Bids' : 'Nhiều lượt đặt nhất'}
                        </option>
                        <option value="newest">{lang === 'en' ? 'Newest' : 'Mới nhất'}</option>
                        <option value="price-asc">
                            {lang === 'en' ? 'Price: Low to High' : 'Giá: Thấp đến Cao'}
                        </option>
                        <option value="price-desc">
                            {lang === 'en' ? 'Price: High to Low' : 'Giá: Cao đến Thấp'}
                        </option>
                    </select>

                    {/* Nút bật bộ lọc trên mobile */}
                    <button
                        type="button"
                        onClick={() => setMobileFilterOpen(!mobileFilterOpen)}
                        className="md:hidden nexus-btn nexus-btn-secondary px-3 py-2 flex items-center gap-1.5 text-sm"
                    >
                        <Filter size={15} />
                        <span>{lang === 'en' ? 'Filters' : 'Bộ lọc'}</span>
                    </button>
                </div>
            </div>

            <div className="flex flex-col md:flex-row gap-6">
                {/* Sidebar bộ lọc trạng thái và giá */}
                <aside
                    className={`w-full md:w-56 shrink-0 space-y-4 ${
                        mobileFilterOpen ? 'block' : 'hidden md:block'
                    }`}
                >
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm mb-3">
                            {lang === 'en' ? 'Status' : 'Trạng thái'}
                        </h3>
                        <div className="space-y-1">
                            {STATUS_TABS.map((t) => (
                                <button
                                    key={t.id}
                                    onClick={() => {
                                        setStatusFilter(t.id)
                                        setPage(1)
                                    }}
                                    className={`flex w-full items-center gap-2 px-2.5 py-2 rounded-lg text-sm transition-colors ${
                                        statusFilter === t.id
                                            ? 'bg-primary-light text-primary font-semibold'
                                            : 'text-gray-600 hover:bg-gray-50'
                                    }`}
                                >
                                    <span
                                        className={`w-2.5 h-2.5 rounded-full ${
                                            t.id === 'active'
                                                ? 'bg-auction'
                                                : t.id === 'scheduled'
                                                  ? 'bg-info'
                                                  : t.id === 'ended'
                                                    ? 'bg-gray-400'
                                                    : 'bg-gray-200'
                                        }`}
                                    />
                                    <span>{t.label}</span>
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm mb-3">
                            {lang === 'en' ? 'Price Range' : 'Khoảng giá'}
                        </h3>
                        <div className="space-y-2">
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Min bid' : 'Giá thấp nhất'}
                                value={minPriceInput}
                                onChange={(e) => setMinPriceInput(e.target.value)}
                                className="text-xs py-1.5"
                            />
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Max bid' : 'Giá cao nhất'}
                                value={maxPriceInput}
                                onChange={(e) => setMaxPriceInput(e.target.value)}
                                className="text-xs py-1.5"
                            />
                            <div className="flex gap-2 pt-1">
                                <Button
                                    variant="primary"
                                    size="sm"
                                    className="flex-1 text-xs"
                                    onClick={handleApplyPrice}
                                >
                                    {lang === 'en' ? 'Apply' : 'Áp dụng'}
                                </Button>
                                {(appliedMinPrice !== undefined || appliedMaxPrice !== undefined) && (
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        className="text-xs"
                                        onClick={() => {
                                            setMinPriceInput('')
                                            setMaxPriceInput('')
                                            setAppliedMinPrice(undefined)
                                            setAppliedMaxPrice(undefined)
                                            setPage(1)
                                        }}
                                    >
                                        <X size={12} />
                                    </Button>
                                )}
                            </div>
                        </div>
                    </div>
                </aside>

                {/* Vùng hiển thị nội dung chính */}
                <div className="flex-1 min-w-0">
                    {/* Trạng thái lỗi (Error State) */}
                    {error && !loading && (
                        <div className="nexus-card p-8 text-center space-y-4 border-red-200 bg-red-50/50 mb-6">
                            <div className="w-12 h-12 bg-red-100 text-red-600 rounded-full flex items-center justify-center mx-auto">
                                <AlertTriangle size={24} />
                            </div>
                            <div>
                                <h3 className="font-bold text-gray-900 text-base">
                                    {lang === 'en'
                                        ? 'Error Loading Auctions'
                                        : 'Lỗi tải danh sách phiên đấu giá'}
                                </h3>
                                <p className="text-sm text-gray-600 mt-1 max-w-md mx-auto">{error}</p>
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

                    {/* Trạng thái đang tải (Loading State với SkeletonCard) */}
                    {loading && (
                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
                            {Array.from({ length: 6 }).map((_, idx) => (
                                <SkeletonCard key={idx} />
                            ))}
                        </div>
                    )}

                    {/* Trạng thái rỗng (Empty State) */}
                    {!loading && !error && auctions.length === 0 && (
                        <div className="nexus-card p-8">
                            <EmptyState
                                icon={<Gavel size={32} />}
                                title={
                                    lang === 'en'
                                        ? 'No auctions found'
                                        : 'Không tìm thấy phiên đấu giá nào'
                                }
                                subtitle={
                                    lang === 'en'
                                        ? 'There are currently no auctions matching your filter criteria. Try clearing filters or searching for something else.'
                                        : 'Không có phiên đấu giá nào phù hợp với bộ lọc hiện tại. Hãy thử thay đổi từ khóa hoặc khoảng giá.'
                                }
                                action={
                                    (statusFilter !== 'all' ||
                                        searchQuery ||
                                        appliedMinPrice !== undefined ||
                                        appliedMaxPrice !== undefined) ? (
                                        <Button
                                            variant="outline"
                                            size="sm"
                                            onClick={handleClearFilters}
                                            className="mt-3"
                                        >
                                            {lang === 'en' ? 'Clear all filters' : 'Xóa toàn bộ bộ lọc'}
                                        </Button>
                                    ) : undefined
                                }
                            />
                        </div>
                    )}

                    {/* Danh sách phiên đấu giá hợp lệ (Success State) */}
                    {!loading && !error && auctions.length > 0 && (
                        <>
                            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
                                {auctions.map((a) => (
                                    <AuctionCard key={a.id} auction={a} />
                                ))}
                            </div>
                            {totalItems > 9 && (
                                <div className="flex justify-center mt-8">
                                    <Pagination
                                        page={page}
                                        total={totalItems}
                                        perPage={9}
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
 *      - success: Hiển thị giao diện 2 cột gồm Gallery ảnh + Lịch sử bid bên trái và Bảng đặt giá bên phải.
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
    const auctionId = String(pageParams?.id || '')
    const [auction, setAuction] = useState<Auction | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [customBid, setCustomBid] = useState('')
    const [bidPlaced, setBidPlaced] = useState(false)
    const [bidError, setBidError] = useState('')
    const [placing, setPlacing] = useState(false)
    const [selectedImageIndex, setSelectedImageIndex] = useState(0)
    const [isOffline, setIsOffline] = useState(() =>
        typeof navigator !== 'undefined' ? !navigator.onLine : false,
    )
    const [reconnecting, setReconnecting] = useState(false)

    // Tải thông tin chi tiết qua API Adapter
    const loadAuctionDetail = useCallback(async (backgroundRefresh = false) => {
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
            const data = await auctionsApi.getAuctionById(auctionId, createdAuctions, auctionReviews)
            setAuction(data)
            if (!backgroundRefresh) setCustomBid(String(data.currentBid + data.minIncrement))
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
    }, [auctionId, createdAuctions, auctionReviews, lang])

    useEffect(() => {
        loadAuctionDetail()
        // Làm mới status và bid history theo chu kỳ mở phiên của Auction Service khi Buyer đang xem detail.
        const refreshTimer = window.setInterval(() => loadAuctionDetail(true), 5000)
        return () => window.clearInterval(refreshTimer)
    }, [loadAuctionDetail])

    /**
     * Chức năng: Giám sát kết nối mạng (online/offline) và tự động đồng bộ dữ liệu phiên đấu giá khi phục hồi.
     * Đầu vào: Sự kiện 'online' và 'offline' từ window.
     * Kết quả: Cập nhật cờ isOffline, đồng bộ lại dữ liệu phiên đấu giá từ server không cần người dùng reload.
     * Luồng:
     *   1. Bắt 'offline': Cập nhật state isOffline = true, hiển thị cảnh báo và khóa thao tác đặt giá.
     *   2. Bắt 'online': Cập nhật state isOffline = false, gọi loadAuctionDetail() để kéo giá chốt và lịch sử mới nhất.
     */
    useEffect(() => {
        const handleOffline = () => {
            setIsOffline(true)
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Network connection lost. Live bidding paused.'
                        : 'Mất kết nối mạng. Đặt giá thầu tạm thời bị tạm dừng.',
                duration: 5000,
            })
        }

        const handleOnline = async () => {
            setIsOffline(false)
            setReconnecting(true)
            showToast({
                type: 'info',
                message:
                    lang === 'en'
                        ? 'Connection restored. Resyncing latest auction data...'
                        : 'Đã khôi phục mạng. Đang kết nối lại và đồng bộ dữ liệu...',
            })
            try {
                await loadAuctionDetail()
                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? 'Auction data resynced successfully!'
                            : 'Đồng bộ dữ liệu phiên đấu giá thành công!',
                })
            } finally {
                setReconnecting(false)
            }
        }

        window.addEventListener('offline', handleOffline)
        window.addEventListener('online', handleOnline)
        return () => {
            window.removeEventListener('offline', handleOffline)
            window.removeEventListener('online', handleOnline)
        }
    }, [loadAuctionDetail, lang, showToast])

    /**
     * Chức năng: Tìm đơn hàng (Order) đã tồn tại nếu người thắng cuộc đã hoàn tất thanh toán cho phiên.
     * Đầu vào: auction?.id hoặc auction?.orderId từ kho lưu trữ đơn hàng.
     * Kết quả: Trả về đối tượng Order nếu có hoặc null.
     */
    const existingOrder = useMemo(() => {
        if (!auction) return null
        const orders = readLocalMockOrders()
        return orders.find((o) => o.auctionId === auction.id || o.id === auction.orderId) || null
    }, [auction])

    /**
     * Chức năng: Xác định mức giá cao nhất mà tài khoản hiện tại đã tham gia đặt giá trong phiên.
     * Đầu vào: auction?.bids và thông tin tài khoản user.
     * Kết quả: Mức giá đặt cao nhất dạng number (hoặc 0 nếu chưa từng tham gia).
     */
    const userHighestBid = useMemo(() => {
        if (!auction || !user) return 0
        const myBids = auction.bids.filter(
            (b) =>
                (user.id && b.bidderId === user.id) ||
                (user.fullName && b.bidderName.toLowerCase() === user.fullName.toLowerCase()),
        )
        if (myBids.length === 0) return 0
        return Math.max(...myBids.map((b) => b.amount))
    }, [auction, user])

    /**
     * Chức năng: Xử lý sự kiện khi đồng hồ đếm ngược kết thúc (Countdown Ends).
     * Luồng xử lý:
     *   1. Kiểm tra trạng thái nếu đang 'active' thì chuyển sang quyết toán kết thúc phiên.
     *   2. Gọi auctionsApi.settleAuction(auction.id) để xác định người thắng và trạng thái chốt.
     *   3. Cập nhật state auction ngay lập tức mà không yêu cầu reload trang.
     */
    const handleCountdownEnd = useCallback(async () => {
        if (!auction || auction.status !== 'active') return
        try {
            const settled = await auctionsApi.settleAuction(auction.id)
            setAuction(settled)
            showToast({
                type: 'info',
                message:
                    lang === 'en'
                        ? 'Auction countdown reached zero. Auction settled!'
                        : 'Phiên đấu giá đã kết thúc và được quyết toán!',
            })
        } catch {
            // Fallback khi settle API gặp sự cố: cập nhật state ended tại client
            setAuction((prev) => {
                if (!prev) return prev
                const sortedBids = [...prev.bids].sort((a, b) => b.amount - a.amount)
                const highest = sortedBids[0]
                const reserveMet =
                    !prev.reservePrice || (highest && highest.amount >= prev.reservePrice)
                return {
                    ...prev,
                    status: 'ended',
                    winnerId: reserveMet && highest ? highest.bidderId : undefined,
                    winnerName: reserveMet && highest ? highest.bidderName : undefined,
                }
            })
        }
    }, [auction, lang, showToast])

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
                            {lang === 'en'
                                ? 'Auction Not Found'
                                : 'Không tìm thấy phiên đấu giá'}
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
                            {lang === 'en'
                                ? '← Back to Auctions'
                                : '← Quay lại danh sách đấu giá'}
                        </Button>
                        {error && (
                            <Button
                                variant="outline"
                                size="md"
                                onClick={() => loadAuctionDetail()}
                            >
                                {lang === 'en' ? 'Retry' : 'Thử lại'}
                            </Button>
                        )}
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    const minBid = auction.currentBid + auction.minIncrement
    const isActive = auction.status === 'active'
    const isScheduled = auction.status === 'scheduled'
    const isEnded = ['ended', 'settled', 'cancelled', 'failed'].includes(auction.status)
    const isSeller = Boolean(user?.id && auction.sellerId === user.id)

    // Người dùng đã từng trả giá trong phiên này
    const hasUserParticipated = bidPlaced || userHighestBid > 0

    // Người dùng hiện tại là người chiến thắng chung cuộc
    const isWinner =
        isEnded &&
        Boolean(
            user?.id &&
                (auction.winnerId === user.id ||
                    (auction.winnerName &&
                        user.fullName &&
                        auction.winnerName.toLowerCase() === user.fullName.toLowerCase()) ||
                    (bidPlaced && !auction.winnerId)),
        )

    // Người dùng tham gia đặt giá nhưng không thắng (Loser State)
    const isLoser = isEnded && hasUserParticipated && !isWinner

    // Phiên kết thúc mà không phát sinh bất kỳ lượt đặt giá nào
    const isNoBids = isEnded && auction.bids.length === 0 && !auction.winnerId

    // Phiên kết thúc nhưng mức giá chưa đạt giá sàn (Reserve Price Not Met)
    const isReserveNotMet =
        isEnded &&
        !isNoBids &&
        Boolean(auction.reservePrice && auction.currentBid < auction.reservePrice)

    // Người dùng hiện đang dẫn đầu trong lúc phiên đang active
    const isHighestBidder = bidPlaced || (isActive && Boolean(user?.id && auction.winnerId === user.id))

    /**
     * Chức năng: Đặt giá thầu cho phiên đấu giá (Place Bid) có tích hợp Anti-sniping.
     * Luồng:
     *   1. Validate quyền: Người bán không được tự đặt giá (chk_seller_not_bidder).
     *   2. Validate giá tối thiểu (minBid) và điểm uy tín người dùng.
     *   3. Gửi auctionsApi.placeBid(); nếu bid rơi vào cửa sổ phút chót (<=5 phút),
     *      phiên được tự động gia hạn (res.extended = true, new endTime).
     *   4. Cập nhật state thời gian kết thúc mới và thông báo toast cảnh báo Anti-sniping.
     */
    const placeBid = async (amount: number) => {
        if (!isActive) return

        // 1. Kiểm tra trạng thái mạng: Không cho phép đặt giá khi đang ngoại tuyến
        if (isOffline || (typeof navigator !== 'undefined' && !navigator.onLine)) {
            setBidError(
                lang === 'en'
                    ? 'Cannot place bid while offline. Please check your internet connection.'
                    : 'Không thể đặt giá khi đang mất mạng. Vui lòng kiểm tra lại kết nối Internet.',
            )
            return
        }

        // 2. Kiểm tra xác thực: Bắt buộc đăng nhập tài khoản trước khi tham gia đấu giá
        if (!user) {
            setBidError(
                lang === 'en'
                    ? 'Please log in to participate in bidding.'
                    : 'Vui lòng đăng nhập để tham gia đặt giá.',
            )
            return
        }

        // 3. Kiểm tra không được là người bán của phiên (chk_seller_not_bidder)
        if (isSeller) {
            setBidError(
                lang === 'en'
                    ? 'You cannot bid on your own auction.'
                    : 'Người bán không được phép tự đặt giá vào phiên của chính mình.',
            )
            return
        }

        // 4. Kiểm tra mức giá tối thiểu
        if (amount < minBid) {
            setBidError(
                lang === 'en'
                    ? `Minimum bid must be at least ${formatCurrency(minBid, lang)}`
                    : `Mức giá đặt phải tối thiểu là ${formatCurrency(minBid, lang)} (Giá hiện tại + Bước giá)`,
            )
            return
        }

        // 5. Kiểm tra điểm uy tín
        if ((user?.reputationScore || 0) < 30 && user?.reputationScore !== undefined) {
            setBidError(tr(lang, 'insufficientReputation'))
            return
        }

        setPlacing(true)
        setBidError('')

        try {
            const res = await auctionsApi.placeBid(auction.id, amount)
            setBidPlaced(true)
            // Cập nhật state chi tiết phiên đấu giá với lượt bid mới và thông tin gia hạn Anti-sniping
            setAuction((prev) => {
                if (!prev) return prev
                return {
                    ...prev,
                    currentBid: res.currentBid,
                    bidCount: res.bidCount,
                    bids: [res.bid, ...prev.bids],
                    winnerId: user?.id || prev.winnerId,
                    winnerName: user?.fullName || prev.winnerName,
                    endTime: res.endTime || prev.endTime,
                    extensionCount: res.extended
                        ? (prev.extensionCount || 0) + 1
                        : prev.extensionCount,
                    isExtended: res.extended ? true : prev.isExtended,
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
            if (res.extended) {
                showToast({
                    type: 'warning',
                    message:
                        lang === 'en'
                            ? 'Anti-sniping triggered! Auction extended by 5 minutes.'
                            : 'Kích hoạt Anti-Sniping! Phiên đấu giá được gia hạn thêm 5 phút.',
                    duration: 6000,
                })
            }
        } catch (err) {
            setBidError(
                err instanceof Error
                    ? err.message
                    : lang === 'en'
                      ? 'Failed to place bid. Please try again.'
                      : 'Đặt giá thầu thất bại. Vui lòng thử lại.',
            )
        } finally {
            setPlacing(false)
        }
    }

    const images =
        auction.product.images && auction.product.images.length > 0
            ? auction.product.images
            : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80']

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
                            className="nexus-card overflow-hidden mb-3 relative bg-slate-50 flex items-center justify-center"
                            style={{ aspectRatio: '4/3' }}
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
                                        <img src={img} className="w-full h-full object-cover" alt="" />
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
                            <span>
                                {lang === 'en' ? 'Seller' : 'Người bán'}:{' '}
                                <span className="font-semibold text-gray-800">
                                    {auction.sellerName}
                                </span>
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
                                {auction.bids.map((bid, i) => (
                                    <div
                                        key={bid.id}
                                        className={`flex items-center justify-between p-3.5 rounded-xl transition-colors ${
                                            i === 0
                                                ? 'bg-orange-50 border-2 border-orange-200'
                                                : 'bg-gray-50 border border-gray-100'
                                        }`}
                                    >
                                        <div className="flex items-center gap-3">
                                            <div
                                                className={`w-8 h-8 rounded-full flex items-center justify-center font-bold text-xs ${
                                                    i === 0
                                                        ? 'bg-amber-500 text-white shadow-sm'
                                                        : 'bg-gray-200 text-gray-600'
                                                }`}
                                            >
                                                {i === 0 ? <Trophy size={14} /> : `#${i + 1}`}
                                            </div>
                                            <div>
                                                <p className="font-semibold text-sm text-gray-900 flex items-center gap-1.5">
                                                    <span>{bid.bidderName}</span>
                                                    {i === 0 && (
                                                        <span className="badge badge-orange text-[10px] px-1.5 py-0.5">
                                                            {lang === 'en' ? 'Highest' : 'Cao nhất'}
                                                        </span>
                                                    )}
                                                </p>
                                                <p className="text-xs text-gray-400">
                                                    {new Date(bid.timestamp).toLocaleTimeString()} ·{' '}
                                                    {new Date(bid.timestamp).toLocaleDateString()}
                                                </p>
                                            </div>
                                        </div>
                                        <span
                                            className={`font-black text-base ${
                                                i === 0 ? 'text-auction' : 'text-gray-800'
                                            }`}
                                        >
                                            {formatCurrency(bid.amount, lang)}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>

                {/* Cột phải: Bảng điều khiển đặt giá (Bidding Panel) */}
                <div className="space-y-4">
                    {/* Trạng thái phiên LIVE */}
                    {isActive && (
                        <div className="nexus-card p-6 border-orange-200 bg-white shadow-sm space-y-4">
                            <div className="flex items-center justify-between gap-2">
                                <div className="flex items-center gap-2">
                                    <span className="badge badge-orange auction-live">LIVE</span>
                                    <span className="text-sm font-semibold text-gray-700">
                                        {lang === 'en'
                                            ? 'Auction in progress'
                                            : 'Phiên đang diễn ra'}
                                    </span>
                                </div>
                                <span className="text-xs text-gray-400">
                                    {auction.bidCount} {lang === 'en' ? 'bids' : 'lượt'}
                                </span>
                            </div>

                            {/* Banner cảnh báo phiên đã được kích hoạt Anti-sniping gia hạn thêm thời gian */}
                            {auction.isExtended && (
                                <div className="p-3 bg-amber-50 rounded-xl border border-amber-300 text-amber-900 flex items-start gap-2.5 text-xs">
                                    <Clock size={16} className="text-amber-600 shrink-0 mt-0.5" />
                                    <div>
                                        <p className="font-bold">
                                            {lang === 'en'
                                                ? `Auction Extended (+${auction.extensionCount || 1} time${(auction.extensionCount || 1) > 1 ? 's' : ''})`
                                                : `Phiên đã được gia hạn (+${auction.extensionCount || 1} lần)`}
                                        </p>
                                        <p className="text-[11px] text-amber-700 mt-0.5 leading-relaxed">
                                            {lang === 'en'
                                                ? 'Anti-sniping protection extended the end time to ensure fair bidding.'
                                                : 'Quy tắc chống bắn tỉa phút chót đã tự động gia hạn thời gian kết thúc.'}
                                        </p>
                                    </div>
                                </div>
                            )}

                            <div className="bg-orange-50/60 p-4 rounded-xl border border-orange-100">
                                <p className="text-xs text-gray-500 font-medium">
                                    {tr(lang, 'currentBid')}
                                </p>
                                <p className="text-3xl font-black text-auction tracking-tight mt-0.5">
                                    {formatCurrency(auction.currentBid, lang)}
                                </p>
                                <p className="text-xs text-gray-400 mt-1">
                                    {lang === 'en'
                                        ? `Starting bid: ${formatCurrency(auction.startingPrice, lang)}`
                                        : `Giá khởi điểm: ${formatCurrency(auction.startingPrice, lang)}`}
                                </p>
                            </div>

                            <div>
                                <div className="flex items-center justify-between mb-1.5">
                                    <p className="text-xs text-gray-500 font-medium">
                                        {tr(lang, 'timeLeft')}
                                    </p>
                                    {auction.isExtended && (
                                        <span className="text-[11px] font-semibold text-amber-700 bg-amber-100 px-2 py-0.5 rounded-full">
                                            {lang === 'en' ? 'Extended' : 'Đã gia hạn'}
                                        </span>
                                    )}
                                </div>
                                {/* CountdownTimer tự động cập nhật mỗi giây và gọi onEnd khi hết giờ mà không cần reload */}
                                <CountdownTimer
                                    endTime={auction.endTime}
                                    large
                                    onEnd={handleCountdownEnd}
                                />
                            </div>

                            {isHighestBidder && (
                                <AlertBanner
                                    type="success"
                                    message={
                                        lang === 'en'
                                            ? 'You are currently the highest bidder!'
                                            : 'Bạn đang dẫn đầu phiên đấu giá này!'
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

                    {/* Trạng thái ENDED: Xử lý hiển thị Winner / Loser / No Bids / Reserve Not Met & Summary */}
                    {isEnded && (
                        <div className="nexus-card p-6 shadow-sm space-y-4">
                            {/* 1. WINNER STATE: Người dùng thắng đấu giá */}
                            {isWinner && (
                                <div className="p-5 rounded-2xl bg-gradient-to-br from-emerald-50 to-emerald-100/60 border-2 border-emerald-300 text-center space-y-3">
                                    <div className="w-16 h-16 bg-emerald-500 text-white rounded-full flex items-center justify-center mx-auto shadow-md">
                                        <Trophy size={32} />
                                    </div>
                                    <div>
                                        <span className="badge badge-green text-xs font-bold px-3 py-1">
                                            {lang === 'en' ? 'WINNER' : 'CHIẾN THẮNG'}
                                        </span>
                                        <h3 className="font-black text-emerald-950 text-xl mt-2">
                                            {lang === 'en'
                                                ? 'Congratulations! You Won This Auction!'
                                                : 'Chúc mừng! Bạn đã thắng phiên đấu giá!'}
                                        </h3>
                                        <p className="text-xs text-emerald-800 mt-1">
                                            {lang === 'en'
                                                ? 'You placed the winning bid for this product.'
                                                : 'Bạn đã đưa ra mức giá trả cao nhất và giành chiến thắng.'}
                                        </p>
                                    </div>

                                    <div className="bg-white/90 backdrop-blur rounded-xl p-3 border border-emerald-200">
                                        <span className="text-xs text-emerald-800 font-medium block">
                                            {lang === 'en' ? 'Winning Final Bid' : 'Giá thắng chung cuộc'}
                                        </span>
                                        <span className="text-3xl font-black text-emerald-700 tracking-tight">
                                            {formatCurrency(auction.currentBid, lang)}
                                        </span>
                                    </div>

                                    {/* Điều hướng tạo Order hoặc xem Order đã có theo contract */}
                                    {existingOrder ? (
                                        <div className="space-y-2 pt-1">
                                            <div className="flex items-center justify-center gap-1.5 text-xs text-emerald-800 font-semibold">
                                                <CheckCircle size={14} className="text-emerald-600" />
                                                <span>
                                                    {lang === 'en'
                                                        ? `Order Created (${existingOrder.orderNo || existingOrder.id})`
                                                        : `Đơn hàng đã được tạo (${existingOrder.orderNo || existingOrder.id})`}
                                                </span>
                                            </div>
                                            <Button
                                                variant="primary"
                                                size="lg"
                                                className="w-full bg-emerald-600 hover:bg-emerald-700 font-bold shadow-md"
                                                onClick={() =>
                                                    navigate('order-detail', { id: existingOrder.id })
                                                }
                                            >
                                                {lang === 'en' ? 'View Order Details' : 'Xem chi tiết đơn hàng'}
                                            </Button>
                                        </div>
                                    ) : (
                                        <Button
                                            variant="primary"
                                            size="lg"
                                            className="w-full bg-emerald-600 hover:bg-emerald-700 font-bold shadow-md"
                                            onClick={() =>
                                                navigate('auction-payment', {
                                                    auctionId: auction.id,
                                                })
                                            }
                                        >
                                            {lang === 'en' ? 'Proceed to Order & Checkout' : 'Tiến hành đặt hàng & Thanh toán'}
                                        </Button>
                                    )}
                                </div>
                            )}

                            {/* 2. LOSER STATE: Người dùng đã tham gia trả giá nhưng bị outbid */}
                            {isLoser && (
                                <div className="p-5 rounded-2xl bg-rose-50/70 border border-rose-200 text-center space-y-3">
                                    <div className="w-14 h-14 bg-rose-100 text-rose-600 rounded-full flex items-center justify-center mx-auto">
                                        <CircleX size={28} />
                                    </div>
                                    <div>
                                        <span className="badge badge-red text-xs font-bold px-3 py-1">
                                            {lang === 'en' ? 'NOT WON' : 'KHÔNG TRÚNG ĐẤU GIÁ'}
                                        </span>
                                        <h3 className="font-bold text-gray-900 text-lg mt-2">
                                            {lang === 'en'
                                                ? 'You did not win this auction'
                                                : 'Rất tiếc! Bạn không thắng phiên đấu giá này'}
                                        </h3>
                                        <p className="text-xs text-gray-600 mt-1">
                                            {lang === 'en'
                                                ? 'Another bidder placed a higher bid before the auction ended.'
                                                : 'Đã có người tham gia khác đặt mức giá cao hơn trước khi phiên kết thúc.'}
                                        </p>
                                    </div>

                                    {/* So sánh giá cao nhất của bạn vs giá chốt */}
                                    <div className="grid grid-cols-2 gap-2 bg-white p-3 rounded-xl border border-rose-100 text-xs text-left">
                                        <div>
                                            <span className="text-gray-500 block">
                                                {lang === 'en' ? 'Your highest bid' : 'Giá cao nhất của bạn'}
                                            </span>
                                            <span className="font-bold text-gray-800 text-sm">
                                                {formatCurrency(userHighestBid, lang)}
                                            </span>
                                        </div>
                                        <div>
                                            <span className="text-gray-500 block">
                                                {lang === 'en' ? 'Winning bid' : 'Giá thắng chung cuộc'}
                                            </span>
                                            <span className="font-bold text-rose-600 text-sm">
                                                {formatCurrency(auction.currentBid, lang)}
                                            </span>
                                        </div>
                                    </div>

                                    <p className="text-[11px] text-gray-500 italic">
                                        {lang === 'en'
                                            ? 'Any security deposit or pre-auth hold will be released automatically.'
                                            : 'Tiền ký quỹ/đặt cọc của bạn (nếu có) sẽ được hoàn trả tự động theo quy chế.'}
                                    </p>

                                    <Button
                                        variant="outline"
                                        size="md"
                                        className="w-full"
                                        onClick={() => navigate('auction-list')}
                                    >
                                        {lang === 'en' ? 'Explore Other Auctions' : 'Duyệt các phiên đấu giá khác'}
                                    </Button>
                                </div>
                            )}

                            {/* 3. NO BIDS STATE: Phiên kết thúc mà không có ai đặt giá */}
                            {isNoBids && (
                                <div className="p-5 rounded-2xl bg-gray-50 border border-gray-200 text-center space-y-3">
                                    <div className="w-12 h-12 bg-gray-200 text-gray-500 rounded-full flex items-center justify-center mx-auto">
                                        <AlertTriangle size={24} />
                                    </div>
                                    <div>
                                        <span className="badge badge-gray text-xs font-bold px-3 py-1">
                                            {lang === 'en' ? 'NO WINNER' : 'CHƯA CÓ NGƯỜI THẮNG'}
                                        </span>
                                        <h3 className="font-bold text-gray-800 text-base mt-2">
                                            {lang === 'en'
                                                ? 'Auction ended — no bids placed'
                                                : 'Phiên kết thúc — chưa có người đặt giá'}
                                        </h3>
                                        <p className="text-xs text-gray-500 mt-1">
                                            {lang === 'en'
                                                ? 'No valid bids were recorded for this item.'
                                                : 'Không có lượt trả giá hợp lệ nào trước khi phiên đấu giá khép lại.'}
                                        </p>
                                    </div>
                                    <Button
                                        variant="outline"
                                        size="md"
                                        className="w-full"
                                        onClick={() => navigate('auction-list')}
                                    >
                                        {lang === 'en' ? 'Browse Live Auctions' : 'Xem các phiên đang diễn ra'}
                                    </Button>
                                </div>
                            )}

                            {/* 4. RESERVE NOT MET STATE: Chưa đạt giá sàn của người bán */}
                            {isReserveNotMet && (
                                <div className="p-5 rounded-2xl bg-amber-50/70 border border-amber-200 text-center space-y-3">
                                    <div className="w-12 h-12 bg-amber-100 text-amber-700 rounded-full flex items-center justify-center mx-auto">
                                        <Shield size={24} />
                                    </div>
                                    <div>
                                        <span className="badge badge-yellow text-xs font-bold px-3 py-1">
                                            {lang === 'en' ? 'RESERVE NOT MET' : 'CHƯA ĐẠT GIÁ SÀN'}
                                        </span>
                                        <h3 className="font-bold text-amber-900 text-base mt-2">
                                            {lang === 'en'
                                                ? 'Reserve price was not reached'
                                                : 'Chưa đạt mức giá sàn của người bán'}
                                        </h3>
                                        <p className="text-xs text-amber-800 mt-1">
                                            {lang === 'en'
                                                ? 'The highest bid did not meet the seller minimum reserve price.'
                                                : 'Mức giá đấu cao nhất chưa đạt mức giá bảo lưu tối thiểu đã cam kết.'}
                                        </p>
                                    </div>
                                </div>
                            )}

                            {/* 5. PUBLIC ENDED STATE: Khách vãng lai / Người dùng không tham gia đặt giá */}
                            {!isWinner && !isLoser && !isNoBids && !isReserveNotMet && (
                                <div className="p-5 rounded-2xl bg-gray-50 border border-gray-200 text-center space-y-2">
                                    <div className="w-12 h-12 bg-gray-200 text-gray-700 rounded-full flex items-center justify-center mx-auto">
                                        <Gavel size={24} />
                                    </div>
                                    <h3 className="font-bold text-gray-900 text-base">
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
                                </div>
                            )}

                            {/* 6. BẢNG TỔNG KẾT KẾT QUẢ ĐẤU GIÁ (Auction Result Summary) */}
                            <div className="border border-gray-200 rounded-xl p-4 bg-gray-50/50 space-y-2.5">
                                <h4 className="text-xs font-bold text-gray-700 uppercase tracking-wider flex items-center justify-between">
                                    <span>{lang === 'en' ? 'Auction Result Summary' : 'Tổng kết kết quả đấu giá'}</span>
                                    <span className="text-[10px] text-gray-400 font-mono">
                                        #{auction.id}
                                    </span>
                                </h4>
                                <div className="divide-y divide-gray-100 text-xs">
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Starting Price' : 'Giá khởi điểm'}</span>
                                        <span className="font-semibold text-gray-800">{formatCurrency(auction.startingPrice, lang)}</span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Final Price' : 'Giá chốt phiên'}</span>
                                        <span className="font-bold text-auction">{formatCurrency(auction.currentBid, lang)}</span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Growth' : 'Mức tăng'}</span>
                                        <span className="font-bold text-emerald-600 flex items-center gap-1">
                                            <TrendingUp size={12} />
                                            +{Math.max(0, (((auction.currentBid - auction.startingPrice) / (auction.startingPrice || 1)) * 100)).toFixed(1)}%
                                        </span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Total Bids' : 'Tổng số lượt đặt giá'}</span>
                                        <span className="font-semibold text-gray-800">{auction.bidCount}</span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Extensions (Anti-sniping)' : 'Số lần gia hạn'}</span>
                                        <span className="font-semibold text-gray-800">{auction.extensionCount || 0}</span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Ended At' : 'Thời gian kết thúc'}</span>
                                        <span className="font-semibold text-gray-800">
                                            {new Date(auction.endTime).toLocaleTimeString()} · {new Date(auction.endTime).toLocaleDateString()}
                                        </span>
                                    </div>
                                    <div className="py-1.5 flex justify-between">
                                        <span className="text-gray-500">{lang === 'en' ? 'Winner' : 'Người thắng'}</span>
                                        <span className="font-bold text-gray-900">
                                            {auction.winnerName || (lang === 'en' ? 'No winner' : 'Không có người thắng')}
                                        </span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Bảng nhập đặt giá thầu (Chỉ hiện khi ACTIVE) */}
                    {isActive && (
                        <div className="nexus-card p-6 shadow-sm space-y-4">
                            <div className="flex items-center justify-between gap-2">
                                <h3 className="font-bold text-gray-900 text-base">{tr(lang, 'placeBid')}</h3>
                                {/* Chỉ báo trạng thái kết nối mạng (Live Sync Indicator) */}
                                <div className="flex items-center gap-1.5 text-xs font-medium">
                                    {isOffline ? (
                                        <span className="flex items-center gap-1.5 text-red-600 bg-red-50 border border-red-200 px-2.5 py-0.5 rounded-full">
                                            <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                                            <span>{lang === 'en' ? 'Offline' : 'Ngoại tuyến'}</span>
                                        </span>
                                    ) : reconnecting ? (
                                        <span className="flex items-center gap-1.5 text-amber-700 bg-amber-50 border border-amber-200 px-2.5 py-0.5 rounded-full">
                                            <span className="w-2 h-2 rounded-full bg-amber-500 animate-ping" />
                                            <span>{lang === 'en' ? 'Syncing...' : 'Đang đồng bộ...'}</span>
                                        </span>
                                    ) : (
                                        <span className="flex items-center gap-1.5 text-emerald-700 bg-emerald-50 border border-emerald-200 px-2.5 py-0.5 rounded-full">
                                            <span className="w-2 h-2 rounded-full bg-emerald-500" />
                                            <span>{lang === 'en' ? 'Live Connected' : 'Kết nối trực tuyến'}</span>
                                        </span>
                                    )}
                                </div>
                            </div>

                            {/* Cảnh báo khi người dùng ngoại tuyến (Mất mạng) */}
                            {isOffline && (
                                <div className="p-3 bg-red-50 rounded-xl border border-red-200 text-red-800 text-xs flex items-start gap-2">
                                    <AlertTriangle size={15} className="shrink-0 text-red-600 mt-0.5" />
                                    <div className="flex-1">
                                        <p className="font-bold">
                                            {lang === 'en'
                                                ? 'Network connection lost'
                                                : 'Mất kết nối mạng Internet'}
                                        </p>
                                        <p className="text-[11px] text-red-700 mt-0.5">
                                            {lang === 'en'
                                                ? 'Bidding is disabled until connection is restored.'
                                                : 'Thao tác đặt giá tạm thời bị khóa cho đến khi kết nối mạng được khôi phục.'}
                                        </p>
                                    </div>
                                </div>
                            )}

                            {bidError && (
                                <AlertBanner
                                    type="error"
                                    message={bidError}
                                    onClose={() => setBidError('')}
                                />
                            )}

                            <div>
                                <p className="text-xs text-gray-500 mb-2 font-medium">
                                    {tr(lang, 'bidIncrement')}:{' '}
                                    <span className="font-bold text-gray-800">
                                        {formatCurrency(auction.minIncrement, lang)}
                                    </span>
                                </p>

                                {/* Các nút cộng nhanh bước giá */}
                                <div className="grid grid-cols-3 gap-2 mb-3">
                                    {[1, 2, 5].map((mult) => (
                                        <button
                                            key={mult}
                                            type="button"
                                            disabled={isSeller || isOffline}
                                            onClick={() =>
                                                setCustomBid(
                                                    String(
                                                        auction.currentBid +
                                                            auction.minIncrement * mult,
                                                    ),
                                                )
                                            }
                                            className="py-2 rounded-xl border border-auction/30 bg-orange-50/50 text-auction text-xs font-bold hover:bg-orange-100/70 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            +{formatCurrency(auction.minIncrement * mult, lang)}
                                        </button>
                                    ))}
                                </div>

                                <Input
                                    label={tr(lang, 'yourBid')}
                                    type="number"
                                    value={customBid}
                                    onChange={(e) => setCustomBid(e.target.value)}
                                    disabled={isSeller || isOffline}
                                    helper={
                                        lang === 'en'
                                            ? `Minimum required: ${formatCurrency(minBid, lang)}`
                                            : `Tối thiểu: ${formatCurrency(minBid, lang)}`
                                    }
                                />
                            </div>

                            {/* Kiểm tra và hiển thị nút đặt giá theo vai trò và quyền hạn người dùng */}
                            {!user ? (
                                <Button
                                    variant="primary"
                                    size="lg"
                                    className="w-full font-bold shadow-md"
                                    onClick={() =>
                                        navigate('login', {
                                            redirect: 'auction-detail',
                                            id: auction.id,
                                        })
                                    }
                                >
                                    {lang === 'en'
                                        ? 'Log in to Place Bid'
                                        : 'Đăng nhập để tham gia đặt giá'}
                                </Button>
                            ) : isSeller ? (
                                <div className="space-y-1.5">
                                    <Button
                                        variant="auction"
                                        size="lg"
                                        className="w-full font-bold shadow-md opacity-60 cursor-not-allowed"
                                        disabled
                                    >
                                        <Gavel size={16} />
                                        {tr(lang, 'placeBid')} – {formatCurrency(Number(customBid) || minBid, lang)}
                                    </Button>
                                    <p className="text-xs text-red-500 text-center font-medium">
                                        {lang === 'en'
                                            ? 'You own this auction and cannot place bids (chk_seller_not_bidder).'
                                            : 'Bạn là người bán của phiên đấu giá này nên không thể tự đặt giá.'}
                                    </p>
                                </div>
                            ) : isOffline ? (
                                <Button
                                    variant="auction"
                                    size="lg"
                                    className="w-full font-bold shadow-md opacity-60 cursor-not-allowed"
                                    disabled
                                >
                                    <Gavel size={16} />
                                    {lang === 'en' ? 'Offline — Bidding Paused' : 'Ngoại tuyến — Tạm dừng đặt giá'}
                                </Button>
                            ) : (user?.reputationScore || 0) < 30 && user?.reputationScore !== undefined ? (
                                <div className="space-y-1.5">
                                    <Button
                                        variant="auction"
                                        size="lg"
                                        className="w-full font-bold shadow-md opacity-60 cursor-not-allowed"
                                        disabled
                                    >
                                        <Gavel size={16} />
                                        {tr(lang, 'placeBid')} – {formatCurrency(Number(customBid) || minBid, lang)}
                                    </Button>
                                    <p className="text-xs text-red-500 text-center font-medium">
                                        {tr(lang, 'insufficientReputation')}
                                    </p>
                                </div>
                            ) : (
                                <Button
                                    variant="auction"
                                    size="lg"
                                    className="w-full font-bold shadow-md"
                                    loading={placing}
                                    onClick={() => placeBid(Number(customBid))}
                                >
                                    <Gavel size={16} />
                                    {tr(lang, 'placeBid')} – {formatCurrency(Number(customBid) || minBid, lang)}
                                </Button>
                            )}

                            {/* Nút hỗ trợ đồng bộ thủ công */}
                            <div className="flex items-center justify-between pt-1 border-t border-gray-100 text-xs text-gray-500">
                                <span className="text-[11px]">
                                    {lang === 'en'
                                        ? 'Real-time synchronization active'
                                        : 'Đồng bộ trực tuyến theo thời gian thực'}
                                </span>
                                <button
                                    type="button"
                                    disabled={reconnecting}
                                    onClick={async () => {
                                        setReconnecting(true)
                                        try {
                                            await loadAuctionDetail()
                                            setIsOffline(
                                                typeof navigator !== 'undefined'
                                                    ? !navigator.onLine
                                                    : false,
                                            )
                                        } finally {
                                            setReconnecting(false)
                                        }
                                    }}
                                    className="text-[11px] font-semibold text-primary hover:underline flex items-center gap-1 cursor-pointer disabled:opacity-50"
                                >
                                    <span className={reconnecting ? 'animate-spin' : ''}>↻</span>
                                    <span>
                                        {lang === 'en' ? 'Sync Prices' : 'Đồng bộ giá'}
                                    </span>
                                </button>
                            </div>

                            {/* Thông báo Anti-sniping */}
                            <div className="p-3 bg-amber-50 rounded-xl border border-amber-200/60 text-xs text-amber-800 space-y-1">
                                <div className="font-bold flex items-center gap-1.5">
                                    <Clock size={13} />
                                    <span>{lang === 'en' ? 'Anti-Sniping Protection' : 'Cơ chế bảo vệ giờ chót'}</span>
                                </div>
                                <p className="text-[11px] text-amber-700 leading-relaxed">
                                    {lang === 'en'
                                        ? 'Bids placed in the last 2 minutes will automatically extend the auction by 2 minutes.'
                                        : 'Lượt trả giá trong 2 phút cuối sẽ tự động gia hạn thêm 2 phút để đảm bảo công bằng.'}
                                </p>
                            </div>
                        </div>
                    )}

                    {/* Card thông tin người bán (Seller Profile) */}
                    <div className="nexus-card p-4 flex items-center gap-3.5">
                        <div className="w-11 h-11 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-base shrink-0">
                            {auction.sellerName ? auction.sellerName[0].toUpperCase() : 'S'}
                        </div>
                        <div className="flex-1 min-w-0">
                            <p className="font-semibold text-sm text-gray-900 truncate">
                                {auction.sellerName}
                            </p>
                            <div className="flex items-center gap-1.5 mt-0.5">
                                <StarRating rating={auction.product.rating || 5} size={11} />
                                <span className="text-xs text-gray-500 font-medium">
                                    {auction.product.rating || 5.0}
                                </span>
                            </div>
                        </div>
                        <span className="badge badge-indigo text-xs">
                            {lang === 'en' ? 'Verified' : 'Xác minh'}
                        </span>
                    </div>
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── My Bids Page ──────────────────────────────────────────────────────────────
export function MyBidsPage() {
    const { lang, navigate } = useApp()
    const [tab, setTab] = useState('active')

    const BIDS = [
        { auction: AUCTIONS[0], myBid: 207.5, status: 'active', isHighest: false },
        { auction: AUCTIONS[3], myBid: 148.0, status: 'won', isHighest: true },
    ]

    const filtered =
        tab === 'active'
            ? BIDS.filter((b) => b.status === 'active')
            : tab === 'won'
              ? BIDS.filter((b) => b.status === 'won')
              : BIDS.filter((b) => b.status === 'lost')

    return (
        <BuyerLayout>
            <h1 className="text-2xl font-bold mb-6">{tr(lang, 'myBids')}</h1>
            <div className="nexus-card">
                <Tabs
                    tabs={[
                        { id: 'active', label: tr(lang, 'statusActive'), count: 1 },
                        { id: 'won', label: lang === 'en' ? 'Won' : 'Đã thắng', count: 1 },
                        { id: 'lost', label: lang === 'en' ? 'Lost' : 'Đã thua', count: 0 },
                    ]}
                    active={tab}
                    onChange={setTab}
                />
                <div className="p-4">
                    {filtered.length === 0 ? (
                        <EmptyState
                            icon={<Gavel size={20} />}
                            title={tr(lang, 'noData')}
                            subtitle={
                                lang === 'en'
                                    ? 'No bids in this category.'
                                    : 'Không có lượt đặt giá nào.'
                            }
                            action={
                                <Button variant="primary" onClick={() => navigate('auction-list')}>
                                    {lang === 'en' ? 'Browse Auctions' : 'Duyệt đấu giá'}
                                </Button>
                            }
                        />
                    ) : (
                        filtered.map((b, i) => (
                            <div
                                key={i}
                                className="border-b border-border last:border-0 py-4 flex gap-4"
                            >
                                <img
                                    src={b.auction.product.images[0]}
                                    alt=""
                                    className="w-16 h-16 object-cover rounded-xl"
                                />
                                <div className="flex-1">
                                    <div className="flex items-center gap-2 mb-1">
                                        <h3 className="font-semibold">
                                            {lang === 'vi'
                                                ? b.auction.product.nameVi
                                                : b.auction.product.name}
                                        </h3>
                                        {b.status === 'won' && (
                                            <span className="badge badge-green gap-1">
                                                <Trophy size={11} />
                                                {lang === 'en' ? 'Won' : 'Đã thắng'}
                                            </span>
                                        )}
                                        {!b.isHighest && b.status === 'active' && (
                                            <span className="badge badge-red">
                                                {tr(lang, 'outbid')}
                                            </span>
                                        )}
                                        {b.isHighest && b.status === 'active' && (
                                            <span className="badge badge-green">
                                                {lang === 'en' ? 'Leading' : 'Đang dẫn'}
                                            </span>
                                        )}
                                    </div>
                                    <div className="flex items-center gap-4 text-sm">
                                        <span className="text-gray-500">
                                            {lang === 'en' ? 'My bid:' : 'Giá của tôi:'}{' '}
                                            <span className="font-bold text-primary">
                                                {formatCurrency(b.myBid, lang)}
                                            </span>
                                        </span>
                                        <span className="text-gray-500">
                                            {tr(lang, 'currentBid')}:{' '}
                                            <span className="font-bold text-auction">
                                                {formatCurrency(b.auction.currentBid, lang)}
                                            </span>
                                        </span>
                                        {b.status === 'active' && (
                                            <CountdownTimer endTime={b.auction.endTime} />
                                        )}
                                    </div>
                                </div>
                                <div className="flex flex-col gap-2 shrink-0">
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() =>
                                            navigate('auction-detail', { id: b.auction.id })
                                        }
                                    >
                                        {lang === 'en' ? 'View Auction' : 'Xem đấu giá'}
                                    </Button>
                                    {b.status === 'won' && (
                                        <Button
                                            variant="primary"
                                            size="sm"
                                            onClick={() =>
                                                navigate('auction-payment', {
                                                    auctionId: b.auction.id,
                                                })
                                            }
                                        >
                                            {lang === 'en' ? 'Pay Now' : 'Thanh toán ngay'}
                                        </Button>
                                    )}
                                </div>
                            </div>
                        ))
                    )}
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Auction Payment Page ──────────────────────────────────────────────────────
/**
 * Chức năng: Màn hình thanh toán và tạo đơn hàng cho phiên đấu giá trúng giải (Auction Settlement Payment).
 * Đầu vào: pageParams.auctionId, user context, app context.
 * Kết quả: Tạo bản ghi Order có sourceType: 'AUCTION' và liên kết auctionId, lưu vào hệ thống và chuyển hướng.
 * Luồng xử lý:
 *   1. Tìm hoặc tải thông tin phiên đấu giá thắng giải.
 *   2. Tính toán tổng tiền thanh toán: Winning Bid + Phí vận chuyển + Thuế.
 *   3. Kiểm tra hạn chót thanh toán (paymentDeadline); nếu quá hạn áp dụng chế tài trừ điểm uy tín.
 *   4. Khi người dùng bấm "Complete Payment":
 *      - Tạo bản ghi Order trúng đấu giá đầy đủ.
 *      - Lưu vào mock store đơn hàng qua writeLocalMockOrders.
 *      - Điều hướng người dùng sang trang chi tiết đơn hàng (order-detail) hoặc lịch sử đơn (order-history).
 * Quy tắc & lý do: Đảm bảo tính toàn vẹn contract đấu giá, chuyển hóa quyền trúng đấu giá thành đơn hàng thực tế.
 */
export function AuctionPaymentPage() {
    const { lang, navigate, pageParams, showToast, user } = useApp()
    const targetAuctionId = String(pageParams?.auctionId || '')
    const [auction, setAuction] = useState<Auction>(() => {
        return (
            auctionsApi.getStoredAuctionById(targetAuctionId) ||
            AUCTIONS.find((a) => a.id === targetAuctionId) ||
            AUCTIONS[3]
        )
    })
    const deadline = new Date(Date.now() + 3600000 * 20).toISOString()
    const [paying, setPaying] = useState(false)
    const [expired, setExpired] = useState(false)

    // Tải thông tin phiên đấu giá mới nhất từ API Adapter nếu có mã phiên
    useEffect(() => {
        if (!targetAuctionId) return
        auctionsApi.getAuctionById(targetAuctionId).then((data) => {
            if (data) setAuction(data)
        }).catch(() => {
            // Sử dụng fallback nếu không tìm thấy
        })
    }, [targetAuctionId])

    /**
     * Chức năng: Thực hiện quyết toán và hoàn tất đặt hàng phiên đấu giá.
     * Luồng:
     *   1. Khởi tạo mã đơn hàng mới ORD-AUC-XXXXXX.
     *   2. Đóng gói Order với sourceType: 'AUCTION', isAuctionOrder: true, auctionId.
     *   3. Ghi vào kho đơn hàng localStorage thông qua writeLocalMockOrders.
     *   4. Điều hướng trực tiếp đến trang chi tiết đơn hàng (order-detail).
     */
    const pay = async () => {
        setPaying(true)
        await new Promise((r) => setTimeout(r, 900))

        const newOrderId = `ORD-AUC-${Date.now().toString().slice(-6)}`
        const finalPrice = auction.currentBid
        const shippingFee = 5.99
        const taxFee = Number((finalPrice * 0.08).toFixed(2))
        const total = Number((finalPrice + shippingFee + taxFee).toFixed(2))

        const auctionOrder: Order = {
            id: newOrderId,
            orderNo: newOrderId,
            buyerId: user?.id || 'usr-buyer-01',
            buyerName: user?.fullName || 'Winner',
            sellerId: auction.sellerId,
            sellerName: auction.sellerName,
            items: [
                {
                    productId: auction.product.id,
                    productName: lang === 'vi' ? auction.product.nameVi : auction.product.name,
                    quantity: 1,
                    unitPrice: finalPrice,
                    subtotal: finalPrice,
                    image: auction.product.images?.[0] || '',
                },
            ],
            subtotal: finalPrice,
            tax: taxFee,
            discount: 0,
            shipping: shippingFee,
            total,
            status: 'PAID',
            refundStatus: 'none',
            paymentStatus: 'held',
            paymentMethod: 'Credit Card (Auction Settlement)',
            shippingAddress: '123 Nguyen Hue Street, District 1, Ho Chi Minh City',
            isAuctionOrder: true,
            sourceType: 'AUCTION',
            auctionId: auction.id,
            paymentDeadline: deadline,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        }

        const currentOrders = readLocalMockOrders()
        writeLocalMockOrders([auctionOrder, ...currentOrders])

        setPaying(false)
        showToast({
            type: 'success',
            message:
                lang === 'en'
                    ? 'Payment successful! Order has been created.'
                    : 'Thanh toán thành công! Đơn hàng đấu giá đã được khởi tạo.',
        })
        navigate('order-detail', { id: newOrderId })
    }

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'My Bids' : 'Lượt đặt giá', page: 'my-bids' },
                    { label: lang === 'en' ? 'Complete Payment' : 'Hoàn tất thanh toán' },
                ]}
            />
            <div className="mt-5 max-w-2xl mx-auto">
                {/* Winner banner */}
                <div className="nexus-card p-6 bg-white border-gray-300 mb-5">
                    <div className="flex items-center gap-4">
                        <div className="w-12 h-12 rounded-lg bg-gray-100 text-gray-600 flex items-center justify-center shrink-0">
                            <Trophy size={22} />
                        </div>
                        <div>
                            <h2 className="text-xl font-bold text-green-900">
                                {lang === 'en'
                                    ? 'Congratulations! You won this auction.'
                                    : 'Chúc mừng! Bạn đã thắng phiên đấu giá này.'}
                            </h2>
                            <p className="text-green-700 mt-1">
                                {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                            </p>
                            <p className="text-3xl font-black text-success mt-1">
                                {formatCurrency(auction.currentBid, lang)}
                            </p>
                        </div>
                    </div>
                </div>

                {/* Payment deadline */}
                <AlertBanner
                    type={expired ? 'error' : 'warning'}
                    message={
                        expired
                            ? lang === 'en'
                                ? 'Payment deadline passed. Your reputation score has been penalized (-10 pts).'
                                : 'Đã quá hạn thanh toán. Điểm uy tín của bạn bị trừ (-10 điểm).'
                            : lang === 'en'
                              ? `Complete payment within 24 hours to avoid reputation penalty.`
                              : `Hoàn tất thanh toán trong 24 giờ để tránh bị trừ điểm uy tín.`
                    }
                />

                {!expired && (
                    <div className="nexus-card p-5 mt-5 mb-5 flex items-center justify-between">
                        <div>
                            <p className="text-sm text-gray-500">{tr(lang, 'paymentDeadline')}</p>
                            <p className="font-semibold text-gray-900">
                                {new Date(deadline).toLocaleString()}
                            </p>
                        </div>
                        <div className="text-right">
                            <p className="text-xs text-gray-400 mb-1">
                                {lang === 'en' ? 'Time remaining' : 'Thời gian còn lại'}
                            </p>
                            <CountdownTimer endTime={deadline} large />
                        </div>
                    </div>
                )}

                <div className="nexus-card p-6 mt-5">
                    <h3 className="font-bold text-lg mb-4">
                        {lang === 'en' ? 'Order Summary' : 'Tóm tắt đơn hàng'}
                    </h3>
                    <div className="flex gap-4 mb-5">
                        <img
                            src={auction.product.images[0]}
                            alt=""
                            className="w-20 h-20 object-cover rounded-xl"
                        />
                        <div>
                            <p className="font-semibold">
                                {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                            </p>
                            <p className="text-sm text-gray-500">
                                {lang === 'en' ? 'Sold by' : 'Bởi'} {auction.sellerName}
                            </p>
                        </div>
                    </div>
                    <div className="space-y-2 text-sm border-t border-border pt-3">
                        {[
                            [
                                lang === 'en' ? 'Winning bid' : 'Giá thắng',
                                formatCurrency(auction.currentBid, lang),
                            ],
                            [tr(lang, 'shipping'), '$5.99'],
                            [tr(lang, 'tax'), formatCurrency(auction.currentBid * 0.08, lang)],
                            [
                                tr(lang, 'total'),
                                formatCurrency(auction.currentBid * 1.08 + 5.99, lang),
                            ],
                        ].map(([k, v], i) => (
                            <div
                                key={String(k)}
                                className={`flex justify-between ${i === 3 ? 'font-bold text-primary border-t border-border pt-2 mt-1' : 'text-gray-600'}`}
                            >
                                <span>{k}</span>
                                <span>{v}</span>
                            </div>
                        ))}
                    </div>
                </div>

                {!expired && (
                    <div className="mt-5 space-y-4">
                        <Input
                            label={lang === 'en' ? 'Card Number' : 'Số thẻ'}
                            placeholder="4242 4242 4242 4242"
                        />
                        <div className="grid grid-cols-2 gap-4">
                            <Input label={lang === 'en' ? 'Expiry' : 'Hạn'} placeholder="MM/YY" />
                            <Input label="CVV" placeholder="123" type="password" />
                        </div>
                        <Button
                            variant="primary"
                            size="lg"
                            className="w-full"
                            loading={paying}
                            onClick={pay}
                        >
                            {lang === 'en' ? 'Complete Payment' : 'Hoàn tất thanh toán'} –{' '}
                            {formatCurrency(auction.currentBid * 1.08 + 5.99, lang)}
                        </Button>
                        <button
                            onClick={() => setExpired(true)}
                            className="text-xs text-gray-400 hover:text-gray-600 w-full text-center"
                        >
                            Simulate expired payment (demo)
                        </button>
                    </div>
                )}
            </div>
        </BuyerLayout>
    )
}
