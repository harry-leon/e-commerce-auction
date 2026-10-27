/**
 * Module DTO và client API cho Shopping Cart (Commerce Service).
 * Tuân thủ theo đặc tả docs/dto-contracts/COMMERCE_SERVICE.md và ApiResponse envelope chuẩn.
 */

import { api, post, type ApiResponse } from './client'

/**
 * Thông tin một dòng sản phẩm trong giỏ hàng từ backend CartItemResponse.
 */
export interface CartItemResponse {
    /** UUID của cart item */
    id: string
    /** UUID của SKU sản phẩm */
    skuId: string
    /** Mã SKU hiển thị */
    skuCode: string
    /** Tên sản phẩm */
    productName: string
    /** URL ảnh đại diện */
    thumbnailUrl: string | null
    /** Đơn giá tại thời điểm xem giỏ */
    unitPrice: number
    /** Số lượng đặt mua */
    quantity: number
    /** Tồn kho khả dụng (null nếu chưa xác minh với Fulfillment Service) */
    stockAvailable: number | null
    /** Trạng thái SKU còn bán hay không */
    isAvailable: boolean
    /** Thành tiền = unitPrice * quantity */
    subtotal: number
}

/**
 * Dữ liệu giỏ hàng trả về từ backend CartResponse.
 */
export interface CartResponse {
    /** UUID của giỏ hàng */
    cartId: string
    /** UUID của chủ sở hữu giỏ */
    userId: string
    /** Trạng thái giỏ: ACTIVE, MERGED, EXPIRED, CONVERTED */
    status: 'ACTIVE' | 'MERGED' | 'EXPIRED' | 'CONVERTED'
    /** Danh sách các dòng hàng */
    items: CartItemResponse[]
    /** Tổng số dòng SKU khác nhau */
    totalItems: number
    /** Tổng số lượng sản phẩm của tất cả các dòng */
    totalQuantity: number
    /** Tạm tính */
    subtotal: number
    /** Số tiền giảm giá */
    discountAmount: number
    /** Phí vận chuyển */
    shippingFee: number
    /** Thuế */
    taxAmount: number
    /** Tổng tiền thanh toán */
    totalAmount: number
    /** Thời gian hết hạn của giỏ */
    expiresAt: string | null
}

/** Payload thêm sản phẩm vào giỏ */
export interface AddToCartRequest {
    skuId: string
    quantity: number
}

/** Payload cập nhật số lượng dòng sản phẩm */
export interface UpdateCartItemRequest {
    quantity: number
}

/** Payload hợp nhất giỏ hàng khách vào giỏ hàng đăng nhập */
export interface MergeCartRequest {
    items: AddToCartRequest[]
}

/**
 * Lấy giỏ hàng đang hoạt động của người dùng hiện tại (GET /api/v1/cart).
 * Luồng: Đọc token Bearer -> gọi Gateway -> Commerce Service -> trả về CartResponse bọc trong ApiResponse.
 * Lỗi: Ném ApiClientError khi 401 (chưa đăng nhập) hoặc lỗi server.
 */
export async function getActiveCart(signal?: AbortSignal): Promise<CartResponse> {
    const response = await api<ApiResponse<CartResponse>>('/cart', { signal })
    if (!response?.data) {
        throw new Error('Dữ liệu giỏ hàng không tồn tại')
    }
    return response.data
}

/**
 * Thêm sản phẩm vào giỏ hàng (POST /api/v1/cart/items).
 * Đầu vào: skuId (UUID), quantity (tối thiểu 1).
 * Kết quả: CartResponse mới nhất sau khi cộng dồn số lượng.
 */
export async function addItemToCart(skuId: string, quantity: number): Promise<CartResponse> {
    const body: AddToCartRequest = { skuId, quantity }
    const response = await post<ApiResponse<CartResponse>>('/cart/items', body)
    if (!response?.data) {
        throw new Error('Không thể thêm sản phẩm vào giỏ')
    }
    return response.data
}

/**
 * Cập nhật số lượng của một dòng sản phẩm (PUT /api/v1/cart/items/{itemId}).
 * Đầu vào: itemId (UUID dòng hàng), quantity (tối thiểu 1).
 * Kết quả: CartResponse đã cập nhật lại tổng tiền.
 */
export async function updateCartItemQuantity(
    itemId: string,
    quantity: number,
): Promise<CartResponse> {
    const body: UpdateCartItemRequest = { quantity }
    const response = await api<ApiResponse<CartResponse>>(
        `/cart/items/${encodeURIComponent(itemId)}`,
        {
            method: 'PUT',
            body: JSON.stringify(body),
        },
    )
    if (!response?.data) {
        throw new Error('Không thể cập nhật số lượng')
    }
    return response.data
}

/**
 * Xóa một sản phẩm khỏi giỏ hàng (DELETE /api/v1/cart/items/{itemId}).
 * Đầu vào: itemId (UUID dòng hàng cần xóa).
 * Kết quả: CartResponse mới sau khi đã loại bỏ dòng sản phẩm.
 */
export async function removeCartItem(itemId: string): Promise<CartResponse> {
    const response = await api<ApiResponse<CartResponse>>(
        `/cart/items/${encodeURIComponent(itemId)}`,
        {
            method: 'DELETE',
        },
    )
    if (!response?.data) {
        throw new Error('Không thể xóa sản phẩm khỏi giỏ')
    }
    return response.data
}

/**
 * Xóa toàn bộ sản phẩm trong giỏ hàng (DELETE /api/v1/cart/items).
 * Kết quả: giỏ hàng trống rỗng (data trả về null).
 */
export async function clearCart(): Promise<void> {
    await api<ApiResponse<null>>('/cart/items', {
        method: 'DELETE',
    })
}
