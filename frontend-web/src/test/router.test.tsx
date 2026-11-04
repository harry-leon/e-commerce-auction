import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import type { AppState, Page } from '../types'
import { DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER } from '../mockData'
import { AppRouter } from '../routes/AppRouter'
import { PublicHeader } from '../components/Layout'

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

/**
 * Đăng xuất từ menu PublicHeader: xóa tài khoản (setUser(null)) và đóng menu,
 * giữ nguyên trang hiện tại thay vì ép chuyển hướng về trang login (theo trải nghiệm e-commerce Shopee).
 */
it('clears user session on header logout without navigating away from current page', async () => {
    holder.current.user = DEMO_BUYER
    holder.current.page = 'product-detail'
    render(<PublicHeader />)

    // Mở dropdown user menu
    const userMenuButton = screen.getByRole('button', { name: new RegExp(DEMO_BUYER.fullName, 'i') })
    fireEvent.click(userMenuButton)

    // Bấm nút Đăng xuất (Log Out)
    const logoutButton = screen.getByRole('button', { name: /logout/i })
    fireEvent.click(logoutButton)

    expect(holder.current.setUser).toHaveBeenCalledWith(null)
    expect(holder.current.navigate).not.toHaveBeenCalledWith('login')
})

it('opens a dashboard directly in the frontend demo without creating a real login session', async () => {
    holder.current.user = null
    holder.current.page = 'seller-dashboard'
    holder.current.pageParams = {}
    render(<AppRouter />)
    await waitFor(() => expect(holder.current.setUser).toHaveBeenCalledWith(DEMO_SELLER))
})

/**
 * Đảm bảo trang Home khi mới vào giữ vai trò khách (guest) chưa đăng nhập,
 * không tự động gán tài khoản ảo để khách có thể duyệt hàng hoặc đăng nhập tài khoản thật.
 */
it('opens Buyer Home directly as guest without auto-logging into mock account', async () => {
    holder.current.user = null
    holder.current.page = 'home'
    holder.current.pageParams = {}
    render(<AppRouter />)
    expect(holder.current.user).toBeNull()
    expect(holder.current.setUser).not.toHaveBeenCalled()
})

/**
 * Khi cần xem thử giao diện Buyer lúc dev, truyền tham số demo=buyer qua URL để nạp mock buyer.
 */
it('opens Buyer Home with buyer mock user when demo=buyer is requested', async () => {
    holder.current.user = null
    holder.current.page = 'home'
    holder.current.pageParams = { demo: 'buyer' }
    render(<AppRouter />)
    await waitFor(() => expect(holder.current.setUser).toHaveBeenCalledWith(DEMO_BUYER))
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
    'sandbox-payment',
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
