import { api, type ApiResponse } from '../../api/client'

/**
 * Kiểm tra kho thật trước khi sang Checkout hoặc retry đơn; không giữ hàng hay tạo thanh toán.
 * Trả thông báo theo tên sản phẩm khi thiếu hàng; response thiếu/sai hoặc lỗi mạng phải chặn để kiểm tra lại.
 * Đây là kiểm tra sớm: backend vẫn kiểm tra lúc tạo đơn và reserve nguyên tử trước thanh toán.
 */
export async function purchaseAvailabilityMessage(
    items: { skuId: string; productName: string; quantity: number }[],
    lang: 'vi' | 'en',
): Promise<string | null> {
    for (const item of items) {
        const response = await api<ApiResponse<{ availableQty: number }>>(
            `/inventory/sku/${encodeURIComponent(item.skuId)}`,
        )
        const available = response?.data?.availableQty
        if (!response?.success || typeof available !== 'number' || !Number.isFinite(available)) {
            throw new Error(
                lang === 'vi'
                    ? 'Chưa kiểm tra được tồn kho. Vui lòng thử lại.'
                    : 'Could not check stock. Please try again.',
            )
        }
        if (available < item.quantity) {
            return lang === 'vi'
                ? `Sản phẩm "${item.productName}" ${available <= 0 ? 'đã hết hàng' : `chỉ còn ${available} sản phẩm`}. Vui lòng cập nhật giỏ hàng.`
                : `"${item.productName}" ${available <= 0 ? 'is out of stock' : `only has ${available} items available`}. Please update your cart.`
        }
    }
    return null
}
