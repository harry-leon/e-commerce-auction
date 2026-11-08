import { useState, useEffect, useCallback } from 'react'
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
} from '../../components/UI'
import { AUCTIONS } from '../../mockData'
import {
    auctionsApi,
    getAuctionSettlement,
    getWinnerAuctionDetails,
    type AuctionSettlement,
    type WinnerAuctionDetails,
} from '../../api/auctions'
import type { Auction } from '../../types'

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

    // Tải thông tin chi tiết qua API Adapter
    const loadAuctionDetail = useCallback(async () => {
        if (!auctionId) {
            setError(lang === 'en' ? 'Auction ID is missing.' : 'Thiếu mã phiên đấu giá.')
            setLoading(false)
            return
        }
        setLoading(true)
        setError('')
        try {
            const data = await auctionsApi.getAuctionById(auctionId, createdAuctions, auctionReviews)
            setAuction(data)
            setCustomBid(String(data.currentBid + data.minIncrement))
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : lang === 'en'
                      ? 'Failed to load auction details.'
                      : 'Không thể tải thông tin chi tiết phiên đấu giá.',
            )
        } finally {
            setLoading(false)
        }
    }, [auctionId, createdAuctions, auctionReviews, lang])

    useEffect(() => {
        loadAuctionDetail()
    }, [loadAuctionDetail])

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
                                onClick={loadAuctionDetail}
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
    const isWinner = auction.winnerId === user?.id
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
                    : `Mức giá đặt phải tối thiểu là ${formatCurrency(minBid, lang)} (Giá hiện tại + Bước giá)`,
            )
            return
        }

        // 3. Kiểm tra điểm uy tín
        if ((user?.reputationScore || 0) < 30 && (user?.reputationScore !== undefined)) {
            setBidError(tr(lang, 'insufficientReputation'))
            return
        }

        setPlacing(true)
        setBidError('')

        try {
            const res = await auctionsApi.placeBid(auction.id, amount)
            setBidPlaced(true)
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
                        <div className="nexus-card p-6 border-orange-200 bg-white shadow-sm">
                            <div className="flex items-center justify-between gap-2 mb-4">
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

                            <div className="mb-4 bg-orange-50/60 p-4 rounded-xl border border-orange-100">
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

                            <div className="mb-5">
                                <p className="text-xs text-gray-500 mb-1.5 font-medium">
                                    {tr(lang, 'timeLeft')}
                                </p>
                                <CountdownTimer endTime={auction.endTime} large />
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
                                            {lang === 'en' ? 'Congratulations! You won!' : 'Chúc mừng! Bạn đã thắng phiên đấu giá!'}
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
                                            {lang === 'en' ? 'Proceed to Checkout' : 'Thanh toán đơn hàng ngay'}
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
                        <div className="nexus-card p-6 shadow-sm space-y-4">
                            <h3 className="font-bold text-gray-900 text-base">{tr(lang, 'placeBid')}</h3>

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
                                            onClick={() =>
                                                setCustomBid(
                                                    String(
                                                        auction.currentBid +
                                                            auction.minIncrement * mult,
                                                    ),
                                                )
                                            }
                                            className="py-2 rounded-xl border border-auction/30 bg-orange-50/50 text-auction text-xs font-bold hover:bg-orange-100/70 transition-colors"
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
                                    helper={
                                        lang === 'en'
                                            ? `Minimum required: ${formatCurrency(minBid, lang)}`
                                            : `Tối thiểu: ${formatCurrency(minBid, lang)}`
                                    }
                                />
                            </div>

                            <Button
                                variant="auction"
                                size="lg"
                                className="w-full font-bold shadow-md"
                                loading={placing}
                                disabled={isSeller}
                                onClick={() => placeBid(Number(customBid))}
                            >
                                <Gavel size={16} />
                                {tr(lang, 'placeBid')} – {formatCurrency(Number(customBid) || minBid, lang)}
                            </Button>

                            {isSeller && (
                                <p className="text-xs text-red-500 text-center">
                                    {lang === 'en'
                                        ? 'You own this auction and cannot place bids.'
                                        : 'Bạn là chủ phiên đấu giá này nên không thể tự đặt giá.'}
                                </p>
                            )}

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
 * Hiển thị settlement và thông tin sản phẩm thật cho winner, nhận auctionId từ route và winnerId từ session.
 * Giá/deadline lấy từ Auction Service; thông tin hàng lấy từ Catalog; quá hạn sẽ khóa form.
 * Tạo order, lưu địa chỉ và chuyển Sandbox đang chờ Commerce hỗ trợ checkout AUCTION.
 */
export function AuctionPaymentPage() {
    const { lang, pageParams, user } = useApp()
    const auctionId = String(pageParams?.auctionId || '')
    const [settlement, setSettlement] = useState<AuctionSettlement | null>(null)
    const [auctionDetails, setAuctionDetails] = useState<WinnerAuctionDetails | null>(null)
    const [loading, setLoading] = useState(true)
    const [loadError, setLoadError] = useState(false)
    const [now, setNow] = useState(Date.now())
    const [recipientName, setRecipientName] = useState(user?.fullName || '')
    const [recipientPhone, setRecipientPhone] = useState('')
    const [shippingAddress, setShippingAddress] = useState('')

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

    const deadline = settlement?.paymentDeadline || ''
    const expired = !deadline || new Date(deadline).getTime() <= now
    const isWinner = !!user?.id && settlement?.winnerId === user.id
    const canEditShipping = !!settlement && isWinner && !expired && !settlement.orderId

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'My Bids' : 'Lượt đặt giá', page: 'my-bids' },
                    { label: tr(lang, 'winnerOrderTitle') },
                ]}
            />
            <div className="mt-5 max-w-2xl mx-auto space-y-5">
                {loading ? (
                    <div role="status" className="nexus-card p-6 text-gray-600">
                        {tr(lang, 'loadingSettlement')}
                    </div>
                ) : loadError || !settlement ? (
                    <AlertBanner type="error" message={tr(lang, 'settlementUnavailable')} />
                ) : settlement.result === 'FAILED' || !settlement.winnerId ? (
                    <AlertBanner type="info" message={tr(lang, 'settlementHasNoWinner')} />
                ) : !isWinner ? (
                    <AlertBanner type="error" message={tr(lang, 'winnerOnly')} />
                ) : (
                    <>
                        <div className="nexus-card p-6 bg-white border-gray-300">
                            <div className="flex items-start gap-4">
                                <div className="w-12 h-12 rounded-lg bg-gray-100 text-gray-600 flex items-center justify-center shrink-0">
                                    <Trophy size={22} />
                                </div>
                                <div className="min-w-0">
                                    <h2 className="text-xl font-bold text-green-900">
                                        {tr(lang, 'winnerOrderHeading')}
                                    </h2>
                                    <p className="text-sm text-gray-500 mt-1">
                                        {tr(lang, 'auctionLabel')} · {settlement.auctionId}
                                    </p>
                                    {auctionDetails && (
                                        <div className="mt-3 flex items-center gap-3">
                                            {auctionDetails.productImage && (
                                                <img
                                                    src={auctionDetails.productImage}
                                                    alt={auctionDetails.productName}
                                                    className="h-14 w-14 rounded-lg object-cover"
                                                />
                                            )}
                                            <p className="font-semibold text-gray-900">
                                                {auctionDetails.productName}
                                            </p>
                                        </div>
                                    )}
                                    <p className="text-3xl font-black text-success mt-2">
                                        {settlement.finalPrice == null ? '—' : formatCurrency(settlement.finalPrice, lang)}
                                    </p>
                                </div>
                            </div>
                        </div>

                        {expired ? (
                            <AlertBanner type="error" message={tr(lang, 'paymentTimedOut')} />
                        ) : (
                            <div className="nexus-card p-5 flex items-center justify-between gap-4">
                                <div>
                                    <p className="text-sm text-gray-500">{tr(lang, 'paymentDeadline')}</p>
                                    <p className="font-semibold text-gray-900">
                                        {new Date(deadline).toLocaleString(lang === 'vi' ? 'vi-VN' : 'en-US')}
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

                        {settlement.orderId && (
                            <AlertBanner
                                type="info"
                                message={`${tr(lang, 'linkedSettlementOrder')} ${settlement.orderId}`}
                            />
                        )}

                        <form className="nexus-card p-6 space-y-4" onSubmit={(event) => event.preventDefault()}>
                            <h3 className="font-bold text-lg">{tr(lang, 'shippingDetails')}</h3>
                            <Input
                                label={tr(lang, 'recipientName')}
                                autoComplete="name"
                                value={recipientName}
                                onChange={(event) => setRecipientName(event.target.value)}
                                disabled={!canEditShipping}
                                required
                                maxLength={255}
                            />
                            <Input
                                label={tr(lang, 'recipientPhone')}
                                autoComplete="tel"
                                type="tel"
                                value={recipientPhone}
                                onChange={(event) => setRecipientPhone(event.target.value)}
                                disabled={!canEditShipping}
                                required
                                pattern="\\+?[0-9]{8,15}"
                            />
                            <label className="flex flex-col gap-1.5 text-sm font-semibold text-gray-700">
                                {tr(lang, 'shippingAddress')}
                                <textarea
                                    className="nexus-input min-h-24"
                                    autoComplete="street-address"
                                    value={shippingAddress}
                                    onChange={(event) => setShippingAddress(event.target.value)}
                                    disabled={!canEditShipping}
                                    required
                                    maxLength={500}
                                />
                            </label>
                            <AlertBanner type="info" message={tr(lang, 'checkoutIntegrationPending')} />
                            <Button type="button" variant="primary" size="lg" className="w-full" disabled>
                                {tr(lang, 'continueSandboxPayment')}
                                {settlement.finalPrice != null && ` · ${formatCurrency(settlement.finalPrice, lang)}`}
                            </Button>
                        </form>
                    </>
                )}
            </div>
        </BuyerLayout>
    )
}
