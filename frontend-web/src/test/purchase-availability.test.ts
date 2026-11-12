import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { purchaseAvailabilityMessage } from '../features/buyer/purchaseAvailability'

vi.mock('../api/client', () => ({ api: vi.fn() }))
const items = [{ skuId: 'shoe', productName: 'Giày chạy bộ', quantity: 4 }]
beforeEach(() => vi.resetAllMocks())

/** Kho trả 0 (kể cả SKU chưa nhập kho) hoặc thiếu số lượng phải báo theo tên trước checkout. */
it.each([0, 3, 4])('checks live available quantity %s', async (availableQty) => {
    vi.mocked(api).mockResolvedValue({ success: true, data: { availableQty } })
    const message = await purchaseAvailabilityMessage(items, 'vi')
    if (availableQty >= 4) expect(message).toBeNull()
    else {
        expect(message).toContain('Giày chạy bộ')
        expect(message).toContain(availableQty === 0 ? 'đã hết hàng' : 'chỉ còn 3')
    }
    expect(api).toHaveBeenCalledWith('/inventory/sku/shoe')
})

/** Response sai hoặc mạng lỗi không phải bằng chứng còn hàng: phải chặn và cho người mua kiểm tra lại. */
it.each([null, { success: true, data: {} }, { success: false, data: { availableQty: 10 } }])(
    'rejects unverifiable stock %s',
    async (response) => {
        vi.mocked(api).mockResolvedValue(response)
        await expect(purchaseAvailabilityMessage(items, 'vi')).rejects.toThrow(
            'Chưa kiểm tra được tồn kho',
        )
    },
)
