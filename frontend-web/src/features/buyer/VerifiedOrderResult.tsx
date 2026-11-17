import { useEffect, useState } from 'react'
import { ordersApi } from '../../api/orders'
import { useApp } from '../../context'
import { Button, CheckCircle, Package } from '../../components/UI'
import type { Order } from '../../types'
import { fireConfetti } from '../../utils/dialogs'

/**
 * Định dạng nhãn trạng thái đơn hàng thân thiện (DoD UI sạch, không lộ enum thô).
 * Đầu vào: status (PAID, PENDING_PAYMENT, CANCELLED...), lang ('vi' | 'en').
 * Kết quả: Chuỗi tiếng Việt/Anh thân thiện với người dùng cuối.
 */
function formatStatusLabel(status: string, lang: string = 'vi'): string {
    const mapVi: Record<string, string> = {
        PAID: 'Đã thanh toán',
        PENDING_PAYMENT: 'Chờ thanh toán',
        PROCESSING: 'Đang xử lý',
        SHIPPED: 'Đang giao hàng',
        DELIVERED: 'Đã giao hàng',
        COMPLETED: 'Hoàn thành',
        CANCELLED: 'Đã hủy',
        REFUNDED: 'Đã hoàn tiền',
    }
    const mapEn: Record<string, string> = {
        PAID: 'Paid',
        PENDING_PAYMENT: 'Pending Payment',
        PROCESSING: 'Processing',
        SHIPPED: 'Shipped',
        DELIVERED: 'Delivered',
        COMPLETED: 'Completed',
        CANCELLED: 'Cancelled',
        REFUNDED: 'Refunded',
    }
    return (lang === 'vi' ? mapVi[status] : mapEn[status]) || status
}

/**
 * Màn hình kết quả thanh toán đã xác thực (VerifiedOrderResult).
 * Chức năng: Hiển thị kết quả thanh toán từ Commerce Backend và cung cấp điều hướng sang Chi tiết đơn / Lịch sử đơn.
 * Đầu vào: orderId từ URL routeParams.
 * Kết quả: Trạng thái thanh toán (Thành công / Chờ xử lý), mã đơn hàng, nút "Xem chi tiết đơn hàng" và "Lịch sử đơn hàng".
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
    const isCod = current?.paymentMethod === 'COD'
    const isPaid = current && (current.status === 'PAID' || current.status === 'COMPLETED')
    const isSuccess = isPaid || isCod
    const isUnverified = !orderId || (error && !current)

    useEffect(() => {
        if (isSuccess) {
            fireConfetti({ particleCount: 100, spread: 80 })
        }
    }, [isSuccess])

    return (
        <div className="max-w-lg mx-auto text-center py-12 px-4 animate-in fade-in zoom-in-95 duration-300">
            {!isUnverified && current && (
                <div className="flex justify-center mb-5">
                    <div
                        className={`w-20 h-20 rounded-3xl shadow-lg flex items-center justify-center transition-transform hover:scale-105 duration-300 ${
                            isSuccess
                                ? 'bg-gradient-to-tr from-emerald-500 to-green-400 text-white shadow-emerald-500/25 ring-8 ring-emerald-50'
                                : 'bg-gradient-to-tr from-blue-500 to-indigo-400 text-white shadow-blue-500/25 ring-8 ring-blue-50'
                        }`}
                    >
                        {isSuccess ? (
                            <CheckCircle size={40} className="stroke-[2.5]" />
                        ) : (
                            <Package size={40} className="stroke-[2.5]" />
                        )}
                    </div>
                </div>
            )}

            <h1 className="text-2xl font-bold mb-3 text-gray-900">
                {current
                    ? isPaid
                        ? lang === 'vi'
                            ? 'Thanh toán thành công'
                            : 'Payment successful'
                        : isCod
                          ? lang === 'vi'
                              ? 'Đặt hàng thành công'
                              : 'Order placed successfully'
                          : lang === 'vi'
                            ? 'Đơn hàng đã được ghi nhận'
                            : 'Order received'
                    : lang === 'vi'
                      ? 'Chưa xác nhận được đơn hàng'
                      : 'Order not verified'}
            </h1>

            {current && (
                <div className="text-sm font-medium text-gray-600 mb-4 font-mono bg-gray-50 py-2.5 px-4 rounded-xl border border-gray-200 inline-block">
                    {current.orderNo ||
                        (current.id.length > 8
                            ? '#' + current.id.slice(0, 8).toUpperCase()
                            : current.id)}{' '}
                    ·{' '}
                    <span
                        className={
                            isPaid
                                ? 'text-emerald-600 font-semibold'
                                : isCod && current.status === 'PENDING_PAYMENT'
                                  ? 'text-emerald-600 font-semibold'
                                  : current.status === 'CANCELLED'
                                    ? 'text-red-600 font-semibold'
                                    : 'text-blue-600 font-semibold'
                        }
                    >
                        {isCod && current.status === 'PENDING_PAYMENT'
                            ? lang === 'vi'
                                ? 'Đã ghi nhận (Thanh toán khi nhận hàng)'
                                : 'Order Placed (Cash on Delivery)'
                            : formatStatusLabel(current.status, lang)}
                    </span>
                </div>
            )}

            {isCod && current && (
                <p className="text-sm text-gray-600 mb-6 bg-emerald-50 p-3.5 rounded-xl border border-emerald-200 text-emerald-800">
                    {lang === 'vi'
                        ? 'Cảm ơn quý khách! Đơn hàng đã được tiếp nhận và người bán đang tiến hành chuẩn bị bưu phẩm. Quý khách vui lòng thanh toán tiền mặt cho nhân viên giao hàng khi nhận kiện hàng.'
                        : 'Thank you! Your order has been placed and the seller is preparing your package. Please pay cash to the courier upon delivery.'}
                </p>
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

            <div className="flex justify-center gap-3 mt-4 flex-wrap">
                {current && (
                    <Button
                        variant="primary"
                        onClick={() => navigate('order-detail', { id: current.id })}
                    >
                        {lang === 'vi' ? 'Xem chi tiết đơn hàng' : 'View order details'}
                    </Button>
                )}
                <Button variant="outline" onClick={() => navigate('order-history')}>
                    {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
                </Button>
                {current && (
                    <Button variant="ghost" onClick={() => navigate('home')}>
                        {lang === 'vi' ? 'Tiếp tục mua sắm' : 'Continue shopping'}
                    </Button>
                )}
            </div>
        </div>
    )
}
