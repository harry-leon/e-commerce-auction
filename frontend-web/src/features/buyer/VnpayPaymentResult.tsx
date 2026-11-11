import { useEffect, useState } from 'react'
import { getPayment, createPaymentAttempt, redirectToGateway, type PaymentResult } from '../../api/payments'
import { useApp } from '../../context'
import { Button } from '../../components/UI'

/**
 * VNPAY return chỉ mang TxnRef để tra cứu; bỏ qua vnp_ResponseCode/status do browser truyền.
 * Poll tối đa 10 lần, chờ 2 giây giữa các lần; unmount/đổi ID hủy timer và bỏ response cũ.
 * Chỉ hiển thị thành công khi backend đã lưu SUCCESS từ IPN; lỗi mạng/401 không bị đổi thành success giả.
 * EXPIRED dừng poll tự động, báo link hết hạn nhưng vẫn cho kiểm tra IPN đến chậm; không tự hướng dẫn trả tiền lần hai.
 * PROCESSING tiếp tục chờ xác nhận kho; REQUIRES_REVIEW thông báo đối soát và không cung cấp nút trả tiền lần nữa.
 * Cung cấp tính năng Thử lại thanh toán (Retry Payment) thông qua API backend thật (POST /api/v1/payments).
 */
export function VnpayPaymentResult({ paymentId }: { paymentId: string }) {
    const { lang, navigate } = useApp()
    const [result, setResult] = useState<PaymentResult | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [refresh, setRefresh] = useState(0)
    const [retrying, setRetrying] = useState(false)
    const [retryError, setRetryError] = useState<string | null>(null)
    useEffect(() => {
        let cancelled = false
        let timer: ReturnType<typeof setTimeout> | undefined
        async function poll(attempt: number) {
            try {
                const data = await getPayment(paymentId)
                if (cancelled) return
                setResult(data)
                setError(null)
                if ((data.status === 'PENDING' || data.status === 'PROCESSING') && attempt < 9)
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
    const expired = current?.status === 'EXPIRED'
    const review = current?.status === 'REQUIRES_REVIEW'

    /**
     * Thử lại thanh toán qua API backend thật (POST /api/v1/payments).
     * 1. Chức năng: Khởi tạo hoặc lấy lại URL thanh toán cho đơn hàng hiện tại nếu còn hiệu lực giữ kho.
     * 2. Đầu vào & kết quả: current.orderId; điều hướng sang cổng paymentUrl thật hoặc hiển thị lỗi từ BE.
     * 3. Luồng xử lý: Gọi createPaymentAttempt(orderId) -> nhận paymentUrl -> chuyển hướng trình duyệt.
     * 4. Quy tắc: Không giả lập chuyển trạng thái thành công; mọi kết quả đều theo máy trạng thái backend.
     */
    const handleRetry = async () => {
        if (!current?.orderId) return
        setRetrying(true)
        setRetryError(null)
        try {
            const retryKey =
                typeof crypto !== 'undefined' && crypto.randomUUID
                    ? crypto.randomUUID()
                    : undefined
            const attempt = await createPaymentAttempt(current.orderId, retryKey)
            if (attempt.paymentUrl) {
                redirectToGateway(attempt.paymentUrl)
                return
            }
            setRetryError(
                lang === 'vi'
                    ? 'Không nhận được đường dẫn thanh toán từ máy chủ.'
                    : 'Payment gateway URL not received from server.',
            )
        } catch (err: unknown) {
            setRetryError(
                err instanceof Error
                    ? err.message
                    : lang === 'vi'
                      ? 'Không thể thử lại thanh toán. Đơn hàng có thể đã hết hạn giữ kho.'
                      : 'Unable to retry payment. Order reservation may have expired.',
            )
        } finally {
            setRetrying(false)
        }
    }
    return (
        <div className="max-w-lg mx-auto text-center py-12">
            <h1 className="text-2xl font-bold mb-4">
                {review
                    ? lang === 'vi'
                        ? 'Thanh toán cần đối soát'
                        : 'Payment requires review'
                    : success
                      ? lang === 'vi'
                          ? 'Thanh toán đã được xác nhận'
                          : 'Payment confirmed'
                      : failed
                        ? lang === 'vi'
                            ? 'Thanh toán không thành công'
                            : 'Payment failed'
                        : expired
                          ? lang === 'vi'
                              ? 'Liên kết thanh toán đã hết hạn'
                              : 'Payment link expired'
                          : lang === 'vi'
                            ? 'Đang chờ xác nhận thanh toán'
                            : 'Waiting for payment confirmation'}
            </h1>
            {(review || current?.status === 'PROCESSING') && (
                <p className="mb-4" role="status">
                    {review
                        ? lang === 'vi'
                            ? 'Đã ghi nhận tiền thanh toán nhưng chưa thể xác nhận đơn hàng. Vui lòng liên hệ hỗ trợ, không thanh toán lại. Hoàn tiền chưa được thực hiện tự động.'
                            : 'Your payment was received, but the order could not be confirmed. Contact support and do not pay again. No automatic refund has been issued.'
                        : lang === 'vi'
                          ? 'Đã ghi nhận thanh toán, đang xác nhận hàng cho đơn đấu giá. Không thanh toán lại.'
                          : 'Payment received. Confirming your auction inventory. Do not pay again.'}
                </p>
            )}
            {expired && (
                <p className="mb-4" role="status">
                    {lang === 'vi'
                        ? 'Chưa có kết quả thanh toán cuối cùng. Nếu đã thanh toán, hãy kiểm tra lại hoặc liên hệ hỗ trợ trước khi tạo giao dịch mới.'
                        : 'The final payment result is not available. If you have paid, check again or contact support before starting another payment.'}
                </p>
            )}
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
            {retryError && (
                <p role="alert" className="mb-4 text-sm text-red-600 bg-red-50 p-2.5 rounded-lg border border-red-200">
                    {retryError}
                </p>
            )}
            <div className="flex justify-center gap-3 flex-wrap">
                {!success && !failed && (
                    <Button variant="primary" onClick={() => setRefresh((value) => value + 1)}>
                        {lang === 'vi' ? 'Kiểm tra lại' : 'Check again'}
                    </Button>
                )}
                {failed && !review && current?.orderId && (
                    <Button variant="primary" loading={retrying} onClick={handleRetry}>
                        {lang === 'vi' ? 'Thử lại thanh toán' : 'Retry Payment'}
                    </Button>
                )}
                <Button variant="outline" onClick={() => navigate('order-history')}>
                    {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
                </Button>
            </div>
        </div>
    )
}
