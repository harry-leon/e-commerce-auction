import { api } from './client'
import type { ApiResponse } from './client'

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
}

/** Poll trạng thái có xác thực; mọi lỗi được chuyển lên UI, tuyệt đối không fallback payment mock. */
export async function getPayment(id: string): Promise<PaymentResult> {
    const response = await api<ApiResponse<PaymentResult>>(`/payments/${encodeURIComponent(id)}`)
    if (!response.success || !response.data)
        throw new Error('Không thể xác nhận kết quả thanh toán')
    return response.data
}

/**
 * Khởi tạo hoặc lấy lại URL thanh toán VNPAY sandbox cho đơn hàng.
 * POST /api/v1/payments với body { orderId } kèm header Idempotency-Key (nếu có).
 * Backend tự kiểm tra quyền sở hữu đơn hàng, winner, giá và tính hợp lệ trước khi sinh paymentUrl sandbox.
 */
export async function startPayment(orderId: string, idempotencyKey?: string): Promise<PaymentResult> {
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

/**
 * Khởi tạo hoặc lấy lại URL phiên thanh toán VNPAY sandbox cho đơn hàng chờ thanh toán.
 * 1. Chức năng: Gửi yêu cầu tới Backend để tạo attempt thanh toán mới hoặc lấy lại URL sandbox còn hiệu lực.
 * 2. Đầu vào và kết quả:
 *    - Đầu vào: orderId (UUID của đơn hàng cần thanh toán), idempotencyKey (UUID dùng để retry sau FAILED).
 *    - Kết quả: Đối tượng PaymentResult chứa paymentId (TxnRef), paymentUrl sandbox và hạn thời gian giữ kho.
 * 3. Luồng xử lý: Gọi startPayment(orderId, idempotencyKey) gửi POST /api/v1/payments với body { orderId } kèm header Idempotency-Key và Bearer JWT của Buyer.
 * 4. Quy tắc và lý do: Số tiền thanh toán (amount) do Backend tính toán từ Order snapshot trong DB, không nhận từ client. Khi retry attempt FAILED bắt buộc gửi key mới chống 409 PAYMENT_RETRY_KEY_REQUIRED.
 * 5. Lỗi và giới hạn: Nếu đơn hàng không ở trạng thái PENDING_PAYMENT hoặc đã hết hạn giữ kho 15 phút, Backend sẽ ném lỗi.
 */
export async function createPaymentAttempt(orderId: string, idempotencyKey?: string): Promise<PaymentResult> {
    return startPayment(orderId, idempotencyKey)
}

/**
 * Điều hướng trình duyệt người dùng sang URL cổng thanh toán ngoại vi (VNPAY Sandbox).
 * 1. Chức năng: Mở cổng thanh toán VNPAY bằng URL sandbox sinh ra từ Backend.
 * 2. Đầu vào và kết quả:
 *    - Đầu vào: url (chuỗi URL thanh toán VNPAY hợp lệ do Backend trả về).
 *    - Kết quả: Không trả về (side effect điều hướng trình duyệt).
 * 3. Luồng xử lý: Kiểm tra môi trường window và gọi window.location.assign(url).
 * 4. Quy tắc và lý do: Đóng gói việc điều hướng ngoại vi vào API client để tách biệt khỏi UI, giúp dễ dàng kiểm thử và nhất quán toàn ứng dụng.
 * 5. Lỗi và giới hạn: Chỉ chạy được trong môi trường trình duyệt client-side; test runner có thể spy hàm này mà không phụ thuộc vào window.location của JSDOM.
 */
export function redirectToGateway(url: string): void {
    if (typeof window !== 'undefined' && window.location) {
        window.location.assign(url)
    }
}
