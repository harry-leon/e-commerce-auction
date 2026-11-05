import { afterEach, expect, it, vi } from 'vitest'
import { ordersApi } from './orders'
import { normalizeOrder } from './orderData'
import { flatten, price } from '../features/catalog/catalog'
import type { ApiCategory, ApiProduct } from './client'

afterEach(() => vi.unstubAllGlobals())

/** Hồi quy lỗi map ở lịch sử Buyer và bảng đơn Seller/Admin: summary thật không có items. */
it('reads backend summaries without inventing items or successful payment', async () => {
    vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: [
                        { id: 'order-1', status: 'PENDING_PAYMENT', itemCount: 2, total: 800000 },
                    ],
                }),
            ),
        ),
    )
    const orders = await ordersApi.getOrders({ role: 'buyer' })
    expect(orders).toHaveLength(1)
    expect(orders[0]).toMatchObject({
        items: [],
        itemCount: 2,
        total: 800000,
        paymentStatus: 'unknown',
    })
    expect(orders[0].items.map((item) => item.productName)).toEqual([])
})

/** Kết quả API rỗng phải là lịch sử trống, không hiện đơn mẫu lưu từ phiên test trước. */
it('keeps a successful empty order list empty', async () => {
    vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: [] }))),
    )
    expect(await ordersApi.getOrders()).toEqual([])
})

/** Chi tiết Commerce dùng skuId/subtotal/shippingFee; bảo vệ tên sản phẩm, số tiền và ID khi ánh xạ. */
it('maps real detail fields and tolerates absent collections', () => {
    expect(
        normalizeOrder({
            id: 'order-1',
            status: 'PAID',
            shippingFee: 25000,
            items: [
                {
                    skuId: 'sku-1',
                    productName: 'Chuột',
                    quantity: 2,
                    unitPrice: 400000,
                    subtotal: 800000,
                },
            ],
        }),
    ).toMatchObject({
        shipping: 25000,
        items: [{ productId: 'sku-1', productName: 'Chuột', quantity: 2, subtotal: 800000 }],
        statusHistory: [],
    })
    expect(normalizeOrder({ id: 'order-2', status: 'PAID', items: null }).items).toEqual([])
    expect(() => normalizeOrder({})).toThrow('Dữ liệu đơn hàng không hợp lệ')
})

/** Node danh mục lá và sản phẩm chưa có SKU không được làm sập trang tìm kiếm. */
it('handles category leaves and products without SKU collections', () => {
    const leaf = { id: 'leaf', name: 'Books' } as ApiCategory
    expect(flatten([leaf])).toEqual([leaf])
    expect(price({ id: 'product-1' } as ApiProduct)).toBe('Chưa có giá bán')
})
