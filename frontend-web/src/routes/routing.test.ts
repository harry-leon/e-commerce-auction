import { afterEach, expect, it, vi } from 'vitest'
import { readRoute, writeRoute } from './routing'

// Kiểm chứng khôi phục URL và mã hóa history bằng window mock, bao gồm link reset từ email.
afterEach(() => vi.unstubAllGlobals())

it('restores the product ID from a direct URL', () => {
    vi.stubGlobal('window', {
        location: { pathname: '/product-detail', search: '?id=product-123' },
    })
    expect(readRoute()).toEqual({ page: 'product-detail', params: { id: 'product-123' } })
})
it('opens the password reset screen from an email link', () => {
    vi.stubGlobal('window', {
        location: { pathname: '/reset-password', search: '?token=reset-token' },
    })
    expect(readRoute().page).toBe('forgot-password')
})
it('encodes search terms in browser history', () => {
    const pushState = vi.fn()
    vi.stubGlobal('window', { history: { pushState } })
    writeRoute('search', { q: 'a & b', category: null })
    expect(pushState).toHaveBeenCalledWith(null, '', '/search?q=a+%26+b')
})
