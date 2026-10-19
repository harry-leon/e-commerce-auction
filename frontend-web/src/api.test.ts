import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { api, mapUser, readSession, saveSession, type ApiUser } from './api'

function storage() {
  const values = new Map<string, string>()
  return { getItem: (key: string) => values.get(key) || null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) }
}
const user: ApiUser = { id: '1', email: 'a@example.com', fullName: 'A', status: 'ACTIVE', createdAt: '', roles: ['BUYER', 'ADMIN'] }

beforeEach(() => {
  vi.stubGlobal('localStorage', storage()); vi.stubGlobal('sessionStorage', storage())
})
afterEach(() => vi.unstubAllGlobals())

// Kiểm chứng token/session, refresh và phản hồi lỗi bằng fetch mock; không cần backend chạy.
describe('API integration', () => {
  it('maps backend role priority and profile fields', () => {
    expect(mapUser({ ...user, identifyNumber: '123', gender: 'FEMALE' })).toMatchObject({ role: 'admin', idNumber: '123', gender: 'female' })
  })
  it('keeps sessions in the selected storage and clears both on logout', () => {
    const session = { accessToken: 'access', refreshToken: 'refresh', user: mapUser(user) }
    saveSession(session, true); expect(readSession()).toEqual(session)
    saveSession(session); expect(localStorage.getItem('nexus.session')).toBeNull()
    saveSession(null); expect(readSession()).toBeNull()
  })
  it('attaches the bearer token to backend requests', async () => {
    saveSession({ accessToken: 'access', refreshToken: 'refresh', user: mapUser(user) })
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: '1' })))
    vi.stubGlobal('fetch', fetcher)
    await api('/users/me')
    expect(fetcher.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer access')
  })
  it('surfaces backend validation errors instead of reporting success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: { message: 'Invalid price range' } }), { status: 400 })))
    await expect(api('/products?minPrice=20&maxPrice=10')).rejects.toThrow('Invalid price range')
  })
  it('refreshes an expired access token and retries the original request', async () => {
    saveSession({ accessToken: 'old', refreshToken: 'refresh', user: mapUser(user) })
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: 'new', refreshToken: 'new-refresh', user })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: '1' })))
    vi.stubGlobal('fetch', fetcher)
    await expect(api('/users/me')).resolves.toEqual({ id: '1' })
    expect(fetcher.mock.calls[2][1].headers.get('Authorization')).toBe('Bearer new')
  })
  it('does not attempt token refresh for an invalid login', async () => {
    saveSession({ accessToken: 'expired', refreshToken: 'refresh', user: mapUser(user) })
    const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
    vi.stubGlobal('fetch', fetcher)
    await expect(api('/auth/login', { method: 'POST' })).rejects.toThrow('401')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0][1].headers.has('Authorization')).toBe(false)
  })
})
