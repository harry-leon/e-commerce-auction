import { api } from './client'
import type { ApiResponse } from './client'

/** Chi tiết dòng sản phẩm trong snapshot thanh toán do BE đóng băng ở thời điểm mở attempt. */
export interface PaymentPriceSnapshotLine {
    orderItemId: string
    skuId: string
    quantity: number
    unitPrice: number
    itemTotal: number
}

/** Snapshot cấu phần giá do PaymentPricingService tính và đóng băng trước khi tạo URL cổng thanh toán. */
export interface PaymentPriceSnapshot {
    subtotal: number
    shippingFee: number
    discount: number
    total: number
    items: PaymentPriceSnapshotLine[]
}

/** Kết quả DB sau IPN; PROCESSING chờ kho, REQUIRES_REVIEW đã thu tiền nhưng cần đối soát, không được thanh toán lại. */
export interface PaymentResult {
    paymentId: string
    orderId: string
    status:
        | 'PENDING'
        | 'PROCESSING'
        | 'REQUIRES_REVIEW'
        | 'EXPIRED'
        | 'SUCCESS'
        | 'FAILED'
        | 'CANCELLED'
    amount: number
    currency: 'VND'
    paymentUrl: string | null
    expiresAt: string
    priceSnapshot?: PaymentPriceSnapshot | null
    inventoryRefId?: string | null
}

/** Poll trạng thái có xác thực; mọi lỗi được chuyển lên UI, tuyệt đối không fallback payment mock. */
export async function getPayment(id: string): Promise<PaymentResult> {
    const response = await api<ApiResponse<PaymentResult>>(`/payments/${encodeURIComponent(id)}`)
    if (!response.success || !response.data)
        throw new Error('Không thể xác nhận kết quả thanh toán')
    return response.data
}

/**
 * Khởi tạo/replay payment cho đơn server; chỉ gửi orderId, backend tự xác minh quyền, lấy giá live mới nhất và giữ kho.
 * Truyền idempotencyKey mới (UUID) khi retry sau giao dịch FAILED để BE tạo attempt mới.
 */
export async function startPayment(
    orderId: string,
    idempotencyKey?: string,
): Promise<PaymentResult> {
    const headers: Record<string, string> = {}
    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey
    }
    const response = await api<ApiResponse<PaymentResult>>('/payments', {
        method: 'POST',
        headers,
        body: JSON.stringify({ orderId }),
    })
    if (!response.success || !response.data) throw new Error('Không thể tạo thanh toán')
    return response.data
}
