import { beforeEach, expect, it, vi } from 'vitest'
import { ApiClientError } from '../api/client'
import { ordersApi, type CreateOrderRequest, type CreateOrderResponse } from '../api/orders'
import {
    recoverOrCreateOrder,
    readCheckoutCommand,
    clearCheckoutCommand,
    checkoutAttemptKey,
} from '../features/buyer/checkoutRecovery'

const request: CreateOrderRequest = {
    shippingAddress: '123 Nguyen Hue',
    recipientName: 'Buyer',
    recipientPhone: '0901234567',
    paymentMethod: 'VNPAY',
}
const order = {
    id: 'server-order',
    orderNo: 'ORD-SERVER',
    paymentMethod: 'VNPAY',
    status: 'PENDING_PAYMENT',
} as CreateOrderResponse
beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
    for (const user of ['buyer-a', 'buyer-b']) clearCheckoutCommand(user)
})

/** Mất ACK không có nghĩa là backend chưa tạo đơn: replay phải giữ nguyên key và payload ban đầu. */
it('replay đúng payload/key sau mất ACK kể cả người dùng đổi form', async () => {
    const create = vi
        .spyOn(ordersApi, 'createOrder')
        .mockRejectedValueOnce(new TypeError('lost ACK'))
        .mockResolvedValueOnce(order)
    await expect(recoverOrCreateOrder('buyer-a', request)).rejects.toThrow('lost ACK')
    const saved = sessionStorage.getItem('nexus.checkout-command:buyer-a')!
    expect(saved).not.toMatch(/cardNumber|cardHolder|cvv|expiryDate|accessToken/)
    expect(JSON.parse(saved).key).toMatch(/^[0-9a-f-]{36}$/i)
    await recoverOrCreateOrder('buyer-a', { ...request, recipientName: 'Changed form' })
    expect(create.mock.calls[1]).toEqual(create.mock.calls[0])
})

/** Reload xóa RAM nhưng command lưu trong tab phải phục hồi đúng order và attemptKey, không gọi Create Order lại. */
it('khôi phục đơn/key sau reload module', async () => {
    const create = vi.spyOn(ordersApi, 'createOrder').mockResolvedValue(order)
    await recoverOrCreateOrder('buyer-a', request)
    const attemptKey = checkoutAttemptKey('buyer-a', order.id)
    vi.resetModules()
    const fresh = await import('../features/buyer/checkoutRecovery')
    await expect(fresh.recoverOrCreateOrder('buyer-a', request)).resolves.toEqual(order)
    expect(fresh.checkoutAttemptKey('buyer-a', order.id)).toBe(attemptKey)
    expect(create).toHaveBeenCalledTimes(1)
})

/** Cùng tab đổi tài khoản không được lấy đơn hoặc attempt key của buyer trước. */
it('cô lập command giữa các buyer', async () => {
    const create = vi.spyOn(ordersApi, 'createOrder').mockResolvedValue(order)
    await recoverOrCreateOrder('buyer-a', request)
    expect(readCheckoutCommand('buyer-b')).toBeNull()
    await recoverOrCreateOrder('buyer-b', request)
    expect(create.mock.calls[0][1]).not.toBe(create.mock.calls[1][1])
})

/** Validation từ chối dứt khoát cho sửa payload, còn 401/5xx chưa kết luận kết quả phải giữ command. */
it.each([400, 401, 503])('xử lý vòng đời command theo HTTP %s', async (status) => {
    vi.spyOn(ordersApi, 'createOrder').mockRejectedValue(
        new ApiClientError('Rejected', 'ERROR', status),
    )
    await expect(recoverOrCreateOrder('buyer-a', request)).rejects.toThrow()
    expect(readCheckoutCommand('buyer-a') !== null).toBe(status !== 400)
})

/** Trình duyệt hết quota vẫn bảo vệ key trong RAM cho lần bấm tiếp theo, không hứa phục hồi sau reload. */
it('giữ command trong RAM nếu ghi storage bị chặn', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('Quota exceeded')
    })
    const create = vi
        .spyOn(ordersApi, 'createOrder')
        .mockRejectedValueOnce(new TypeError('Network'))
        .mockResolvedValueOnce(order)
    await expect(recoverOrCreateOrder('buyer-a', request)).rejects.toThrow()
    await recoverOrCreateOrder('buyer-a', request)
    expect(create.mock.calls[1]).toEqual(create.mock.calls[0])
})

/** Ghi command ban đầu thành công nhưng lưu response hết quota: phải ưu tiên order trong RAM thay vì bản storage cũ. */
it('ưu tiên RAM mới nhất nếu chỉ lần ghi response gặp lỗi quota', async () => {
    const create = vi.spyOn(ordersApi, 'createOrder').mockImplementationOnce(async () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('Quota exceeded')
        })
        return order
    })
    await recoverOrCreateOrder('buyer-a', request)
    await expect(recoverOrCreateOrder('buyer-a', request)).resolves.toEqual(order)
    expect(checkoutAttemptKey('buyer-a', order.id)).toMatch(/^[0-9a-f-]{36}$/i)
    expect(create).toHaveBeenCalledTimes(1)
})
