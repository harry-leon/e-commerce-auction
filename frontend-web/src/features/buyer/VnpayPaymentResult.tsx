import { useEffect, useState } from 'react'
import { getPayment, type PaymentResult } from '../../api/payments'
import { useApp } from '../../context'
import { Button } from '../../components/UI'

/**
 * VNPAY return chỉ mang TxnRef để tra cứu; bỏ qua vnp_ResponseCode/status do browser truyền.
 * Poll tối đa 10 lần, chờ 2 giây giữa các lần; unmount/đổi ID hủy timer và bỏ response cũ.
 * Chỉ hiển thị thành công khi backend đã lưu SUCCESS từ IPN; lỗi mạng/401 không bị đổi thành success giả.
 */
export function VnpayPaymentResult({ paymentId }: { paymentId: string }) {
    const { lang, navigate } = useApp()
    const [result, setResult] = useState<PaymentResult | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [refresh, setRefresh] = useState(0)
    useEffect(() => {
        let cancelled = false
        let timer: ReturnType<typeof setTimeout> | undefined
        async function poll(attempt: number) {
            try {
                const data = await getPayment(paymentId)
                if (cancelled) return
                setResult(data)
                setError(null)
                if (data.status === 'PENDING' && attempt < 9)
                    timer = setTimeout(() => void poll(attempt + 1), 2000)
            } catch {
                if (!cancelled)
                    setError(
                        lang === 'vi'
                            ? 'Chưa xác nhận được thanh toán. Hãy đăng nhập và kiểm tra lại.'
                            : 'Unable to verify payment. Sign in and check again.',
                    )
            }
        }
        void poll(0)
        return () => {
            cancelled = true
            clearTimeout(timer)
        }
    }, [paymentId, refresh, lang])

    const current = result?.paymentId === paymentId ? result : null
    const success = current?.status === 'SUCCESS'
    const failed = current?.status === 'FAILED' || current?.status === 'CANCELLED'
    return (
        <div className="max-w-lg mx-auto text-center py-12">
            <h1 className="text-2xl font-bold mb-4">
                {success
                    ? lang === 'vi'
                        ? 'Thanh toán đã được xác nhận'
                        : 'Payment confirmed'
                    : failed
                      ? lang === 'vi'
                          ? 'Thanh toán không thành công'
                          : 'Payment failed'
                      : lang === 'vi'
                        ? 'Đang chờ xác nhận thanh toán'
                        : 'Waiting for payment confirmation'}
            </h1>
            {current && (
                <p className="mb-4">
                    {lang === 'vi' ? 'Mã đơn hàng: ' : 'Order: '}
                    {current.orderId}
                </p>
            )}
            {error && (
                <p role="alert" className="mb-4">
                    {error}
                </p>
            )}
            {!success && !failed && (
                <Button variant="primary" onClick={() => setRefresh((value) => value + 1)}>
                    {lang === 'vi' ? 'Kiểm tra lại' : 'Check again'}
                </Button>
            )}
            <Button variant="outline" onClick={() => navigate('order-history')}>
                {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
            </Button>
        </div>
    )
}
