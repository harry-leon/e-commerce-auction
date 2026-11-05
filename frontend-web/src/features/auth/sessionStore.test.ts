import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useSessionStore } from './sessionStore'
import { api, mapUser, readSession, saveSession } from '../../api/client'

const profile = {
    id: 'buyer-cookie',
    email: 'buyer@example.test',
    fullName: 'Cookie Buyer',
    status: 'ACTIVE',
    roles: ['BUYER'],
    createdAt: '2026-11-05T00:00:00Z',
}
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })

beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    saveSession(null)
})
afterEach(() => vi.unstubAllGlobals())

/** Return từ VNPAY/tab mới có cookie nhưng thiếu cache profile: nạp lại từ /users/me và chỉ lưu user. */
it('restores the UI from authenticated cookies without a sessionStorage profile', async () => {
    const fetcher = vi.fn().mockResolvedValue(reply({ success: true, data: profile }))
    vi.stubGlobal('fetch', fetcher)
    await useSessionStore.getState().restoreSession()
    expect(useSessionStore.getState().user?.id).toBe(profile.id)
    expect(readSession()?.user.id).toBe(profile.id)
    expect(fetcher).toHaveBeenCalledWith(
        '/api/v1/users/me',
        expect.objectContaining({ credentials: 'include' }),
    )
    expect(localStorage.getItem('nexus.session')).toBeNull()
    expect(sessionStorage.getItem('nexus.session')).not.toContain('Token')
})

/** Access hết hạn sau thời gian ở cổng thanh toán: vẫn refresh được khi cache tab rỗng và đồng bộ header. */
it('refreshes from cookies even when the UI has no cached user', async () => {
    const fetcher = vi
        .fn()
        .mockResolvedValueOnce(reply({}, 401))
        .mockResolvedValueOnce(reply({ success: true, data: { user: profile } }))
        .mockResolvedValueOnce(reply({ success: true, data: profile }))
    vi.stubGlobal('fetch', fetcher)
    await useSessionStore.getState().restoreSession()
    expect(useSessionStore.getState().user?.id).toBe(profile.id)
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
        '/api/v1/users/me',
        '/api/v1/auth/refresh',
        '/api/v1/users/me',
    ])
})

/** Lỗi mạng/service lúc refresh không được coi là thao tác đăng xuất. */
it('preserves the current user on a transient refresh failure', async () => {
    saveSession({ user: mapUser(profile) })
    vi.stubGlobal(
        'fetch',
        vi
            .fn()
            .mockResolvedValueOnce(reply({}, 401))
            .mockRejectedValueOnce(new TypeError('Failed to fetch')),
    )
    await expect(api('/payments/payment-1')).rejects.toThrow('Failed to fetch')
    expect(readSession()?.user.id).toBe(profile.id)
    expect(useSessionStore.getState().user?.id).toBe(profile.id)
})

/** Chỉ refresh bị server từ chối xác thực mới xóa cả cache và user đang hiển thị. */
it('clears the UI and cached profile when the server confirms session expiration', async () => {
    saveSession({ user: mapUser(profile) })
    vi.stubGlobal(
        'fetch',
        vi.fn().mockImplementation(() => Promise.resolve(reply({}, 401))),
    )
    await expect(api('/payments/payment-1')).rejects.toThrow('401')
    expect(readSession()).toBeNull()
    expect(useSessionStore.getState().user).toBeNull()
})

/** Request khôi phục chậm không được phục hồi tài khoản cũ sau khi tài khoản khác đã đăng nhập. */
it('shares a pending restore and ignores its stale result after the user changes', async () => {
    let resolve!: (response: Response) => void
    const fetcher = vi.fn().mockReturnValue(
        new Promise<Response>((done) => {
            resolve = done
        }),
    )
    vi.stubGlobal('fetch', fetcher)
    const first = useSessionStore.getState().restoreSession()
    const second = useSessionStore.getState().restoreSession()
    expect(first).toBe(second)
    await useSessionStore.getState().setUser({ ...mapUser(profile), id: 'new-buyer' })
    resolve(reply({ success: true, data: profile }))
    await first
    expect(useSessionStore.getState().user?.id).toBe('new-buyer')
    expect(readSession()?.user.id).toBe('new-buyer')
    expect(fetcher).toHaveBeenCalledTimes(1)
})

/** Refresh bắt đầu trước lần login mới không được xóa user mới khi phản hồi hết phiên cũ đến muộn. */
it('does not clear a newer login when an older refresh is rejected', async () => {
    saveSession({ user: mapUser(profile) })
    let rejectRefresh!: (response: Response) => void
    let markStarted!: () => void
    const started = new Promise<void>((done) => {
        markStarted = done
    })
    vi.stubGlobal(
        'fetch',
        vi
            .fn()
            .mockResolvedValueOnce(reply({}, 401))
            .mockImplementationOnce(() => {
                markStarted()
                return new Promise<Response>((done) => {
                    rejectRefresh = done
                })
            }),
    )
    const request = api('/payments/payment-1')
    const rejected = expect(request).rejects.toThrow('401')
    await started
    await useSessionStore.getState().setUser({ ...mapUser(profile), id: 'new-login' })
    rejectRefresh(reply({}, 401))
    await rejected
    expect(readSession()?.user.id).toBe('new-login')
    expect(useSessionStore.getState().user?.id).toBe('new-login')
})
