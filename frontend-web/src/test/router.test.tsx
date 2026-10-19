import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import type { AppState, Page } from '../types'
import { DEMO_ADMIN } from '../mockData'
import { AppRouter } from '../routes/AppRouter'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))

// Hồi quy router sau refactor: route công khai, phân quyền khu vực và mọi route đã đăng ký phải render đúng nhánh.
beforeEach(() => {
    holder.current = {
        lang: 'en',
        setLang: vi.fn(),
        page: 'home',
        navigate: vi.fn(),
        pageParams: {},
        user: DEMO_ADMIN,
        setUser: vi.fn(),
        cartCount: 0,
        setCartCount: vi.fn(),
        toasts: [],
        showToast: vi.fn(),
        dismissToast: vi.fn(),
        auctionReviews: {},
        reviewAuction: vi.fn(),
        createdAuctions: [],
        submitAuction: vi.fn(),
    }
    vi.stubGlobal(
        'fetch',
        vi.fn(async (input) => {
            const url = String(input)
            if (url.endsWith('/categories') || url.includes('/products?'))
                return new Response(JSON.stringify({ success: true, data: [], meta: { total: 0 } }))
            return new Response(JSON.stringify({ message: 'No fixture data' }), { status: 404 })
        }),
    )
})

it('requires a login for private routes and denies a wrong staff role', () => {
    holder.current.user = null
    holder.current.page = 'profile'
    const { rerender } = render(<AppRouter />)
    expect(screen.getByRole('button', { name: 'Login' })).toBeVisible()
    for (const page of ['admin-users', 'seller-products', 'support-profile'] as Page[]) {
        holder.current.user = { ...DEMO_ADMIN, role: 'buyer' }
        holder.current.page = page
        rerender(<AppRouter />)
        expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    }
})

const pages = [
    'login',
    'register',
    'forgot-password',
    'home',
    'search',
    'product-detail',
    'cart',
    'checkout',
    'payment-result',
    'order-history',
    'order-detail',
    'profile',
    'seller-shop',
    'my-reputation',
    'public-reputation',
    'notifications',
    'auction-list',
    'auction-detail',
    'my-bids',
    'auction-payment',
    'seller-dashboard',
    'seller-products',
    'seller-create-product',
    'seller-auctions',
    'seller-create-auction',
    'seller-orders',
    'seller-balance',
    'seller-inventory',
    'seller-reputation',
    'seller-notifications',
    'seller-profile',
    'admin-dashboard',
    'admin-users',
    'admin-user-detail',
    'admin-roles',
    'admin-categories',
    'admin-products',
    'admin-orders',
    'admin-auctions',
    'admin-warehouses',
    'admin-shipments',
    'admin-reputation',
    'admin-event-logs',
    'admin-audit-logs',
    'admin-system-config',
    'admin-system-health',
    'admin-profile',
    'support-dashboard',
    'support-order-lookup',
    'support-user-lookup',
    'support-profile',
] as Page[]
for (const page of pages)
    it(`resolves ${page} without a missing import or unauthorized fallback`, async () => {
        holder.current.page = page
        const { container } = render(<AppRouter />)
        expect(container.textContent?.trim()).not.toBe('Page not found')
        expect(container.textContent?.trim()).not.toBe('Access denied')
        await waitFor(() => expect(container.textContent?.trim().length).toBeGreaterThan(10))
    })

it('shows a clear not-found state for an unknown URL', () => {
    holder.current.page = 'missing' as Page
    render(<AppRouter />)
    expect(screen.getByRole('alert')).toHaveTextContent('Page not found')
})
