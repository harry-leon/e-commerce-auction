import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api, ApiClientError } from './client'
import { connectNotificationStream } from './notificationStream'

vi.mock('./client', async (original) => ({
    ...(await original<typeof import('./client')>()),
    api: vi.fn(),
}))

/** Transport fixture giữ frame đã gửi; các test bảo vệ cookie flow, reconnect và cleanup phiên cũ. */
class Socket {
    static OPEN = 1
    static instances: Socket[] = []
    readyState = 1
    onopen?: () => void
    onmessage?: (event: { data: string }) => void
    onclose?: () => void
    onerror?: () => void
    send = vi.fn()
    close = vi.fn(() => {
        this.readyState = 3
        this.onclose?.()
    })
    constructor(public url: string) {
        Socket.instances.push(this)
    }
    receive(data: string) {
        this.onmessage?.({ data })
    }
}
let cleanup: (() => void) | undefined
beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    vi.mocked(api).mockResolvedValue({})
    Socket.instances = []
    vi.stubGlobal('WebSocket', Socket)
})
afterEach(() => {
    cleanup?.()
    cleanup = undefined
    vi.unstubAllGlobals()
    vi.useRealTimers()
})

/** CONNECT không có token; server resolve kênh /user từ cookie JWT đã xác thực tại handshake. */
it('connects on the same origin and subscribes to the authenticated personal queue', async () => {
    const connected = vi.fn()
    cleanup = connectNotificationStream(vi.fn(), connected)
    await vi.advanceTimersByTimeAsync(0)
    const socket = Socket.instances[0]
    expect(socket.url).toMatch(/^ws:\/\/.*\/ws\/notifications$/)
    socket.onopen?.()
    expect(socket.send.mock.calls[0][0]).not.toMatch(/Authorization|token/i)
    socket.receive('CONNECTED\nversion:1.2\n\n\0')
    expect(socket.send).toHaveBeenLastCalledWith(
        expect.stringContaining('destination:/user/queue/notifications'),
    )
    expect(connected).toHaveBeenCalledOnce()
})

/** Frame JSON chia nhỏ/ghép chung chỉ phát đúng một toast; payload hỏng và subscription khác bị bỏ qua. */
it('reassembles frames, ignores malformed data and deduplicates pushes', async () => {
    const message = vi.fn()
    cleanup = connectNotificationStream(message, vi.fn())
    await vi.advanceTimersByTimeAsync(0)
    const socket = Socket.instances[0]
    const body = JSON.stringify({ id: 'event-1', title: 'Thắng đấu giá', content: 'Đơn hàng mới' })
    const frame = 'MESSAGE\nsubscription:notifications\n\n' + body + '\0'
    socket.receive(frame.slice(0, 12))
    socket.receive(frame.slice(12) + frame + 'MESSAGE\nsubscription:notifications\n\nbad-json\0')
    socket.receive('MESSAGE\nsubscription:other\n\n' + body + '\0')
    expect(message).toHaveBeenCalledOnce()
    expect(message).toHaveBeenCalledWith(JSON.parse(body))
})

/** Socket hết hạn phải kiểm tra lại cookie qua API trước reconnect, giữ backoff khi hạ tầng chưa phục hồi. */
it('revalidates the HTTP session before reconnecting after transport close', async () => {
    cleanup = connectNotificationStream(vi.fn(), vi.fn())
    await vi.advanceTimersByTimeAsync(0)
    Socket.instances[0].close()
    await vi.advanceTimersByTimeAsync(999)
    expect(Socket.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(api).toHaveBeenCalledTimes(2)
    expect(Socket.instances).toHaveLength(2)
})

/** Logout hủy request trước CONNECT và bỏ response đến trễ, không tạo socket của tài khoản cũ. */
it('aborts pending authentication and does not reconnect after cleanup', async () => {
    let resolve: (value: unknown) => void = () => undefined
    vi.mocked(api).mockReturnValue(
        new Promise((done) => {
            resolve = done
        }),
    )
    cleanup = connectNotificationStream(vi.fn(), vi.fn())
    const signal = vi.mocked(api).mock.calls[0][1]?.signal
    cleanup()
    resolve({})
    await vi.advanceTimersByTimeAsync(60_000)
    expect(signal?.aborted).toBe(true)
    expect(Socket.instances).toHaveLength(0)
})

/** Server xác nhận hết phiên/thiếu quyền thì dừng vòng retry; lỗi mạng vẫn cho phép phục hồi. */
it('stops reconnect attempts when the HTTP session is unauthorized', async () => {
    vi.mocked(api).mockRejectedValue(new ApiClientError('Hết phiên', 'AUTH_UNAUTHORIZED', 401))
    cleanup = connectNotificationStream(vi.fn(), vi.fn())
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api).toHaveBeenCalledOnce()
    expect(Socket.instances).toHaveLength(0)
})

/** Heartbeat giữ proxy hoạt động, socket im lặng quá ngưỡng bị đóng để khôi phục kết nối. */
it('sends heartbeats and reconnects when the server stops responding', async () => {
    cleanup = connectNotificationStream(vi.fn(), vi.fn())
    await vi.advanceTimersByTimeAsync(0)
    const socket = Socket.instances[0]
    socket.receive('CONNECTED\nversion:1.2\n\n\0')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(socket.send).toHaveBeenLastCalledWith('\n')
    await vi.advanceTimersByTimeAsync(20_000)
    expect(socket.close).toHaveBeenCalled()
})
