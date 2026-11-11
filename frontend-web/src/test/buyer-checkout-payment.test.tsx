import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../types'
import { DEMO_BUYER } from '../mockData'
import { VerifiedOrderResult } from '../features/buyer/VerifiedOrderResult'
import { ProductDetailPage } from '../features/catalog/ProductDetailPage'
import { CheckoutPage, PaymentResultPage } from '../features/buyer/BuyerPages'
import { ordersApi } from '../api/orders'
import * as cartApi from '../api/cart'
import * as paymentsApi from '../api/payments'
import { ApiClientError, saveSession } from '../api/client'
import { clearLoadCache } from '../hooks/useLoad'

/**
 * Bộ kiểm thử toàn diện luồng Buyer, Cart & Checkout (Task 4 - Quốc Hào):
 * 1. Chức năng: Kiểm tra luồng chọn SKU -> Thêm giỏ -> Checkout; kiểm tra đồng bộ giá BE và cảnh báo chênh lệch;
 *               kết nối Payment Attempt -> Sandbox VNPAY -> Kết quả xác thực và Retry.
 * 2. Tình huống bảo vệ:
 *    - Đảm bảo chọn đúng SKU khi sản phẩm có nhiều biến thể, hiển thị đúng giá và thêm đúng SKU vào giỏ/checkout.
 *    - Cảnh báo khi giá BE tính khác giá hiển thị client (re-validation).
 *    - Không tự ý fallback VNPAY lỗi thành đơn COD hoặc sandbox giả lập.
 *    - Chỉ hiển thị thanh toán thành công theo kết quả Backend đã xác thực, hỗ trợ thử lại (Retry) bằng API thật.
 */

const contextHolder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => contextHolder.current }))

describe('Buyer, Cart và Checkout - Task Quốc Hào', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        clearLoadCache()
        sessionStorage.clear()
        localStorage.clear()
        window.history.replaceState(null, '', '/')

        // Khởi tạo phiên người mua hợp lệ để gọi API giỏ hàng và đơn hàng có JWT
        saveSession({
            user: DEMO_BUYER,
        })

        contextHolder.current = {
            lang: 'vi',
            setLang: vi.fn(),
            page: 'product-detail',
            navigate: vi.fn(),
            pageParams: { id: 'multi-sku-product' },
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
    })

    /**
     * Tình huống 1: Sản phẩm có nhiều biến thể SKU.
     * Người mua chọn SKU khác nhau -> giá cập nhật tương ứng -> thêm vào giỏ đúng SKU đã chọn.
     */
    it('ProductDetailPage: chọn biến thể SKU, cập nhật giá hiển thị và thêm đúng SKU vào giỏ hàng', async () => {
        const multiSkuProduct = {
            id: 'multi-sku-product',
            name: 'Áo thun thể thao Nexus Pro',
            description: 'Áo thun thoáng khí',
            categoryId: 'cat-fashion',
            sellerId: 'seller-1',
            status: 'ACTIVE',
            auctionEnabled: false,
            createdAt: '2026-11-01T00:00:00Z',
            skus: [
                {
                    id: 'sku-size-s',
                    skuCode: 'TSHIRT-S',
                    price: 150000,
                    originalPrice: 200000,
                    status: 'ACTIVE',
                },
                {
                    id: 'sku-size-l',
                    skuCode: 'TSHIRT-L',
                    price: 180000,
                    originalPrice: 220000,
                    status: 'ACTIVE',
                },
            ],
            images: [{ id: 'img-1', url: '/tshirt.jpg', sortOrder: 0 }],
        }

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string) => {
                if (input.includes('/products/multi-sku-product')) {
                    return new Response(JSON.stringify({ success: true, data: multiSkuProduct }), {
                        status: 200,
                    })
                }
                if (input.includes('/products')) {
                    return new Response(JSON.stringify({ success: true, data: [] }), {
                        status: 200,
                    })
                }
                return new Response(JSON.stringify({ success: true, data: null }), { status: 200 })
            }),
        )

        const addItemSpy = vi.spyOn(cartApi, 'addItemToCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [],
            totalItems: 1,
            totalQuantity: 2,
            subtotal: 360000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 360000,
            expiresAt: null,
        })

        render(<ProductDetailPage />)

        // Đợi dữ liệu sản phẩm tải xong
        expect(await screen.findByText('Áo thun thoáng khí')).toBeVisible()

        // Ban đầu chọn SKU đầu tiên (TSHIRT-S, giá 150.000)
        expect(screen.getAllByText(/150\.000/).length).toBeGreaterThanOrEqual(1)

        // Chọn biến thể TSHIRT-L (giá 180.000)
        const variantLBtn = screen.getByRole('radio', { name: /TSHIRT-L/i })
        expect(variantLBtn).toBeVisible()
        fireEvent.click(variantLBtn)

        // Giá cập nhật lên 180.000
        await waitFor(() => {
            expect(screen.getAllByText(/180\.000/).length).toBeGreaterThanOrEqual(1)
        })

        // Bấm Thêm vào giỏ
        const addBtn = screen.getByRole('button', { name: /thêm vào giỏ|add to cart/i })
        fireEvent.click(addBtn)

        // Kiểm tra đúng SKU ID 'sku-size-l' được gửi đi
        await waitFor(() => {
            expect(addItemSpy).toHaveBeenCalledWith('sku-size-l', 1)
        })

        addItemSpy.mockRestore()
    })

    /**
     * Tình huống 2: Mua ngay với biến thể SKU đã chọn chuyển thẳng tới Checkout.
     */
    it('ProductDetailPage: bấm Mua ngay với SKU đã chọn đồng bộ giỏ hàng và chuyển sang checkout', async () => {
        const multiSkuProduct = {
            id: 'multi-sku-product',
            name: 'Áo thun thể thao Nexus Pro',
            description: 'Áo thun thoáng khí',
            categoryId: 'cat-fashion',
            sellerId: 'seller-1',
            status: 'ACTIVE',
            auctionEnabled: false,
            createdAt: '2026-11-01T00:00:00Z',
            skus: [
                { id: 'sku-size-s', skuCode: 'TSHIRT-S', price: 150000, status: 'ACTIVE' },
                { id: 'sku-size-l', skuCode: 'TSHIRT-L', price: 180000, status: 'ACTIVE' },
            ],
            images: [{ id: 'img-1', url: '/tshirt.jpg', sortOrder: 0 }],
        }

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string) => {
                if (input.includes('/products/multi-sku-product')) {
                    return new Response(JSON.stringify({ success: true, data: multiSkuProduct }), {
                        status: 200,
                    })
                }
                return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 })
            }),
        )

        const addItemSpy = vi.spyOn(cartApi, 'addItemToCart').mockResolvedValueOnce({} as any)

        render(<ProductDetailPage />)
        expect(await screen.findByText('Áo thun thoáng khí')).toBeVisible()

        // Chọn TSHIRT-L
        const variantLBtn = screen.getByRole('radio', { name: /TSHIRT-L/i })
        fireEvent.click(variantLBtn)

        // Bấm Mua ngay
        const buyBtn = screen.getByRole('button', { name: /mua ngay|buy now/i })
        fireEvent.click(buyBtn)

        await waitFor(() => {
            expect(addItemSpy).toHaveBeenCalledWith('sku-size-l', 1)
            expect(contextHolder.current.navigate).toHaveBeenCalledWith('checkout')
        })

        addItemSpy.mockRestore()
    })

    /**
     * Tình huống 3: Checkout Preview thông báo khi giá Backend tính khác với giá hiển thị client.
     */
    it('CheckoutPage: hiển thị cảnh báo khi giá Backend tính khác với giá đang hiển thị và cập nhật tổng tiền', async () => {
        // Mock getActiveCart trả về giỏ hàng với giá cũ (500.000₫)
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'cart-item-1',
                    skuId: 'sku-1',
                    skuCode: 'NK-AF1-WHT',
                    productName: 'Nike Air Force 1',
                    thumbnailUrl: '/nike.png',
                    unitPrice: 500000,
                    quantity: 1,
                    stockAvailable: 5,
                    isAvailable: true,
                    subtotal: 500000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 500000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 500000,
            expiresAt: null,
        })

        // Mock checkoutPreview trả về giá mới (550.000₫) kèm cảnh báo
        const previewSpy = vi.spyOn(ordersApi, 'checkoutPreview').mockResolvedValueOnce({
            subtotal: 550000,
            discountAmount: 0,
            shippingFee: 55000,
            taxAmount: 0,
            totalAmount: 605000,
            appliedVoucher: null,
            isOrderable: true,
            warnings: ['Giá SKU NK-AF1-WHT đã thay đổi; đơn sẽ dùng giá hiện tại'],
        })

        render(<CheckoutPage />)

        // Đợi nạp giỏ hàng
        await screen.findByText('Nike Air Force 1')

        // Đi từ Bước 1 -> 2 -> 3 -> 4
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText('Thẻ quốc tế')).toBeVisible())

        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() =>
            expect(screen.getByRole('heading', { name: /xác nhận|confirmation/i })).toBeVisible(),
        )

        // Đợi preview kích hoạt và hiển thị thông báo chênh lệch giá từ BE
        await waitFor(() => {
            expect(previewSpy).toHaveBeenCalled()
            expect(
                screen.getByText(/Giá SKU NK-AF1-WHT đã thay đổi; đơn sẽ dùng giá hiện tại/i),
            ).toBeVisible()
        })

        previewSpy.mockRestore()
    })

    /**
     * Tình huống 4: Khi VNPAY bị lỗi, KHÔNG âm thầm chuyển sang COD hoặc tạo đơn sandbox mock;
     * hiển thị chính xác lỗi backend trả về để người mua biết và thử lại.
     */
    it('CheckoutPage: VNPAY lỗi không được âm thầm tạo COD hoặc chuyển sang thanh toán mô phỏng', async () => {
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'item-1',
                    skuId: 'sku-1',
                    skuCode: 'NK-AF1',
                    productName: 'Nike Air Force',
                    thumbnailUrl: '/nike.png',
                    unitPrice: 500000,
                    quantity: 1,
                    stockAvailable: 5,
                    isAvailable: true,
                    subtotal: 500000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 500000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 500000,
            expiresAt: null,
        })

        // Mock createOrder ném lỗi 503 chưa cấu hình merchant VNPAY
        const createSpy = vi
            .spyOn(ordersApi, 'createOrder')
            .mockRejectedValueOnce(
                new ApiClientError(
                    'Chưa cấu hình merchant VNPAY sandbox hợp lệ',
                    'PAYMENT_NOT_CONFIGURED',
                    503,
                ),
            )

        render(<CheckoutPage />)
        await screen.findByText('Nike Air Force')

        // Bước 1 -> 2
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        // Bước 2 -> 3
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/VNPAY QR/i)).toBeVisible())

        // Chọn VNPAY
        fireEvent.click(screen.getByLabelText(/VNPAY QR/i))

        // Bước 3 -> 4
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /đặt hàng|place order/i })).toBeVisible(),
        )

        // Bấm Đặt hàng
        fireEvent.click(screen.getByRole('button', { name: /đặt hàng|place order/i }))

        // Kỳ vọng: Hiển thị đúng lỗi từ BE, tuyệt đối không chuyển sang sandbox-payment giả lập
        await waitFor(() => {
            expect(screen.getByText(/Chưa cấu hình merchant VNPAY sandbox hợp lệ/i)).toBeVisible()
        })
        expect(contextHolder.current.navigate).not.toHaveBeenCalledWith(
            'sandbox-payment',
            expect.anything(),
        )

        createSpy.mockRestore()
    })

    /**
     * Tình huống 5: Tạo đơn VNPAY -> nếu response chưa có paymentUrl thì gọi createPaymentAttempt -> chuyển hướng cổng thật.
     */
    it('CheckoutPage: nối tạo đơn thành công sang Payment Attempt để lấy URL thanh toán VNPAY', async () => {
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'item-1',
                    skuId: 'sku-1',
                    skuCode: 'NK-AF1',
                    productName: 'Nike Air Force',
                    thumbnailUrl: '/nike.png',
                    unitPrice: 500000,
                    quantity: 1,
                    stockAvailable: 5,
                    isAvailable: true,
                    subtotal: 500000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 500000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 500000,
            expiresAt: null,
        })

        // Mock createOrder thành công nhưng chưa có paymentUrl sẵn
        const createSpy = vi.spyOn(ordersApi, 'createOrder').mockResolvedValueOnce({
            id: 'real-order-uuid',
            orderNo: 'ORD-202611-001',
            status: 'PENDING_PAYMENT',
            subtotal: 500000,
            shippingFee: 55000,
            discount: 0,
            total: 555000,
            paymentMethod: 'VNPAY',
        })

        // Mock createPaymentAttempt trả về paymentUrl VNPAY sandbox
        const attemptSpy = vi.spyOn(paymentsApi, 'createPaymentAttempt').mockResolvedValueOnce({
            paymentId: 'txn-ref-123',
            orderId: 'real-order-uuid',
            status: 'PENDING',
            amount: 555000,
            currency: 'VND',
            paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=55500000',
            expiresAt: '2026-11-10T12:00:00Z',
        })
        const redirectSpy = vi.spyOn(paymentsApi, 'redirectToGateway').mockImplementation(() => {})

        render(<CheckoutPage />)
        await screen.findByText('Nike Air Force')

        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/VNPAY QR/i)).toBeVisible())

        fireEvent.click(screen.getByLabelText(/VNPAY QR/i))
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /đặt hàng|place order/i })).toBeVisible(),
        )

        fireEvent.click(screen.getByRole('button', { name: /đặt hàng|place order/i }))

        await waitFor(() => {
            expect(createSpy).toHaveBeenCalled()
            expect(attemptSpy).toHaveBeenCalledWith('real-order-uuid', expect.any(String))
            expect(redirectSpy).toHaveBeenCalledWith(
                'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=55500000',
            )
        })

        createSpy.mockRestore()
        attemptSpy.mockRestore()
        redirectSpy.mockRestore()
    })

    /**
     * Tình huống 6: Tại PaymentResultPage khi thanh toán thất bại hoặc hết hạn,
     * cho phép người mua Thử lại thanh toán (Retry Payment) thông qua createPaymentAttempt.
     */
    it('VnpayPaymentResult: thanh toán thất bại hỗ trợ Thử lại thanh toán qua createPaymentAttempt', async () => {
        contextHolder.current.pageParams = { vnp_TxnRef: 'txn-failed-001' }

        vi.spyOn(paymentsApi, 'getPayment').mockResolvedValueOnce({
            paymentId: 'txn-failed-001',
            orderId: 'order-retry-uuid',
            status: 'FAILED',
            amount: 500000,
            currency: 'VND',
            paymentUrl: null,
            expiresAt: '2026-11-10T12:00:00Z',
        })

        const attemptSpy = vi.spyOn(paymentsApi, 'createPaymentAttempt').mockResolvedValueOnce({
            paymentId: 'txn-new-002',
            orderId: 'order-retry-uuid',
            status: 'PENDING',
            amount: 500000,
            currency: 'VND',
            paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=txn-new-002',
            expiresAt: '2026-11-10T12:15:00Z',
        })
        const redirectSpy = vi.spyOn(paymentsApi, 'redirectToGateway').mockImplementation(() => {})

        render(<PaymentResultPage />)

        expect(await screen.findByText('Thanh toán không thành công')).toBeVisible()

        // Bấm Thử lại thanh toán
        const retryBtn = screen.getByRole('button', { name: /thử lại thanh toán|retry payment/i })
        expect(retryBtn).toBeVisible()
        fireEvent.click(retryBtn)

        await waitFor(() => {
            expect(attemptSpy).toHaveBeenCalledWith('order-retry-uuid', expect.any(String))
            expect(redirectSpy).toHaveBeenCalledWith(
                'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=txn-new-002',
            )
        })

        attemptSpy.mockRestore()
        redirectSpy.mockRestore()
    })

    /**
     * Tình huống 7: VerifiedOrderResult không tự tạo đơn PAID giả lập khi URL mang cờ status=success.
     */
    it('VerifiedOrderResult: không tự tạo đơn PAID giả lập khi URL mang status=success mà BE chưa xác nhận', async () => {
        contextHolder.current.pageParams = { orderId: 'ORD-UNKNOWN-999', status: 'success' }

        vi.spyOn(ordersApi, 'getOrderDetailV2').mockRejectedValueOnce(new Error('Order not found'))
        vi.spyOn(ordersApi, 'getOrderById').mockResolvedValueOnce(null)

        render(<PaymentResultPage />)

        // Không hiển thị "Thanh toán thành công"
        expect(await screen.findByRole('alert')).toBeVisible()
        expect(screen.queryByText(/Thanh toán thành công/i)).not.toBeInTheDocument()
    })

    /**
     * Tình huống 8: Tạo đơn thành công nhưng attempt lỗi -> lưu đơn đã tạo; bấm lại sẽ tiếp tục trên đúng đơn đó
     * thay vì gọi createOrder lần hai làm mất giỏ hàng.
     */
    it('CheckoutPage: khi tạo attempt lỗi, giữ đơn đã tạo và tiếp tục thanh toán trên đúng đơn đó mà không tạo đơn mới', async () => {
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'item-1',
                    skuId: 'sku-1',
                    skuCode: 'NK-AF1',
                    productName: 'Nike Air Force',
                    thumbnailUrl: '/nike.png',
                    unitPrice: 500000,
                    quantity: 1,
                    stockAvailable: 5,
                    isAvailable: true,
                    subtotal: 500000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 500000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 500000,
            expiresAt: null,
        })

        const createSpy = vi.spyOn(ordersApi, 'createOrder').mockResolvedValueOnce({
            id: 'order-first-success-uuid',
            orderNo: 'ORD-SUCCESS-001',
            status: 'PENDING_PAYMENT',
            subtotal: 500000,
            shippingFee: 55000,
            discount: 0,
            total: 555000,
            paymentMethod: 'VNPAY',
        })

        // Lần 1: attempt ném lỗi
        // Lần 2: attempt thành công
        const attemptSpy = vi
            .spyOn(paymentsApi, 'createPaymentAttempt')
            .mockRejectedValueOnce(new Error('Mạng thanh toán tạm bận'))
            .mockResolvedValueOnce({
                paymentId: 'txn-second-001',
                orderId: 'order-first-success-uuid',
                status: 'PENDING',
                amount: 555000,
                currency: 'VND',
                paymentUrl:
                    'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=txn-second-001',
                expiresAt: '2026-11-10T12:00:00Z',
            })

        const redirectSpy = vi.spyOn(paymentsApi, 'redirectToGateway').mockImplementation(() => {})

        const { unmount } = render(<CheckoutPage />)
        await screen.findByText('Nike Air Force')

        // Bước 1 -> 2
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())

        // Bước 2 -> 3
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/VNPAY QR/i)).toBeVisible())

        // Chọn VNPAY
        fireEvent.click(screen.getByLabelText(/VNPAY QR/i))
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /đặt hàng|place order/i })).toBeVisible(),
        )

        // Bấm đặt hàng lần 1 -> attempt lỗi
        fireEvent.click(screen.getByRole('button', { name: /đặt hàng|place order/i }))
        expect(await screen.findByText('Mạng thanh toán tạm bận')).toBeVisible()
        expect(createSpy).toHaveBeenCalledTimes(1)
        expect(attemptSpy).toHaveBeenCalledTimes(1)

        // Nút chuyển thành "Thử lại thanh toán"
        // Mô phỏng reload: React state bị mất và giỏ server đã chuyển đổi thành đơn, không còn item.
        const firstAttemptKey = attemptSpy.mock.calls[0][1]
        unmount()
        vi.mocked(cartApi.getActiveCart).mockResolvedValueOnce({
            cartId: 'cart',
            userId: 'user',
            status: 'ACTIVE',
            items: [],
            totalItems: 0,
            totalQuantity: 0,
            subtotal: 0,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 0,
            expiresAt: null,
        })
        render(<CheckoutPage />)
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        const retryBtn = screen.getByRole('button', { name: /thử lại thanh toán|retry payment/i })
        expect(retryBtn).toBeVisible()

        // Bấm thử lại -> chỉ gọi tiếp createPaymentAttempt trên order-first-success-uuid, KHÔNG gọi createOrder lần 2!
        fireEvent.click(retryBtn)

        await waitFor(() => {
            expect(createSpy).toHaveBeenCalledTimes(1)
            expect(attemptSpy).toHaveBeenCalledTimes(2)
            expect(attemptSpy).toHaveBeenLastCalledWith('order-first-success-uuid', firstAttemptKey)
            expect(redirectSpy).toHaveBeenCalledWith(
                'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=txn-second-001',
            )
        })

        createSpy.mockRestore()
        attemptSpy.mockRestore()
        redirectSpy.mockRestore()
    })

    /**
     * Tình huống 9: Lỗi nghiệp vụ có từ "hết hạn" (ví dụ đơn hết hạn giữ kho) không được hiểu nhầm thành 401 hết phiên đăng nhập.
     * Phiên đăng nhập người dùng phải được giữ nguyên.
     */
    it('CheckoutPage: lỗi nghiệp vụ hết hạn thanh toán không làm xóa session đăng nhập', async () => {
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValueOnce({
            cartId: 'cart-1',
            userId: 'user-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'item-1',
                    skuId: 'sku-1',
                    skuCode: 'NK-AF1',
                    productName: 'Nike Air Force',
                    thumbnailUrl: '/nike.png',
                    unitPrice: 500000,
                    quantity: 1,
                    stockAvailable: 5,
                    isAvailable: true,
                    subtotal: 500000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 500000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 500000,
            expiresAt: null,
        })

        vi.spyOn(ordersApi, 'createOrder').mockRejectedValueOnce(
            new ApiClientError(
                'Thời gian giữ kho đã hết hạn, vui lòng đặt lại',
                'BUSINESS_ERROR',
                400,
            ),
        )

        render(<CheckoutPage />)
        await screen.findByText('Nike Air Force')

        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText(/GHN/i)).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() => expect(screen.getByText('Thẻ quốc tế')).toBeVisible())
        fireEvent.click(screen.getByRole('button', { name: /tiếp theo|next/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /đặt hàng|place order/i })).toBeVisible(),
        )

        fireEvent.click(screen.getByRole('button', { name: /đặt hàng|place order/i }))

        // Hiển thị lỗi nghiệp vụ, nhưng KHÔNG hiển thị modal cảnh báo "Phiên đăng nhập đã hết hạn"
        expect(
            await screen.findByText('Thời gian giữ kho đã hết hạn, vui lòng đặt lại'),
        ).toBeVisible()
        expect(screen.queryByText('Phiên đăng nhập đã hết hạn')).not.toBeInTheDocument()
        expect(sessionStorage.getItem('nexus.session')).not.toBeNull()
    })

    /**
     * Tình huống 10: VnpayPaymentResult không hiển thị nút Thử lại thanh toán khi trạng thái là EXPIRED.
     */
    it('VnpayPaymentResult: không hiển thị nút Thử lại thanh toán khi attempt ở trạng thái EXPIRED', async () => {
        contextHolder.current.pageParams = { vnp_TxnRef: 'txn-expired-001' }

        vi.spyOn(paymentsApi, 'getPayment').mockResolvedValueOnce({
            paymentId: 'txn-expired-001',
            orderId: 'order-expired-uuid',
            status: 'EXPIRED',
            amount: 500000,
            currency: 'VND',
            paymentUrl: null,
            expiresAt: '2026-11-10T12:00:00Z',
        })

        render(<PaymentResultPage />)

        expect(await screen.findByText('Liên kết thanh toán đã hết hạn')).toBeVisible()
        // Không được có nút "Thử lại thanh toán"
        expect(
            screen.queryByRole('button', { name: /thử lại thanh toán|retry payment/i }),
        ).not.toBeInTheDocument()
        // Phải có nút "Lịch sử đơn hàng"
        expect(
            screen.getByRole('button', { name: /lịch sử đơn hàng|order history/i }),
        ).toBeVisible()
    })

    /**
     * Tình huống 11: VnpayPaymentResult không hiển thị nút Thử lại thanh toán khi attempt ở trạng thái CANCELLED
     * (Do Backend từ chối retry các trạng thái khác FAILED và PENDING).
     */
    it('VnpayPaymentResult: không hiển thị nút Thử lại thanh toán khi attempt ở trạng thái CANCELLED', async () => {
        contextHolder.current.pageParams = { vnp_TxnRef: 'txn-cancelled-001' }

        vi.spyOn(paymentsApi, 'getPayment').mockResolvedValueOnce({
            paymentId: 'txn-cancelled-001',
            orderId: 'order-cancelled-uuid',
            status: 'CANCELLED',
            amount: 500000,
            currency: 'VND',
            paymentUrl: null,
            expiresAt: '2026-11-10T12:00:00Z',
        })

        render(<PaymentResultPage />)

        expect(await screen.findByText('Giao dịch thanh toán đã bị hủy')).toBeVisible()
        // Tuyệt đối không hiển thị nút Thử lại thanh toán
        expect(
            screen.queryByRole('button', { name: /thử lại thanh toán|retry payment/i }),
        ).not.toBeInTheDocument()
        // Phải có nút "Lịch sử đơn hàng"
        expect(
            screen.getByRole('button', { name: /lịch sử đơn hàng|order history/i }),
        ).toBeVisible()
    })

    /**
     * Tình huống 12: VnpayPaymentResult giữ nguyên Idempotency-Key khi retry gặp lỗi mạng chưa rõ kết quả,
     * để lần bấm tiếp theo gửi cùng key cho Backend replay thay vì tạo key mới gây PAYMENT_IN_PROGRESS.
     */
    it('VnpayPaymentResult: giữ nguyên Idempotency-Key khi retry gặp lỗi mạng và người dùng bấm thử lại lần nữa', async () => {
        contextHolder.current.pageParams = { vnp_TxnRef: 'txn-failed-001' }

        vi.spyOn(paymentsApi, 'getPayment').mockResolvedValueOnce({
            paymentId: 'txn-failed-001',
            orderId: 'order-failed-uuid',
            status: 'FAILED',
            amount: 500000,
            currency: 'VND',
            paymentUrl: null,
            expiresAt: '2026-11-10T12:00:00Z',
        })

        const attemptSpy = vi
            .spyOn(paymentsApi, 'createPaymentAttempt')
            // Lần 1: Lỗi mạng rớt kết nối
            .mockRejectedValueOnce(new Error('Network error'))
            // Lần 2: Thành công trả về sandbox url
            .mockResolvedValueOnce({
                paymentId: 'txn-new-002',
                orderId: 'order-failed-uuid',
                status: 'PENDING',
                amount: 500000,
                currency: 'VND',
                paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?token=abc',
                expiresAt: '2026-11-10T12:15:00Z',
            })

        const redirectSpy = vi.spyOn(paymentsApi, 'redirectToGateway').mockImplementation(() => {})

        render(<PaymentResultPage />)

        expect(await screen.findByText('Thanh toán không thành công')).toBeVisible()
        const retryBtn = screen.getByRole('button', { name: /thử lại thanh toán|retry payment/i })

        // Bấm retry lần 1 (bị lỗi mạng)
        fireEvent.click(retryBtn)
        expect(await screen.findByText('Network error')).toBeVisible()
        expect(attemptSpy).toHaveBeenCalledTimes(1)
        const firstAttemptKey = attemptSpy.mock.calls[0][1]
        expect(firstAttemptKey).toBeDefined()

        // Bấm retry lần 2 (người dùng thử lại sau khi mạng ổn định)
        fireEvent.click(retryBtn)
        await waitFor(() => expect(attemptSpy).toHaveBeenCalledTimes(2))
        const secondAttemptKey = attemptSpy.mock.calls[1][1]

        // Phải gửi CÙNG Idempotency-Key để BE replay attempt cũ
        expect(secondAttemptKey).toBe(firstAttemptKey)
        expect(redirectSpy).toHaveBeenCalledWith(
            'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?token=abc',
        )
    })
    /** Mất ACK rồi attempt mới FAILED: replay cùng key một lần, sau kết quả rõ ràng retry tiếp phải có key khác. */
    it('retry mất ACK rồi FAILED không bị replay vô hạn key cũ', async () => {
        contextHolder.current.pageParams = { vnp_TxnRef: 'failed-old' }
        vi.spyOn(paymentsApi, 'getPayment').mockImplementation(async (id) => ({
            paymentId: id,
            orderId: 'order',
            status: id === 'review-new' ? 'REQUIRES_REVIEW' : 'FAILED',
            amount: 100,
            currency: 'VND',
            paymentUrl: null,
            expiresAt: '',
        }))
        const attempt = vi
            .spyOn(paymentsApi, 'createPaymentAttempt')
            .mockRejectedValueOnce(new Error('lost ACK'))
            .mockResolvedValueOnce({
                paymentId: 'failed-new',
                orderId: 'order',
                status: 'FAILED',
                amount: 100,
                currency: 'VND',
                paymentUrl: null,
                expiresAt: '',
            })
            .mockResolvedValueOnce({
                paymentId: 'review-new',
                orderId: 'order',
                status: 'REQUIRES_REVIEW',
                amount: 100,
                currency: 'VND',
                paymentUrl: null,
                expiresAt: '',
            })
        render(<PaymentResultPage />)
        fireEvent.click(await screen.findByRole('button', { name: /thử lại thanh toán/i }))
        await screen.findByText('lost ACK')
        fireEvent.click(screen.getByRole('button', { name: /thử lại thanh toán/i }))
        await waitFor(() => expect(paymentsApi.getPayment).toHaveBeenCalledWith('failed-new'))
        fireEvent.click(screen.getByRole('button', { name: /thử lại thanh toán/i }))
        await screen.findByText('Thanh toán cần đối soát')
        expect(attempt.mock.calls[1][1]).toBe(attempt.mock.calls[0][1])
        expect(attempt.mock.calls[2][1]).not.toBe(attempt.mock.calls[1][1])
        expect(
            screen.queryByRole('button', { name: /thử lại thanh toán/i }),
        ).not.toBeInTheDocument()
    })

    /** Khi retry trả trạng thái không cần URL, UI phải theo dõi attempt BE trả về và ngừng mời trả tiền lần hai. */
    it.each(['PROCESSING', 'REQUIRES_REVIEW', 'SUCCESS'] as const)(
        'retry nhận %s không báo thiếu URL',
        async (status) => {
            contextHolder.current.pageParams = { vnp_TxnRef: 'failed-old' }
            vi.spyOn(paymentsApi, 'getPayment').mockImplementation(async (id) => ({
                paymentId: id,
                orderId: 'order',
                status: id === 'failed-old' ? 'FAILED' : status,
                amount: 100,
                currency: 'VND',
                paymentUrl: null,
                expiresAt: '',
            }))
            vi.spyOn(paymentsApi, 'createPaymentAttempt').mockResolvedValue({
                paymentId: 'current-new',
                orderId: 'order',
                status,
                amount: 100,
                currency: 'VND',
                paymentUrl: null,
                expiresAt: '',
            })
            render(<PaymentResultPage />)
            fireEvent.click(await screen.findByRole('button', { name: /thử lại thanh toán/i }))
            await waitFor(() => expect(paymentsApi.getPayment).toHaveBeenCalledWith('current-new'))
            expect(
                screen.queryByText('Không nhận được đường dẫn thanh toán từ máy chủ.'),
            ).not.toBeInTheDocument()
            expect(
                screen.queryByRole('button', { name: /thử lại thanh toán/i }),
            ).not.toBeInTheDocument()
        },
    )

    /** Local PAID và query success không thay thế xác thực BE khi API đơn lỗi. */
    it('không lấy PAID từ localStorage khi BE chưa xác nhận', async () => {
        vi.spyOn(ordersApi, 'getOrderDetailV2').mockRejectedValue(new Error('Backend unavailable'))
        const local = vi
            .spyOn(ordersApi, 'getOrderById')
            .mockResolvedValue({ id: 'ORD-LOCAL', status: 'PAID' } as NonNullable<
                Awaited<ReturnType<typeof ordersApi.getOrderById>>
            >)
        render(<VerifiedOrderResult orderId="ORD-LOCAL" />)
        await screen.findByRole('alert')
        expect(local).not.toHaveBeenCalled()
        expect(screen.queryByText('Thanh toán thành công')).not.toBeInTheDocument()
    })

    /** Chuyển từ đơn đã trả sang đơn API lỗi không được giữ nhãn thành công của đơn cũ. */
    it('bỏ kết quả cũ khi đổi orderId', async () => {
        vi.spyOn(ordersApi, 'getOrderDetailV2')
            .mockResolvedValueOnce({ id: 'paid', status: 'PAID' } as Awaited<
                ReturnType<typeof ordersApi.getOrderDetailV2>
            >)
            .mockRejectedValueOnce(new Error('Unavailable'))
        const view = render(<VerifiedOrderResult orderId="paid" />)
        await screen.findByText('Thanh toán thành công')
        view.rerender(<VerifiedOrderResult orderId="unknown" />)
        await screen.findByRole('alert')
        expect(screen.queryByText('Thanh toán thành công')).not.toBeInTheDocument()
    })
})
