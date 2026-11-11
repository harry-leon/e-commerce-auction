import { ApiClientError } from '../../api/client'
import { ordersApi, type CreateOrderRequest, type CreateOrderResponse } from '../../api/orders'

/** Phục hồi command checkout theo buyer qua mất ACK/reload; mọi giá và trạng thái vẫn do backend xác nhận. */
interface CheckoutCommand {
    userId: string
    key: string
    request: CreateOrderRequest
    order?: CreateOrderResponse
    attemptKey?: string
}
const prefix = 'nexus.checkout-command:'
const memory = new Map<string, CheckoutCommand>()
const volatile = new Set<string>()

/**
 * Command theo tài khoản, chỉ chứa payload đặt hàng/key/orderId; không lưu dữ liệu thẻ hay JWT.
 * Storage là dữ liệu phục hồi giao diện; backend vẫn kiểm tra quyền và quyết định giá/trạng thái.
 * Nếu storage bị chặn, RAM cho phép thử lại trong cùng trang; reload khi đó cần tra lịch sử đơn.
 */
export function readCheckoutCommand(userId: string): CheckoutCommand | null {
    // Ghi storage thất bại có thể để lại bản cũ; RAM mới nhất phải thắng bản đã lưu trước request.
    if (volatile.has(userId)) return memory.get(userId) || null
    try {
        const saved = JSON.parse(
            sessionStorage.getItem(prefix + userId) || 'null',
        ) as CheckoutCommand | null
        if (saved?.userId === userId && saved.key && saved.request) return saved
        return volatile.has(userId) ? memory.get(userId) || null : null
    } catch {
        return memory.get(userId) || null
    }
}

// Lưu trước request để mất ACK vẫn replay cùng key và payload, không dùng giỏ backend đã chuyển đổi.
function save(command: CheckoutCommand): void {
    memory.set(command.userId, command)
    try {
        sessionStorage.setItem(prefix + command.userId, JSON.stringify(command))
        volatile.delete(command.userId)
    } catch {
        volatile.add(command.userId)
    }
}

/** Chỉ dọn command sau bàn giao thanh toán/COD hoặc khi Create Order bị từ chối dứt khoát. */
export function clearCheckoutCommand(userId: string): void {
    memory.delete(userId)
    volatile.delete(userId)
    try {
        sessionStorage.removeItem(prefix + userId)
    } catch {
        /* Không sửa trạng thái đơn ở backend. */
    }
}

/**
 * Đóng băng key/payload trước Create Order; nếu đã nhận đơn thì trả đơn cũ mà không gọi Create Order lần hai.
 * Lỗi mạng/5xx/401 giữ command; lỗi nghiệp vụ 4xx cho phép sửa form và gửi command mới.
 */
export async function recoverOrCreateOrder(
    userId: string,
    request: CreateOrderRequest,
): Promise<CreateOrderResponse> {
    const command = readCheckoutCommand(userId) || { userId, key: crypto.randomUUID(), request }
    if (command.order) return command.order
    save(command)
    try {
        command.order = await ordersApi.createOrder(command.request, command.key)
        save(command)
        return command.order
    } catch (error) {
        if (
            error instanceof ApiClientError &&
            error.status >= 400 &&
            error.status < 500 &&
            error.status !== 401
        ) {
            clearCheckoutCommand(userId)
        }
        throw error
    }
}

/** Key attempt giữ qua lỗi mạng/reload, chỉ cấp cho đúng order đã tạo trong command của buyer. */
export function checkoutAttemptKey(userId: string, orderId: string): string {
    const command = readCheckoutCommand(userId)
    if (!command || command.order?.id !== orderId)
        throw new Error('Không tìm thấy đơn checkout đang xử lý')
    command.attemptKey ??= crypto.randomUUID()
    save(command)
    return command.attemptKey
}
