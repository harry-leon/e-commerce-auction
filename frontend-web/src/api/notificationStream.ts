import { api, ApiClientError } from './client'

export const NOTIFICATIONS_CHANGED = 'nexus:notifications-changed'

/**
 * Cấu trúc thông báo đẩy PUSH qua WebSocket STOMP.
 * Bổ sung các trường ngữ cảnh đấu giá: type (AUCTION_WON, AUCTION_OUTBID,...),
 * orderId, auctionId, paymentLink, paymentDeadline để giao diện hiển thị nút thao tác.
 */
export interface NotificationPush {
    id: string
    title: string
    content: string
    type?: string
    orderId?: string
    auctionId?: string
    paymentLink?: string | null
    paymentDeadline?: string | null
    occurredAt?: string
}

/**
 * Nhận STOMP push qua Gateway cùng origin; cookie HttpOnly xác thực handshake, JS không đọc token.
 * Chỉ subscribe kênh /user do server resolve từ JWT. Reconnect dùng HTTP client để refresh cookie nếu cần,
 * backoff tối đa 30 giây; heartbeat phát hiện socket mất mạng. Cleanup hủy HTTP, timer và socket cũ.
 * Push là tín hiệu cập nhật: hộp thư vẫn tải dữ liệu bền vững từ REST khi kết nối/reconnect.
 */
export function connectNotificationStream(
    onMessage: (event: NotificationPush) => void,
    onConnected: () => void,
): () => void {
    let stopped = false
    let socket: WebSocket | undefined
    let retry: ReturnType<typeof setTimeout> | undefined
    let heartbeat: ReturnType<typeof setInterval> | undefined
    let handshake: ReturnType<typeof setTimeout> | undefined
    let attempt = 0
    const controller = new AbortController()
    const seen = new Set<string>()

    /** Một reconnect đang chờ tại mỗi thời điểm; logout/unmount không khởi động lại socket. */
    function scheduleRetry(longer = false) {
        if (stopped || retry) return
        // Khi lỗi liên tiếp hoặc backend offline, giãn thời gian thử lại tối đa 60s để tránh làm nghẽn console và hao tổn CPU
        const delay =
            longer || attempt > 3 ? 60_000 : Math.min(1000 * 2 ** Math.min(attempt++, 5), 30_000)
        retry = setTimeout(() => {
            retry = undefined
            void connect()
        }, delay)
    }

    /** Dọn timer của một transport trước khi tạo transport tiếp theo hoặc khi component unmount. */
    function clearTransportTimers() {
        clearInterval(heartbeat)
        clearTimeout(handshake)
    }

    async function connect() {
        try {
            // Request xác thực nhẹ dùng chung cơ chế refresh một lần của API client; không gửi JWT qua URL/STOMP.
            await api('/notifications?page=0&size=1', { signal: controller.signal })
            if (stopped) return
            const url = new URL('/ws/notifications', window.location.href)
            url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
            const current = new WebSocket(url.toString())
            socket = current
            let buffer = ''
            let lastReceived = Date.now()
            handshake = setTimeout(() => current.close(), 10_000)
            current.onopen = () => {
                if (stopped) return current.close()
                current.send(
                    'CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:10000,10000\n\n\0',
                )
            }
            current.onmessage = ({ data }) => {
                if (stopped || current !== socket) return
                lastReceived = Date.now()
                if (typeof data !== 'string') return current.close(1002)
                buffer = (buffer + data).replace(/^[\r\n]+/, '')
                if (buffer.length > 64 * 1024) return current.close(1009)
                // JSON không chứa NUL thô; ghép frame chia nhỏ và xử lý nhiều frame trong một WS message.
                while (buffer.includes('\0')) {
                    const end = buffer.indexOf('\0')
                    const frame = buffer.slice(0, end).replace(/\r\n/g, '\n')
                    buffer = buffer.slice(end + 1).replace(/^[\r\n]+/, '')
                    const split = frame.indexOf('\n\n')
                    if (split < 0) continue
                    const lines = frame.slice(0, split).split('\n')
                    const command = lines.shift()
                    if (command === 'CONNECTED') {
                        attempt = 0
                        clearTransportTimers()
                        current.send(
                            'SUBSCRIBE\nid:notifications\ndestination:/user/queue/notifications\nack:auto\n\n\0',
                        )
                        heartbeat = setInterval(() => {
                            if (current.readyState !== WebSocket.OPEN) return
                            if (Date.now() - lastReceived > 25_000) current.close()
                            else current.send('\n')
                        }, 10_000)
                        onConnected()
                    } else if (command === 'MESSAGE') {
                        if (!lines.includes('subscription:notifications')) continue
                        try {
                            const event: unknown = JSON.parse(frame.slice(split + 2))
                            if (typeof event !== 'object' || event === null) continue
                            const value = event as Partial<NotificationPush>
                            if (
                                typeof value.id !== 'string' ||
                                typeof value.title !== 'string' ||
                                typeof value.content !== 'string' ||
                                seen.has(value.id)
                            )
                                continue
                            seen.add(value.id)
                            if (seen.size > 100) seen.delete(seen.values().next().value!)
                            onMessage(value as NotificationPush)
                        } catch {
                            // Payload lỗi không được biến thành toast thành công hoặc dữ liệu hộp thư giả.
                        }
                    } else if (command === 'ERROR') {
                        current.close()
                    }
                }
            }
            current.onerror = () => current.close()
            current.onclose = () => {
                if (current !== socket) return
                clearTransportTimers()
                scheduleRetry()
            }
        } catch (error) {
            // Hết phiên/thiếu quyền dừng retry; lỗi hạ tầng giữ HTTP inbox và thử lại với backoff.
            if (error instanceof ApiClientError && [401, 403].includes(error.status)) return
            const isServerOrOffline =
                error instanceof ApiClientError &&
                ([500, 502, 503, 504].includes(error.status) || error.code === 'BACKEND_OFFLINE')
            scheduleRetry(isServerOrOffline)
        }
    }

    const onOnline = () => {
        attempt = 0
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            clearTimeout(retry)
            retry = undefined
            void connect()
        }
    }
    window.addEventListener('online', onOnline)

    void connect()
    return () => {
        stopped = true
        window.removeEventListener('online', onOnline)
        controller.abort()
        clearTimeout(retry)
        clearTransportTimers()
        socket?.close()
    }
}
