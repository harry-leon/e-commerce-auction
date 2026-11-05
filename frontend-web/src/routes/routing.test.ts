import { afterEach, expect, it, vi } from 'vitest'
import { readRoute, writeRoute } from './routing'

// Dùng history của JSDOM để kiểm tra URL thật; không thay window vì thuộc tính này không cho định nghĩa lại.
afterEach(() => {
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
})

it('restores the product ID from a direct URL', () => {
    window.history.replaceState(null, '', '/product-detail?id=product-123')
    expect(readRoute()).toEqual({ page: 'product-detail', params: { id: 'product-123' } })
})
it('opens the password reset screen from an email link', () => {
    window.history.replaceState(null, '', '/reset-password?token=reset-token')
    expect(readRoute().page).toBe('forgot-password')
})
it('encodes search terms in browser history', () => {
    const pushState = vi.spyOn(window.history, 'pushState')
    writeRoute('search', { q: 'a & b', category: null })
    expect(pushState).toHaveBeenCalledWith(null, '', '/search?q=a+%26+b')
})
