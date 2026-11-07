import { expect, it } from 'vitest'
import { getEffectivePaymentStatus } from '../payment'
import { formatCurrency, formatDate, formatDateTime, tr } from '../i18n'
import { ORDERS } from '../mockData'

/** Tiền đang bảo vệ chỉ được giải phóng khi hết hạn và không có tranh chấp đang mở/xem xét. */
it('releases expired protection but retains a payment while a dispute is unresolved', () => {
    const now = Date.parse('2026-10-19T00:00:00Z')
    const order = {
        ...ORDERS[0],
        paymentStatus: 'protection_period' as const,
        protectionEndsAt: '2026-10-18',
        disputeStatus: 'none' as const,
    }
    expect(getEffectivePaymentStatus(order, now)).toBe('released')
    expect(getEffectivePaymentStatus({ ...order, disputeStatus: 'open' }, now)).toBe(
        'protection_period',
    )
    expect(getEffectivePaymentStatus({ ...order, protectionEndsAt: '2026-10-20' }, now)).toBe(
        'protection_period',
    )
})

/** Cùng dữ liệu nghiệp vụ phải có bản dịch và định dạng phù hợp khi đổi ngôn ngữ giao diện. */
it('formats Vietnamese currency/date and resolves both language dictionaries', () => {
    expect(formatCurrency(1000, 'vi')).toMatch(/1[.,]000/)
    expect(formatDate('2026-10-19', 'vi')).toContain('2026')
    expect(formatDateTime('2026-11-05T10:30:00Z', 'vi')).toMatch(/2026/)
    expect(formatDateTime('2026-11-05T10:30:00Z', 'vi')).toMatch(/\d{1,2}:\d{2}/)
    expect(tr('vi', 'login')).toBe('Đăng nhập')
    expect(tr('en', 'login')).toBe('Login')
})
