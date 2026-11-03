import { useEffect, useState } from 'react'
import { ordersApi } from '../../api/orders'
import { useApp } from '../../context'
import { Button } from '../../components/UI'
import type { Order } from '../../types'

/** Tra đơn thực khi URL không có mã payment; không suy ra đã thu tiền từ query, kể cả đơn COD. */
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
    return (
        <div className="max-w-lg mx-auto text-center py-12">
            <h1 className="text-2xl font-bold mb-4">
                {order
                    ? lang === 'vi'
                        ? 'Đơn hàng đã được ghi nhận'
                        : 'Order received'
                    : lang === 'vi'
                      ? 'Chưa xác nhận được đơn hàng'
                      : 'Order not verified'}
            </h1>
            {order && (
                <p>
                    {order.id} · {order.status}
                </p>
            )}
            {(!orderId || error) && (
                <p role="alert">
                    {lang === 'vi'
                        ? 'Thiếu mã đơn hoặc chưa tải được dữ liệu. Hãy đăng nhập và kiểm tra lịch sử đơn hàng.'
                        : 'The order ID is missing or unavailable. Sign in and check your order history.'}
                </p>
            )}
            <Button variant="outline" onClick={() => navigate('order-history')}>
                {lang === 'vi' ? 'Lịch sử đơn hàng' : 'Order history'}
            </Button>
        </div>
    )
}
