import { beforeEach, expect, it, vi } from 'vitest'
import { getNotifications, readNotification } from '../api/notifications'

beforeEach(() => {
    sessionStorage.clear()
    vi.unstubAllGlobals()
})

/** Client không gửi userId tùy ý, dùng đúng endpoint GET/read/read-all và chuyển tiếp AbortSignal. */
it('uses the authenticated inbox contract for listing and marking read', async () => {
    const inbox = { content: [], totalElements: 0, unreadCount: 0 }
    const fetcher = vi
        .fn()
        .mockImplementation(() =>
            Promise.resolve(
                new Response(JSON.stringify({ success: true, data: inbox }), { status: 200 }),
            ),
        )
    vi.stubGlobal('fetch', fetcher)
    const controller = new AbortController()
    expect(await getNotifications(2, controller.signal)).toEqual(inbox)
    expect(fetcher).toHaveBeenLastCalledWith(
        '/api/v1/notifications?page=2&size=20',
        expect.objectContaining({ signal: controller.signal }),
    )
    await readNotification('notice-1')
    expect(fetcher).toHaveBeenLastCalledWith(
        '/api/v1/notifications/notice-1/read',
        expect.objectContaining({ method: 'POST' }),
    )
    await readNotification()
    expect(fetcher).toHaveBeenLastCalledWith(
        '/api/v1/notifications/read-all',
        expect.objectContaining({ method: 'POST' }),
    )
})

/** Payload rỗng hoặc lỗi HTTP phải reject để UI báo lỗi, không tạo dữ liệu/thành công demo. */
it('rejects missing data and forbidden reads instead of falling back to mock', async () => {
    vi.stubGlobal(
        'fetch',
        vi
            .fn()
            .mockResolvedValue(
                new Response(JSON.stringify({ success: true, data: null }), { status: 200 }),
            ),
    )
    await expect(getNotifications(0)).rejects.toThrow('Không thể tải thông báo')
    vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: false,
                    error: { code: 'FORBIDDEN', message: 'Denied' },
                }),
                { status: 403 },
            ),
        ),
    )
    await expect(readNotification('other-user')).rejects.toThrow()
})
