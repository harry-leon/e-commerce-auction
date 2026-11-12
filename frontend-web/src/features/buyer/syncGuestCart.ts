import { addItemToCart } from '../../api/cart'
import { getGuestCart, removeGuestCartItem, updateGuestCartItem } from './guestCart'

const pending = new Map<string, Promise<void>>()

/**
 * Đồng bộ giỏ khách theo tài khoản, dùng chung Promise cho Cart/Checkout và effect chạy lại.
 * POST cộng dồn nên không gửi song song cùng giỏ; chỉ xóa từng dòng local sau ACK thành công.
 * Lỗi được trả cho màn hình, không tự retry POST vì mất ACK có thể xảy ra sau khi server đã cộng.
 * Khóa chỉ có hiệu lực trong tab hiện tại; chưa chống lặp giữa nhiều tab hoặc sau reload.
 */
export function syncGuestCart(userId: string): Promise<void> {
    const existing = pending.get(userId)
    if (existing) return existing
    const request = (async () => {
        for (const item of getGuestCart().items) {
            await addItemToCart(item.skuId, item.quantity)
            // Giữ phần số lượng mới thêm trong lúc chờ API, chỉ trừ phần đã đồng bộ.
            const current = getGuestCart().items.find((line) => line.id === item.id)
            if (current && current.quantity === item.quantity) removeGuestCartItem(item.id)
            else if (current) {
                updateGuestCartItem(item.id, current.quantity - item.quantity)
            }
        }
    })().finally(() => pending.delete(userId))
    pending.set(userId, request)
    return request
}
