import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import {
    api,
    mapUser,
    readSession,
    saveSession,
    readRememberedEmail,
    saveRememberedEmail,
    errorMessage,
    type ApiUser,
} from './client'

function storage() {
    const values = new Map<string, string>()
    return {
        getItem: (key: string) => values.get(key) || null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
    }
}
const user: ApiUser = {
    id: '1',
    email: 'a@example.com',
    fullName: 'A',
    status: 'ACTIVE',
    createdAt: '',
    roles: ['BUYER', 'ADMIN'],
}

beforeEach(() => {
    vi.stubGlobal('localStorage', storage())
    vi.stubGlobal('sessionStorage', storage())
})
afterEach(() => vi.unstubAllGlobals())

// Kiểm chứng token/session, refresh và phản hồi lỗi bằng fetch mock; không cần backend chạy.
describe('API integration', () => {
    // Hồi quy quy định lưu token: dữ liệu phiên cũ trong localStorage phải bị xóa, chỉ email được phép ghi nhớ.
    it('removes legacy local tokens and remembers or forgets only the email', () => {
        localStorage.setItem('nexus.session', JSON.stringify({ accessToken: 'legacy' }))
        expect(readSession()).toBeNull()
        expect(localStorage.getItem('nexus.session')).toBeNull()
        saveRememberedEmail('buyer@example.com', true)
        expect(readRememberedEmail()).toBe('buyer@example.com')
        saveRememberedEmail('', false)
        expect(readRememberedEmail()).toBe('')
        sessionStorage.setItem('nexus.session', 'invalid JSON')
        expect(readSession()).toBeNull()
        expect(errorMessage('unknown')).toBe('Request failed')
    })
    it('clears an invalid session if refresh fails and returns a failed request to the caller', async () => {
        saveSession({ user: mapUser(user) })
        vi.stubGlobal(
            'fetch',
            vi.fn(
                async () =>
                    new Response(JSON.stringify({ message: 'Expired refresh' }), { status: 401 }),
            ),
        )
        await expect(api('/users/me')).rejects.toThrow('Expired refresh')
        expect(readSession()).toBeNull()
    })
    it('accepts an empty successful response for DELETE', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(async () => new Response(null, { status: 204 })),
        )
        await expect(api('/categories/id', { method: 'DELETE' })).resolves.toBeUndefined()
    })
    it('maps backend role priority and profile fields', () => {
        expect(mapUser({ ...user, identifyNumber: '123', gender: 'FEMALE' })).toMatchObject({
            role: 'admin',
            idNumber: '123',
            gender: 'female',
        })
    })
    it('keeps sessions in the selected storage and clears both on logout', () => {
        const session = { user: mapUser(user) }
        saveSession(session)
        expect(readSession()).toEqual({ user: session.user })
        expect(sessionStorage.getItem('nexus.session')).not.toContain('accessToken')
        saveSession(session)
        expect(localStorage.getItem('nexus.session')).toBeNull()
        saveSession(null)
        expect(readSession()).toBeNull()
    })
    // Cookie được browser gửi; không có Bearer hoặc token trong request do JavaScript tạo.
    it('uses cookies and a CSRF header for backend requests', async () => {
        saveSession({ user: mapUser(user) })
        const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: '1' })))
        vi.stubGlobal('fetch', fetcher)
        await api('/users/me')
        expect(fetcher.mock.calls[0][1].headers.has('Authorization')).toBe(false)
        expect(fetcher.mock.calls[0][1].credentials).toBe('include')
        expect(fetcher.mock.calls[0][1].headers.get('X-Nexus-Request')).toBe('1')
    })
    it('surfaces backend validation errors instead of reporting success', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                new Response(JSON.stringify({ error: { message: 'Invalid price range' } }), {
                    status: 400,
                }),
            ),
        )
        await expect(api('/products?minPrice=20&maxPrice=10')).rejects.toThrow(
            'Invalid price range',
        )
    })
    it('refreshes an expired access token and retries the original request', async () => {
        saveSession({ user: mapUser(user) })
        const fetcher = vi
            .fn()
            .mockResolvedValueOnce(new Response('{}', { status: 401 }))
            .mockResolvedValueOnce(new Response(JSON.stringify({ user })))
            .mockResolvedValueOnce(new Response(JSON.stringify({ id: '1' })))
        vi.stubGlobal('fetch', fetcher)
        await expect(api('/users/me')).resolves.toEqual({ id: '1' })
        expect(fetcher.mock.calls[2][1].headers.has('Authorization')).toBe(false)
        expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({})
    })
    it('does not attempt token refresh for an invalid login', async () => {
        saveSession({ user: mapUser(user) })
        const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
        vi.stubGlobal('fetch', fetcher)
        await expect(api('/auth/login', { method: 'POST' })).rejects.toThrow('401')
        expect(fetcher).toHaveBeenCalledTimes(1)
        expect(fetcher.mock.calls[0][1].headers.has('Authorization')).toBe(false)
    })
    /**
     * Tình huống: Khách chưa có phiên (chưa đăng nhập hoặc refresh đã thất bại với 400 AUTH_REFRESH_TOKEN_EXPIRED).
     * Bảo vệ: Khi các request nền (như polling đấu giá định kỳ) gặp 401, không liên tục gọi POST /auth/refresh
     * gây tràn log lỗi ở Console và tăng tải Gateway vô ích.
     */
    it('does not repeatedly attempt token refresh when a guest has no active session', async () => {
        saveSession(null)
        const fetcher = vi
            .fn()
            .mockResolvedValue(
                new Response(JSON.stringify({ message: 'Unauthorized' }), { status: 401 }),
            )
        vi.stubGlobal('fetch', fetcher)
        await expect(api('/auctions?sort=ending_soon')).rejects.toThrow()
        await expect(api('/auctions?sort=ending_soon')).rejects.toThrow()
        // Mỗi lần gọi api chỉ tạo đúng 1 request tới endpoint chính, tuyệt đối không gọi tới /auth/refresh
        expect(fetcher).toHaveBeenCalledTimes(2)
        expect(fetcher.mock.calls.every(([url]) => !String(url).includes('/auth/refresh'))).toBe(
            true,
        )
    })
})
