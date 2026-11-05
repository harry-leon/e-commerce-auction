import { useEffect, useState } from 'react'
import { ordersApi } from '../../api/orders'
import { useApp } from '../../context'
import { Button, CheckCircle, Package } from '../../components/UI'
import type { Order } from '../../types'

/**
 * Chức năng: Hiển thị kết quả tra cứu đơn hàng khi URL chỉ mang orderId (không có paymentId VNPAY).
 * Đầu vào: orderId (mã đơn hàng từ route/params), pageParams từ Context.
 * Kết quả: Xác thực trạng thái đơn hàng trực tiếp từ API backend có chứng thực; không suy đoán đã thu tiền từ query browser.
 * Luồng xử lý:
 *   1. Lấy orderId, nếu rỗng hiển thị thông báo alert yêu cầu vào lịch sử đơn hàng.
 *   2. Gọi ordersApi.getOrderDetailV2(orderId) từ Commerce Service backend.
 *   3. Nếu API lỗi (401, 404, lỗi mạng), ghi nhận error và hiển thị alert, không fallback sang kho mock.
 *   4. Nếu đơn COD hoặc chưa thanh toán, hiển thị tiêu đề "Đơn hàng đã được ghi nhận" thay vì "Thanh toán thành công".
 * Quy tắc và lý do:
 *   - Tuyệt đối không tin cậy query URL để xác nhận đã thu tiền.
 *   - Giữ nút "Lịch sử đơn hàng" cho mọi trường hợp để người mua có thể đối soát.
 * Lỗi và giới hạn: Không tự động refresh token nếu phiên hết hạn; báo lỗi để người mua đăng nhập lại.
 */
export function VerifiedOrderResult({ orderId }: { orderId: string }) {
    const { lang, navigate } = useApp()
    const [order, setOrder] = useState<Order | null>(null)
    const [error, setError] = useState(false)

    useEffect(() => {
        let cancelled = false
        if (!orderId) return

        // API này không fallback mock; 401/404 và lỗi mạng không được biến thành đơn đã xác nhận.
        void ordersApi
            .getOrderDetailV2(orderId)
            .then((data) => {
                if (!cancelled) setOrder(data)
            })
            .catch(() => {
                if (!cancelled) setError(true)
            })

        return () => {
            cancelled = true
        }
    }, [orderId])

    const isPaid = order && (order.status === 'PAID' || order.status === 'COMPLETED')
    const isUnverified = !orderId || (error && !order)

    return (
        <div className="max-w-lg mx-auto text-center py-12">
            {!isUnverified && order && (
                <div className="flex justify-center mb-4">
                    <div className="w-16 h-16 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center">
                        {isPaid ? <CheckCircle size={36} /> : <Package size={36} />}
                    </div>
                </div>
            )}

            <h1 className="text-2xl font-bold mb-3 text-gray-900">
                {order
                    ? isPaid
                        ? lang === 'vi'
                            ? 'Thanh toán thành công'
                            : 'Payment successful'
                        : lang === 'vi'
                          ? 'Đơn hàng đã được ghi nhận'
                          : 'Order received'
                    : lang === 'vi'
                      ? 'Chưa xác nhận được đơn hàng'
                      : 'Order not verified'}
            </h1>

            {order && (
                <div className="text-sm font-medium text-gray-600 mb-6 font-mono bg-gray-50 py-2.5 px-4 rounded-xl border border-gray-200 inline-block">
                    {order.id} ·{' '}
                    <span
                        className={
                            isPaid
                                ? 'text-emerald-600 font-semibold'
                                : order.status === 'CANCELLED'
                                  ? 'text-red-600 font-semibold'
                                  : 'text-blue-600 font-semibold'
                        }
                    >
                        {order.status}
                    </span>
                </div>
            )}

            {isUnverified && (
                <p
                    role="alert"
                    className="text-sm text-amber-600 mb-6 bg-amber-50 p-3 rounded-lg border border-amber-200"
                >
                    {lang === 'vi'
                        ? 'Thiếu mã đơn hoặc chưa tải được dữ liệu. Hãy đăng nhập và kiểm tra lịch sử đơn hàng.'
                        : 'The order ID is missing or unavailable. Sign in and check your order history.'}
                </p>
            )}

            <div className="flex justify-center gap-3 mt-4">
                <Button variant="outline" onClick={() => navigate('order-history')}>
                    {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
                </Button>
                {order && (
                    <Button variant="primary" onClick={() => navigate('home')}>
                        {lang === 'vi' ? 'Tiếp tục mua sắm' : 'Continue shopping'}
                    </Button>
                )}
            </div>
        </div>
    )
}
