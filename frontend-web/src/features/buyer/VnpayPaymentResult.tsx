import { useEffect, useState, useRef } from 'react'
import {
    getPayment,
    createPaymentAttempt,
    redirectToGateway,
    type PaymentResult,
} from '../../api/payments'
import { useApp } from '../../context'
import { Button } from '../../components/UI'
import { fireConfetti } from '../../utils/dialogs'

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
    // Key gắn với attempt nguồn; lỗi mạng giữ nguyên, response FAILED của attempt mới mới cho sinh key tiếp.
    const retryKeyRef = useRef<{ source: string; key: string } | null>(null)
    const retryBusy = useRef(false)
    const [selected, setSelected] = useState({ route: paymentId, id: paymentId })
    const activeId = selected.route === paymentId ? selected.id : paymentId

    useEffect(() => {
        let isCancelled = false
        let timer: ReturnType<typeof setTimeout> | undefined
        async function poll(attempt: number) {
            try {
                const data = await getPayment(activeId)
                if (isCancelled) return
                setResult(data)
                setError(null)
                // Poll attempt nguồn FAILED không được xóa key của retry đang mất ACK.
                if ((data.status === 'PENDING' || data.status === 'PROCESSING') && attempt < 9)
                    timer = setTimeout(() => void poll(attempt + 1), 2000)
            } catch {
                if (!isCancelled)
                    setError(
                        lang === 'vi'
                            ? 'Chưa xác nhận được thanh toán. Hãy đăng nhập và kiểm tra lại.'
                            : 'Unable to verify payment. Sign in and check again.',
                    )
            }
        }
        void poll(0)
        return () => {
            isCancelled = true
            clearTimeout(timer)
        }
    }, [activeId, refresh, lang])

    const current = result?.paymentId === activeId ? result : null
    const success = current?.status === 'SUCCESS'
    const failed = current?.status === 'FAILED'
    const cancelled = current?.status === 'CANCELLED'
    const expired = current?.status === 'EXPIRED'
    const review = current?.status === 'REQUIRES_REVIEW'

    useEffect(() => {
        if (success) {
            fireConfetti({ particleCount: 100, spread: 80 })
        }
    }, [success])

    /**
     * Thử lại thanh toán qua API backend thật (POST /api/v1/payments).
     * 1. Chức năng: Khởi tạo hoặc lấy lại URL thanh toán cho đơn hàng hiện tại nếu còn hiệu lực giữ kho.
     * 2. Đầu vào & kết quả: current.orderId; điều hướng sang cổng paymentUrl thật hoặc hiển thị lỗi từ BE.
     * 3. Luồng xử lý: Giữ nguyên Idempotency-Key giữa các lần bấm nếu request trước chưa rõ kết quả để BE replay attempt đang xử lý,
     *    chỉ sinh key mới khi attempt trước đó đã có kết quả FAILED dứt điểm.
     * 4. Quy tắc: Không cho phép retry khi CANCELLED hoặc EXPIRED (BE chỉ cho phép retry trên FAILED).
     */
    const handleRetry = async () => {
        // Chỉ cho phép retry khi đơn có orderId và trạng thái là FAILED (BE từ chối retry các trạng thái khác)
        if (!current?.orderId || current.status !== 'FAILED' || retryBusy.current) return
        retryBusy.current = true
        setRetrying(true)
        setRetryError(null)
        try {
            if (retryKeyRef.current?.source !== current.paymentId) {
                retryKeyRef.current = { source: current.paymentId, key: crypto.randomUUID() }
            }
            const attempt = await createPaymentAttempt(current.orderId, retryKeyRef.current.key)
            if (attempt.orderId !== current.orderId) throw new Error('Payment order mismatch')
            if (attempt.status === 'PENDING' && attempt.paymentUrl) {
                // Redirect lỗi vẫn giữ key để replay cùng giao dịch, không tạo lần thu tiền khác.
                redirectToGateway(attempt.paymentUrl)
            }
            // Theo dõi đúng attempt BE trả về: FAILED cho phép retry mới, PROCESSING/REVIEW/SUCCESS chặn trả thêm.
            setResult(attempt)
            setSelected({ route: paymentId, id: attempt.paymentId })
            if (attempt.status !== 'PENDING') {
                retryKeyRef.current = null
                // URL cũng trỏ tới attempt mới để reload không quay về kết quả FAILED của giao dịch nguồn.
                navigate('payment-result', { vnp_TxnRef: attempt.paymentId })
                return
            }
            if (attempt.paymentUrl) return
            setRetryError(
                lang === 'vi'
                    ? 'Không nhận được đường dẫn thanh toán từ máy chủ.'
                    : 'Payment gateway URL not received from server.',
            )
        } catch (err: unknown) {
            // Chưa biết kết quả request thì giữ key; backend replay sẽ trả trạng thái attempt chính xác.
            setRetryError(
                err instanceof Error
                    ? err.message
                    : lang === 'vi'
                      ? 'Không thể thử lại thanh toán. Đơn hàng có thể đã hết hạn giữ kho.'
                      : 'Unable to retry payment. Order reservation may have expired.',
            )
        } finally {
            retryBusy.current = false
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
                        : cancelled
                          ? lang === 'vi'
                              ? 'Giao dịch thanh toán đã bị hủy'
                              : 'Payment cancelled'
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
            {cancelled && (
                <p className="mb-4" role="status">
                    {lang === 'vi'
                        ? 'Giao dịch thanh toán đã bị hủy bởi người dùng hoặc cổng thanh toán. Bạn có thể kiểm tra lại lịch sử đơn hàng.'
                        : 'Payment transaction was cancelled. You can review your order history.'}
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
                <p
                    role="alert"
                    className="mb-4 text-sm text-red-600 bg-red-50 p-2.5 rounded-lg border border-red-200"
                >
                    {retryError}
                </p>
            )}
            <div className="flex justify-center gap-3 flex-wrap">
                {!success && !failed && !cancelled && (
                    <Button variant="primary" onClick={() => setRefresh((value) => value + 1)}>
                        {lang === 'vi' ? 'Kiểm tra lại' : 'Check again'}
                    </Button>
                )}
                {failed && !review && current?.orderId && (
                    <Button variant="primary" loading={retrying} onClick={handleRetry}>
                        {lang === 'vi' ? 'Thử lại thanh toán' : 'Retry Payment'}
                    </Button>
                )}
                {/*
                 * Chức năng: Điều hướng người dùng tới màn hình chi tiết đơn hàng sau thanh toán.
                 * Đầu vào: current.orderId từ kết quả thanh toán.
                 * Kết quả: Chuyển sang màn hình /order-detail với ID đơn hàng.
                 */}
                {current?.orderId && (
                    <Button
                        variant="primary"
                        onClick={() => navigate('order-detail', { id: current.orderId })}
                    >
                        {lang === 'vi' ? 'Xem chi tiết đơn hàng' : 'View order details'}
                    </Button>
                )}
                <Button variant="outline" onClick={() => navigate('order-history')}>
                    {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
                </Button>
            </div>
        </div>
    )
}
