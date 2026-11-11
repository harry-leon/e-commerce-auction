import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { WinnerPaymentButton } from '../features/buyer/WinnerPaymentButton'
import { startPayment, type PaymentResult } from '../api/payments'

const state = vi.hoisted(() => ({ lang: 'vi', navigate: vi.fn() }))
vi.mock('../context', () => ({ useApp: () => state }))
vi.mock('../api/payments', () => ({ startPayment: vi.fn() }))
const payment: PaymentResult = {
    paymentId: 'payment-1',
    orderId: 'order-1',
    status: 'PENDING',
    amount: 100000,
    currency: 'VND',
    paymentUrl: null,
    expiresAt: '2026-11-11T00:00:00Z',
}

beforeEach(() => vi.resetAllMocks())

/** Tiền đã thu/chờ kho đi đến màn hình đọc backend; không mở lại URL để trả tiền lần hai. */
it.each(['PROCESSING', 'REQUIRES_REVIEW'] as const)(
    'opens backend result for %s',
    async (status) => {
        vi.mocked(startPayment).mockResolvedValue({ ...payment, status })
        render(<WinnerPaymentButton orderId={payment.orderId} />)
        fireEvent.click(screen.getByRole('button'))
        await waitFor(() =>
            expect(state.navigate).toHaveBeenCalledWith('payment-result', {
                vnp_TxnRef: payment.paymentId,
            }),
        )
        expect(startPayment).toHaveBeenCalledWith(payment.orderId)
    },
)

/** Server trả URL ngoài sandbox phải bị chặn, kể cả host có tên gần giống VNPAY. */
it('rejects an untrusted payment redirect', async () => {
    vi.mocked(startPayment).mockResolvedValue({
        ...payment,
        paymentUrl: 'https://sandbox.vnpayment.vn.attacker.test/pay',
    })
    render(<WinnerPaymentButton orderId={payment.orderId} />)
    fireEvent.click(screen.getByRole('button'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa thể mở thanh toán')
    expect(state.navigate).not.toHaveBeenCalled()
})

/** Mạng lỗi chỉ báo chưa mở được payment, không tạo mock hoặc tự xác nhận PAID từ client. */
it('reports upstream errors without a demo fallback', async () => {
    vi.mocked(startPayment).mockRejectedValue(new Error('Unavailable'))
    render(<WinnerPaymentButton orderId={payment.orderId} />)
    fireEvent.click(screen.getByRole('button'))
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(state.navigate).not.toHaveBeenCalled()
})
