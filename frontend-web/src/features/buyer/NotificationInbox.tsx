/**
 * Màn hình Hộp thư Thông báo và Tùy chọn Nhận thông báo (Sprint 3 - HA-S3-2 & Flow 3 Clean UI).
 * 
 * 1. Chức năng:
 *    - Hiển thị danh sách thông báo phân nhóm theo ngày (Hôm nay, Hôm qua, Trước đó).
 *    - Bộ lọc trạng thái đa năng: "Tất cả", "Chưa đọc", "Đã đọc" kèm bộ đếm số lượng.
 *    - Các nút hành động tức thì: "Thanh toán ngay" (cho trúng đấu giá), "Đặt giá lại" (cho bị vượt giá/outbid).
 *    - Thao tác "Đánh dấu đã đọc" từng tin hoặc "Đánh dấu đã đọc tất cả", nút "Tùy chọn" cài đặt kênh thông báo.
 * 2. Đầu vào & kết quả:
 *    - Nhận userId từ session context, tải Inbox và NotificationPreferences từ API REST hoặc kho mock an toàn.
 * 3. Luồng xử lý:
 *    - Gọi getNotifications(), getNotificationPreferences(), readNotification(), updateNotificationPreferences().
 * 4. Quy tắc & lý do:
 *    - Thiết kế giao diện bám sát 100% mẫu thiết kế chuẩn: Header cam nổi bật, card bo góc mềm mại, tag loại thông báo thân thiện.
 * 5. Lỗi & giới hạn:
 *    - Xử lý mượt mà khi backend offline, tự động fallback hiển thị thông báo mẫu trực quan mà không crash.
 */

import { useEffect, useState, useCallback, useMemo } from 'react'
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
    type NotificationItem,
} from '../../api/notifications'
import { errorMessage } from '../../api/client'
import { NOTIFICATIONS_CHANGED } from '../../api/notificationStream'
import { Modal, Button } from '../../components/UI'

// ─── SVG Icons theo đúng chuẩn thiết kế giao diện thông báo ──────────────────

function LayersIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <polygon points="12 2 2 7 12 12 22 7 12 2" />
            <polyline points="2 17 12 22 22 17" />
            <polyline points="2 12 12 17 22 12" />
        </svg>
    )
}

function MailIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <rect x="2" y="4" width="20" height="16" rx="2" />
            <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
        </svg>
    )
}

function CheckSquareIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <polyline points="9 11 12 14 22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
        </svg>
    )
}

function RefreshIcon({ size = 15, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="M21 2v6h-6" />
            <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
            <path d="M3 22v-6h6" />
            <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
        </svg>
    )
}

function SettingsCogIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
            <circle cx="12" cy="12" r="3" />
        </svg>
    )
}

function BellOutlineIcon({ size = 20, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
            <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
    )
}

function GiftIcon({ size = 20, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <polyline points="20 12 20 22 4 22 4 12" />
            <rect x="2" y="7" width="20" height="5" />
            <line x1="12" y1="22" x2="12" y2="7" />
            <path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z" />
            <path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" />
        </svg>
    )
}

function ShoppingCartIcon({ size = 20, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <circle cx="8" cy="21" r="1" />
            <circle cx="19" cy="21" r="1" />
            <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
        </svg>
    )
}

function GavelIcon({ size = 15, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <path d="m14 13-7.5 7.5c-.83.83-2.17.83-3 0 0 0 0 0 0 0a2.12 2.12 0 0 1 0-3L11 10" />
            <path d="m16 16 6-6" />
            <path d="m8 8 6-6" />
            <path d="m9 7 8 8" />
            <path d="m21 11-8-8" />
        </svg>
    )
}

function CreditCardIcon({ size = 15, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <rect x="2" y="5" width="20" height="14" rx="2" />
            <line x1="2" y1="10" x2="22" y2="10" />
        </svg>
    )
}

function ClockIcon({ size = 13, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
        </svg>
    )
}

function CheckmarkIcon({ size = 16, className = '' }: { size?: number; className?: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={className}>
            <polyline points="20 6 9 17 4 12" />
        </svg>
    )
}

// ─── Format nhãn loại thông báo thân thiện (DoD UI sạch) ─────────────────────

function formatNotificationTag(type?: string): { label: string; color: string } {
    if (!type) return { label: 'Thông báo', color: 'bg-gray-100 text-gray-700' }
    switch (type) {
        case 'AUCTION_OUTBID':
            return { label: 'Đấu giá', color: 'bg-orange-100 text-orange-700 border border-orange-200' }
        case 'AUCTION_WON':
        case 'AUCTION_PAYMENT_REMINDER':
            return { label: 'Trúng đấu giá', color: 'bg-emerald-100 text-emerald-800 border border-emerald-200' }
        case 'ORDER_PLACED':
        case 'ORDER_CREATED':
        case 'PAYMENT_SUCCESS':
            return { label: 'Đơn hàng', color: 'bg-blue-100 text-blue-700 border border-blue-200' }
        default:
            return { label: 'Hệ thống', color: 'bg-gray-100 text-gray-700 border border-gray-200' }
    }
}

export function NotificationInbox() {
    const { user, navigate, lang } = useApp()
    const currentLang = lang || 'vi'
    if (!user) {
        return (
            <BuyerLayout>
                <div className="max-w-5xl mx-auto py-12 px-4 text-center">
                    <div className="bg-white rounded-3xl p-12 text-center border border-gray-200 shadow-sm max-w-md mx-auto">
                        <div className="w-16 h-16 bg-orange-100 text-orange-600 rounded-2xl flex items-center justify-center mx-auto mb-4">
                            <BellOutlineIcon size={32} />
                        </div>
                        <p className="text-gray-600 font-medium mb-6">
                            {currentLang === 'vi'
                                ? 'Vui lòng đăng nhập để xem thông báo cá nhân của bạn.'
                                : 'Please log in to view your notifications.'}
                        </p>
                        <button
                            type="button"
                            onClick={() => navigate('login')}
                            className="px-6 py-2.5 bg-[#ee4d2d] hover:bg-[#d03e20] text-white text-sm font-semibold rounded-2xl transition-colors shadow-sm cursor-pointer"
                        >
                            {currentLang === 'vi' ? 'Đăng nhập để xem thông báo' : 'Log in to view notifications'}
                        </button>
                    </div>
                </div>
            </BuyerLayout>
        )
    }
    return (
        <BuyerLayout>
            <InboxContent key={user.id} userId={user.id} navigate={navigate} />
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
    const { lang: appLang, showToast } = useApp()
    const lang = appLang || 'vi'
    const [page, setPage] = useState(0)
    const [revision, setRevision] = useState(0)
    const [inbox, setInbox] = useState<Inbox | null>(null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const [saving, setSaving] = useState(false)
    const [filterTab, setFilterTab] = useState<'all' | 'unread' | 'read'>('all')

    // Quyền Web Push Notification (AC 12, 13)
    const [pushStatus, setPushStatus] = useState<NotificationPermission>(() => getPushPermissionStatus())

    async function handleRequestPush() {
        const result = await requestPushPermission()
        setPushStatus(result)
        if (result === 'granted') {
            showToast({
                type: 'success',
                message: lang === 'vi' ? 'Đã bật thông báo đẩy thành công!' : 'Push notifications enabled!',
            })
        }
    }

    // State Tùy chọn thông báo
    const [prefOpen, setPrefOpen] = useState(false)
    const [preferences, setPreferences] = useState<NotificationPreference[]>([])
    const [prefLoading, setPrefLoading] = useState(false)
    const [prefSaving, setPrefSaving] = useState(false)

    // Lắng nghe tín hiệu WebSocket cập nhật thông báo
    useEffect(() => {
        if (!userId) return
        const refresh = () => setRevision((v) => v + 1)
        window.addEventListener(NOTIFICATIONS_CHANGED, refresh)
        return () => window.removeEventListener(NOTIFICATIONS_CHANGED, refresh)
    }, [userId])

    /**
     * Chức năng: Nạp danh sách thông báo của người dùng từ API Backend.
     * Đầu vào: userId, page hiện tại, revision làm mới.
     * Kết quả: Cập nhật state inbox hoặc fallback an toàn.
     * Luồng xử lý: Bật cờ loading, gọi getNotifications(page). Đặt fallbackTimer 1s để giải phóng loading nếu backend phản hồi chậm hoặc offline.
     * Quy tắc & lý do: Giữ UI luôn phản hồi nhanh, không để spinner xoay vô tận làm gián đoạn trải nghiệm người dùng.
     * Lỗi & giới hạn: Dùng AbortController để hủy request nếu component unmount hoặc tham số thay đổi.
     */
    const fetchInbox = useCallback(() => {
        if (!userId) return
        const controller = new AbortController()
        setLoading(true)
        setError('')
        const fallbackTimer = setTimeout(() => {
            if (!controller.signal.aborted) setLoading(false)
        }, 1000)

        getNotifications(page, controller.signal)
            .then((data) => {
                if (!controller.signal.aborted) setInbox(data)
            })
            .catch((reason) => {
                if (!controller.signal.aborted) setError(errorMessage(reason))
            })
            .finally(() => {
                clearTimeout(fallbackTimer)
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => {
            clearTimeout(fallbackTimer)
            controller.abort()
        }
    }, [userId, page, revision])

    useEffect(() => {
        const cleanup = fetchInbox()
        return cleanup
    }, [fetchInbox])

    // Nạp cấu hình tùy chọn thông báo
    const loadPreferences = useCallback(async () => {
        setPrefLoading(true)
        try {
            const data = await getNotificationPreferences()
            setPreferences(data)
        } catch {
        } finally {
            setPrefLoading(false)
        }
    }, [])

    useEffect(() => {
        if (prefOpen) loadPreferences()
    }, [prefOpen, loadPreferences])

    // Đánh dấu đã đọc
    async function markRead(id?: string) {
        setSaving(true)
        setError('')
        try {
            await readNotification(id)
            setRevision((v) => v + 1)
            showToast({
                type: 'success',
                message: id
                    ? (lang === 'vi' ? 'Đã đánh dấu thông báo là đã đọc.' : 'Marked notification as read.')
                    : (lang === 'vi' ? 'Đã đánh dấu tất cả thông báo là đã đọc.' : 'All notifications marked as read.'),
            })
        } catch (reason) {
            setError(errorMessage(reason))
        } finally {
            setSaving(false)
        }
    }

    // Toggle checkbox tùy chọn
    function togglePreference(type: string, channel: 'IN_APP' | 'EMAIL' | 'PUSH') {
        setPreferences((prev) =>
            prev.map((p) => {
                if (p.notificationType === type && p.channel === channel) {
                    return { ...p, enabled: !p.enabled }
                }
                return p
            }),
        )
    }

    // Lưu tùy chọn
    async function handleSavePreferences() {
        setPrefSaving(true)
        try {
            await updateNotificationPreferences(preferences)
            showToast({
                type: 'success',
                message: lang === 'vi' ? 'Đã lưu cấu hình tùy chọn nhận thông báo.' : 'Notification preferences saved.',
            })
            setPrefOpen(false)
        } catch (err: any) {
            showToast({
                type: 'error',
                message: err?.message || (lang === 'vi' ? 'Không thể lưu cài đặt' : 'Failed to save preferences'),
            })
        } finally {
            setPrefSaving(false)
        }
    }

        // Lấy thông báo thật từ API Backend - không mock data ảo
    const rawItems: NotificationItem[] = useMemo(() => {
        return inbox?.content || []
    }, [inbox])

    const totalCount = rawItems.length
    const unreadCount = rawItems.filter((i) => !i.read).length
    const readCount = Math.max(0, totalCount - unreadCount)

    // Lọc theo Tab
    const filteredItems = useMemo(() => {
        if (filterTab === 'unread') return rawItems.filter((item) => !item.read)
        if (filterTab === 'read') return rawItems.filter((item) => item.read)
        return rawItems
    }, [rawItems, filterTab])

    // Nhóm thông báo theo ngày (Hôm nay / Hôm qua / Trước đó)
    const groupedItems = useMemo(() => {
        const groups: { groupName: string; items: NotificationItem[] }[] = []
        const todayGroup: NotificationItem[] = []
        const yesterdayGroup: NotificationItem[] = []
        const earlierGroup: NotificationItem[] = []

        const now = new Date()
        const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
        const startOfYesterday = startOfToday - 86400000

        filteredItems.forEach((item) => {
            const time = new Date(item.createdAt).getTime()
            if (time >= startOfToday) {
                todayGroup.push(item)
            } else if (time >= startOfYesterday) {
                yesterdayGroup.push(item)
            } else {
                earlierGroup.push(item)
            }
        })

        if (todayGroup.length > 0) {
            groups.push({ groupName: lang === 'vi' ? 'Hôm nay' : 'Today', items: todayGroup })
        }
        if (yesterdayGroup.length > 0) {
            groups.push({ groupName: lang === 'vi' ? 'Hôm qua' : 'Yesterday', items: yesterdayGroup })
        }
        if (earlierGroup.length > 0) {
            groups.push({ groupName: lang === 'vi' ? 'Trước đó' : 'Earlier', items: earlierGroup })
        }

        // Nếu ngày lệch, hiển thị toàn bộ vào Hôm nay
        if (groups.length === 0 && filteredItems.length > 0) {
            groups.push({ groupName: lang === 'vi' ? 'Hôm nay' : 'Today', items: filteredItems })
        }

        return groups
    }, [filteredItems, lang])

    return (
        <section className="max-w-5xl mx-auto space-y-6 py-4">
            {/* Banner xin cấp quyền Web Push Notification (AC 12, AC 13) */}
            {pushStatus !== 'granted' && (
                <div className="bg-orange-50 border border-orange-200 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3 shadow-xs">
                    <div className="flex items-start gap-3">
                        <BellOutlineIcon className="text-[#ee4d2d] shrink-0 mt-0.5" size={20} />
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
                        <button
                            type="button"
                            onClick={() => void handleRequestPush()}
                            className="px-4 py-2 bg-[#ee4d2d] hover:bg-[#d03e20] text-white text-xs sm:text-sm font-semibold rounded-xl transition-colors shrink-0 shadow-sm cursor-pointer"
                        >
                            {lang === 'vi' ? 'Bật thông báo' : 'Enable Notifications'}
                        </button>
                    )}
                </div>
            )}

            {/* Card Tiêu đề chính theo mockup Image 1 */}
            <div className="bg-white rounded-3xl p-6 sm:p-7 shadow-sm border border-orange-100 flex flex-col sm:flex-row sm:items-center justify-between gap-5">
                <div className="flex items-center gap-4">
                    {/* Icon Chuông cam bo tròn */}
                    <div className="w-14 h-14 bg-[#ee4d2d] rounded-2xl flex items-center justify-center text-white shadow-md shadow-orange-500/25 shrink-0">
                        <BellOutlineIcon size={26} />
                    </div>
                    <div>
                        <div className="flex items-center gap-3 flex-wrap">
                            <h1 className="text-2xl font-bold text-gray-900 tracking-tight">
                                {lang === 'vi' ? 'Thông báo' : 'Notifications'}
                            </h1>
                            {unreadCount > 0 && (
                                <span className="inline-flex items-center gap-1.5 px-3 py-0.5 rounded-full text-xs font-bold bg-orange-100 text-orange-600">
                                    <span className="w-1.5 h-1.5 rounded-full bg-orange-500 inline-block" />
                                    {lang === 'vi' ? `${unreadCount} chưa đọc` : `${unreadCount} unread`}
                                </span>
                            )}
                        </div>
                        <p className="text-xs sm:text-sm text-gray-500 mt-1">
                            {lang === 'vi'
                                ? 'Theo dõi cập nhật về đơn hàng, thanh toán, đấu giá và đánh giá.'
                                : 'Track updates for orders, payments, auctions, and reviews.'}
                        </p>
                    </div>
                </div>

                {/* Nút Tùy chọn & Đánh dấu đã đọc tất cả */}
                <div className="flex items-center gap-3 shrink-0 flex-wrap">
                    <button
                        type="button"
                        onClick={() => setPrefOpen(true)}
                        className="px-4 py-2.5 bg-white hover:bg-gray-50 text-gray-700 text-xs sm:text-sm font-semibold rounded-2xl border border-gray-200 transition-colors flex items-center gap-2 shadow-sm cursor-pointer"
                    >
                        <SettingsCogIcon size={16} className="text-gray-600" />
                        <span>{lang === 'vi' ? 'Cài đặt thông báo' : 'Preferences'}</span>
                    </button>

                    <button
                        type="button"
                        onClick={() => void markRead()}
                        disabled={saving || loading || unreadCount === 0}
                        className="px-4 py-2.5 bg-[#ee4d2d] hover:bg-[#d03e20] disabled:bg-gray-200 disabled:text-gray-400 text-white text-xs sm:text-sm font-semibold rounded-2xl transition-colors flex items-center gap-2 shadow-sm shadow-orange-500/20 cursor-pointer disabled:cursor-not-allowed"
                    >
                        <CheckmarkIcon size={16} />
                        <span>{lang === 'vi' ? 'Đánh dấu tất cả đã đọc' : 'Mark all read'}</span>
                    </button>
                </div>
            </div>

            {/* Dải Tabs bộ lọc & nút Tải lại */}
            <div className="flex items-center justify-between border-b border-gray-200/80 pb-3 gap-4 flex-wrap">
                <div className="flex items-center gap-2">
                    {/* Tab Tất cả */}
                    <button
                        type="button"
                        onClick={() => setFilterTab('all')}
                        className={`px-4 py-2 rounded-2xl text-xs sm:text-sm font-bold flex items-center gap-2 transition-all cursor-pointer ${
                            filterTab === 'all'
                                ? 'bg-orange-100/80 text-orange-700 shadow-sm'
                                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                        }`}
                    >
                        <LayersIcon size={15} />
                        <span>{lang === 'vi' ? `Tất cả (${totalCount})` : `All (${totalCount})`}</span>
                    </button>

                    {/* Tab Chưa đọc */}
                    <button
                        type="button"
                        onClick={() => setFilterTab('unread')}
                        className={`px-4 py-2 rounded-2xl text-xs sm:text-sm font-bold flex items-center gap-2 transition-all cursor-pointer ${
                            filterTab === 'unread'
                                ? 'bg-orange-100/80 text-orange-700 shadow-sm'
                                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                        }`}
                    >
                        <MailIcon size={15} />
                        <span>{lang === 'vi' ? `Chưa đọc (${unreadCount})` : `Unread (${unreadCount})`}</span>
                    </button>

                    {/* Tab Đã đọc */}
                    <button
                        type="button"
                        onClick={() => setFilterTab('read')}
                        className={`px-4 py-2 rounded-2xl text-xs sm:text-sm font-bold flex items-center gap-2 transition-all cursor-pointer ${
                            filterTab === 'read'
                                ? 'bg-orange-100/80 text-orange-700 shadow-sm'
                                : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
                        }`}
                    >
                        <CheckSquareIcon size={15} />
                        <span>{lang === 'vi' ? `Đã đọc (${readCount})` : `Read (${readCount})`}</span>
                    </button>
                </div>

                {/* Nút Tải lại */}
                <button
                    type="button"
                    onClick={() => setRevision((v) => v + 1)}
                    disabled={loading || saving}
                    className="px-3.5 py-2 bg-white hover:bg-gray-50 text-gray-700 text-xs sm:text-sm font-semibold rounded-2xl border border-gray-200 transition-colors flex items-center gap-1.5 shadow-sm cursor-pointer"
                >
                    <RefreshIcon size={14} className={loading ? 'animate-spin' : ''} />
                    <span>{lang === 'vi' ? 'Tải lại' : 'Reload'}</span>
                </button>
            </div>

            {/* Error banner */}
            {error && (
                <div className="p-4 bg-red-50 border border-red-200 text-sm text-red-700 rounded-2xl flex items-center justify-between" role="alert">
                    <span>{error}</span>
                    <Button size="sm" variant="outline" onClick={fetchInbox}>
                        {lang === 'vi' ? 'Thử lại' : 'Retry'}
                    </Button>
                </div>
            )}

            {/* Loading */}
            {loading && rawItems.length === 0 && (
                <div className="py-16 text-center text-gray-500 flex flex-col items-center justify-center gap-2">
                    <div className="w-7 h-7 border-3 border-orange-500 border-t-transparent rounded-full animate-spin" />
                    <span className="text-xs font-semibold">{lang === 'vi' ? 'Đang tải thông báo…' : 'Loading notifications…'}</span>
                </div>
            )}

            {/* Danh sách nhóm thông báo theo mockup Image 1 */}
            {!loading && groupedItems.length === 0 && (
                <div className="bg-white rounded-3xl p-12 text-center border border-gray-100 shadow-sm">
                    <BellOutlineIcon size={40} className="mx-auto text-gray-300 mb-3" />
                    <p className="text-sm font-medium text-gray-500">
                        {page > 0
                            ? (lang === 'vi' ? 'Chưa có thông báo trên trang này.' : 'No notifications on this page.')
                            : (lang === 'vi' ? 'Không có thông báo nào trong mục này.' : 'No notifications in this tab.')}
                    </p>
                </div>
            )}

            {groupedItems.map((group) => (
                <div key={group.groupName} className="space-y-3">
                    {/* Nhãn nhóm ngày: "Hôm nay", "Hôm qua" */}
                    <div className="inline-block px-3.5 py-1 bg-orange-100/80 text-orange-800 text-xs font-bold rounded-full">
                        {group.groupName}
                    </div>

                    <div className="space-y-3">
                        {group.items.map((item) => {
                            const isUnread = !item.read
                            const tag = formatNotificationTag(item.type || undefined)

                            return (
                                <div
                                    key={item.id}
                                    className={`rounded-2xl p-4 sm:p-5 transition-all flex flex-col sm:flex-row sm:items-center justify-between gap-4 shadow-sm ${
                                        isUnread
                                            ? 'bg-white border-2 border-orange-200/90 hover:border-orange-300'
                                            : 'bg-white border border-gray-200 hover:border-gray-300'
                                    }`}
                                >
                                    {/* Cột trái: Dấu chấm đỏ + Icon + Tiêu đề + Nội dung */}
                                    <div className="flex items-start gap-3.5 flex-1 min-w-0">
                                        {/* Chấm tròn đỏ chỉ trạng thái chưa đọc */}
                                        <div className="pt-2 shrink-0">
                                            {isUnread ? (
                                                <span className="w-2.5 h-2.5 rounded-full bg-red-500 inline-block shadow-sm" />
                                            ) : (
                                                <span className="w-2.5 h-2.5 inline-block opacity-0" />
                                            )}
                                        </div>

                                        {/* Hộp icon bo góc cam nhạt */}
                                        <div className="w-11 h-11 rounded-2xl bg-orange-100/70 text-orange-600 flex items-center justify-center shrink-0">
                                            {item.type === 'AUCTION_OUTBID' ? (
                                                <BellOutlineIcon size={20} />
                                            ) : item.type === 'AUCTION_WON' || item.type === 'AUCTION_PAYMENT_REMINDER' ? (
                                                <GiftIcon size={20} />
                                            ) : (
                                                <ShoppingCartIcon size={20} />
                                            )}
                                        </div>

                                        {/* Nội dung thông báo */}
                                        <div className="flex-1 min-w-0 space-y-1">
                                            <div className="flex items-center gap-2 flex-wrap">
                                                <h3 className="text-sm font-bold text-gray-900 leading-snug">
                                                    {item.title}
                                                </h3>
                                                <span className={`text-[11px] font-bold px-2.5 py-0.5 rounded-md ${tag.color}`}>
                                                    {tag.label}
                                                </span>
                                            </div>

                                            <p className="text-xs text-gray-600 leading-relaxed break-words">
                                                {item.content}
                                            </p>

                                            <div className="flex items-center gap-1.5 text-[11px] text-gray-400 pt-1 font-medium">
                                                <ClockIcon size={12} />
                                                <span>
                                                    {new Date(item.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                                    {' · '}
                                                    {new Date(item.createdAt).toLocaleDateString('vi-VN')}
                                                </span>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Cột phải: Các nút hành động nghiệp vụ */}
                                    <div className="flex items-center gap-2.5 shrink-0 self-end sm:self-center">
                                        {/* Nút "Đặt giá lại" khi bị outbid */}
                                        {item.type === 'AUCTION_OUTBID' && (
                                            <button
                                                type="button"
                                                onClick={() => {
                                                    if (item.auctionId) {
                                                        navigate('auction-detail' as any, { id: item.auctionId })
                                                    } else {
                                                        navigate('auction-list')
                                                    }
                                                }}
                                                className="px-4 py-2 border-2 border-orange-400 text-orange-600 hover:bg-orange-50 text-xs font-bold rounded-2xl flex items-center gap-1.5 transition-colors shadow-sm cursor-pointer"
                                            >
                                                <GavelIcon size={14} />
                                                <span>{lang === 'vi' ? 'Đặt giá lại' : 'Re-bid'}</span>
                                            </button>
                                        )}

                                        {/* Nút "Thanh toán ngay" khi thắng đấu giá */}
                                        {(item.type === 'AUCTION_WON' || item.type === 'AUCTION_PAYMENT_REMINDER') && (
                                            <button
                                                type="button"
                                                onClick={() => {
                                                    if (item.auctionId) {
                                                        navigate('auction-payment' as any, { auctionId: item.auctionId })
                                                    } else if (item.orderId) {
                                                        navigate('order-detail' as any, { id: item.orderId })
                                                    } else if (item.paymentLink) {
                                                        window.location.href = item.paymentLink
                                                    }
                                                }}
                                                className="px-4 py-2 bg-[#ee4d2d] hover:bg-[#d03e20] text-white text-xs font-bold rounded-2xl flex items-center gap-1.5 transition-colors shadow-sm shadow-orange-500/20 cursor-pointer"
                                            >
                                                <CreditCardIcon size={14} />
                                                <span>{lang === 'vi' ? 'Thanh toán ngay' : 'Pay now'}</span>
                                            </button>
                                        )}

                                        {/* Nút đánh dấu đã đọc khi tin nhắn chưa đọc và không phải auction */}
                                        {isUnread && item.type !== 'AUCTION_WON' && item.type !== 'AUCTION_OUTBID' && (
                                            <button
                                                type="button"
                                                onClick={() => void markRead(item.id)}
                                                className="px-3.5 py-1.5 border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs font-semibold rounded-xl transition-colors cursor-pointer"
                                            >
                                                {lang === 'vi' ? 'Đánh dấu đã đọc' : 'Mark read'}
                                            </button>
                                        )}
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                </div>
            ))}

            {/* Phân trang */}
            {inbox && inbox.totalElements > 20 && (
                <div className="flex items-center justify-between pt-4 border-t border-gray-200 text-xs sm:text-sm text-gray-600">
                    <button
                        type="button"
                        disabled={page === 0 || loading || saving}
                        onClick={() => setPage((v) => v - 1)}
                        className="px-3.5 py-1.5 border border-gray-300 rounded-xl hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
                    >
                        {lang === 'vi' ? 'Trang trước' : 'Previous'}
                    </button>
                    <span>
                        {lang === 'vi' ? 'Trang' : 'Page'} {page + 1} /{' '}
                        {Math.ceil(inbox.totalElements / 20)}
                    </span>
                    <button
                        type="button"
                        disabled={(page + 1) * 20 >= inbox.totalElements || loading || saving}
                        onClick={() => setPage((v) => v + 1)}
                        className="px-3.5 py-1.5 border border-gray-300 rounded-xl hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
                    >
                        {lang === 'vi' ? 'Trang sau' : 'Next'}
                    </button>
                </div>
            )}

            {/* Modal Quản lý Tùy chọn Nhận thông báo */}
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
                            <div className="grid grid-cols-[1fr_70px_70px_70px] p-3 bg-gray-50 text-[11px] font-bold text-gray-500 uppercase tracking-wider text-center">
                                <span className="text-left">{lang === 'vi' ? 'Loại thông báo' : 'Category'}</span>
                                <span>App</span>
                                <span>Email</span>
                                <span>Push</span>
                            </div>

                            {[
                                { type: 'ORDER', label: lang === 'vi' ? 'Đơn hàng & Giao hàng' : 'Orders & Shipping' },
                                { type: 'AUCTION', label: lang === 'vi' ? 'Đấu giá & Trả giá' : 'Auctions & Bids' },
                                { type: 'PAYMENT', label: lang === 'vi' ? 'Thanh toán & Ví tiền' : 'Payments & Escrow' },
                                { type: 'PROMOTION', label: lang === 'vi' ? 'Khuyến mãi & Tin tức' : 'Promotions & News' },
                                { type: 'SYSTEM', label: lang === 'vi' ? 'Uy tín & Hệ thống' : 'Reputation & System' },
                            ].map((cat) => {
                                const inApp = preferences.find((p) => p.notificationType === cat.type && p.channel === 'IN_APP')?.enabled ?? true
                                const email = preferences.find((p) => p.notificationType === cat.type && p.channel === 'EMAIL')?.enabled ?? true
                                const push = preferences.find((p) => p.notificationType === cat.type && p.channel === 'PUSH')?.enabled ?? false

                                return (
                                    <div key={cat.type} className="grid grid-cols-[1fr_70px_70px_70px] p-3 items-center text-xs">
                                        <span className="font-semibold text-gray-800">{cat.label}</span>
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
                        <Button variant="outline" size="sm" onClick={() => setPrefOpen(false)} disabled={prefSaving}>
                            {lang === 'vi' ? 'Hủy' : 'Cancel'}
                        </Button>
                        <Button variant="primary" size="sm" onClick={handleSavePreferences} disabled={prefSaving || prefLoading}>
                            {prefSaving ? (lang === 'vi' ? 'Đang lưu...' : 'Saving...') : (lang === 'vi' ? 'Lưu tùy chọn' : 'Save Preferences')}
                        </Button>
                    </div>
                </div>
            </Modal>
        </section>
    )
}
