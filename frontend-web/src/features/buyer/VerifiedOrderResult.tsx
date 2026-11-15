import { useEffect, useState } from 'react'
import { ordersApi } from '../../api/orders'
import { useApp } from '../../context'
import { Button, CheckCircle, Package } from '../../components/UI'
import type { Order } from '../../types'
import { fireConfetti } from '../../utils/dialogs'

/**
 * Chỉ công nhận trạng thái đơn từ Commerce; query thành công và đơn local PAID không chứng minh đã thu tiền.
 * Đổi ID hoặc API lỗi bỏ kết quả cũ và báo chưa xác nhận, không fallback sang dữ liệu demo.
 */
export function VerifiedOrderResult({ orderId }: { orderId: string }) {
    const { lang, navigate, user, pageParams } = useApp()
    const [order, setOrder] = useState<Order | null>(null)
    const [error, setError] = useState(false)
    const queryStatus = String(pageParams?.status || '')

    useEffect(() => {
        let cancelled = false
        setOrder(null)
        setError(false)
        if (!orderId) {
            setError(true)
            return
        }

        // Bước 1: Thử truy vấn đơn hàng từ Backend Commerce Service trước
        ordersApi
            .getOrderDetailV2(orderId)
            .then((data) => {
                if (!cancelled && data) {
                    setOrder(data)
                    setError(false)
                }
            })
            .catch(() => {
                if (!cancelled) setError(true)
            })

        return () => {
            cancelled = true
        }
    }, [orderId, queryStatus, user, lang])

    const current = order?.id === orderId ? order : null
    const isPaid = current && (current.status === 'PAID' || current.status === 'COMPLETED')
    const isUnverified = !orderId || (error && !current)

    useEffect(() => {
        if (isPaid) {
            fireConfetti({ particleCount: 100, spread: 80 })
        }
    }, [isPaid])

    return (
        <div className="max-w-lg mx-auto text-center py-12 px-4 animate-in fade-in zoom-in-95 duration-300">
            {!isUnverified && current && (
                <div className="flex justify-center mb-5">
                    <div
                        className={`w-20 h-20 rounded-3xl shadow-lg flex items-center justify-center transition-transform hover:scale-105 duration-300 ${
                            isPaid
                                ? 'bg-gradient-to-tr from-emerald-500 to-green-400 text-white shadow-emerald-500/25 ring-8 ring-emerald-50'
                                : 'bg-gradient-to-tr from-blue-500 to-indigo-400 text-white shadow-blue-500/25 ring-8 ring-blue-50'
                        }`}
                    >
                        {isPaid ? <CheckCircle size={40} className="stroke-[2.5]" /> : <Package size={40} className="stroke-[2.5]" />}
                    </div>
                </div>
            )}

            <h1 className="text-2xl font-bold mb-3 text-gray-900">
                {current
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

            {current && (
                <div className="text-sm font-medium text-gray-600 mb-6 font-mono bg-gray-50 py-2.5 px-4 rounded-xl border border-gray-200 inline-block">
                    {current.orderNo || current.id} ·{' '}
                    <span
                        className={
                            isPaid
                                ? 'text-emerald-600 font-semibold'
                                : current.status === 'CANCELLED'
                                  ? 'text-red-600 font-semibold'
                                  : 'text-blue-600 font-semibold'
                        }
                    >
                        {current.status}
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
                {current && (
                    <Button variant="primary" onClick={() => navigate('home')}>
                        {lang === 'vi' ? 'Tiếp tục mua sắm' : 'Continue shopping'}
                    </Button>
                )}
            </div>
        </div>
    )
}
