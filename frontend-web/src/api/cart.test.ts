/**
 * Unit test cho Shopping Cart API client (Commerce Service).
 * Kiểm tra các hàm getActiveCart, addItemToCart, updateCartItemQuantity, removeCartItem, clearCart.
 * Luồng: Mock fetch tại ranh giới HTTP, kiểm tra endpoint URL, HTTP method, request payload và dữ liệu trả về.
 * Xử lý lỗi: Kiểm tra ném ngoại lệ khi backend trả về response không có data hoặc thất bại.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
    getActiveCart,
    addItemToCart,
    updateCartItemQuantity,
    removeCartItem,
    clearCart,
    type CartResponse,
} from './cart'

const mockCartResponse: CartResponse = {
    cartId: 'cart-uuid-1',
    userId: 'user-uuid-1',
    status: 'ACTIVE',
    items: [
        {
            id: 'item-uuid-1',
            skuId: 'sku-uuid-1',
            skuCode: 'SKU-001',
            productName: 'Mechanical Keyboard',
            thumbnailUrl: 'https://example.com/keyboard.jpg',
            unitPrice: 120,
            quantity: 2,
            stockAvailable: 15,
            isAvailable: true,
            subtotal: 240,
        },
    ],
    totalItems: 1,
    totalQuantity: 2,
    subtotal: 240,
    discountAmount: 0,
    shippingFee: 0,
    taxAmount: 19.2,
    totalAmount: 259.2,
    expiresAt: null,
}

describe('Cart API Service', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    /**
     * Kiểm tra getActiveCart:
     * - Gửi GET /cart
     * - Trả về CartResponse khi thành công
     * - Ném lỗi khi backend trả data null/undefined
     */
    it('getActiveCart: lấy giỏ hàng thành công từ backend', async () => {
        const fetcher = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: mockCartResponse,
                }),
                { status: 200 },
            ),
        )
        vi.stubGlobal('fetch', fetcher)

        const cart = await getActiveCart()
        expect(cart).toEqual(mockCartResponse)
        expect(fetcher.mock.calls[0][0]).toContain('/cart')
    })

    it('getActiveCart: ném ngoại lệ khi dữ liệu giỏ hàng rỗng', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    new Response(JSON.stringify({ success: true, data: null }), { status: 200 }),
                ),
        )

        await expect(getActiveCart()).rejects.toThrow('Dữ liệu giỏ hàng không tồn tại')
    })

    /**
     * Kiểm tra addItemToCart:
     * - Gửi POST /cart/items với body { skuId, quantity }
     * - Trả về CartResponse mới
     * - Ném lỗi khi data null
     */
    it('addItemToCart: gửi yêu cầu thêm sản phẩm với skuId và quantity', async () => {
        const fetcher = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: mockCartResponse,
                }),
                { status: 200 },
            ),
        )
        vi.stubGlobal('fetch', fetcher)

        const result = await addItemToCart('sku-uuid-1', 2)
        expect(result).toEqual(mockCartResponse)
        const callArgs = fetcher.mock.calls[0]
        expect(callArgs[0]).toContain('/cart/items')
        expect(callArgs[1].method).toBe('POST')
        expect(JSON.parse(callArgs[1].body)).toEqual({ skuId: 'sku-uuid-1', quantity: 2 })
    })

    it('addItemToCart: ném ngoại lệ khi backend không trả dữ liệu sau khi thêm', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    new Response(JSON.stringify({ success: true, data: null }), { status: 200 }),
                ),
        )

        await expect(addItemToCart('sku-uuid-1', 1)).rejects.toThrow(
            'Không thể thêm sản phẩm vào giỏ',
        )
    })

    /**
     * Kiểm tra updateCartItemQuantity:
     * - Gửi PUT /cart/items/{itemId} với body { quantity }
     * - Trả về CartResponse mới
     */
    it('updateCartItemQuantity: gửi yêu cầu cập nhật số lượng dòng sản phẩm', async () => {
        const fetcher = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: {
                        ...mockCartResponse,
                        items: [{ ...mockCartResponse.items[0], quantity: 5, subtotal: 600 }],
                        totalQuantity: 5,
                        subtotal: 600,
                    },
                }),
                { status: 200 },
            ),
        )
        vi.stubGlobal('fetch', fetcher)

        const result = await updateCartItemQuantity('item-uuid-1', 5)
        expect(result.totalQuantity).toBe(5)
        const callArgs = fetcher.mock.calls[0]
        expect(callArgs[0]).toContain('/cart/items/item-uuid-1')
        expect(callArgs[1].method).toBe('PUT')
        expect(JSON.parse(callArgs[1].body)).toEqual({ quantity: 5 })
    })

    it('updateCartItemQuantity: ném ngoại lệ khi phản hồi rỗng', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    new Response(JSON.stringify({ success: true, data: null }), { status: 200 }),
                ),
        )

        await expect(updateCartItemQuantity('item-uuid-1', 3)).rejects.toThrow(
            'Không thể cập nhật số lượng',
        )
    })

    /**
     * Kiểm tra removeCartItem:
     * - Gửi DELETE /cart/items/{itemId}
     * - Trả về CartResponse mới
     */
    it('removeCartItem: gửi yêu cầu xóa dòng sản phẩm', async () => {
        const fetcher = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: {
                        ...mockCartResponse,
                        items: [],
                        totalItems: 0,
                        totalQuantity: 0,
                        subtotal: 0,
                    },
                }),
                { status: 200 },
            ),
        )
        vi.stubGlobal('fetch', fetcher)

        const result = await removeCartItem('item-uuid-1')
        expect(result.totalItems).toBe(0)
        const callArgs = fetcher.mock.calls[0]
        expect(callArgs[0]).toContain('/cart/items/item-uuid-1')
        expect(callArgs[1].method).toBe('DELETE')
    })

    it('removeCartItem: ném ngoại lệ khi phản hồi rỗng', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    new Response(JSON.stringify({ success: true, data: null }), { status: 200 }),
                ),
        )

        await expect(removeCartItem('item-uuid-1')).rejects.toThrow(
            'Không thể xóa sản phẩm khỏi giỏ',
        )
    })

    /**
     * Kiểm tra clearCart:
     * - Gửi DELETE /cart/items
     * - Xóa toàn bộ giỏ thành công
     */
    it('clearCart: gửi yêu cầu xóa toàn bộ giỏ hàng', async () => {
        const fetcher = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: null,
                }),
                { status: 200 },
            ),
        )
        vi.stubGlobal('fetch', fetcher)

        await clearCart()
        const callArgs = fetcher.mock.calls[0]
        expect(callArgs[0]).toContain('/cart/items')
        expect(callArgs[1].method).toBe('DELETE')
    })
})
