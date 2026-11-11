import { useEffect, useState } from 'react'
import { BuyerLayout } from '../../components/Layout'
import { useApp } from '../../context'
import { getNotifications, readNotification, type Inbox } from '../../api/notifications'
import { errorMessage } from '../../api/client'

/** Hộp thư IN_APP thật; hủy request cũ khi đổi user/trang, giữ lỗi rõ ràng và không thay bằng dữ liệu demo. */
export function NotificationInbox() {
    const { user, navigate } = useApp()
    // Key theo tài khoản buộc bỏ state/request cũ; thông báo tài khoản trước không xuất hiện sau đổi phiên.
    return (
        <BuyerLayout>
            <InboxContent key={user?.id || 'guest'} userId={user?.id} navigate={navigate} />
        </BuyerLayout>
    )
}

/** Đọc trang 20 tin và refresh thủ công; chỉ hỗ trợ IN_APP cho ba sự kiện Sprint 1, chưa có email/push/preferences. */
function InboxContent({
    userId,
    navigate,
}: {
    userId?: string
    navigate: ReturnType<typeof useApp>['navigate']
}) {
    const [page, setPage] = useState(0)
    const [revision, setRevision] = useState(0)
    const [inbox, setInbox] = useState<Inbox | null>(null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const [saving, setSaving] = useState(false)

    useEffect(() => {
        if (!userId) return
        const controller = new AbortController()
        setLoading(true)
        setInbox(null)
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

    // Ghi READ tại server rồi tải lại tổng unread; lỗi giữ nguyên dữ liệu để người dùng có thể thử lại.
    async function markRead(id?: string) {
        setSaving(true)
        setError('')
        try {
            await readNotification(id)
            setRevision((value) => value + 1)
        } catch (reason) {
            setError(errorMessage(reason))
        } finally {
            setSaving(false)
        }
    }

    return (
        <section className="max-w-3xl mx-auto nexus-card p-5">
            <h1 className="text-2xl font-bold mb-3">Thông báo đơn hàng và thanh toán</h1>
            <p className="text-sm text-gray-500 mb-4">
                Thông báo trong ứng dụng (IN_APP). Email và push chưa được hỗ trợ.
            </p>
            {!userId ? (
                <button onClick={() => navigate('login')}>Đăng nhập để xem thông báo</button>
            ) : (
                <>
                    <div className="flex gap-4 mb-4">
                        <button
                            disabled={loading || saving}
                            onClick={() => setRevision((value) => value + 1)}
                        >
                            Tải lại
                        </button>
                        <button
                            disabled={loading || saving || !inbox?.unreadCount}
                            onClick={() => void markRead()}
                        >
                            Đánh dấu tất cả đã đọc
                        </button>
                    </div>
                    {error && <p role="alert">{error}</p>}
                    {loading && (
                        <div className="flex items-center gap-2 py-4 text-sm text-gray-500" role="status">
                            <div
                                className="spinner-border spinner-border-sm text-primary"
                                style={{ color: '#ee4d2d' }}
                                role="status"
                            >
                                <span className="visually-hidden">Loading...</span>
                            </div>
                            <span>Đang tải thông báo…</span>
                        </div>
                    )}
                    {inbox && (
                        <>
                            <p className="text-sm mb-3">
                                {inbox.unreadCount} chưa đọc · {inbox.totalElements} thông báo
                            </p>
                            {inbox.content.length === 0 && <p>Chưa có thông báo trên trang này.</p>}
                            <ul>
                                {(inbox?.content || []).map((item) => (
                                    <li key={item.id} className="border-b py-4">
                                        <h2 className={item.read ? '' : 'font-bold'}>
                                            {item.title}
                                        </h2>
                                        <p>{item.content}</p>
                                        <time dateTime={item.createdAt}>
                                            {new Date(item.createdAt).toLocaleString()}
                                        </time>
                                        {!item.read && (
                                            <button
                                                className="ml-4"
                                                disabled={saving || loading}
                                                onClick={() => void markRead(item.id)}
                                            >
                                                Đánh dấu đã đọc
                                            </button>
                                        )}
                                    </li>
                                ))}
                            </ul>
                            <div className="flex gap-4 mt-4">
                                <button
                                    disabled={page === 0 || loading || saving}
                                    onClick={() => setPage((value) => value - 1)}
                                >
                                    Trang trước
                                </button>
                                <span>Trang {page + 1}</span>
                                <button
                                    disabled={
                                        (page + 1) * 20 >= inbox.totalElements || loading || saving
                                    }
                                    onClick={() => setPage((value) => value + 1)}
                                >
                                    Trang sau
                                </button>
                            </div>
                        </>
                    )}
                </>
            )}
        </section>
    )
}
