import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState, User } from '../types'
import { AUCTIONS, PRODUCTS, DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER, DEMO_SUPPORT } from '../mockData'
import { ORDERS, USERS } from '../mockData'
import { auctionsApi } from '../api/auctions'
import { AuctionCard, ProductCard } from '../components/Cards'
import { NumberStepper, PasswordInput, Pagination, Toggle, ConfirmModal } from '../components/UI'
import * as Admin from '../features/admin/AdminPages'
import * as Buyer from '../features/buyer/BuyerPages'
import * as Seller from '../features/seller/SellerPages'
import * as Auction from '../features/auction/AuctionPages'
import * as Profile from '../features/profile/ProfilePage'
import * as Support from '../features/support/SupportPages'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))

/**
 * Hồi quy các màn đang được router dùng; hồ sơ chỉ kiểm tra ProfilePage gọi API, không chạy bản demo đã thay thế.
 * Đây là kiểm tra khả dụng/render UI sau khi chuyển thư mục, không coi prototype là nghiệp vụ backend hoàn chỉnh.
 */
beforeEach(() => {
    holder.current = {
        lang: 'en',
        setLang: vi.fn(),
        page: 'home',
        navigate: vi.fn(),
        pageParams: {},
        user: DEMO_BUYER,
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
        vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: [] }))),
    )
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
})

const groups: [string, Record<string, React.ComponentType>, User][] = [
    ['admin', Admin, DEMO_ADMIN],
    ['buyer', Buyer, DEMO_BUYER],
    ['seller', Seller, DEMO_SELLER],
    ['auction', Auction, DEMO_BUYER],
    ['profile', Profile, DEMO_ADMIN],
    ['support', Support, DEMO_SUPPORT],
]
for (const [area, pages, user] of groups) {
    describe(`${area} screen availability`, () => {
        for (const [name, Page] of Object.entries(pages)) {
            it(`${name} renders navigation and content for its role`, async () => {
                // Mỗi module nhận đúng vai trò của nhóm route; các hồ sơ role cũ đã dùng chung ProfilePage.
                holder.current.user = user
                const { container } = render(<Page />)
                expect(container.textContent?.trim().length).toBeGreaterThan(20)
                expect(
                    screen.queryByText('Application Error / Lỗi ứng dụng'),
                ).not.toBeInTheDocument()
                // Đợi request header hoàn tất để cleanup không che giấu cập nhật bất đồng bộ của cây danh mục.
                await vi.waitFor(() =>
                    expect(container.querySelector('header, aside, main, h1, h2')).not.toBeNull(),
                )
            })
        }
    })
}

// Bảo vệ empty state để trang chủ không tự hiển thị auction fixture khi API không có dữ liệu.
it('shows a clear empty state instead of default live auction fixtures', async () => {
    render(<Buyer.HomePage />)

    expect(await screen.findByText('No live or upcoming auctions right now. Please check back soon.')).toBeInTheDocument()
    expect(screen.queryByText(AUCTIONS[0].product.name)).not.toBeInTheDocument()
    expect(screen.queryByText(AUCTIONS[1].product.name)).not.toBeInTheDocument()
})

// Bảo vệ việc đưa phiên scheduled đã có trong dữ liệu Buyer lên trang chủ cùng phiên đang diễn ra.
it('shows upcoming scheduled auctions on the home page', async () => {
    const getAuctions = vi.spyOn(auctionsApi, 'getAuctions').mockResolvedValue({
        items: [{ ...AUCTIONS[2], id: 'upcoming-home-test', status: 'scheduled' }],
        total: 1,
        page: 1,
        limit: 8,
        totalPages: 1,
    })
    render(<Buyer.HomePage />)

    expect(await screen.findAllByText(AUCTIONS[2].product.name)).not.toHaveLength(0)
    expect(screen.queryByText('No live or upcoming auctions right now. Please check back soon.')).not.toBeInTheDocument()
    expect(getAuctions).toHaveBeenCalledWith(expect.objectContaining({ status: 'all' }))
})

// Đảm bảo CTA phụ trên hero luôn đưa Buyer tới danh sách đấu giá thay vì một sản phẩm mẫu.
it('navigates from the home hero to the auction list', () => {
    vi.spyOn(auctionsApi, 'getAuctions').mockResolvedValue({
        items: [],
        total: 0,
        page: 1,
        limit: 8,
        totalPages: 1,
    })
    render(<Buyer.HomePage />)

    fireEvent.click(screen.getByRole('button', { name: 'Bid Now' }))

    expect(holder.current.navigate).toHaveBeenCalledWith('auction-list')
})

it('adds a product to the cart without opening its detail page, then opens detail on card click', () => {
    const product = { ...PRODUCTS[0], stock: 2 }
    render(<ProductCard product={product} />)
    fireEvent.click(screen.getByRole('button'))
    expect(holder.current.setCartCount).toHaveBeenCalledWith(1)
    expect(holder.current.navigate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByText(product.name))
    expect(holder.current.navigate).toHaveBeenCalledWith('product-detail', { id: product.id })
})

it('displays auction statuses and routes cards to the corresponding auction', () => {
    for (const status of ['active', 'scheduled', 'ended', 'rejected'] as const) {
        const { unmount } = render(<AuctionCard auction={{ ...AUCTIONS[0], status }} />)
        fireEvent.click(screen.getByText(AUCTIONS[0].product.name))
        expect(holder.current.navigate).toHaveBeenCalledWith('auction-detail', {
            id: AUCTIONS[0].id,
        })
        unmount()
    }
})

it('supports password visibility, quantity boundaries, toggling and pagination callbacks', () => {
    const change = vi.fn()
    let view = render(<PasswordInput label="Secret" defaultValue="value" />)
    expect(screen.getByLabelText('Secret')).toHaveAttribute('type', 'password')
    fireEvent.click(screen.getByRole('button'))
    expect(screen.getByLabelText('Secret')).toHaveAttribute('type', 'text')
    view.unmount()
    view = render(<NumberStepper value={2} min={1} max={3} onChange={change} />)
    const quantityButtons = screen.getAllByRole('button')
    fireEvent.click(quantityButtons[0])
    fireEvent.click(quantityButtons[1])
    expect(change).toHaveBeenCalledWith(1)
    expect(change).toHaveBeenCalledWith(3)
    view.unmount()
    view = render(<Toggle checked={false} onChange={change} label="Enabled" />)
    fireEvent.click(screen.getByRole('button'))
    expect(change).toHaveBeenCalledWith(true)
    view.unmount()
    render(<Pagination page={2} total={50} perPage={10} onChange={change} />)
    fireEvent.click(screen.getByRole('button', { name: '3' }))
    expect(change).toHaveBeenCalledWith(3)
})

it('requires explicit confirmation and supports dismissing a destructive action', () => {
    const confirm = vi.fn(),
        close = vi.fn()
    const { rerender } = render(
        <ConfirmModal
            open
            onClose={close}
            onConfirm={confirm}
            title="Delete item"
            message="Cannot undo"
            danger
        />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(close).toHaveBeenCalledOnce()
    expect(confirm).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(confirm).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '' }))
    expect(close).toHaveBeenCalledTimes(3)
    rerender(
        <ConfirmModal
            open={false}
            onClose={close}
            onConfirm={confirm}
            title="Delete item"
            message="Cannot undo"
        />,
    )
    expect(screen.queryByText('Cannot undo')).not.toBeInTheDocument()
})

// Prototype hỗ trợ vẫn phải tìm đúng fixture và giữ tiến độ case trong state; không khẳng định đã lưu backend.
it('looks up an order and a user and displays their details in the support workspace', () => {
    holder.current.user = DEMO_SUPPORT
    const view = render(<Support.SupportOrderLookupPage />)
    fireEvent.change(screen.getByPlaceholderText('Enter Order ID (e.g. ORD-20240215-001)'), {
        target: { value: ORDERS[0].id },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Lookup' }))
    expect(screen.getAllByText(ORDERS[0].buyerName).length).toBeGreaterThan(0)
    view.unmount()
    render(<Support.SupportUserLookupPage />)
    fireEvent.change(screen.getByPlaceholderText('Enter email or user ID...'), {
        target: { value: USERS[0].email },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Lookup' }))
    expect(screen.getByText(USERS[0].email, { exact: false })).toBeVisible()
})

it('opens a support case, saves progress and requires a recommendation before escalation', () => {
    holder.current.user = DEMO_SUPPORT
    render(<Support.SupportDashboardPage />)
    fireEvent.click(screen.getAllByRole('button', { name: 'Handle Case' })[0])
    expect(screen.getByText('Handle support case')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Escalate to Admin' })).toBeDisabled()
    fireEvent.change(
        screen.getByPlaceholderText('Record evidence from Buyer, Seller, and carrier...'),
        { target: { value: 'Carrier confirmed receipt' } },
    )
    fireEvent.click(screen.getByRole('button', { name: 'Save Progress' }))
    expect(holder.current.showToast).toHaveBeenCalledWith(
        expect.objectContaining({ message: 'Case progress saved.' }),
    )
    fireEvent.click(screen.getAllByRole('button', { name: 'Handle Case' })[0])
    const recommendation = screen
        .getAllByRole('combobox')
        .find((select) => select.querySelector('option[value="refund-buyer"]'))
    expect(recommendation).toBeDefined()
    fireEvent.change(recommendation!, { target: { value: 'refund-buyer' } })
    fireEvent.click(screen.getByRole('button', { name: 'Escalate to Admin' }))
    expect(holder.current.showToast).toHaveBeenCalledWith(
        expect.objectContaining({ message: 'Case escalated to Admin for a financial decision.' }),
    )
})
