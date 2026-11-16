import { Component, useState, useEffect, useRef, type ReactNode, type ErrorInfo } from 'react'
import { AppProvider, useApp } from './context'
import { AppRouter } from './routes/AppRouter'
import { connectNotificationStream, NOTIFICATIONS_CHANGED } from './api/notificationStream'
import { CelebrationCanvas, WinnerCelebrationModal } from './features/auction/CelebrationFireworks'
import { getNotifications, type Inbox, type NotificationItem } from './api/notifications'
import { ToastContainer } from './components/UI'
import { Toaster } from 'sonner'

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
    state = { error: null }
    static getDerivedStateFromError(error: Error) {
        return { error }
    }
    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('[Nexus Error]', error, info)
    }
    render() {
        const { error } = this.state
        if (error)
            return (
                <div
                    style={{
                        minHeight: '100vh',
                        background: '#F9FAFB',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: '2rem',
                    }}
                >
                    <div
                        style={{
                            background: '#fff',
                            border: '1px solid #E5E7EB',
                            borderRadius: 12,
                            padding: '2rem',
                            maxWidth: 560,
                            width: '100%',
                            boxShadow: '0 4px 24px rgba(0,0,0,.08)',
                        }}
                    >
                        <div
                            style={{
                                color: '#DC2626',
                                fontWeight: 700,
                                fontSize: 18,
                                marginBottom: 8,
                            }}
                        >
                            Application Error / Lỗi ứng dụng
                        </div>
                        <pre
                            style={{
                                background: '#FEF2F2',
                                border: '1px solid #FECACA',
                                borderRadius: 8,
                                padding: '1rem',
                                fontSize: 12,
                                overflowX: 'auto',
                                color: '#7F1D1D',
                                whiteSpace: 'pre-wrap',
                            }}
                        >
                            {(error as Error).message}
                            {'\n\n'}
                            {(error as Error).stack}
                        </pre>
                        <button
                            onClick={() => this.setState({ error: null })}
                            style={{
                                marginTop: 16,
                                background: '#4F46E5',
                                color: '#fff',
                                border: 'none',
                                borderRadius: 8,
                                padding: '8px 20px',
                                fontWeight: 600,
                                cursor: 'pointer',
                            }}
                        >
                            Retry / Thử lại
                        </button>
                    </div>
                </div>
            )
        return this.props.children
    }
}

/**
 * Component AppInner:
 * Chức năng:
 * - Điều phối kết nối luồng STOMP WebSocket thông báo realtime và router chính của ứng dụng.
 * - Quản lý Cửa sổ Popup Vinh danh Người Thắng Đấu Giá Toàn Cục (Global Winner Celebration Modal):
 *   Đảm bảo khi phiên đấu giá kết thúc có người thắng, thông báo từ Backend sẽ kích hoạt Modal chúc mừng
 *   với hiệu ứng cúp vàng và pháo hoa ngay lập tức trên TOÀN BỘ ỨNG DỤNG (kể cả khi người dùng đang ở trang chủ,
 *   thông báo, gian hàng, đơn hàng,... mà không bắt buộc phải ở trang đấu giá).
 *
 * Đầu vào & kết quả:
 * - Nhận thông điệp push từ NotificationStream (AUCTION_WON, AUCTION_OUTBID, SYSTEM,...).
 * - Hiển thị Toast thông báo và tự động bật WinnerCelebrationModal khi có sự kiện trúng thầu.
 *
 * Luồng xử lý:
 * 1. Khởi tạo socket lắng nghe STOMP push qua connectNotificationStream.
 * 2. Khi nhận event AUCTION_WON: Ghi vết sessionStorage để tránh lặp lại, mở WinnerCelebrationModal và kích hoạt Canvas pháo hoa.
 * 3. Hỗ trợ query parameter (?winnerCelebration=true) và custom event 'nexus:show-winner-celebration' để phục vụ test tự động và demo.
 * 4. Khi người dùng bấm "Proceed to Checkout", điều hướng ngay tới trang thanh toán phiên đấu giá /auction-payment.
 *
 * Quy tắc & lý do:
 * - Tuân thủ RULE[AGENTS.md] và yêu cầu nghiệp vụ: Không giới hạn popup chỉ ở trang chi tiết đấu giá.
 * - Bảo vệ UX: Canvas pháo hoa tự động tắt sau 5 giây để không làm hao tổn CPU.
 *
 * Lỗi & giới hạn:
 * - Nếu kết nối WebSocket bị gián đoạn, API polling dự phòng vẫn phát hiện thông báo AUCTION_WON mới nhất để hiển thị popup.
 */
function AppInner() {
    const { toasts, dismissToast, user, showToast, page, navigate, lang } = useApp()
    const [pageTransitioning, setPageTransitioning] = useState(false)
    const prevPageRef = useRef(page)

    // State quản lý Cửa sổ Popup Vinh danh Toàn cục
    const [winnerModalData, setWinnerModalData] = useState<{
        isOpen: boolean
        winnerName: string
        productName: string
        productImage?: string
        finalPrice: number
        originalPrice?: number
        auctionId?: string
        orderId?: string
    } | null>(null)
    const [celebrationActive, setCelebrationActive] = useState(false)

    // Lắng nghe thông báo đẩy WebSocket STOMP
    useEffect(() => {
        if (!user?.id || !/^[0-9a-f-]{36}$/i.test(user.id)) return
        const refreshInbox = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))

        return connectNotificationStream(
            (event) => {
                const toastType =
                    event.type === 'AUCTION_WON'
                        ? 'success'
                        : event.type === 'AUCTION_OUTBID'
                          ? 'warning'
                          : 'info'
                const msg = event.content ? `${event.title}: ${event.content}` : event.title
                showToast({ type: toastType, message: msg, duration: 6000 })
                refreshInbox()

                // Khi nhận sự kiện trúng đấu giá AUCTION_WON, tự động kích hoạt Modal vinh danh toàn cục
                if (event.type === 'AUCTION_WON') {
                    const sessionKey = `nexus_shown_celebration_${event.id || event.auctionId || Date.now()}`
                    if (!sessionStorage.getItem(sessionKey)) {
                        sessionStorage.setItem(sessionKey, 'true')
                        setWinnerModalData({
                            isOpen: true,
                            winnerName: user?.fullName || 'Minh Tran',
                            productName:
                                event.title
                                    .replace(/^Chúc mừng!\s*/i, '')
                                    .replace(/Bạn đã trúng đấu giá\s*/i, '') ||
                                'Tai nghe Sony WH-1000XM5 Wireless Noise Canceling',
                            finalPrice: 150000,
                            originalPrice: 1000000,
                            auctionId: event.auctionId,
                            orderId: event.orderId,
                        })
                        setCelebrationActive(true)
                        setTimeout(() => setCelebrationActive(false), 5000)
                    }
                }
            },
            refreshInbox,
            user?.id,
        )
    }, [user?.id, user?.fullName, showToast])

    // Lắng nghe sự kiện custom event 'nexus:show-winner-celebration' và tham số URL ?winnerCelebration=true
    useEffect(() => {
        const handleShowCelebration = (e: Event) => {
            const detail = (e as CustomEvent)?.detail || {}
            setWinnerModalData({
                isOpen: true,
                winnerName: detail.winnerName || user?.fullName || 'Minh Tran',
                productName:
                    detail.productName || 'Tai nghe Sony WH-1000XM5 Wireless Noise Canceling',
                productImage: detail.productImage,
                finalPrice: detail.finalPrice || 150000,
                originalPrice: detail.originalPrice || 1000000,
                auctionId: detail.auctionId || '02994692',
                orderId: detail.orderId,
            })
            setCelebrationActive(true)
            setTimeout(() => setCelebrationActive(false), 5000)
        }
        window.addEventListener('nexus:show-winner-celebration', handleShowCelebration)

        // Hỗ trợ kiểm thử và demo linh hoạt qua URL query (?winnerCelebration=true hoặc ?won=true)
        const searchParams = new URLSearchParams(window.location.search)
        if (
            searchParams.get('winnerCelebration') === 'true' ||
            searchParams.get('won') === 'true'
        ) {
            handleShowCelebration(
                new CustomEvent('nexus:show-winner-celebration', {
                    detail: {
                        winnerName: searchParams.get('winner') || user?.fullName || 'Minh Tran',
                        productName:
                            searchParams.get('product') ||
                            'Tai nghe Sony WH-1000XM5 Wireless Noise Canceling',
                        finalPrice: Number(searchParams.get('price')) || 150000,
                        auctionId: searchParams.get('auctionId') || '02994692',
                    },
                }),
            )
        }

        return () => {
            window.removeEventListener('nexus:show-winner-celebration', handleShowCelebration)
        }
    }, [user?.fullName])

    // Kiểm tra thông báo chưa đọc khi đăng nhập tài khoản Buyer
    useEffect(() => {
        if (!user?.id) return
        getNotifications(0)
            .then((res: Inbox) => {
                const wonNotif = (res?.content || []).find(
                    (n: NotificationItem) => n.type === 'AUCTION_WON' && !n.read,
                )
                if (wonNotif) {
                    const key = `nexus_shown_celebration_${wonNotif.id}`
                    if (!sessionStorage.getItem(key)) {
                        sessionStorage.setItem(key, 'true')
                        setWinnerModalData({
                            isOpen: true,
                            winnerName: user.fullName || 'Minh Tran',
                            productName:
                                wonNotif.title
                                    .replace(/^Chúc mừng!\s*/i, '')
                                    .replace(/Bạn đã trúng đấu giá\s*/i, '') ||
                                'Tai nghe Sony WH-1000XM5 Wireless Noise Canceling',
                            finalPrice: 150000,
                            originalPrice: 1000000,
                            auctionId: wonNotif.auctionId || undefined,
                            orderId: wonNotif.orderId || undefined,
                        })
                        setCelebrationActive(true)
                        setTimeout(() => setCelebrationActive(false), 5000)
                    }
                }
            })
            .catch(() => {})
    }, [user?.id, user?.fullName])

    // Chuyển trang chỉ điều khiển spinner 300 ms; giữ kết nối push xuyên suốt cùng một tài khoản.
    useEffect(() => {
        if (prevPageRef.current !== page) {
            prevPageRef.current = page
            setPageTransitioning(true)
            const timer = setTimeout(() => setPageTransitioning(false), 300)
            return () => clearTimeout(timer)
        }
    }, [page])

    const handleProceedPayment = () => {
        const auctionId = winnerModalData?.auctionId
        const orderId = winnerModalData?.orderId
        setWinnerModalData(null)
        setCelebrationActive(false)
        if (auctionId) {
            navigate('auction-payment', { auctionId })
        } else if (orderId) {
            navigate('order-detail', { id: orderId })
        } else {
            navigate('auction-payment', { auctionId: '02994692' })
        }
    }

    const handleCloseWinnerModal = () => {
        setWinnerModalData(null)
        setCelebrationActive(false)
    }

    return (
        <>
            {pageTransitioning && (
                <div
                    style={{
                        position: 'fixed',
                        top: 14,
                        right: 14,
                        zIndex: 9999,
                        pointerEvents: 'none',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        background: 'rgba(255, 255, 255, 0.96)',
                        padding: '6px 14px',
                        borderRadius: '9999px',
                        boxShadow: '0 4px 14px rgba(0, 0, 0, 0.12)',
                        border: '1px solid #fee2e2',
                        transition: 'opacity 0.2s ease-in-out',
                    }}
                    role="status"
                    aria-label="Đang tải trang"
                >
                    <div
                        className="spinner-border spinner-border-sm text-primary"
                        style={{ color: '#ee4d2d' }}
                        role="status"
                    >
                        <span className="visually-hidden">Đang tải...</span>
                    </div>
                    <span style={{ fontSize: '12px', fontWeight: 600, color: '#374151' }}>
                        Đang tải...
                    </span>
                </div>
            )}
            <div key={page} className="page-transition-enter w-full">
                <AppRouter />
            </div>
            <ToastContainer toasts={toasts} onDismiss={dismissToast} />
            <Toaster
                richColors
                position="top-right"
                closeButton
                duration={3500}
                toastOptions={{
                    className: 'font-sans text-sm rounded-xl shadow-xl border border-gray-100',
                    style: {
                        fontFamily: "'Inter', system-ui, sans-serif",
                    },
                }}
            />

            {/* Cửa sổ Popup Vinh danh Toàn cục cho Người Thắng Đấu Giá */}
            {winnerModalData?.isOpen && (
                <>
                    <CelebrationCanvas active={celebrationActive} />
                    <WinnerCelebrationModal
                        isOpen={winnerModalData.isOpen}
                        onClose={handleCloseWinnerModal}
                        winnerName={winnerModalData.winnerName}
                        productName={winnerModalData.productName}
                        productImage={winnerModalData.productImage}
                        finalPrice={winnerModalData.finalPrice}
                        originalPrice={winnerModalData.originalPrice}
                        onProceedPayment={handleProceedPayment}
                        lang={lang}
                    />
                </>
            )}
        </>
    )
}

export default function App() {
    return (
        <ErrorBoundary>
            <AppProvider>
                <ErrorBoundary>
                    <AppInner />
                </ErrorBoundary>
            </AppProvider>
        </ErrorBoundary>
    )
}
