import { beforeEach, expect, it, vi } from 'vitest'
import { addItemToCart } from '../api/cart'
import { addGuestCartItem, getGuestCart } from '../features/buyer/guestCart'
import { syncGuestCart } from '../features/buyer/syncGuestCart'

vi.mock('../api/cart', () => ({ addItemToCart: vi.fn() }))

beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
})

/** Hai effect Cart/Checkout cùng đọc giỏ khách phải dùng một POST cộng dồn, không biến 3 thành 4. */
it('shares an in-flight guest sync and clears only acknowledged quantities', async () => {
    addGuestCartItem({ skuId: 'sku-1', productName: 'Giày', unitPrice: 77280, quantity: 1 })
    let acknowledge!: () => void
    vi.mocked(addItemToCart).mockImplementation(
        () =>
            new Promise((resolve) => {
                acknowledge = () => resolve({} as Awaited<ReturnType<typeof addItemToCart>>)
            }),
    )
    const first = syncGuestCart('buyer')
    const second = syncGuestCart('buyer')
    expect(second).toBe(first)
    expect(addItemToCart).toHaveBeenCalledTimes(1)
    addGuestCartItem({ skuId: 'sku-1', productName: 'Giày', unitPrice: 77280, quantity: 2 })
    acknowledge()
    await first
    expect(getGuestCart().totalQuantity).toBe(2)
    vi.mocked(addItemToCart).mockResolvedValue({} as Awaited<ReturnType<typeof addItemToCart>>)
    await syncGuestCart('buyer')
    expect(addItemToCart).toHaveBeenLastCalledWith('sku-1', 2)
    expect(getGuestCart().totalQuantity).toBe(0)
})

/** Lỗi POST không được xóa dữ liệu local hay tự retry khi chưa biết server đã cộng hay chưa. */
it('keeps unacknowledged items and propagates sync errors', async () => {
    addGuestCartItem({ skuId: 'sku-1', productName: 'Giày', unitPrice: 77280, quantity: 1 })
    vi.mocked(addItemToCart).mockRejectedValue(new Error('timeout'))
    await expect(syncGuestCart('buyer')).rejects.toThrow('timeout')
    expect(addItemToCart).toHaveBeenCalledTimes(1)
    expect(getGuestCart().totalQuantity).toBe(1)
})
