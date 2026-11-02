import { useState } from 'react'
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
    CheckCircle,
    ClipboardList,
    Trophy,
    Clock,
} from '../../components/UI'
import { AUCTIONS } from '../../mockData'

// ─── Auction List Page ─────────────────────────────────────────────────────────
export function AuctionListPage() {
    const { lang, auctionReviews, createdAuctions } = useApp()
    const [statusFilter, setStatusFilter] = useState('all')
    const [sortBy, setSortBy] = useState('ending-soon')
    const [page, setPage] = useState(1)

    const visibleAuctions = [...createdAuctions, ...AUCTIONS]
        .map((auction) => ({
            ...auction,
            status: auctionReviews[auction.id]?.status || auction.status,
        }))
        .filter((auction) => !['pending_approval', 'rejected'].includes(auction.status))
    const filtered =
        statusFilter === 'all'
            ? visibleAuctions
            : visibleAuctions.filter((a) => a.status === statusFilter)

    const STATUS_TABS = [
        { id: 'all', label: tr(lang, 'all') },
        { id: 'active', label: tr(lang, 'statusActive') },
        { id: 'scheduled', label: tr(lang, 'statusScheduled') },
        { id: 'ended', label: tr(lang, 'statusEnded') },
    ]

    return (
        <BuyerLayout>
            <div className="flex items-center justify-between mb-6">
                <div>
                    <h1 className="text-2xl font-bold text-gray-900">{tr(lang, 'auctions')}</h1>
                    <p className="text-gray-500 text-sm mt-1">
                        {lang === 'en'
                            ? `${AUCTIONS.filter((a) => a.status === 'active').length} live auctions right now`
                            : `${AUCTIONS.filter((a) => a.status === 'active').length} phiên đấu giá đang diễn ra`}
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    <select
                        value={sortBy}
                        onChange={(e) => setSortBy(e.target.value)}
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
                    </select>
                </div>
            </div>

            <div className="flex gap-6">
                {/* Sidebar */}
                <aside className="w-52 shrink-0 space-y-4">
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm mb-3">
                            {lang === 'en' ? 'Status' : 'Trạng thái'}
                        </h3>
                        {STATUS_TABS.map((t) => (
                            <button
                                key={t.id}
                                onClick={() => setStatusFilter(t.id)}
                                className={`flex w-full items-center gap-2 px-2 py-1.5 rounded-lg text-sm transition-colors ${statusFilter === t.id ? 'bg-primary-light text-primary font-semibold' : 'text-gray-600 hover:bg-gray-50'}`}
                            >
                                <span
                                    className={`w-2 h-2 rounded-full ${t.id === 'active' ? 'bg-auction' : t.id === 'scheduled' ? 'bg-info' : t.id === 'ended' ? 'bg-gray-400' : 'bg-gray-200'}`}
                                />
                                {t.label}
                            </button>
                        ))}
                    </div>
                    <div className="nexus-card p-4">
                        <h3 className="font-bold text-sm mb-3">
                            {lang === 'en' ? 'Price Range' : 'Khoảng giá'}
                        </h3>
                        <div className="space-y-2">
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Min bid' : 'Giá thấp nhất'}
                                className="text-xs py-1.5"
                            />
                            <Input
                                type="number"
                                placeholder={lang === 'en' ? 'Max bid' : 'Giá cao nhất'}
                                className="text-xs py-1.5"
                            />
                        </div>
                    </div>
                </aside>

                <div className="flex-1">
                    {filtered.length === 0 ? (
                        <EmptyState
                            icon={<Gavel size={20} />}
                            title={tr(lang, 'noData')}
                            subtitle={
                                lang === 'en'
                                    ? 'No auctions in this category.'
                                    : 'Không có phiên đấu giá nào.'
                            }
                        />
                    ) : (
                        <>
                            <div className="grid grid-cols-3 gap-5">
                                {filtered.map((a) => (
                                    <AuctionCard key={a.id} auction={a} />
                                ))}
                            </div>
                            <div className="flex justify-center mt-8">
                                <Pagination
                                    page={page}
                                    total={filtered.length * 3}
                                    perPage={9}
                                    onChange={setPage}
                                />
                            </div>
                        </>
                    )}
                </div>
            </div>
        </BuyerLayout>
    )
}

// ─── Auction Detail Page ────────────────────────────────────────────────────────
export function AuctionDetailPage() {
    const { lang, navigate, pageParams, user, showToast, createdAuctions, auctionReviews } =
        useApp()
    const sourceAuction =
        [...createdAuctions, ...AUCTIONS].find((a) => a.id === pageParams?.id) || AUCTIONS[0]
    const auction = {
        ...sourceAuction,
        status: auctionReviews[sourceAuction.id]?.status || sourceAuction.status,
    }
    const [customBid, setCustomBid] = useState(String(auction.currentBid + auction.minIncrement))
    const [bidPlaced, setBidPlaced] = useState(false)
    const [bidError, setBidError] = useState('')
    const [placing, setPlacing] = useState(false)

    const minBid = auction.currentBid + auction.minIncrement
    const isActive = auction.status === 'active'
    const isScheduled = auction.status === 'scheduled'
    const isEnded = ['ended', 'settled', 'cancelled', 'failed'].includes(auction.status)
    const isWinner = auction.winnerId === user?.id
    const isHighestBidder = bidPlaced

    const placeBid = async (amount: number) => {
        if (!isActive) return
        if (amount < minBid) {
            setBidError(`${tr(lang, 'bidTooLow')} ${formatCurrency(minBid, lang)}`)
            return
        }
        if ((user?.reputationScore || 0) < 30) {
            setBidError(tr(lang, 'insufficientReputation'))
            return
        }
        setPlacing(true)
        await new Promise((r) => setTimeout(r, 700))
        setBidPlaced(true)
        setBidError('')
        showToast({
            type: 'success',
            message:
                lang === 'en'
                    ? `Bid of ${formatCurrency(amount, lang)} placed!`
                    : `Đã đặt giá ${formatCurrency(amount, lang)}!`,
        })
        showToast({ type: 'warning', message: tr(lang, 'antiSniping'), duration: 5000 })
        setPlacing(false)
    }

    return (
        <BuyerLayout>
            <Breadcrumb
                items={[
                    { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                    { label: lang === 'en' ? 'Auctions' : 'Đấu giá', page: 'auction-list' },
                    { label: lang === 'vi' ? auction.product.nameVi : auction.product.name },
                ]}
            />
            <div className="mt-5 grid grid-cols-[1fr_400px] gap-8">
                {/* Left: product gallery + info */}
                <div>
                    <div
                        className="nexus-card overflow-hidden mb-3 relative bg-orange-50"
                        style={{ aspectRatio: '4/3' }}
                    >
                        <img
                            src={auction.product.images[0]}
                            alt={auction.product.name}
                            className="w-full h-full object-cover"
                        />
                        {isActive && (
                            <div className="absolute top-3 left-3 badge badge-orange auction-live text-sm px-3 py-1">
                                LIVE AUCTION
                            </div>
                        )}
                        {isEnded && (
                            <div className="absolute inset-0 bg-black/30 flex items-center justify-center">
                                <span className="badge badge-gray text-xl px-6 py-3">
                                    {tr(lang, 'auctionEnded')}
                                </span>
                            </div>
                        )}
                    </div>
                    <div className="flex gap-2 mb-6">
                        {auction.product.images.map((img, i) => (
                            <div
                                key={i}
                                className="gallery-thumb w-16 h-16 overflow-hidden bg-gray-100 active"
                            >
                                <img src={img} className="w-full h-full object-cover" alt="" />
                            </div>
                        ))}
                    </div>

                    <div className="nexus-card p-5 mb-5">
                        <h1 className="text-2xl font-bold text-gray-900 mb-2">
                            {lang === 'vi' ? auction.product.nameVi : auction.product.name}
                        </h1>
                        <div className="flex items-center gap-3 mb-4 text-sm text-gray-500">
                            <span>
                                {lang === 'en' ? 'Sold by' : 'Bởi'}{' '}
                                <span className="font-semibold text-gray-800">
                                    {auction.sellerName}
                                </span>
                            </span>
                            <span>·</span>
                            <span>
                                {lang === 'en'
                                    ? `${auction.bidCount} bids`
                                    : `${auction.bidCount} lượt đặt giá`}
                            </span>
                        </div>
                        <p className="text-gray-600 text-sm leading-relaxed">
                            {lang === 'vi'
                                ? auction.product.descriptionVi
                                : auction.product.description}
                        </p>
                    </div>

                    {/* Bid history */}
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Bid History' : 'Lịch sử đặt giá'}
                        </h3>
                        {auction.bids.length === 0 ? (
                            <EmptyState
                                icon={<ClipboardList size={20} />}
                                title={lang === 'en' ? 'No bids yet' : 'Chưa có lượt đặt giá'}
                                subtitle={
                                    lang === 'en'
                                        ? 'Be the first to place a bid!'
                                        : 'Hãy là người đầu tiên đặt giá!'
                                }
                            />
                        ) : (
                            <div className="space-y-2">
                                {auction.bids.map((bid, i) => (
                                    <div
                                        key={bid.id}
                                        className={`flex items-center justify-between p-3 rounded-xl ${i === 0 ? 'bg-auction-light border border-auction/20' : 'bg-gray-50'}`}
                                    >
                                        <div className="flex items-center gap-3">
                                            <div className="w-8 h-8 rounded-full flex items-center justify-center font-bold text-sm bg-gray-100 text-gray-600">
                                                {i === 0 ? <Trophy size={15} /> : i + 1}
                                            </div>
                                            <div>
                                                <p className="font-semibold text-sm">
                                                    {bid.bidderName}
                                                </p>
                                                <p className="text-xs text-gray-400">
                                                    {new Date(bid.timestamp).toLocaleTimeString()}
                                                </p>
                                            </div>
                                        </div>
                                        <span
                                            className={`font-bold ${i === 0 ? 'text-auction text-lg' : 'text-gray-700'}`}
                                        >
                                            {formatCurrency(bid.amount, lang)}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>

                {/* Right: bid panel */}
                <div className="space-y-4">
                    {/* Status bar */}
                    {isActive && (
                        <div className="nexus-card p-5 border-gray-300 bg-white">
                            <div className="flex items-center gap-2 mb-3">
                                <span className="badge badge-orange auction-live">LIVE</span>
                                <span className="text-sm text-gray-500">
                                    {lang === 'en'
                                        ? 'Auction in progress'
                                        : 'Phiên đấu giá đang diễn ra'}
                                </span>
                            </div>
                            <div className="mb-4">
                                <p className="text-sm text-gray-500">{tr(lang, 'currentBid')}</p>
                                <p className="text-4xl font-black text-auction">
                                    {formatCurrency(auction.currentBid, lang)}
                                </p>
                                <p className="text-xs text-gray-400 mt-1">
                                    {lang === 'en'
                                        ? `Starting bid: ${formatCurrency(auction.startingPrice, lang)}`
                                        : `Giá khởi điểm: ${formatCurrency(auction.startingPrice, lang)}`}
                                </p>
                            </div>
                            <div className="mb-5">
                                <p className="text-sm text-gray-500 mb-1">{tr(lang, 'timeLeft')}</p>
                                <CountdownTimer endTime={auction.endTime} large />
                            </div>
                            {isHighestBidder && (
                                <AlertBanner type="success" message={tr(lang, 'highestBidder')} />
                            )}
                        </div>
                    )}

                    {isScheduled && (
                        <div className="nexus-card p-5 border-blue-200 bg-blue-50">
                            <div className="text-center py-4">
                                <Clock size={32} className="mx-auto mb-3 text-gray-500" />
                                <h3 className="font-bold text-blue-900 mb-2">
                                    {tr(lang, 'auctionNotStarted')}
                                </h3>
                                <p className="text-sm text-blue-700">
                                    {lang === 'en' ? 'Starts' : 'Bắt đầu'}:{' '}
                                    {new Date(auction.startTime).toLocaleString()}
                                </p>
                                <p className="text-2xl font-bold text-blue-800 mt-2">
                                    {formatCurrency(auction.startingPrice, lang)}
                                </p>
                            </div>
                        </div>
                    )}

                    {isEnded && (
                        <div className="nexus-card p-5">
                            <div className="text-center py-4">
                                {isWinner ? (
                                    <>
                                        <CheckCircle
                                            size={40}
                                            className="text-gray-600 mx-auto mb-3"
                                        />
                                        <h3 className="font-bold text-success text-lg">
                                            {lang === 'en' ? 'You won!' : 'Bạn đã thắng!'}
                                        </h3>
                                        <p className="text-2xl font-black text-gray-900 mt-2">
                                            {formatCurrency(auction.currentBid, lang)}
                                        </p>
                                        <Button
                                            variant="primary"
                                            size="lg"
                                            className="mt-4 w-full"
                                            onClick={() =>
                                                navigate('auction-payment', {
                                                    auctionId: auction.id,
                                                })
                                            }
                                        >
                                            {tr(lang, 'paymentDeadline')}
                                        </Button>
                                    </>
                                ) : auction.winnerId ? (
                                    <>
                                        <Gavel size={32} className="mx-auto mb-3 text-gray-500" />
                                        <h3 className="font-bold text-gray-700">
                                            {tr(lang, 'auctionEnded')}
                                        </h3>
                                        <p className="text-sm text-gray-500 mt-1">
                                            {lang === 'en'
                                                ? `Won by ${auction.winnerName}`
                                                : `Người thắng: ${auction.winnerName}`}
                                        </p>
                                        <p className="text-2xl font-black text-auction mt-2">
                                            {formatCurrency(auction.currentBid, lang)}
                                        </p>
                                    </>
                                ) : (
                                    <>
                                        <AlertTriangle
                                            size={32}
                                            className="mx-auto mb-3 text-gray-500"
                                        />
                                        <h3 className="font-bold text-gray-700">
                                            {lang === 'en'
                                                ? 'Auction ended — no winner'
                                                : 'Phiên đấu giá kết thúc — không có người thắng'}
                                        </h3>
                                    </>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Bid input panel (active only) */}
                    {isActive && (
                        <div className="nexus-card p-5">
                            <h3 className="font-bold mb-4">{tr(lang, 'placeBid')}</h3>
                            {bidError && (
                                <AlertBanner
                                    type="error"
                                    message={bidError}
                                    onClose={() => setBidError('')}
                                />
                            )}
                            <div className="mb-4">
                                <p className="text-xs text-gray-500 mb-2">
                                    {tr(lang, 'bidIncrement')}:{' '}
                                    {formatCurrency(auction.minIncrement, lang)}
                                </p>
                                <div className="flex gap-2 mb-3">
                                    {[1, 2, 5].map((mult) => (
                                        <button
                                            key={mult}
                                            onClick={() =>
                                                setCustomBid(
                                                    String(
                                                        auction.currentBid +
                                                            auction.minIncrement * mult,
                                                    ),
                                                )
                                            }
                                            className="flex-1 py-2 rounded-xl border-2 border-auction text-auction text-sm font-bold hover:bg-auction-light transition-colors"
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
                                            ? `Minimum: ${formatCurrency(minBid, lang)}`
                                            : `Tối thiểu: ${formatCurrency(minBid, lang)}`
                                    }
                                />
                            </div>
                            <Button
                                variant="auction"
                                size="lg"
                                className="w-full"
                                loading={placing}
                                onClick={() => placeBid(Number(customBid))}
                            >
                                <Gavel size={16} />
                                {tr(lang, 'placeBid')} – {formatCurrency(Number(customBid), lang)}
                            </Button>
                            <p className="text-xs text-gray-400 text-center mt-3">
                                {lang === 'en'
                                    ? 'Anti-sniping: bids in the last 5 min extend the auction by 5 min.'
                                    : 'Chống sniping: đặt giá 5 phút cuối sẽ gia hạn thêm 5 phút.'}
                            </p>
                        </div>
                    )}

                    {/* Seller card */}
                    <div className="nexus-card p-4 flex items-center gap-4">
                        <div className="w-10 h-10 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold">
                            {auction.sellerName[0]}
                        </div>
                        <div className="flex-1">
                            <p className="font-semibold text-sm">{auction.sellerName}</p>
                            <div className="flex items-center gap-1.5">
                                <StarRating rating={auction.product.sellerRating} size={11} />
                                <span className="text-xs text-gray-500">
                                    {auction.product.sellerRating}
                                </span>
                            </div>
                        </div>
                        <span
                            className={`badge ${auction.product.sellerTrust === 'gold' ? 'badge-yellow' : 'badge-indigo'}`}
                        >
                            {auction.product.sellerTrust.charAt(0).toUpperCase() +
                                auction.product.sellerTrust.slice(1)}
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
export function AuctionPaymentPage() {
    const { lang, navigate, pageParams, showToast } = useApp()
    const auction = AUCTIONS.find((a) => a.id === pageParams?.auctionId) || AUCTIONS[3]
    const deadline = new Date(Date.now() + 3600000 * 20).toISOString()
    const [paying, setPaying] = useState(false)
    const [expired, setExpired] = useState(false)

    const pay = async () => {
        setPaying(true)
        await new Promise((r) => setTimeout(r, 1200))
        setPaying(false)
        showToast({
            type: 'success',
            message: lang === 'en' ? 'Payment successful!' : 'Thanh toán thành công!',
        })
        navigate('order-history')
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
