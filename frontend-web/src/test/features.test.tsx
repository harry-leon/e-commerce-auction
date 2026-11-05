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
import { CartPage, CheckoutPage, SandboxPaymentPage } from '../features/buyer/BuyerPages'
import { flatten, price } from '../features/catalog/catalog'
import { readSession } from '../api/client'
import { ordersApi } from '../api/orders'

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

const mockCartItem = {
    id: 'item-1',
    skuId: 'sku-1',
    skuCode: 'LAPTOP-1',
    productName: 'API Laptop',
    thumbnailUrl: '/laptop.png',
    unitPrice: 500,
    quantity: 1,
    stockAvailable: 10,
    isAvailable: true,
    subtotal: 500,
}

const mockCart = {
    cartId: 'cart-1',
    userId: 'user-1',
    status: 'ACTIVE' as const,
    items: [mockCartItem],
    totalItems: 1,
    totalQuantity: 1,
    subtotal: 500,
    discountAmount: 0,
    shippingFee: 15,
    taxAmount: 40,
    totalAmount: 555,
    expiresAt: null,
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
            if (path.endsWith('/auth/login') || path.endsWith('/auth/register')) payload = { user }
            else if (path.includes('/auth/')) payload = { message: 'OK' }
            else if (path.includes('/activity-history'))
                // User Service bọc trang lịch sử trong data; fixture phải giữ contract để bảo vệ luồng hiển thị sau reload.
                payload = {
                    success: true,
                    data: {
                        content: [
                            {
                                id: 'log-1',
                                action: 'LOGIN',
                                description: 'Signed in',
                                createdAt: '2026-10-19',
                            },
                        ],
                    },
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
            else if (path.includes('/cart/items') && init.method === 'DELETE') {
                payload = {
                    success: true,
                    data: {
                        ...mockCart,
                        items: [],
                        totalItems: 0,
                        totalQuantity: 0,
                        subtotal: 0,
                        totalAmount: 0,
                    },
                }
            } else if (path.includes('/cart/items') && init.method === 'PUT') {
                const body = init.body ? JSON.parse(String(init.body)) : { quantity: 2 }
                const qty = body.quantity || 1
                payload = {
                    success: true,
                    data: {
                        ...mockCart,
                        items: [
                            {
                                ...mockCartItem,
                                quantity: qty,
                                subtotal: mockCartItem.unitPrice * qty,
                            },
                        ],
                        totalQuantity: qty,
                        subtotal: mockCartItem.unitPrice * qty,
                        totalAmount: mockCartItem.unitPrice * qty + 15 + 40,
                    },
                }
            } else if (path.includes('/cart/items') && init.method === 'POST') {
                payload = { success: true, data: mockCart }
            } else if (path.includes('/cart')) {
                payload = { success: true, data: mockCart }
            } else
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
        // Form hiện tại có hai nhãn theo ngôn ngữ; sửa đúng trường name khi giao diện đang dùng tiếng Anh.
        fireEvent.change(screen.getByLabelText('Category name (English)'), {
            target: { value: 'Books' },
        })
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
        // Sau đăng nhập chỉ cache profile; token nằm trong cookie HttpOnly.
        expect(readSession()?.user.id).toBe(user.id)
        expect(sessionStorage.getItem('nexus.session')).not.toContain('accessToken')
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
        await ui.type(screen.getByLabelText('Citizen ID number (CCCD)'), '123456789012')
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
    // Hồi quy chuyển IAM: ứng dụng chỉ yêu cầu email, không còn nhập/reset token cục bộ.
    it('requests an IAM reset email and directs the user to the email link', async () => {
        const ui = userEvent.setup()
        render(<ForgotPasswordPage />)
        await ui.click(screen.getByRole('button', { name: 'Send Reset Link' }))
        expect(screen.getByText(/Invalid email/i)).toBeVisible()
        await ui.type(screen.getByLabelText('Email'), user.email)
        await ui.click(screen.getByRole('button', { name: 'Send Reset Link' }))
        expect(await screen.findByRole('status')).toHaveTextContent(/link in your email/i)
        expect(screen.queryByLabelText('Reset token')).not.toBeInTheDocument()
        await ui.click(screen.getByRole('button', { name: 'Back to Login' }))
        expect(holder.current.navigate).toHaveBeenCalledWith('login')
    })
})

describe('Cart, Checkout và Payment Result', () => {
    /**
     * Kiểm tra CartPage: apply voucher hợp lệ/không hợp lệ.
    /**
     * Kiểm tra CartPage:
     * 1. Lấy và hiển thị sản phẩm giỏ hàng từ backend API (Accept Criteria 1)
     * 2. Áp dụng mã giảm giá voucher (hợp lệ và không hợp lệ)
     * 3. Hiển thị tổng tiền và tóm tắt thanh toán (Accept Criteria 3)
     */
    it('CartPage: hiển thị danh sách sản phẩm từ backend và áp dụng voucher', async () => {
        render(<CartPage />)
        // Accept Criteria 1: Buyer lấy và xem được danh sách sản phẩm trong giỏ hàng từ Backend
        await screen.findByRole('heading', { level: 1 })
        expect(screen.getByText('API Laptop')).toBeVisible()

        // Accept Criteria 3: Tổng tiền được hiển thị dựa trên dữ liệu API
        expect(screen.getByText(/order summary|tóm tắt đơn hàng/i)).toBeVisible()

        // Nhập voucher sai → hiển thị lỗi
        const voucherInput = screen.getByPlaceholderText('NEXUS10')
        fireEvent.change(voucherInput, { target: { value: 'WRONG' } })
        fireEvent.click(screen.getByRole('button', { name: /áp|apply/i }))
        await waitFor(() => expect(screen.getByText(/invalid|không hợp lệ/i)).toBeVisible())

        // Nhập voucher đúng → showToast được gọi
        fireEvent.change(voucherInput, { target: { value: 'NEXUS10' } })
        fireEvent.click(screen.getByRole('button', { name: /áp|apply/i }))
        await waitFor(() => expect(holder.current.showToast).toHaveBeenCalled())
    })

    /**
     * Kiểm tra CartPage: cập nhật số lượng và xóa sản phẩm khỏi giỏ hàng (Accept Criteria 2).
     */
    it('CartPage: cập nhật số lượng và xóa sản phẩm khỏi giỏ hàng', async () => {
        render(<CartPage />)
        await screen.findByText('API Laptop')

        // Cập nhật số lượng qua nút tăng (+) của NumberStepper
        const plusBtn = screen.getByRole('button', { name: '+' })
        fireEvent.click(plusBtn)
        await waitFor(() => expect(holder.current.showToast).toHaveBeenCalled())

        // Xóa sản phẩm đơn lẻ bằng nút Trash
        const trashBtn = screen.getByRole('button', { name: /xóa khỏi giỏ|remove item/i })
        fireEvent.click(trashBtn)
        await waitFor(() => expect(holder.current.showToast).toHaveBeenCalled())
    })

    /**
     * Kiểm tra CartPage: mở modal xác nhận và dọn toàn bộ giỏ hàng (Accept Criteria 2).
     */
    it('CartPage: mở modal xác nhận và dọn toàn bộ giỏ hàng', async () => {
        render(<CartPage />)
        await screen.findByText('API Laptop')

        // Bấm nút Xóa tất cả
        const clearBtn = screen.getByRole('button', { name: /clear cart|xóa tất cả/i })
        fireEvent.click(clearBtn)

        // Modal xác nhận xuất hiện
        await waitFor(() =>
            expect(screen.getByText(/are you sure you want to remove all items/i)).toBeVisible(),
        )
        // Bấm Confirm
        const confirmBtn = screen.getByRole('button', { name: /confirm|xác nhận/i })
        fireEvent.click(confirmBtn)
        await waitFor(() => expect(holder.current.showToast).toHaveBeenCalled())
    })

    it('CartPage: điều hướng sang Checkout khi bấm nút', async () => {
        render(<CartPage />)
        const checkoutBtn = await screen.findByRole('button', { name: /checkout|thanh toán/i })
        fireEvent.click(checkoutBtn)
        expect(holder.current.navigate).toHaveBeenCalledWith('checkout')
    })

    /**
     * Kiểm tra CartPage: xử lý trạng thái giỏ hàng trống và lỗi API (Accept Criteria 5).
     */
    it('CartPage: xử lý trạng thái giỏ hàng trống', async () => {
        const prevFetch = globalThis.fetch
        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const path = input.split('?')[0]
                if (path.includes('/cart')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                ...mockCart,
                                items: [],
                                totalItems: 0,
                                totalQuantity: 0,
                                subtotal: 0,
                                totalAmount: 0,
                            },
                        }),
                        { status: 200 },
                    )
                }
                return prevFetch(input, init)
            }),
        )

        render(<CartPage />)
        await waitFor(() => expect(screen.getByText(/cart is empty|đang trống/i)).toBeVisible())
        const startShoppingBtn = screen.getByRole('button', {
            name: /start shopping|bắt đầu mua sắm/i,
        })
        fireEvent.click(startShoppingBtn)
        expect(holder.current.navigate).toHaveBeenCalledWith('home')
    })

    it('CartPage: xử lý trạng thái lỗi API và cho phép thử lại', async () => {
        sessionStorage.setItem('nexus.session', JSON.stringify({ user }))
        const prevFetch = globalThis.fetch
        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const path = input.split('?')[0]
                if (path.includes('/cart')) {
                    return new Response(
                        JSON.stringify({
                            success: false,
                            message: 'Connection timed out',
                        }),
                        { status: 500 },
                    )
                }
                return prevFetch(input, init)
            }),
        )

        render(<CartPage />)
        await waitFor(() => expect(screen.getByText(/connection timed out/i)).toBeVisible())
        const tryAgainBtn = screen.getByRole('button', { name: /try again|thử lại/i })
        expect(tryAgainBtn).toBeVisible()
    })

    /**
     * Kiểm tra CheckoutPage: kiểm tra dữ liệu bắt buộc (Accept Criteria 4).
     * Bắt lỗi khi bỏ trống hoặc nhập sai định dạng tại Bước 1 và Bước 3.
     */
    it('CheckoutPage: kiểm tra dữ liệu bắt buộc tại Bước 1 (Họ tên, SĐT, Địa chỉ)', async () => {
        render(<CheckoutPage />)
        expect(screen.getByRole('heading', { name: /checkout|thanh toán/i })).toBeVisible()

        // Xóa sạch các trường để kích hoạt validation
        const nameInput = screen.getByPlaceholderText('Nguyen Van A')
        const phoneInput = screen.getByPlaceholderText('0901234567')
        const streetInput = screen.getByPlaceholderText('123 Nguyen Hue Street')

        fireEvent.change(nameInput, { target: { value: '' } })
        fireEvent.change(phoneInput, { target: { value: '123' } }) // SĐT sai định dạng VN
        fireEvent.change(streetInput, { target: { value: '' } })

        // Bấm Next
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))

        // Vẫn ở bước 1 và hiển thị lỗi validation
        await waitFor(() => {
            expect(screen.getByText(/full name is required|họ và tên là bắt buộc/i)).toBeVisible()
            expect(
                screen.getByText(/invalid phone number|số điện thoại không hợp lệ/i),
            ).toBeVisible()
            expect(
                screen.getByText(/street address is required|địa chỉ cụ thể là bắt buộc/i),
            ).toBeVisible()
        })
    })

    it('CheckoutPage: kiểm tra dữ liệu bắt buộc tại Bước 3 (Thông tin thẻ)', async () => {
        render(<CheckoutPage />)
        // Sang bước 2
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        // Sang bước 3
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/stripe/i)).toBeVisible())

        // Nhập sai số thẻ và mã CVV
        const cardInput = screen.getByPlaceholderText('4242 4242 4242 4242')
        const cvvInput = screen.getByPlaceholderText('123')
        fireEvent.change(cardInput, { target: { value: '1234' } })
        fireEvent.change(cvvInput, { target: { value: '9' } })

        // Bấm Next
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))

        // Hiển thị lỗi validation thẻ
        await waitFor(() => {
            expect(
                screen.getByText(/card number must be 16 digits|số thẻ phải gồm đúng 16 chữ số/i),
            ).toBeVisible()
            expect(
                screen.getByText(/cvv must be 3 or 4 digits|mã cvv phải gồm 3 hoặc 4 chữ số/i),
            ).toBeVisible()
        })
    })

    /**
     * Kiểm tra CheckoutPage: flow đa bước (1→2→3→4), mỗi bước có nút Next/Back.
     * Bảo vệ hồi quy luồng mua hàng quan trọng nhất của Buyer.
     */
    it('CheckoutPage: điều hướng qua các bước 1→2→3→4 và hiển thị đúng nội dung', async () => {
        render(<CheckoutPage />)
        // Bước 1: Địa chỉ giao hàng
        expect(screen.getByRole('heading', { name: /checkout|thanh toán/i })).toBeVisible()
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))

        // Bước 2: Phương thức vận chuyển
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())
        // Chọn GHTK
        const radios = screen.getAllByRole('radio')
        fireEvent.click(radios[1])
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))

        // Bước 3: Thanh toán
        await waitFor(() => expect(screen.getByText(/stripe/i)).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))

        // Bước 4: Xác nhận – hiển thị GHTK Standard
        await waitFor(() => expect(screen.getByText(/GHTK Standard/i)).toBeVisible())
    })

    it('CheckoutPage: nút Back đưa về bước trước', async () => {
        render(<CheckoutPage />)
        // Vào bước 2
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())
        // Back về bước 1
        fireEvent.click(screen.getByRole('button', { name: /back|quay lại/i }))
        await waitFor(() =>
            expect(screen.getByRole('heading', { name: /checkout|thanh toán/i })).toBeVisible(),
        )
    })

    it('CheckoutPage: đặt hàng với phương thức VNPAY và chuyển hướng tới sandbox-payment (Accept Criteria 1, 3, 4)', async () => {
        // Cô lập kiểm tra điều hướng UI bằng response fixture; VNPAY production không còn fallback URL mock.
        const createSpy = vi.spyOn(ordersApi, 'createOrder').mockResolvedValueOnce({
            id: 'test-order',
            orderNo: 'ORD-TEST',
            status: 'PENDING_PAYMENT',
            subtotal: 100,
            shippingFee: 0,
            discount: 0,
            total: 100,
            paymentMethod: 'VNPAY',
            paymentUrl: '/sandbox-payment?provider=vnpay',
        })
        render(<CheckoutPage />)
        // Bước 1 -> Bước 2
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        // Bước 2 -> Bước 3
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/VNPAY QR/i)).toBeVisible())

        // Chọn VNPAY
        const vnpayRadio = screen.getByLabelText(/VNPAY QR/i)
        fireEvent.click(vnpayRadio)

        // Bước 3 -> Bước 4
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /place order|đặt hàng/i })).toBeVisible(),
        )

        // Bấm Đặt hàng
        fireEvent.click(screen.getByRole('button', { name: /place order|đặt hàng/i }))

        // Chuyển hướng sang sandbox-payment
        await waitFor(() => {
            expect(holder.current.navigate).toHaveBeenCalledWith(
                'sandbox-payment',
                expect.objectContaining({ provider: 'vnpay' }),
            )
        })
        createSpy.mockRestore()
    })

    it('CheckoutPage: hiển thị thông báo lỗi khi đặt hàng thất bại (Accept Criteria 2, 5)', async () => {
        const spy = vi
            .spyOn(ordersApi, 'createOrder')
            .mockRejectedValueOnce(new Error('Gateway connection timeout'))
        render(<CheckoutPage />)
        // 1 -> 2 -> 3 -> 4
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() => expect(screen.getByText(/stripe/i)).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: /next|tiếp/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /place order|đặt hàng/i })).toBeVisible(),
        )

        // Bấm Đặt hàng
        fireEvent.click(screen.getByRole('button', { name: /place order|đặt hàng/i }))

        // Hiển thị lỗi rõ ràng trên AlertBanner
        await waitFor(() => {
            expect(screen.getByText(/Gateway connection timeout/i)).toBeVisible()
        })
        expect(holder.current.showToast).toHaveBeenCalledWith(
            expect.objectContaining({ type: 'error', message: 'Gateway connection timeout' }),
        )
        spy.mockRestore()
    })

    it('SandboxPaymentPage: hiển thị giao diện sandbox và mô phỏng thanh toán thành công (Accept Criteria 3)', async () => {
        holder.current.pageParams = {
            orderId: 'ORD-TEST-999',
            amount: 450000,
            provider: 'vnpay',
        }
        render(<SandboxPaymentPage />)

        expect(screen.getByText('ORD-TEST-999')).toBeVisible()
        expect(screen.getByText(/Sandbox Environment|Môi trường Sandbox/i)).toBeVisible()

        // Bấm Xác nhận thanh toán thành công
        const successBtn = screen.getByRole('button', {
            name: /Simulate Success|thanh toán thành công/i,
        })
        fireEvent.click(successBtn)

        await waitFor(() => {
            expect(holder.current.navigate).toHaveBeenCalledWith('payment-result', {
                status: 'success',
                orderId: 'ORD-TEST-999',
            })
        })
    })

    it('SandboxPaymentPage: mô phỏng thanh toán thất bại điều hướng về payment-result failed (Accept Criteria 2)', async () => {
        holder.current.pageParams = {
            orderId: 'ORD-TEST-999',
            amount: 450000,
            provider: 'vnpay',
        }
        render(<SandboxPaymentPage />)

        // Bấm Mô phỏng thanh toán thất bại
        const failBtn = screen.getByRole('button', {
            name: /Simulate Failure|thanh toán thất bại/i,
        })
        fireEvent.click(failBtn)

        await waitFor(() => {
            expect(holder.current.navigate).toHaveBeenCalledWith('payment-result', {
                status: 'failed',
                orderId: 'ORD-TEST-999',
                reason: 'Sandbox transaction rejected',
            })
        })
    })

    /**
     * Kiểm tra nút Hủy thanh toán tại SandboxPaymentPage điều hướng về payment-result cancelled.
     */
    it('SandboxPaymentPage: nút hủy thanh toán chuyển sang payment-result cancelled', async () => {
        holder.current.pageParams = {
            orderId: 'ORD-CANCEL-TEST',
            amount: 200000,
            provider: 'vnpay',
        }
        render(<SandboxPaymentPage />)
        const cancelBtn = screen.getByRole('button', {
            name: /cancel payment|hủy thanh toán/i,
        })
        expect(cancelBtn).toBeVisible()
        fireEvent.click(cancelBtn)
        expect(holder.current.navigate).toHaveBeenCalledWith('payment-result', {
            status: 'cancelled',
            orderId: 'ORD-CANCEL-TEST',
        })
    })
})

describe('ProductDetailPage (catalog) – Add to Cart và Tab', () => {
    /**
     * Kiểm tra các luồng trong ProductDetailPage của catalog chưa được cover:
     * - Nút Add to Cart gọi setCartCount và showToast
     * - Nút Buy Now điều hướng đến trang checkout
     * - Chuyển tab mô tả/đánh giá/thông số
     */
    it('click Add to Cart cập nhật cartCount và hiển thị toast', async () => {
        holder.current.pageParams = { id: 'product-1' }
        render(<ProductDetailPage />)
        await screen.findByText('Live description')

        // Bấm nút Thêm vào giỏ
        const addBtn = screen.getByRole('button', { name: /thêm vào giỏ|add to cart/i })
        fireEvent.click(addBtn)
        expect(holder.current.showToast).toHaveBeenCalled()
    })

    it('click Buy Now điều hướng đến trang checkout', async () => {
        holder.current.pageParams = { id: 'product-1' }
        render(<ProductDetailPage />)
        await screen.findByText('Live description')

        const buyBtn = screen.getByRole('button', { name: /mua ngay|buy now/i })
        fireEvent.click(buyBtn)
        expect(holder.current.navigate).toHaveBeenCalledWith('checkout')
    })

    it('chuyển tab sang Specifications hiển thị thông số kỹ thuật', async () => {
        holder.current.pageParams = { id: 'product-1' }
        render(<ProductDetailPage />)
        await screen.findByText('Live description')

        // Tìm tất cả buttons, click tab Specifications
        const allButtons = screen.getAllByRole('button')
        const specsTab = allButtons.find((btn) =>
            /^specifications|^thông số/i.test(btn.textContent || ''),
        )
        expect(specsTab).toBeDefined()
        fireEvent.click(specsTab!)
        // Tab Specifications hiển thị bảng thông số (có row "Weight")
        await waitFor(() => expect(screen.getByText('Weight')).toBeVisible())
    })
})
