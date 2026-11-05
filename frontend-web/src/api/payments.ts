import { api } from './client'
import type { ApiResponse } from './client'

/** Kết quả DB sau IPN; EXPIRED do backend tính theo hạn URL, chưa phải kết luận thất bại/đã thu tiền. */
export interface PaymentResult {
    paymentId: string
    orderId: string
    status: 'PENDING' | 'EXPIRED' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
    amount: number
    currency: 'VND'
    paymentUrl: string | null
    expiresAt: string
}

/** Poll trạng thái có xác thực; mọi lỗi được chuyển lên UI, tuyệt đối không fallback payment mock. */
export async function getPayment(id: string): Promise<PaymentResult> {
    const response = await api<ApiResponse<PaymentResult>>(`/payments/${encodeURIComponent(id)}`)
    if (!response.success || !response.data)
        throw new Error('Không thể xác nhận kết quả thanh toán')
    return response.data
}
