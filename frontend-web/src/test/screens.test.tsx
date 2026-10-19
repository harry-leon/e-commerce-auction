import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState, User } from '../types'
import { AUCTIONS, PRODUCTS, DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER, DEMO_SUPPORT } from '../mockData'
import { ORDERS, USERS } from '../mockData'
import { AuctionCard, ProductCard } from '../components/Cards'
import { NumberStepper, PasswordInput, Pagination, Toggle, ConfirmModal } from '../components/UI'
import * as Admin from '../features/admin/AdminPages'
import * as Buyer from '../features/buyer/BuyerPages'
import * as Seller from '../features/seller/SellerPages'
import * as Auction from '../features/auction/AuctionPages'
import * as Profile from '../features/profile/ProfilePages'
import * as Support from '../features/support/SupportPages'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))

/**
 * Hồi quy giao diện: mỗi màn đã tồn tại phải dựng được với đúng vai trò và dữ liệu fixture hợp lệ.
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
                holder.current.user = name.startsWith('SellerProfile')
                    ? DEMO_SELLER
                    : name.startsWith('SupportProfile')
                      ? DEMO_SUPPORT
                      : user
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

// Bảo vệ hành vi prototype danh mục cũ khi tái tổ chức source; màn production dùng AdminCategoriesPage qua HTTP riêng.
it('validates names and supports create, rename and delete in the legacy category prototype', () => {
    holder.current.user = DEMO_ADMIN
    render(<Admin.AdminCategoriesPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Add Category' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(screen.getByText('Both English and Vietnamese names are required.')).toBeVisible()
    fireEvent.change(screen.getByLabelText('Name (EN)'), { target: { value: 'Test category' } })
    fireEvent.change(screen.getByLabelText('Name (VI)'), { target: { value: 'Danh mục test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(screen.getByText('Test category')).toBeVisible()
    fireEvent.click(screen.getAllByTitle('Edit category').slice(-1)[0])
    fireEvent.change(screen.getByLabelText('Name (EN)'), { target: { value: 'Renamed category' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByText('Renamed category')).toBeVisible()
    fireEvent.click(screen.getAllByTitle('Delete category').slice(-1)[0])
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(screen.queryByText('Renamed category')).not.toBeInTheDocument()
})
