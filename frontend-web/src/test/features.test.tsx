import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../types'
import { DEMO_ADMIN } from '../mockData'
import { SearchPage } from '../features/catalog/SearchPage'
import { ProductDetailPage } from '../features/catalog/ProductDetailPage'
import { ProfilePage } from '../features/profile/ProfilePage'
import { AdminCategoriesPage } from '../features/admin/AdminCategoriesPage'
import { LoginPage, RegisterPage, ForgotPasswordPage } from '../features/auth/AuthPages'
import { flatten, price } from '../features/catalog/catalog'
import { readSession } from '../api/client'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))
const user = {
    id: 'user-1',
    fullName: 'Buyer',
    email: 'buyer@example.com',
    roles: ['BUYER'],
    status: 'ACTIVE',
    createdAt: '',
    phone: '0912345678',
    gender: 'OTHER',
}
const category = {
    id: 'cat-1',
    name: 'Electronics',
    nameVi: 'Điện tử',
    level: 1,
    parentId: null,
    status: 'ACTIVE' as const,
    productCount: 1,
    children: [],
}
const product = {
    id: 'product-1',
    name: 'API Laptop',
    description: 'Live description',
    categoryId: 'cat-1',
    sellerId: 'seller-1',
    status: 'ACTIVE',
    auctionEnabled: false,
    createdAt: '',
    skus: [{ id: 'sku-1', skuCode: 'LAPTOP-1', price: 500, status: 'ACTIVE' }],
    images: [{ id: 'img-1', url: '/laptop.png', sortOrder: 0 }],
}

/** Mock tại ranh giới HTTP để kiểm tra form → client → payload thật, gồm lỗi nghiệp vụ và cập nhật sau khi lưu. */
beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
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
        vi.fn(async (input: string, init: RequestInit = {}) => {
            const path = input.split('?')[0]
            let payload: unknown
            if (path.endsWith('/auth/login') || path.endsWith('/auth/register'))
                payload = { accessToken: 'access', refreshToken: 'refresh', user }
            else if (path.includes('/auth/')) payload = { message: 'OK' }
            else if (path.includes('/activity-history'))
                payload = {
                    content: [
                        {
                            id: 'log-1',
                            action: 'LOGIN',
                            description: 'Signed in',
                            createdAt: '2026-10-19',
                        },
                    ],
                }
            else if (path.endsWith('/users/me'))
                payload = { ...user, ...(init.body ? JSON.parse(String(init.body)) : {}) }
            else if (path.includes('/categories'))
                payload = {
                    success: true,
                    data: init.method && init.method !== 'GET' ? category : [category],
                }
            else if (path.endsWith('/products/product-1'))
                payload = { success: true, data: product }
            else
                payload = {
                    success: true,
                    data: [product],
                    meta: { total: 25, page: 0, pageSize: 12 },
                }
            return new Response(JSON.stringify(payload), {
                status: init.method === 'DELETE' ? 200 : 200,
            })
        }),
    )
})

describe('catalog API screens', () => {
    it('loads products, changes filters and pages, and opens the selected product', async () => {
        render(<SearchPage />)
        await screen.findByText('API Laptop')
        fireEvent.change(screen.getByLabelText('Tìm kiếm'), { target: { value: 'Laptop' } })
        fireEvent.change(screen.getByLabelText('Giá từ'), { target: { value: '100' } })
        fireEvent.change(screen.getByLabelText('Giá đến'), { target: { value: '900' } })
        fireEvent.change(screen.getByLabelText('Danh mục'), { target: { value: 'cat-1' } })
        fireEvent.change(screen.getByLabelText('Loại sản phẩm'), { target: { value: 'false' } })
        fireEvent.change(screen.getByLabelText('Sắp xếp'), { target: { value: 'name,asc' } })
        await waitFor(() => expect(screen.getByText('API Laptop')).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: 'Sau' }))
        await waitFor(() => expect(screen.getByText('Trang 2')).toBeVisible())
        await waitFor(() => expect(screen.getByRole('button', { name: 'Trước' })).toBeEnabled())
        fireEvent.click(screen.getByRole('button', { name: 'Trước' }))
        await screen.findByText('API Laptop')
        fireEvent.click(screen.getByRole('button', { name: /API Laptop/ }))
        expect(holder.current.navigate).toHaveBeenCalledWith('product-detail', { id: 'product-1' })
        expect(
            vi
                .mocked(fetch)
                .mock.calls.some(
                    ([url]) =>
                        String(url).includes('minPrice=100') &&
                        String(url).includes('categoryId=cat-1'),
                ),
        ).toBe(true)
    })
    it('renders API errors and empty results instead of demo products', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(
                async () =>
                    new Response(JSON.stringify({ success: true, data: [], meta: { total: 0 } })),
            ),
        )
        const { unmount } = render(<SearchPage />)
        await screen.findByText('Không có sản phẩm phù hợp.')
        unmount()
        vi.stubGlobal(
            'fetch',
            vi.fn(
                async () =>
                    new Response(JSON.stringify({ message: 'Catalog offline' }), { status: 503 }),
            ),
        )
        render(<SearchPage />)
        await screen.findByText('Catalog offline')
    })
    it('shows product SKU/image details and supports returning to results', async () => {
        holder.current.pageParams = { id: 'product-1' }
        render(<ProductDetailPage />)
        await screen.findByText('Live description')
        expect(screen.getByText(/LAPTOP-1/)).toBeVisible()
        fireEvent.click(screen.getByRole('button', { name: 'Ảnh 1' }))
        fireEvent.click(screen.getByRole('button', { name: 'Quay lại sản phẩm' }))
        expect(holder.current.navigate).toHaveBeenCalledWith('search')
    })
    it('keeps tree depth and only prices active SKUs', () => {
        expect(flatten([category])).toHaveLength(1)
        expect(price(product)).toContain('500')
        expect(price({ ...product, skus: [] })).toBe('Chưa có giá bán')
    })
})

describe('profile and category writes', () => {
    it('saves profile, displays history and signs out after changing password', async () => {
        render(<ProfilePage />)
        await screen.findByDisplayValue('Buyer')
        expect(await screen.findByText(/Signed in/)).toBeVisible()
        fireEvent.change(screen.getByLabelText('Họ tên'), { target: { value: 'Updated Buyer' } })
        fireEvent.click(screen.getByRole('button', { name: 'Lưu thông tin' }))
        await waitFor(() =>
            expect(holder.current.setUser).toHaveBeenCalledWith(
                expect.objectContaining({ fullName: 'Updated Buyer' }),
            ),
        )
        fireEvent.change(screen.getByLabelText('Mật khẩu hiện tại'), {
            target: { value: 'Current123!' },
        })
        fireEvent.change(screen.getByLabelText('Mật khẩu mới'), {
            target: { value: 'Updated123!' },
        })
        fireEvent.change(screen.getByLabelText('Xác nhận mật khẩu'), {
            target: { value: 'Mismatch' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }))
        await screen.findByText('Mật khẩu xác nhận không khớp')
        fireEvent.change(screen.getByLabelText('Xác nhận mật khẩu'), {
            target: { value: 'Updated123!' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }))
        await waitFor(() => expect(holder.current.navigate).toHaveBeenCalledWith('login'))
        expect(holder.current.setUser).toHaveBeenCalledWith(null)
    })
    it('persists category edits and handles cancellation and failed deletion', async () => {
        render(<AdminCategoriesPage />)
        await screen.findByText(/Điện tử ·/)
        fireEvent.change(screen.getByLabelText('Tên tiếng Anh'), { target: { value: 'Books' } })
        fireEvent.change(screen.getByLabelText('Tên tiếng Việt'), { target: { value: 'Sách' } })
        fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))
        await waitFor(() =>
            expect(
                vi.mocked(fetch).mock.calls.some(([, options]) => options?.method === 'POST'),
            ).toBe(true),
        )
        await waitFor(() => expect(screen.getByRole('button', { name: 'Sửa' })).toBeEnabled())
        fireEvent.click(screen.getByRole('button', { name: 'Sửa' }))
        fireEvent.click(screen.getByRole('button', { name: 'Hủy' }))
        fireEvent.click(screen.getByRole('button', { name: 'Sửa' }))
        fireEvent.click(screen.getByRole('button', { name: 'Lưu' }))
        await waitFor(() =>
            expect(
                vi.mocked(fetch).mock.calls.some(([, options]) => options?.method === 'PUT'),
            ).toBe(true),
        )
        await waitFor(() => expect(screen.getByRole('button', { name: 'Xóa' })).toBeEnabled())
        vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
        fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
        vi.mocked(fetch).mockResolvedValueOnce(
            new Response(JSON.stringify({ message: 'Category has products' }), { status: 400 }),
        )
        fireEvent.click(screen.getByRole('button', { name: 'Xóa' }))
        await screen.findByText('Category has products')
    })
})

describe('IAM forms', () => {
    it('logs in, remembers only email and never puts a token into localStorage', async () => {
        const ui = userEvent.setup()
        render(<LoginPage />)
        await ui.type(screen.getByLabelText('Email', { exact: true }), user.email)
        await ui.type(screen.getByLabelText('Password', { exact: true }), 'StrongPassword1!')
        await ui.click(screen.getByText('Remember email'))
        await ui.click(screen.getByRole('button', { name: 'Login' }))
        await waitFor(() => expect(holder.current.navigate).toHaveBeenCalledWith('home'))
        expect(readSession()?.accessToken).toBe('access')
        expect(localStorage.getItem('nexus.session')).toBeNull()
        expect(localStorage.getItem('nexus.remembered-email')).toBe(user.email)
    })
    it('validates each registration step and submits the backend DTO', async () => {
        const ui = userEvent.setup()
        render(<RegisterPage />)
        await ui.click(screen.getByRole('button', { name: 'Next' }))
        expect(screen.getByText(/Invalid email/i)).toBeVisible()
        await ui.type(screen.getByLabelText('Email'), user.email)
        await ui.type(screen.getByLabelText('Phone'), '0912345678')
        await ui.click(screen.getByRole('button', { name: 'Next' }))
        await ui.click(screen.getByRole('button', { name: 'Next' }))
        await ui.type(screen.getByLabelText('Full Name'), 'New Buyer')
        await ui.type(screen.getByLabelText('ID Number'), '123456789012')
        fireEvent.change(screen.getByLabelText('Date of Birth'), {
            target: { value: '2000-01-01' },
        })
        await ui.click(screen.getByRole('button', { name: 'Next' }))
        await ui.type(screen.getByLabelText('Password', { exact: true }), 'StrongPassword1!')
        await ui.type(screen.getByLabelText('Confirm Password'), 'StrongPassword1!')
        await ui.click(screen.getByRole('button', { name: 'Register' }))
        await waitFor(() => expect(holder.current.navigate).toHaveBeenCalledWith('login'))
        const registration = vi
            .mocked(fetch)
            .mock.calls.find(([url]) => String(url).endsWith('/auth/register'))
        expect(JSON.parse(String(registration?.[1]?.body))).toMatchObject({
            identifyNumber: '123456789012',
            fullName: 'New Buyer',
            gender: 'MALE',
        })
    })
    it('sends email, rejects mismatching passwords and consumes the reset token', async () => {
        const ui = userEvent.setup()
        render(<ForgotPasswordPage />)
        await ui.click(screen.getByRole('button', { name: 'Send Reset Link' }))
        expect(screen.getByText(/Invalid email/i)).toBeVisible()
        await ui.type(screen.getByLabelText('Email'), user.email)
        await ui.click(screen.getByRole('button', { name: 'Send Reset Link' }))
        await screen.findByLabelText('Reset token')
        await ui.type(screen.getByLabelText('Reset token'), 'mail-token')
        await ui.type(screen.getByLabelText('New Password'), 'NewPassword1!')
        await ui.type(screen.getByLabelText('Confirm Password'), 'Mismatch')
        await ui.click(screen.getByRole('button', { name: 'Reset Password' }))
        expect(screen.getByText(/match/i)).toBeVisible()
        await ui.clear(screen.getByLabelText('Confirm Password'))
        await ui.type(screen.getByLabelText('Confirm Password'), 'NewPassword1!')
        await ui.click(screen.getByRole('button', { name: 'Reset Password' }))
        await screen.findByText('Password reset successfully!')
        await ui.click(screen.getByRole('button', { name: 'Back to Login' }))
        expect(holder.current.navigate).toHaveBeenCalledWith('login')
    })
})
