import { useState } from 'react'
import { useApp } from '../../context'
import { Button } from '../../components/UI'
import { startPayment } from '../../api/payments'

/**
 * Tạo/replay VNPAY từ orderId thật; backend kiểm tra winner và giá, không ghi PAID hoặc payment mock vào storage.
 * Chặn click lặp khi chờ API; chỉ redirect đúng sandbox, tiền đã thu thì chuyển sang trang đọc kết quả backend.
 */
export function WinnerPaymentButton({
    orderId,
    idempotencyKey,
    label,
}: {
    orderId: string
    idempotencyKey?: string
    label?: string
}) {
    const { lang, navigate } = useApp()
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)

    /** Request chỉ có ID đơn (và Idempotency-Key mới khi retry); BE tự kiểm tra giá live và giữ kho. */
    async function pay() {
        if (busy) return
        setBusy(true)
        setError(null)
        try {
            const payment = idempotencyKey
                ? await startPayment(orderId, idempotencyKey)
                : await startPayment(orderId)
            if (payment.status !== 'PENDING') {
                navigate('payment-result', { vnp_TxnRef: payment.paymentId })
                return
            }
            if (!payment.paymentUrl) throw new Error('Missing payment URL')
            const url = new URL(payment.paymentUrl)
            if (
                url.origin !== 'https://sandbox.vnpayment.vn' ||
                url.pathname !== '/paymentv2/vpcpay.html' ||
                url.username ||
                url.password ||
                url.hash
            )
                throw new Error('Invalid payment URL')
            window.location.assign(url.href)
        } catch {
            setError(
                lang === 'vi'
                    ? 'Chưa thể mở thanh toán. Hãy kiểm tra trạng thái đơn hoặc thử lại; không tạo giao dịch khác nếu đã trả tiền.'
                    : 'Unable to open payment. Check your order or retry; do not start another transaction if you already paid.',
            )
        } finally {
            setBusy(false)
        }
    }

    return (
        <div className="mt-4">
            <Button variant="primary" disabled={busy} onClick={() => void pay()}>
                {busy
                    ? lang === 'vi'
                        ? 'Đang kiểm tra…'
                        : 'Checking…'
                    : label || (lang === 'vi' ? 'Thanh toán VNPAY' : 'Pay with VNPAY')}
            </Button>
            {error && (
                <p role="alert" className="mt-2 text-sm text-red-600">
                    {error}
                </p>
            )}
        </div>
    )
}
