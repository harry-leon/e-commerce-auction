/**
 * Màn hình Hộp thư Thông báo và Tùy chọn Nhận thông báo (Sprint 3 - HA-S3-2).
 * 
 * 1. Chức năng:
 *    - Hiển thị danh sách thông báo IN_APP (Đơn hàng, Thanh toán, Đấu giá, Uy tín).
 *    - Phân biệt trạng thái đã đọc và chưa đọc, hỗ trợ đánh dấu từng tin hoặc toàn bộ hộp thư.
 *    - Quản lý tùy chọn nhận thông báo (Notification Preferences) theo kênh (IN_APP, EMAIL, PUSH) và loại tin.
 *    - Xử lý xin cấp quyền Web Push Notification trên trình duyệt (AC 12, AC 13).
 *    - Lắng nghe sự kiện đẩy thời gian thực qua WebSocket/STOMP (NOTIFICATIONS_CHANGED).
 * 2. Đầu vào & kết quả:
 *    - Nhận userId từ session context, tải Inbox và NotificationPreferences từ API.
 * 3. Luồng xử lý:
 *    - Gọi getNotifications(), getNotificationPreferences(), readNotification(), updateNotificationPreferences().
 * 4. Quy tắc & lý do:
 *    - Hộp thư gắn liền với JWT subject để bảo vệ dữ liệu riêng tư.
 *    - Chỉ hiển thị banner xin cấp quyền khi trình duyệt hỗ trợ Notification API và chưa bị chặn.
 * 5. Lỗi & giới hạn:
 *    - Xử lý đầy đủ loading spinner, empty state và error state kèm nút Thử lại.
 */

import { useEffect, useState, useCallback } from 'react'
import { BuyerLayout } from '../../components/Layout'
import { useApp } from '../../context'
import {
    getNotifications,
    readNotification,
    getNotificationPreferences,
    updateNotificationPreferences,
    requestPushPermission,
    getPushPermissionStatus,
    type Inbox,
    type NotificationPreference,
} from '../../api/notifications'
import { errorMessage } from '../../api/client'
import { NOTIFICATIONS_CHANGED } from '../../api/notificationStream'
// Sử dụng các icon và modal dựng sẵn từ hệ thống UI chung của Nexus
import { Bell, CheckCircle, Settings2, Button, Modal } from '../../components/UI'

/**
 * Các icon SVG nội bộ phục vụ giao diện tùy chọn thông báo và refresh danh sách
 */
function RefreshIcon({ size = 13, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="M21 2v6h-6" />
            <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
            <path d="M3 22v-6h6" />
            <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
        </svg>
    )
}

function PhoneIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
            <line x1="12" y1="18" x2="12.01" y2="18" />
        </svg>
    )
}

function MailIcon({ size = 12, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
            <polyline points="22,6 12,13 2,6" />
        </svg>
    )
}

function MonitorIcon({ size = 12, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
        </svg>
    )
}

/**
 * Chuyển đổi mã loại thông báo (enum thô) sang nhãn tiếng Việt/tiếng Anh thân thiện với người dùng (DoD UI sạch).
 * Đầu vào: type (ví dụ 'AUCTION_WON', 'AUCTION_OUTBID'), lang ('vi' | 'en').
 * Kết quả: Chuỗi nhãn hiển thị dễ hiểu, không để lộ enum thô hệ thống trên giao diện.
 */
function formatNotificationType(type?: string, lang: string = 'vi'): string {
    if (!type) return ''
    const mapVi: Record<string, string> = {
        AUCTION_WON: 'Trúng đấu giá',
        AUCTION_OUTBID: 'Bị trả giá cao hơn',
        AUCTION_PAYMENT_REMINDER: 'Nhắc thanh toán',
        ORDER_PLACED: 'Đơn hàng mới',
        ORDER_CREATED: 'Đơn hàng mới',
        PAYMENT_SUCCESS: 'Thanh toán thành công',
        PAYMENT_FAILED: 'Thanh toán thất bại',
        ORDER_STATUS_CHANGED: 'Cập nhật đơn hàng',
        REFUND_REQUESTED: 'Yêu cầu hoàn tiền',
        REFUND_ISSUED: 'Hoàn tiền thành công',
        REFUND_REJECTED: 'Từ chối hoàn tiền',
    }
    const mapEn: Record<string, string> = {
        AUCTION_WON: 'Auction Won',
        AUCTION_OUTBID: 'Outbid',
        AUCTION_PAYMENT_REMINDER: 'Payment Reminder',
        ORDER_PLACED: 'Order Placed',
        ORDER_CREATED: 'Order Placed',
        PAYMENT_SUCCESS: 'Payment Success',
        PAYMENT_FAILED: 'Payment Failed',
        ORDER_STATUS_CHANGED: 'Order Update',
        REFUND_REQUESTED: 'Refund Requested',
        REFUND_ISSUED: 'Refund Issued',
        REFUND_REJECTED: 'Refund Rejected',
    }
    if (lang === 'vi') return mapVi[type] || type.replace(/_/g, ' ')
    return mapEn[type] || type.replace(/_/g, ' ')
}

export function NotificationInbox() {
    const { user, navigate } = useApp()
    return (
        <BuyerLayout>
            <InboxContent key={user?.id || 'guest'} userId={user?.id} navigate={navigate} />
        </BuyerLayout>
    )
}

function InboxContent({
    userId,
    navigate,
}: {
    userId?: string
    navigate: ReturnType<typeof useApp>['navigate']
}) {
    // Mặc định ngôn ngữ tiếng Việt (vi) khi context chưa khởi tạo hoặc trong unit test
    const { lang: appLang, showToast } = useApp()
    const lang = appLang || 'vi'
    const [page, setPage] = useState(0)
    const [revision, setRevision] = useState(0)
    const [inbox, setInbox] = useState<Inbox | null>(null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const [saving, setSaving] = useState(false)
    const [filterTab, setFilterTab] = useState<'all' | 'unread'>('all')

    // State cho Tùy chọn thông báo (Notification Preferences)
    const [prefOpen, setPrefOpen] = useState(false)
    const [preferences, setPreferences] = useState<NotificationPreference[]>([])
    const [prefLoading, setPrefLoading] = useState(false)
    const [prefSaving, setPrefSaving] = useState(false)

    // State cho Web Push Permission trên trình duyệt
    const [pushStatus, setPushStatus] = useState<NotificationPermission>(getPushPermissionStatus())

    // Lắng nghe tín hiệu WebSocket cập nhật thông báo
    useEffect(() => {
        if (!userId) return
        const refresh = () => setRevision((value) => value + 1)
        window.addEventListener(NOTIFICATIONS_CHANGED, refresh)
        return () => window.removeEventListener(NOTIFICATIONS_CHANGED, refresh)
    }, [userId])

    // Nạp danh sách thông báo từ REST
    const fetchInbox = useCallback(() => {
        if (!userId) return
        const controller = new AbortController()
        setLoading(true)
        setError('')
        getNotifications(page, controller.signal)
            .then((data) => {
                if (!controller.signal.aborted) setInbox(data)
            })
            .catch((reason) => {
                if (!controller.signal.aborted) setError(errorMessage(reason))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [userId, page, revision])

    useEffect(() => {
        const cleanup = fetchInbox()
        return cleanup
    }, [fetchInbox])

    // Nạp tùy chọn nhận thông báo khi mở modal cấu hình
    const loadPreferences = useCallback(async () => {
        setPrefLoading(true)
        try {
            const data = await getNotificationPreferences()
            setPreferences(data)
        } catch {
            // Đã có fallback trong hàm
        } finally {
            setPrefLoading(false)
        }
    }, [])

    useEffect(() => {
        if (prefOpen) {
            loadPreferences()
        }
    }, [prefOpen, loadPreferences])

    // Đánh dấu đã đọc một thông báo hoặc tất cả
    async function markRead(id?: string) {
        setSaving(true)
        setError('')
        try {
            await readNotification(id)
            setRevision((value) => value + 1)
            showToast({
                type: 'success',
                message: id
                    ? lang === 'vi'
                        ? 'Đã đánh dấu thông báo là đã đọc.'
                        : 'Marked notification as read.'
                    : lang === 'vi'
                      ? 'Đã đánh dấu tất cả thông báo là đã đọc.'
                      : 'All notifications marked as read.',
            })
        } catch (reason) {
            setError(errorMessage(reason))
        } finally {
            setSaving(false)
        }
    }

    // Xử lý bật quyền nhận thông báo đẩy trên trình duyệt (AC 12, AC 13)
    async function handleRequestPush() {
        const status = await requestPushPermission()
        setPushStatus(status)
        if (status === 'granted') {
            showToast({
                type: 'success',
                message:
                    lang === 'vi'
                        ? 'Đã bật thông báo đẩy trên trình duyệt thành công!'
                        : 'Push notifications enabled successfully!',
            })
            // Hiển thị thông báo chào mừng
            if (typeof window !== 'undefined' && 'Notification' in window) {
                new Notification(lang === 'vi' ? 'Nexus Marketplace' : 'Nexus Notification', {
                    body:
                        lang === 'vi'
                            ? 'Bạn sẽ nhận được thông báo ngay khi có sự kiện đơn hàng hoặc đấu giá.'
                            : 'You will now receive instant order and auction alerts.',
                    icon: '/favicon.ico',
                })
            }
        } else if (status === 'denied') {
            showToast({
                type: 'error',
                message:
                    lang === 'vi'
                        ? 'Quyền thông báo bị từ chối trong cài đặt trình duyệt của bạn.'
                        : 'Notification permission was denied in browser settings.',
            })
        }
    }

    // Toggle một cài đặt kênh thông báo
    function togglePreference(type: string, channel: 'IN_APP' | 'EMAIL' | 'PUSH') {
        setPreferences((prev) =>
            prev.map((item) => {
                if (item.notificationType === type && item.channel === channel) {
                    return { ...item, enabled: !item.enabled }
                }
                return item
            }),
        )
    }

    // Lưu tùy chọn thông báo
    async function handleSavePreferences() {
        setPrefSaving(true)
        try {
            await updateNotificationPreferences(preferences)
            showToast({
                type: 'success',
                message:
                    lang === 'vi'
                        ? 'Đã lưu cấu hình tùy chọn nhận thông báo.'
                        : 'Notification preferences saved.',
            })
            setPrefOpen(false)
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'vi' ? 'Không thể lưu cài đặt' : 'Failed to save preferences'),
            })
        } finally {
            setPrefSaving(false)
        }
    }

    const items = inbox?.content || []
    const displayItems =
        filterTab === 'unread' ? items.filter((item) => !item.read) : items

    return (
        <section className="max-w-4xl mx-auto space-y-5">
            {/* Tiêu đề & Action Bar */}
            <div className="nexus-card p-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                    <div className="flex items-center gap-3 flex-wrap">
                        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
                            <Bell className="text-primary" size={24} />
                            {lang === 'vi' ? 'Hộp thư Thông báo' : 'Notifications Inbox'}
                        </h1>
                        <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-semibold bg-orange-100 text-primary border border-orange-200">
                            {lang === 'vi'
                                ? `${inbox?.unreadCount ?? 0} chưa đọc · ${inbox?.totalElements ?? 0} thông báo`
                                : `${inbox?.unreadCount ?? 0} unread · ${inbox?.totalElements ?? 0} notifications`}
                        </span>
                    </div>
                    <p className="text-sm text-gray-500 mt-1">
                        {lang === 'vi'
                            ? 'Theo dõi cập nhật đơn hàng, thanh toán, đấu giá và uy tín tài khoản.'
                            : 'Track updates for orders, payments, auctions, and reputation.'}
                    </p>
                </div>
                {userId && (
                    <div className="flex items-center gap-2">
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setPrefOpen(true)}
                            className="flex items-center gap-1.5"
                        >
                            <Settings2 size={15} />
                            {lang === 'vi' ? 'Cài đặt thông báo' : 'Preferences'}
                        </Button>
                        <Button
                            variant="outline"
                            size="sm"
                            disabled={loading || saving || !inbox?.unreadCount}
                            onClick={() => void markRead()}
                            className="flex items-center gap-1.5"
                        >
                            <CheckCircle size={15} />
                            {lang === 'vi' ? 'Đánh dấu tất cả đã đọc' : 'Mark all read'}
                        </Button>
                    </div>
                )}
            </div>

            {/* Banner cấp quyền Web Push Notification trên trình duyệt (AC 12, AC 13) */}
            {userId && pushStatus !== 'granted' && (
                <div className="p-4 bg-gradient-to-r from-orange-50 to-amber-50 border border-orange-200 rounded-xl flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-sm">
                    <div className="flex items-start gap-3">
                        <PhoneIcon className="text-primary shrink-0 mt-0.5" size={20} />
                        <div>
                            <p className="font-semibold text-gray-900">
                                {lang === 'vi'
                                    ? 'Nhận thông báo đơn hàng & đấu giá tức thì'
                                    : 'Receive Instant Order & Auction Push Alerts'}
                            </p>
                            <p className="text-xs text-gray-600 mt-0.5">
                                {pushStatus === 'denied'
                                    ? lang === 'vi'
                                        ? 'Quyền thông báo đang bị chặn trong trình duyệt. Bạn có thể mở lại trong cài đặt trang web.'
                                        : 'Push notifications are currently blocked in your browser settings.'
                                    : lang === 'vi'
                                      ? 'Bật thông báo đẩy để không bỏ lỡ khi có người trả giá cao hơn hoặc đơn hàng được giao.'
                                      : 'Enable push alerts to never miss an outbid or delivery update.'}
                            </p>
                        </div>
                    </div>
                    {pushStatus !== 'denied' && (
                        <Button
                            variant="primary"
                            size="sm"
                            onClick={handleRequestPush}
                            className="shrink-0"
                        >
                            {lang === 'vi' ? 'Bật thông báo ngay' : 'Enable Push Alerts'}
                        </Button>
                    )}
                </div>
            )}

            {!userId ? (
                <div className="nexus-card p-12 text-center">
                    <Bell size={40} className="mx-auto text-gray-300 mb-3" />
                    <p className="text-gray-600 font-medium mb-4">
                        {lang === 'vi'
                            ? 'Vui lòng đăng nhập để xem thông báo cá nhân của bạn.'
                            : 'Please log in to view your notifications.'}
                    </p>
                    <Button variant="primary" onClick={() => navigate('login')}>
                        {lang === 'vi' ? 'Đăng nhập để xem thông báo' : 'Log in to view notifications'}
                    </Button>
                </div>
            ) : (
                <div className="nexus-card overflow-hidden">
                    {/* Filter tabs */}
                    <div className="flex items-center justify-between px-6 pt-4 pb-2 border-b border-border bg-gray-50/50">
                        <div className="flex items-center gap-2">
                            <button
                                onClick={() => setFilterTab('all')}
                                className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-colors ${
                                    filterTab === 'all'
                                        ? 'bg-primary text-white'
                                        : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                                }`}
                            >
                                {lang === 'vi' ? 'Tất cả' : 'All'} ({inbox?.totalElements || 0})
                            </button>
                            <button
                                onClick={() => setFilterTab('unread')}
                                className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-colors ${
                                    filterTab === 'unread'
                                        ? 'bg-primary text-white'
                                        : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                                }`}
                            >
                                {lang === 'vi' ? 'Chưa đọc' : 'Unread'} ({inbox?.unreadCount || 0})
                            </button>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            disabled={loading || saving}
                            onClick={() => setRevision((v) => v + 1)}
                            className="text-xs"
                        >
                            <RefreshIcon size={13} className="mr-1" />
                            {lang === 'vi' ? 'Tải lại' : 'Reload'}
                        </Button>
                    </div>

                    {error && (
                        <div
                            className="p-4 bg-red-50 border-b border-red-200 text-sm text-red-700 flex items-center justify-between"
                            role="alert"
                        >
                            <span>{error}</span>
                            <Button size="sm" variant="outline" onClick={fetchInbox}>
                                {lang === 'vi' ? 'Thử lại' : 'Retry'}
                            </Button>
                        </div>
                    )}
                    {loading && (
                        <div
                            className="py-16 text-center text-gray-500 flex flex-col items-center justify-center gap-2"
                            role="status"
                        >
                            <div className="spinner-border spinner-border-sm text-primary inline-block w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin" />
                            <span className="text-sm">
                                {lang === 'vi' ? 'Đang tải thông báo…' : 'Loading notifications…'}
                            </span>
                        </div>
                    )}

                    {!loading && displayItems.length === 0 && (
                        <div className="py-16 text-center text-gray-400">
                            <Bell size={36} className="mx-auto text-gray-300 mb-2" />
                            <p className="text-sm font-medium">
                                {filterTab === 'unread'
                                    ? lang === 'vi'
                                        ? 'Bạn không có thông báo chưa đọc nào.'
                                        : 'No unread notifications.'
                                    : lang === 'vi'
                                      ? 'Chưa có thông báo trên trang này.'
                                      : 'No notifications on this page.'}
                            </p>
                        </div>
                    )}

                    {!loading && displayItems.length > 0 && (
                        <div className="divide-y divide-border">
                            {displayItems.map((item) => (
                                <div
                                    key={item.id}
                                    className={`p-5 transition-colors flex items-start justify-between gap-4 ${
                                        item.read ? 'bg-white' : 'bg-orange-50/40 hover:bg-orange-50/60'
                                    }`}
                                >
                                    <div className="flex items-start gap-3 flex-1 min-w-0">
                                        <div
                                            className={`w-2.5 h-2.5 rounded-full mt-1.5 shrink-0 ${
                                                item.read ? 'bg-transparent' : 'bg-primary animate-pulse'
                                            }`}
                                        />
                                        <div className="flex-1 min-w-0 space-y-1">
                                            <div className="flex items-center gap-2 flex-wrap">
                                                <h3
                                                    className={`text-sm ${
                                                        item.read ? 'font-semibold text-gray-800' : 'font-bold text-gray-900'
                                                    }`}
                                                >
                                                    {item.title}
                                                </h3>
                                                {item.type && (
                                                    <span className="text-[10px] font-bold tracking-wide px-2 py-0.5 rounded bg-orange-50 text-orange-700 border border-orange-200">
                                                        {formatNotificationType(item.type, lang)}
                                                    </span>
                                                )}
                                            </div>
                                            <p className="text-xs text-gray-600 leading-relaxed break-words">
                                                {item.content}
                                            </p>
                                            <time
                                                dateTime={item.createdAt}
                                                className="text-[11px] text-gray-400 block pt-1"
                                            >
                                                {new Date(item.createdAt).toLocaleString()}
                                            </time>
                                        </div>
                                    </div>

                                                                        <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
                                        {/* Nút hành động thanh toán cho thông báo thắng đấu giá */}
                                        {(item.type === 'AUCTION_WON' || item.type === 'AUCTION_PAYMENT_REMINDER') && (
                                            <button
                                                className="px-3 py-1 bg-[#ee4d2d] text-white text-xs font-semibold rounded-md hover:bg-[#d03e20] transition-colors shadow-sm"
                                                onClick={() => {
                                                    if (item.auctionId) {
                                                        navigate('auction-payment' as any, { auctionId: item.auctionId })
                                                    } else if (item.orderId) {
                                                        navigate('order-detail' as any, { id: item.orderId })
                                                    } else if (item.paymentLink) {
                                                        window.location.href = item.paymentLink
                                                    }
                                                }}
                                            >
                                                {lang === 'vi' ? 'Thanh toán ngay' : 'Pay now'}
                                            </button>
                                        )}

                                        {/* Nút xem lại phiên đấu giá khi bị trả giá cao hơn (Outbid) */}
                                        {item.type === 'AUCTION_OUTBID' && (
                                            <button
                                                className="px-3 py-1 bg-amber-600 text-white text-xs font-semibold rounded-md hover:bg-amber-700 transition-colors shadow-sm"
                                                onClick={() => {
                                                    if (item.auctionId) {
                                                        navigate('auction-detail' as any, { id: item.auctionId })
                                                    } else {
                                                        navigate('auction-list')
                                                    }
                                                }}
                                            >
                                                {lang === 'vi' ? 'Đấu giá lại' : 'Re-bid'}
                                            </button>
                                        )}

                                        {/* Nút xem đơn hàng cho thông báo đơn thông thường */}
                                        {item.type !== 'AUCTION_WON' &&
                                            item.type !== 'AUCTION_PAYMENT_REMINDER' &&
                                            item.type !== 'AUCTION_OUTBID' &&
                                            item.orderId && (
                                                <button
                                                    className="px-3 py-1 bg-gray-100 text-gray-700 text-xs font-medium rounded-md hover:bg-gray-200 transition-colors"
                                                    onClick={() => navigate('order-detail' as any, { id: item.orderId })}
                                                >
                                                    {lang === 'vi' ? 'Xem đơn hàng' : 'View order'}
                                                </button>
                                            )}

                                        {!item.read && (
                                            <Button
                                                variant="outline"
                                                size="sm"
                                                disabled={saving || loading}
                                                onClick={() => void markRead(item.id)}
                                                className="text-xs shrink-0"
                                            >
                                                {lang === 'vi' ? 'Đánh dấu đã đọc' : 'Mark read'}
                                            </Button>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}

                    {/* Pagination */}
                    {inbox && inbox.totalElements > 20 && (
                        <div className="p-4 border-t border-border flex items-center justify-between text-xs text-gray-600 bg-gray-50/50">
                            <Button
                                variant="outline"
                                size="sm"
                                disabled={page === 0 || loading || saving}
                                onClick={() => setPage((v) => v - 1)}
                            >
                                {lang === 'vi' ? 'Trang trước' : 'Previous'}
                            </Button>
                            <span>
                                {lang === 'vi' ? 'Trang' : 'Page'} {page + 1} /{' '}
                                {Math.ceil(inbox.totalElements / 20)}
                            </span>
                            <Button
                                variant="outline"
                                size="sm"
                                disabled={(page + 1) * 20 >= inbox.totalElements || loading || saving}
                                onClick={() => setPage((v) => v + 1)}
                            >
                                {lang === 'vi' ? 'Trang sau' : 'Next'}
                            </Button>
                        </div>
                    )}
                </div>
            )}

            {/* Modal Quản lý Tùy chọn Nhận thông báo (Notification Preferences - AC 11) */}
            <Modal
                open={prefOpen}
                onClose={() => setPrefOpen(false)}
                title={lang === 'vi' ? 'Cài đặt Tùy chọn Nhận thông báo' : 'Notification Preferences'}
            >
                <div className="space-y-5">
                    <p className="text-xs text-gray-500 leading-relaxed">
                        {lang === 'vi'
                            ? 'Chọn kênh bạn muốn nhận thông báo cho từng loại sự kiện trên hệ thống.'
                            : 'Select which channels you want to receive alerts for each category.'}
                    </p>

                    {prefLoading ? (
                        <div className="py-8 text-center text-gray-500" role="status">
                            <div className="spinner-border spinner-border-sm text-primary mb-2 inline-block w-5 h-5 border-2 border-primary border-t-transparent rounded-full animate-spin" />
                            <p className="text-xs">
                                {lang === 'vi' ? 'Đang nạp cài đặt...' : 'Loading preferences...'}
                            </p>
                        </div>
                    ) : (
                        <div className="border border-border rounded-xl overflow-hidden divide-y divide-border">
                            {/* Header row */}
                            <div className="grid grid-cols-[1fr_70px_70px_70px] p-3 bg-gray-50 text-[11px] font-bold text-gray-500 uppercase tracking-wider text-center">
                                <span className="text-left">
                                    {lang === 'vi' ? 'Loại thông báo' : 'Category'}
                                </span>
                                <span className="flex items-center justify-center gap-1" title="In-App">
                                    <MonitorIcon size={12} /> App
                                </span>
                                <span className="flex items-center justify-center gap-1" title="Email">
                                    <MailIcon size={12} /> Email
                                </span>
                                <span className="flex items-center justify-center gap-1" title="Web Push">
                                    <PhoneIcon size={12} /> Push
                                </span>
                            </div>

                            {[
                                {
                                    type: 'ORDER',
                                    label: lang === 'vi' ? 'Đơn hàng & Giao hàng' : 'Orders & Shipping',
                                },
                                {
                                    type: 'AUCTION',
                                    label: lang === 'vi' ? 'Đấu giá & Trả giá' : 'Auctions & Bids',
                                },
                                {
                                    type: 'PAYMENT',
                                    label: lang === 'vi' ? 'Thanh toán & Ví tiền' : 'Payments & Escrow',
                                },
                                {
                                    type: 'PROMOTION',
                                    label: lang === 'vi' ? 'Khuyến mãi & Tin tức' : 'Promotions & News',
                                },
                                {
                                    type: 'SYSTEM',
                                    label: lang === 'vi' ? 'Uy tín & Hệ thống' : 'Reputation & System',
                                },
                            ].map((cat) => {
                                const inApp = preferences.find(
                                    (p) => p.notificationType === cat.type && p.channel === 'IN_APP',
                                )?.enabled ?? true
                                const email = preferences.find(
                                    (p) => p.notificationType === cat.type && p.channel === 'EMAIL',
                                )?.enabled ?? true
                                const push = preferences.find(
                                    (p) => p.notificationType === cat.type && p.channel === 'PUSH',
                                )?.enabled ?? false

                                return (
                                    <div
                                        key={cat.type}
                                        className="grid grid-cols-[1fr_70px_70px_70px] p-3 items-center text-xs"
                                    >
                                        <span className="font-semibold text-gray-800">
                                            {cat.label}
                                        </span>
                                        <div className="flex justify-center">
                                            <input
                                                type="checkbox"
                                                checked={inApp}
                                                onChange={() => togglePreference(cat.type, 'IN_APP')}
                                                className="w-4 h-4 accent-primary cursor-pointer"
                                                aria-label={`${cat.label} in-app`}
                                            />
                                        </div>
                                        <div className="flex justify-center">
                                            <input
                                                type="checkbox"
                                                checked={email}
                                                onChange={() => togglePreference(cat.type, 'EMAIL')}
                                                className="w-4 h-4 accent-primary cursor-pointer"
                                                aria-label={`${cat.label} email`}
                                            />
                                        </div>
                                        <div className="flex justify-center">
                                            <input
                                                type="checkbox"
                                                checked={push}
                                                onChange={() => togglePreference(cat.type, 'PUSH')}
                                                className="w-4 h-4 accent-primary cursor-pointer"
                                                aria-label={`${cat.label} push`}
                                            />
                                        </div>
                                    </div>
                                )
                            })}
                        </div>
                    )}

                    <div className="flex justify-end gap-2 pt-2 border-t border-border">
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setPrefOpen(false)}
                            disabled={prefSaving}
                        >
                            {lang === 'vi' ? 'Hủy' : 'Cancel'}
                        </Button>
                        <Button
                            variant="primary"
                            size="sm"
                            onClick={handleSavePreferences}
                            disabled={prefSaving || prefLoading}
                        >
                            {prefSaving
                                ? lang === 'vi'
                                    ? 'Đang lưu...'
                                    : 'Saving...'
                                : lang === 'vi'
                                  ? 'Lưu tùy chọn'
                                  : 'Save Preferences'}
                        </Button>
                    </div>
                </div>
            </Modal>
        </section>
    )
}
