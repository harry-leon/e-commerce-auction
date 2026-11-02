import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { PaymentResultPage } from '../features/buyer/BuyerPages'
import { getPayment, type PaymentResult } from '../api/payments'
import type { AppState } from '../types'

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
