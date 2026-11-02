import { api } from './client'
import type { ApiResponse } from './client'

/** Snapshot payment từ DB sau IPN; không dùng mã kết quả browser làm bằng chứng đã trả tiền. */
export interface PaymentResult {
    paymentId: string
    orderId: string
    status: 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
    amount: number
    currency: 'VND'
    paymentUrl: string
    expiresAt: string
}

/** Poll trạng thái có xác thực; mọi lỗi được chuyển lên UI, tuyệt đối không fallback payment mock. */
export async function getPayment(id: string): Promise<PaymentResult> {
    const response = await api<ApiResponse<PaymentResult>>(`/payments/${encodeURIComponent(id)}`)
    if (!response.success || !response.data)
        throw new Error('Không thể xác nhận kết quả thanh toán')
    return response.data
}
