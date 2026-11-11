import { useEffect, useState } from 'react'
import { ordersApi, readLocalMockOrders, writeLocalMockOrders } from '../../api/orders'
import { useApp } from '../../context'
import { Button, CheckCircle, Package } from '../../components/UI'
import type { Order } from '../../types'

/**
 * Component hiển thị kết quả xử lý đơn hàng khi quay về từ luồng thanh toán hoặc đặt hàng thành công.
 *
 * Chức năng:
 * - Tra cứu và hiển thị trạng thái thực tế của đơn hàng thông qua backend API hoặc kho đơn cục bộ (mock/offline).
 * - Cung cấp giao diện thông báo trực quan (icon, mã đơn, trạng thái, nút chuyển trang).
 *
 * Đầu vào và kết quả:
 * - Đầu vào: orderId (UUID hoặc mã đơn ORD-...) từ URL query params.
 * - Kết quả: Hiển thị trạng thái đơn hàng (PAID, PENDING_PAYMENT...) hoặc cảnh báo nếu hoàn toàn không tìm thấy đơn.
 *
 * Luồng xử lý:
 * 1. Nhận orderId; nếu rỗng đánh dấu lỗi và bỏ qua gọi API.
 * 2. Nếu orderId là UUID: gọi ordersApi.getOrderDetailV2(orderId) từ Backend.
 * 3. Nếu Backend lỗi hoặc orderId dạng ORD-...: fallback gọi ordersApi.getOrderById(orderId) từ localStorage.
 * 4. Nếu orderId dạng ORD-... và query status=success mà chưa có trong store: tự động khởi tạo đơn mock đã thanh toán (PAID)
 *    và lưu vào localStorage để Buyer và Admin xem được ngay trong lịch sử đơn.
 * 5. Nếu không tìm thấy và không có xác nhận thành công: hiển thị thông báo alert chưa xác nhận.
 *
 * Quy tắc và lý do:
 * - Hỗ trợ cả đơn hàng thật trên Backend và đơn hàng thử nghiệm sandbox/mock lưu tại browser.
 * - Đảm bảo dữ liệu đơn lưu vào localStorage có đầy đủ items để không bao giờ xảy ra lỗi undefined.map().
 *
 * Lỗi và giới hạn:
 * - Nếu token hết hạn, người dùng có thể bấm "Lịch sử đơn hàng" để được chuyển hướng và làm mới phiên làm việc.
 */
export function VerifiedOrderResult({ orderId }: { orderId: string }) {
    const { lang, navigate, user, pageParams } = useApp()
    const [order, setOrder] = useState<Order | null>(null)
    const [error, setError] = useState(false)
    const queryStatus = String(pageParams?.status || '')

    useEffect(() => {
        let cancelled = false
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
            .catch(async () => {
                // Bước 2: Khi Backend trả lỗi (ví dụ mã đơn mock ORD-... trong môi trường dev cục bộ),
                // fallback tìm kiếm đơn đã lưu trong kho cục bộ
                try {
                    const fallbackOrder = await ordersApi.getOrderById(orderId)
                    if (!cancelled && fallbackOrder) {
                        setOrder(fallbackOrder)
                        setError(false)
                        return
                    }
                } catch {
                    // Bỏ qua lỗi tìm kiếm cục bộ
                }

                // Tuyệt đối không tự động sinh đơn PAID giả lập khi URL mang cờ status=success/00;
                // chỉ hiển thị kết quả theo đúng trạng thái đơn hàng đã được xác thực từ Backend.
                if (!cancelled) {
                    setError(true)
                }
            })

        return () => {
            cancelled = true
        }
    }, [orderId, queryStatus, user, lang])

    const isPaid = order && (order.status === 'PAID' || order.status === 'COMPLETED')
    const isUnverified = !orderId || (error && !order)

    return (
        <div className="max-w-lg mx-auto text-center py-12 px-4">
            {!isUnverified && order && (
                <div className="flex justify-center mb-4">
                    <div
                        className={`w-16 h-16 rounded-full flex items-center justify-center ${
                            isPaid
                                ? 'bg-emerald-100 text-emerald-600'
                                : 'bg-blue-100 text-blue-600'
                        }`}
                    >
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
                    {(order as any).orderNo || order.id} ·{' '}
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
