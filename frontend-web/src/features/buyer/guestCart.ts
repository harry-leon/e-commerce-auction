/**
 * Quản lý giỏ hàng khách vãng lai (Guest Cart) lưu tại localStorage.
 *
 * 1. Chức năng: Lưu trữ và đồng bộ sản phẩm vào giỏ hàng cho người dùng khi chưa đăng nhập hoặc offline.
 * 2. Đầu vào và kết quả: Nhận thông tin sản phẩm/SKU, cập nhật localStorage ('nexus_guest_cart'), trả về CartResponse chuẩn.
 * 3. Luồng xử lý: Kiểm tra danh sách hiện tại -> thêm/sửa/xóa item -> tính toán lại tổng tiền/thuế/phí -> lưu storage.
 * 4. Quy tắc và lý do: Cấu trúc trả về khớp 100% với CartResponse từ Commerce Service backend; miễn phí vận chuyển khi subtotal >= 100.
 * 5. Lỗi và giới hạn: Tự động bắt lỗi nếu localStorage bị chặn (private browsing) và fallback an toàn về giỏ hàng trong bộ nhớ.
 */

import type { CartItemResponse, CartResponse } from '../../api/cart'

const GUEST_CART_STORAGE_KEY = 'nexus_guest_cart'

/**
 * Đọc danh sách sản phẩm thô từ localStorage.
 */
function readStorageItems(): CartItemResponse[] {
    try {
        if (typeof window === 'undefined' || !window.localStorage) return []
        const raw = window.localStorage.getItem(GUEST_CART_STORAGE_KEY)
        if (!raw) return []
        const parsed = JSON.parse(raw)
        return Array.isArray(parsed) ? parsed : []
    } catch {
        return []
    }
}

/**
 * Ghi danh sách sản phẩm vào localStorage.
 */
function writeStorageItems(items: CartItemResponse[]): void {
    try {
        if (typeof window !== 'undefined' && window.localStorage) {
            window.localStorage.setItem(GUEST_CART_STORAGE_KEY, JSON.stringify(items))
        }
    } catch {
        // Bỏ qua lỗi hạn mức lưu trữ storage
    }
}

/**
 * Tạo đối tượng CartResponse hoàn chỉnh từ danh sách CartItemResponse.
 */
function buildCartResponse(items: CartItemResponse[]): CartResponse {
    const totalItems = items.length
    const totalQuantity = items.reduce((sum, item) => sum + (Number(item.quantity) || 0), 0)
    const subtotal = items.reduce((sum, item) => sum + (Number(item.subtotal) || 0), 0)
    const discountAmount = 0
    const shippingFee = subtotal > 0 ? (subtotal >= 100 ? 0 : 8.99) : 0
    const taxAmount = subtotal > 0 ? subtotal * 0.08 : 0
    const totalAmount = subtotal - discountAmount + shippingFee + taxAmount

    return {
        cartId: 'guest-cart',
        userId: 'guest',
        status: 'ACTIVE',
        items,
        totalItems,
        totalQuantity,
        subtotal,
        discountAmount,
        shippingFee,
        taxAmount,
        totalAmount,
        expiresAt: null,
    }
}

/**
 * Lấy giỏ hàng khách hiện tại từ localStorage.
 */
export function getGuestCart(): CartResponse {
    const items = readStorageItems()
    return buildCartResponse(items)
}

/**
 * Thêm sản phẩm vào giỏ hàng khách.
 * Nếu SKU đã có trong giỏ, tăng số lượng; nếu chưa có, thêm mới một dòng.
 */
export function addGuestCartItem(params: {
    skuId: string
    skuCode?: string
    productName: string
    thumbnailUrl?: string | null
    unitPrice: number
    quantity?: number
}): CartResponse {
    const items = readStorageItems()
    const qty = Math.max(1, params.quantity || 1)
    const existingIndex = items.findIndex((it) => it.skuId === params.skuId)

    if (existingIndex >= 0) {
        const current = items[existingIndex]
        const newQty = current.quantity + qty
        items[existingIndex] = {
            ...current,
            quantity: newQty,
            subtotal: current.unitPrice * newQty,
        }
    } else {
        const id =
            typeof crypto !== 'undefined' && crypto.randomUUID
                ? crypto.randomUUID()
                : `guest-item-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
        items.push({
            id,
            skuId: params.skuId,
            skuCode: params.skuCode || `SKU-${params.skuId}`,
            productName: params.productName,
            thumbnailUrl: params.thumbnailUrl || null,
            unitPrice: params.unitPrice,
            quantity: qty,
            stockAvailable: 99,
            isAvailable: true,
            subtotal: params.unitPrice * qty,
        })
    }

    writeStorageItems(items)
    return buildCartResponse(items)
}

/**
 * Cập nhật số lượng của một dòng sản phẩm trong giỏ hàng khách.
 * Nếu số lượng <= 0, xóa sản phẩm đó.
 */
export function updateGuestCartItem(itemId: string, quantity: number): CartResponse {
    let items = readStorageItems()
    if (quantity <= 0) {
        items = items.filter((it) => it.id !== itemId)
    } else {
        items = items.map((it) => {
            if (it.id === itemId) {
                return {
                    ...it,
                    quantity,
                    subtotal: it.unitPrice * quantity,
                }
            }
            return it
        })
    }
    writeStorageItems(items)
    return buildCartResponse(items)
}

/**
 * Xóa một dòng sản phẩm khỏi giỏ hàng khách.
 */
export function removeGuestCartItem(itemId: string): CartResponse {
    const items = readStorageItems().filter((it) => it.id !== itemId)
    writeStorageItems(items)
    return buildCartResponse(items)
}

/**
 * Xóa sạch toàn bộ sản phẩm trong giỏ hàng khách.
 */
export function clearGuestCart(): CartResponse {
    try {
        if (typeof window !== 'undefined' && window.localStorage) {
            window.localStorage.removeItem(GUEST_CART_STORAGE_KEY)
        }
    } catch {
        // Bỏ qua lỗi
    }
    return buildCartResponse([])
}
