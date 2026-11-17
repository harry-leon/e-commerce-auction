/**
 * Module kết nối WebSocket STOMP thời gian thực cho hệ thống Đấu giá (Auction Realtime Stream).
 *
 * Chức năng:
 * - Thay thế hoàn toàn cơ chế HTTP Polling 5s/lần (gây nghẽn mạng và sập server).
 * - Kết nối STOMP WebSocket qua Gateway route /ws/auctions.
 * - Nhận tín hiệu đẩy tức thì (< 50ms) từ Backend khi có lượt đặt giá mới (BID_PLACED),
 *   gia hạn phiên (AUCTION_EXTENDED), mở phiên (AUCTION_STARTED) hoặc kết thúc phiên (AUCTION_CLOSED).
 * - Tích hợp BroadcastChannel và CustomEvent để đồng bộ ngay lập tức giữa các tab trình duyệt.
 */

import { readSession } from './client'

export interface AuctionRealtimeEvent {
    type: 'BID_PLACED' | 'AUCTION_EXTENDED' | 'AUCTION_STARTED' | 'AUCTION_CLOSED' | 'PRICE_UPDATE'
    auctionId: string
    currentPrice?: number
    amount?: number
    bidCount?: number
    bidderId?: string
    bidderName?: string
    endTime?: string
    status?: string
    message?: string
    timestamp?: string
}

export const AUCTION_EVENT_NAME = 'nexus:auction-realtime-event'
const BROADCAST_CHANNEL_NAME = 'nexus:auction-stream-channel'

/**
 * Phát sự kiện đấu giá realtime tới các thành phần trong cùng tab và các tab khác.
 */
export function broadcastAuctionEvent(event: AuctionRealtimeEvent): void {
    if (typeof window === 'undefined') return

    // 1. Phát CustomEvent cho các component trong cùng window
    try {
        window.dispatchEvent(new CustomEvent(AUCTION_EVENT_NAME, { detail: event }))
    } catch {}

    // 2. Phát BroadcastChannel cho các tab trình duyệt khác cùng origin
    try {
        if ('BroadcastChannel' in window) {
            const bc = new BroadcastChannel(BROADCAST_CHANNEL_NAME)
            bc.postMessage(event)
            bc.close()
        }
    } catch {}
}

/**
 * Đăng ký lắng nghe sự kiện đấu giá thời gian thực.
 * Tự động kết nối WebSocket STOMP /ws/auctions qua API Gateway,
 * đồng thời lắng nghe sự kiện đồng bộ giữa các tab trình duyệt.
 *
 * @param onEvent Callback nhận bản tin sự kiện khi có giá mới hoặc phiên thay đổi
 * @param filterAuctionId (Tùy chọn) Lọc riêng cho một phiên đấu giá cụ thể (AuctionDetailPage)
 * @returns Hàm cleanup hủy kết nối và gỡ bỏ listener khi component unmount
 */
export function connectAuctionStream(
    onEvent: (event: AuctionRealtimeEvent) => void,
    filterAuctionId?: string,
): () => void {
    if (typeof window === 'undefined') {
        return () => {}
    }

    let stopped = false
    let socket: WebSocket | null = null
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let heartbeatTimer: ReturnType<typeof setInterval> | undefined
    let handshakeTimer: ReturnType<typeof setTimeout> | undefined
    let attempt = 0

    // 1. Lắng nghe CustomEvent cục bộ trong tab hiện tại
    const handleLocalEvent = (customEvt: Event) => {
        const detail = (customEvt as CustomEvent<AuctionRealtimeEvent>).detail
        if (!detail || !detail.auctionId) return
        if (filterAuctionId && detail.auctionId !== filterAuctionId) return
        onEvent(detail)
    }
    window.addEventListener(AUCTION_EVENT_NAME, handleLocalEvent)

    // 2. Lắng nghe BroadcastChannel từ các tab khác
    let bc: BroadcastChannel | null = null
    if ('BroadcastChannel' in window) {
        try {
            bc = new BroadcastChannel(BROADCAST_CHANNEL_NAME)
            bc.onmessage = (msgEvt) => {
                const data = msgEvt.data as AuctionRealtimeEvent
                if (!data || !data.auctionId) return
                if (filterAuctionId && data.auctionId !== filterAuctionId) return
                onEvent(data)
            }
        } catch {}
    }

    // 3. Cơ chế kết nối STOMP WebSocket tới Backend qua Gateway
    function scheduleRetry() {
        if (stopped || retryTimer) return
        // Giãn thời gian thử lại lũy thừa (1s -> 2s -> 4s -> max 20s)
        const delay = Math.min(1000 * 2 ** Math.min(attempt++, 4), 20_000)
        retryTimer = setTimeout(() => {
            retryTimer = undefined
            void connect()
        }, delay)
    }

    function clearTimers() {
        if (heartbeatTimer) clearInterval(heartbeatTimer)
        if (handshakeTimer) clearTimeout(handshakeTimer)
    }

    async function connect() {
        if (stopped) return

        try {
            // Xác định URL WebSocket dựa theo Gateway
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
            const host = window.location.host
            const wsUrl = `${protocol}//${host}/ws/auctions`

            const current = new WebSocket(wsUrl)
            socket = current
            let buffer = ''
            let lastReceived = Date.now()

            handshakeTimer = setTimeout(() => {
                if (current.readyState !== WebSocket.OPEN) {
                    try {
                        current.close()
                    } catch {}
                }
            }, 8_000)

            current.onopen = () => {
                if (stopped) {
                    current.close()
                    return
                }

                // Gửi STOMP CONNECT frame
                const session = readSession() as any
                const headers: string[] = [
                    'CONNECT',
                    'accept-version:1.2',
                    'host:localhost',
                    'heart-beat:10000,10000',
                ]
                const token = session?.token || session?.accessToken
                if (token) {
                    headers.push(`Authorization:Bearer ${token}`)
                }
                current.send(`${headers.join('\n')}\n\n\0`)
            }

            current.onmessage = ({ data }) => {
                if (stopped || current !== socket) return
                lastReceived = Date.now()

                if (typeof data !== 'string') return
                buffer = (buffer + data).replace(/^[\r\n]+/, '')

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
                        clearTimers()

                        // Đăng ký nhận bản tin từ Topic
                        // Nếu có filterAuctionId: subscribe kênh chi tiết /topic/auctions/{id}
                        // Nếu không: subscribe kênh tổng sàn /topic/auctions
                        const destination = filterAuctionId
                            ? `/topic/auctions/${filterAuctionId}`
                            : '/topic/auctions'

                        current.send(
                            `SUBSCRIBE\nid:sub-auction-${Date.now()}\ndestination:${destination}\nack:auto\n\n\0`,
                        )

                        // Duy trì Heartbeat mỗi 10 giây
                        heartbeatTimer = setInterval(() => {
                            if (current.readyState !== WebSocket.OPEN) return
                            if (Date.now() - lastReceived > 25_000) {
                                current.close()
                            } else {
                                current.send('\n')
                            }
                        }, 10_000)
                    } else if (command === 'MESSAGE') {
                        try {
                            const bodyText = frame.slice(split + 2).trim()
                            if (!bodyText) continue
                            const payload = JSON.parse(bodyText)

                            // Chuẩn hóa bản tin nhận được từ backend
                            const eventObj: AuctionRealtimeEvent = {
                                type:
                                    payload.type ||
                                    (payload.amount ? 'BID_PLACED' : 'PRICE_UPDATE'),
                                auctionId: payload.auctionId || payload.id || '',
                                currentPrice:
                                    payload.currentPrice ?? payload.amount ?? payload.price,
                                amount: payload.amount ?? payload.currentPrice,
                                bidCount: payload.bidCount,
                                bidderId: payload.bidderId,
                                bidderName: payload.bidderName,
                                endTime: payload.endTime || payload.newEndTime,
                                status: payload.status,
                                message: payload.message,
                                timestamp:
                                    payload.timestamp ||
                                    payload.placedAt ||
                                    new Date().toISOString(),
                            }

                            if (eventObj.auctionId) {
                                if (!filterAuctionId || eventObj.auctionId === filterAuctionId) {
                                    onEvent(eventObj)
                                }
                            }
                        } catch {
                            // Bỏ qua bản tin lỗi định dạng
                        }
                    } else if (command === 'ERROR') {
                        current.close()
                    }
                }
            }

            current.onerror = () => {
                try {
                    current.close()
                } catch {}
            }

            current.onclose = () => {
                if (current !== socket) return
                clearTimers()
                scheduleRetry()
            }
        } catch {
            scheduleRetry()
        }
    }

    // Kích hoạt kết nối WebSocket
    void connect()

    // Trả về hàm dọn dẹp khi unmount
    return () => {
        stopped = true
        window.removeEventListener(AUCTION_EVENT_NAME, handleLocalEvent)
        if (bc) {
            try {
                bc.close()
            } catch {}
        }
        if (retryTimer) clearTimeout(retryTimer)
        clearTimers()
        if (socket) {
            try {
                socket.close()
            } catch {}
            socket = null
        }
    }
}
