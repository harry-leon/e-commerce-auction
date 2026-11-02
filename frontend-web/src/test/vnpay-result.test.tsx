import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { PaymentResultPage } from '../features/buyer/BuyerPages'
import { getPayment, type PaymentResult } from '../api/payments'
import type { AppState } from '../types'
import type { Order } from '../types'
import { ordersApi } from '../api/orders'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))
vi.mock('../api/payments', () => ({ getPayment: vi.fn() }))
const pending: PaymentResult = {
    paymentId: 'payment-test',
    orderId: 'order-test',
    status: 'PENDING',
    amount: 50000,
    currency: 'VND',
    paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html',
    expiresAt: '2026-10-29T00:15:00Z',
}

beforeEach(() => {
    vi.resetAllMocks()
    holder.current = {
        lang: 'vi',
        navigate: vi.fn(),
        pageParams: { vnp_TxnRef: pending.paymentId, status: 'success', vnp_ResponseCode: '00' },
    } as unknown as AppState
})

/** Query status=success/ResponseCode=00 không được tự hiển thị đã trả tiền khi IPN chưa cập nhật DB. */
it('waits for backend confirmation even if browser query claims success', async () => {
    vi.mocked(getPayment).mockResolvedValue(pending)
    render(<PaymentResultPage />)
    expect(await screen.findByText('Đang chờ xác nhận thanh toán')).toBeVisible()
    expect(screen.queryByText('Thanh toán đã được xác nhận')).not.toBeInTheDocument()
    vi.mocked(getPayment).mockResolvedValue({ ...pending, status: 'SUCCESS' })
    fireEvent.click(screen.getByRole('button', { name: 'Kiểm tra lại' }))
    expect(await screen.findByText('Thanh toán đã được xác nhận')).toBeVisible()
})

/** Backend FAILED là kết quả cuối, query từ provider không được ghi đè bằng success. */
it('shows persisted failure and does not simulate payment retries', async () => {
    vi.mocked(getPayment).mockResolvedValue({ ...pending, status: 'FAILED' })
    render(<PaymentResultPage />)
    expect(await screen.findByText('Thanh toán không thành công')).toBeVisible()
    expect(screen.queryByText('Thanh toán đã được xác nhận')).not.toBeInTheDocument()
})

/** API lỗi/phiên hết hạn chỉ báo chưa xác nhận được; unmount không tiếp tục cập nhật UI từ response cũ. */
it('shows an error instead of fake success when polling fails', async () => {
    vi.mocked(getPayment).mockRejectedValue(new Error('Unauthorized'))
    const rendered = render(<PaymentResultPage />)
    await waitFor(() => expect(screen.getByRole('alert')).toBeVisible())
    expect(screen.queryByText('Thanh toán đã được xác nhận')).not.toBeInTheDocument()
    rendered.unmount()
})

/** Thiếu mã tra cứu thì mọi query thành công/thất bại đều không thể chứng minh đã thu tiền. */
it.each(['success', 'SUCCESS', 'failed', 'pending', 'cancelled', 'unknown'])(
    'does not trust a %s query without a transaction ID',
    async (status) => {
        holder.current.pageParams = { status, vnp_ResponseCode: '00' }
        render(<PaymentResultPage />)
        expect(screen.getByRole('alert')).toBeVisible()
        expect(screen.queryByText('Thanh toán đã được xác nhận')).not.toBeInTheDocument()
        expect(getPayment).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', { name: 'Lịch sử đơn hàng' }))
        expect(holder.current.navigate).toHaveBeenCalledWith('order-history')
    },
)

/** COD đã tạo chỉ xác nhận đơn, không tuyên bố thanh toán thành công theo query browser. */
it('loads a persisted COD order without claiming payment was received', async () => {
    holder.current.pageParams = { orderId: 'order-cod', status: 'success' }
    const spy = vi.spyOn(ordersApi, 'getOrderDetailV2').mockResolvedValue({
        id: 'order-cod',
        status: 'PENDING_PAYMENT',
        paymentMethod: 'COD',
    } as Order)
    render(<PaymentResultPage />)
    expect(await screen.findByText('Đơn hàng đã được ghi nhận')).toBeVisible()
    expect(spy).toHaveBeenCalledWith('order-cod')
    expect(screen.queryByText(/Thanh toán thành công/)).not.toBeInTheDocument()
    spy.mockRestore()
})

/** Không dùng kho mock khi API đơn lỗi; mã đơn có thật trong URL cũng không chứng minh đã tạo thành công. */
it('keeps an order unverified when the authenticated lookup fails', async () => {
    holder.current.pageParams = { orderId: 'order-missing', status: 'success' }
    const spy = vi.spyOn(ordersApi, 'getOrderDetailV2').mockRejectedValue(new Error('Not found'))
    render(<PaymentResultPage />)
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.queryByText('Đơn hàng đã được ghi nhận')).not.toBeInTheDocument()
    spy.mockRestore()
})
