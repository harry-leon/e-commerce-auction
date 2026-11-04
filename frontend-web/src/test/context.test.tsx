import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AppProvider, useApp } from '../context'
import { useSessionStore } from '../features/auth/sessionStore'
import { AUCTIONS, DEMO_BUYER } from '../mockData'

// Kiểm chứng state thật và history thật của jsdom, không mock useApp trong file này.
beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    useSessionStore.setState({ user: null })
    window.history.replaceState(null, '', '/')
})
afterEach(() => vi.useRealTimers())

// Logout chỉ xóa state sau khi server xác nhận xóa cookie HttpOnly.
it('synchronizes navigation, browser history, session and shared UI state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    const { result } = renderHook(() => useApp(), { wrapper: AppProvider })
    act(() => result.current.navigate('search', { q: 'laptop' }))
    expect(window.location.pathname).toBe('/search')
    expect(result.current.pageParams.q).toBe('laptop')
    act(() => {
        window.history.replaceState(null, '', '/product-detail?id=product-2')
        window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(result.current.page).toBe('product-detail')
    act(() => {
        result.current.setUser(DEMO_BUYER)
        result.current.setLang('vi')
        result.current.setCartCount(2)
    })
    expect(result.current.user?.id).toBe(DEMO_BUYER.id)
    expect(result.current.lang).toBe('vi')
    expect(result.current.cartCount).toBe(2)
    act(() => result.current.reviewAuction('auction-1', 'rejected', 'Missing details'))
    expect(result.current.auctionReviews['auction-1']?.reason).toBe('Missing details')
    act(() => result.current.submitAuction(AUCTIONS[0]))
    expect(result.current.createdAuctions[0].id).toBe(AUCTIONS[0].id)
    await act(async () => {
        await result.current.setUser(null)
    })
    expect(result.current.user).toBeNull()
    vi.unstubAllGlobals()
})

it('dismisses a toast manually and expires another after its timeout', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useApp(), { wrapper: AppProvider })
    act(() => result.current.showToast({ type: 'success', message: 'Saved' }))
    const id = result.current.toasts[0].id
    act(() => result.current.dismissToast(id))
    expect(result.current.toasts).toHaveLength(0)
    act(() => result.current.showToast({ type: 'error', message: 'Failed', duration: 100 }))
    act(() => vi.advanceTimersByTime(100))
    expect(result.current.toasts).toHaveLength(0)
})
