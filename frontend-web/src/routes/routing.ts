import type { Page } from '../types'

/**
 * Khôi phục màn hình và tham số từ URL khi tải trang hoặc bấm Back/Forward.
 * Link có token đi tới màn reset mật khẩu; URL gốc mở catalog. AppRouter xử lý route không tồn tại.
 */
export function readRoute(): { page: Page; params: Record<string, unknown> } {
    const query = new URLSearchParams(window.location.search)
    const segment = window.location.pathname.slice(1)
    return {
        page: (query.has('token') ? 'forgot-password' : segment || 'home') as Page,
        params: Object.fromEntries(query.entries()),
    }
}

/** Ghi màn hình/tham số vào browser history, mã hóa query và bỏ giá trị null; AppProvider cập nhật React state riêng. */
export function writeRoute(page: Page, params: Record<string, unknown>) {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params))
        if (value != null) query.set(key, String(value))
    window.history.pushState(null, '', `/${page}${query.size ? `?${query}` : ''}`)
}
