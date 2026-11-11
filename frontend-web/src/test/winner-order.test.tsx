/**
 * Bộ kiểm thử cho giao diện Đơn hàng Thắng đấu giá (Winner Order Page) - [LD-S2-3].
 *
 * Chức năng:
 *   Kiểm tra toàn bộ luồng nghiệp vụ của người thắng cuộc trên màn hình AuctionPaymentPage:
 *   1. Hiển thị chi tiết phiên đấu giá đã thắng, giá chốt và đếm ngược thời hạn thanh toán (AC1).
 *   2. Kiểm soát quyền truy cập: Chỉ winner thực sự mới được thao tác form giao hàng và checkout.
 *   3. Nhập thông tin vận chuyển và chuyển hướng sang cổng thanh toán VNPAY sandbox (AC2).
 *   4. Đảm bảo nguyên tắc One Settlement = One Order: Không tạo trùng đơn hàng nếu đã có orderId liên kết (AC2).
 *   5. Xử lý chính xác trạng thái quá hạn thanh toán Payment Timeout: Khóa form và vô hiệu hóa nút checkout (AC3).
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuctionPaymentPage } from '../features/auction/AuctionPages'
import {
    getAuctionSettlement,
    getWinnerAuctionDetails,
    type AuctionSettlement,
    type WinnerAuctionDetails,
} from '../api/auctions'
import { ordersApi, type CreateOrderResponse } from '../api/orders'
import { startPayment, type PaymentResult } from '../api/payments'
import type { AppState, User } from '../types'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))
vi.mock('../api/auctions', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../api/auctions')>()
    return {
        ...actual,
        getAuctionSettlement: vi.fn(),
        getWinnerAuctionDetails: vi.fn(),
    }
})
vi.mock('../api/orders', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../api/orders')>()
    return {
        ...actual,
        ordersApi: {
            ...actual.ordersApi,
            createOrder: vi.fn(),
        },
    }
})
vi.mock('../api/payments', () => ({
    startPayment: vi.fn(),
}))

describe('[LD-S2-3] Winner Order Page, Shipping Information & Payment Checkout', () => {
    const mockWinner = {
        id: 'user-winner-1',
        fullName: 'Nguyễn Văn Thắng',
        email: 'winner@nexus.com',
        role: 'buyer',
    } as unknown as User

    const futureDeadline = new Date(Date.now() + 24 * 3600 * 1000).toISOString()
    const pastDeadline = new Date(Date.now() - 3600 * 1000).toISOString()

    const sampleSettlement: AuctionSettlement = {
        id: 'settlement-uuid-001',
        auctionId: 'auc-winner-101',
        winnerId: 'user-winner-1',
        finalPrice: 15000000,
        result: 'SUCCESS',
        orderId: null,
        paymentDeadline: futureDeadline,
        settledAt: new Date(Date.now() - 10000).toISOString(),
    }

    const sampleProductDetails: WinnerAuctionDetails = {
        auctionId: 'auc-winner-101',
        productId: 'prod-rare-keyboard',
        productName: 'Bàn phím cơ Custom Cao Cấp Limited',
        productImage: 'https://images.unsplash.com/photo-1595225476474-87563907a212?w=100',
    }

    const samplePaymentResult: PaymentResult = {
        paymentId: 'pay-vnp-999',
        orderId: 'ORD-AUCTION-001',
        status: 'PENDING',
        amount: 15000000,
        currency: 'VND',
        paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=pay-vnp-999',
        expiresAt: futureDeadline,
    }

    beforeEach(() => {
        vi.resetAllMocks()
        holder.current = {
            lang: 'vi',
            user: mockWinner,
            pageParams: { auctionId: 'auc-winner-101' },
            navigate: vi.fn(),
            showToast: vi.fn(),
        } as unknown as AppState

        vi.mocked(getAuctionSettlement).mockResolvedValue(sampleSettlement)
        vi.mocked(getWinnerAuctionDetails).mockResolvedValue(sampleProductDetails)
        vi.mocked(ordersApi.createOrder).mockResolvedValue({
            id: 'ORD-AUCTION-001',
            orderNo: 'ORD-AUCTION-001',
            status: 'PENDING_PAYMENT',
            subtotal: 15000000,
            shippingFee: 0,
            discount: 0,
            total: 15000000,
            paymentMethod: 'VNPAY',
        } as CreateOrderResponse)
        vi.mocked(startPayment).mockResolvedValue(samplePaymentResult)
    })

    /**
     * AC1: Người thắng cuộc xem được thông tin chi tiết phiên đấu giá đã thắng và đếm ngược deadline thanh toán.
     * Tình huống nghiệp vụ:
     * - Winner mở trang thanh toán của phiên đã thắng.
     * - Hệ thống tải settlement và thông tin sản phẩm thật.
     * - Hiển thị giá chốt, tên sản phẩm, ảnh đại diện và hạn thanh toán còn hiệu lực.
     */
    it('hiển thị chi tiết phiên thắng, tên sản phẩm, giá chốt và hạn thanh toán cho người thắng (AC1)', async () => {
        render(<AuctionPaymentPage />)

        // Kiểm tra nạp dữ liệu settlement và catalog thành công
        expect(await screen.findByText('Bạn đã thắng phiên đấu giá')).toBeVisible()
        expect(screen.getByText('Bàn phím cơ Custom Cao Cấp Limited')).toBeVisible()
        expect(screen.getAllByText(/15\.000\.000/)[0]).toBeVisible()
        expect(screen.getByText('Hạn thanh toán')).toBeVisible()

        // Tên người nhận mặc định điền từ họ tên trong profile người thắng
        const recipientInput = screen.getByLabelText('Tên người nhận') as HTMLInputElement
        expect(recipientInput.value).toBe('Nguyễn Văn Thắng')
    })

    /**
     * AC1: Bảo vệ quyền riêng tư và phân quyền winner.
     * Tình huống nghiệp vụ:
     * - Người dùng không phải là người chiến thắng cố tình truy cập link checkout của phiên người khác.
     * - Hệ thống chặn và hiển thị thông báo lỗi 'Settlement này thuộc về người đặt giá khác.'
     */
    it('từ chối hiển thị form giao hàng khi người dùng không phải người thắng cuộc', async () => {
        holder.current = {
            ...holder.current,
            user: { id: 'other-user-999', fullName: 'Người khác', role: 'buyer', email: 'other@nexus.com' } as unknown as User,
        }

        render(<AuctionPaymentPage />)

        expect(await screen.findByText('Settlement này thuộc về người đặt giá khác.')).toBeVisible()
        expect(screen.queryByText('Thông tin giao hàng')).not.toBeInTheDocument()
    })

    /**
     * AC3: Xử lý chính xác trạng thái trên UI khi phiên bị quá hạn thanh toán (Payment Timeout).
     * Tình huống nghiệp vụ:
     * - Phiên đấu giá có paymentDeadline đã trôi qua (quá hạn).
     * - Hệ thống hiển thị cảnh báo lỗi 'Đã quá hạn thanh toán. Không thể tiếp tục checkout.'
     * - Khóa toàn bộ các ô nhập địa chỉ, điện thoại và vô hiệu hóa nút thanh toán.
     */
    it('hiển thị cảnh báo hết hạn, khóa form giao hàng và nút thanh toán khi đã quá hạn thanh toán (AC3)', async () => {
        vi.mocked(getAuctionSettlement).mockResolvedValue({
            ...sampleSettlement,
            paymentDeadline: pastDeadline,
        })

        render(<AuctionPaymentPage />)

        expect(await screen.findByText('Đã quá hạn thanh toán. Không thể tiếp tục checkout.')).toBeVisible()

        // Form giao hàng và nút checkout bị vô hiệu hóa
        const nameInput = screen.getByLabelText('Tên người nhận')
        const phoneInput = screen.getByLabelText('Số điện thoại')
        const addressInput = screen.getByLabelText('Địa chỉ giao hàng')
        const payButton = screen.getByRole('button', { name: /Tiếp tục đến thanh toán Sandbox/i })

        expect(nameInput).toBeDisabled()
        expect(phoneInput).toBeDisabled()
        expect(addressInput).toBeDisabled()
        expect(payButton).toBeDisabled()
    })

    /**
     * AC2: Nhập thông tin giao hàng và chuyển hướng thanh toán Sandbox thành công.
     * Tình huống nghiệp vụ:
     * - Người thắng điền đầy đủ số điện thoại và địa chỉ nhận hàng.
     * - Nhấn thanh toán: Hệ thống kiểm tra đơn hàng được Backend tự động khởi tạo từ settlement và gọi cổng thanh toán VNPAY sandbox.
     * - Backend từ chối đơn AUCTION qua POST /orders nên FE không gọi ordersApi.createOrder.
     * - Redirect trình duyệt sang URL sandbox.vnpayment.vn đã được ký hợp lệ.
     */
    it('cho phép nhập thông tin giao hàng và chuyển hướng thanh toán Sandbox thành công (AC2)', async () => {
        // Lần đầu tải trang chưa có orderId, sau khi submit tải lại settlement server đã có orderId
        vi.mocked(getAuctionSettlement)
            .mockResolvedValueOnce(sampleSettlement)
            .mockResolvedValueOnce({
                ...sampleSettlement,
                orderId: 'ORD-AUCTION-001',
            })

        render(<AuctionPaymentPage />)

        await screen.findByText('Bạn đã thắng phiên đấu giá')

        // Điền thông tin giao hàng
        const phoneInput = screen.getByLabelText('Số điện thoại')
        const addressInput = screen.getByLabelText('Địa chỉ giao hàng')
        fireEvent.change(phoneInput, { target: { value: '0912345678' } })
        fireEvent.change(addressInput, { target: { value: '456 Hai Bà Trưng, Phường Bến Nghé, Quận 1' } })

        const submitButton = screen.getByRole('button', { name: /Tiếp tục đến thanh toán Sandbox/i })
        fireEvent.click(submitButton)

        // Không gọi ordersApi.createOrder vì Backend chỉ hỗ trợ đơn REGULAR cho POST /orders
        expect(ordersApi.createOrder).not.toHaveBeenCalled()

        // Kỳ vọng gọi khởi tạo thanh toán VNPAY với orderId sinh từ Backend
        await waitFor(() => {
            expect(startPayment).toHaveBeenCalledWith('ORD-AUCTION-001', expect.any(String))
        })
    })

    /**
     * Thông báo chờ khi đơn hàng đang được backend khởi tạo tự động.
     * Tình huống nghiệp vụ:
     * - Khi scheduler handover backend chưa xử lý xong, orderId vẫn là null.
     * - FE hiển thị thông báo rõ ràng cho người dùng thay vì gọi lệnh tạo đơn sai hợp đồng.
     */
    it('hiển thị thông báo khi đơn hàng đang được backend khởi tạo tự động', async () => {
        vi.mocked(getAuctionSettlement).mockResolvedValue(sampleSettlement)

        render(<AuctionPaymentPage />)

        await screen.findByText('Bạn đã thắng phiên đấu giá')

        const phoneInput = screen.getByLabelText('Số điện thoại')
        const addressInput = screen.getByLabelText('Địa chỉ giao hàng')
        fireEvent.change(phoneInput, { target: { value: '0912345678' } })
        fireEvent.change(addressInput, { target: { value: '456 Hai Bà Trưng, Phường Bến Nghé, Quận 1' } })

        const submitButton = screen.getByRole('button', { name: /Tiếp tục đến thanh toán Sandbox/i })
        fireEvent.click(submitButton)

        expect(await screen.findByText(/Đơn hàng đang được hệ thống tạo tự động/)).toBeVisible()
        expect(ordersApi.createOrder).not.toHaveBeenCalled()
        expect(startPayment).not.toHaveBeenCalled()
    })

    /**
     * AC2: Nguyên tắc One Settlement = One Order (Chống tạo trùng đơn hàng).
     * Tình huống nghiệp vụ:
     * - Khi settlement đã có sẵn orderId (ví dụ người dùng quay lại trang sau khi đã bấm thanh toán lần đầu).
     * - Nhấn thanh toán: Hệ thống tái sử dụng orderId cũ, gọi trực tiếp startPayment và KHÔNG gọi ordersApi.createOrder.
     */
    it('không tạo trùng đơn hàng khi settlement đã có liên kết orderId từ trước (One Settlement = One Order) (AC2)', async () => {
        vi.mocked(getAuctionSettlement).mockResolvedValue({
            ...sampleSettlement,
            orderId: 'ORD-PREVIOUSLY-CREATED-888',
        })

        render(<AuctionPaymentPage />)

        expect(await screen.findByText(/Đơn hàng đã được liên kết với settlement này/)).toBeVisible()
        expect(screen.getByText('ORD-PREVIOUSLY-CREATED-888')).toBeVisible()

        // Nhấn nút thanh toán lại
        const payButton = screen.getByRole('button', { name: /Tiếp tục đến thanh toán Sandbox/i })
        fireEvent.click(payButton)

        // Không được phép gọi createOrder tạo mới đơn hàng
        await waitFor(() => {
            expect(startPayment).toHaveBeenCalledWith('ORD-PREVIOUSLY-CREATED-888', expect.any(String))
        })
        expect(ordersApi.createOrder).not.toHaveBeenCalled()
    })

    /**
     * Báo lỗi validation khi thông tin giao hàng chưa đầy đủ.
     * Tình huống nghiệp vụ:
     * - Người dùng để trống số điện thoại hoặc địa chỉ.
     * - Hệ thống hiển thị cảnh báo lỗi và không gọi backend.
     */
    it('hiển thị thông báo lỗi khi thông tin giao hàng bị bỏ trống', async () => {
        render(<AuctionPaymentPage />)

        await screen.findByText('Bạn đã thắng phiên đấu giá')

        // Để trống số điện thoại và địa chỉ
        const submitButton = screen.getByRole('button', { name: /Tiếp tục đến thanh toán Sandbox/i })
        fireEvent.click(submitButton)

        expect(await screen.findByText('Vui lòng điền đầy đủ thông tin giao hàng trước khi tiếp tục.')).toBeVisible()
        expect(ordersApi.createOrder).not.toHaveBeenCalled()
        expect(startPayment).not.toHaveBeenCalled()
    })
})
