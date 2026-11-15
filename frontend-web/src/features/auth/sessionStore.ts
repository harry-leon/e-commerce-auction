import { create } from 'zustand'
import {
    api,
    mapUser,
    readSession,
    saveSession,
    setCanAttemptRefresh,
    subscribeSession,
    type ApiResponse,
    type ApiUser,
} from '../../api/client'
import type { User } from '../../types'

/**
 * Zustand giữ profile cho các màn hình; reload/return từ VNPAY xác minh lại cookie qua /users/me.
 * Chỉ lưu profile; logout chờ server xóa cookie, lỗi giữ phiên để thử lại.
 */
export const useSessionStore = create<{
    user: User | null
    sessionReady: boolean
    setUser: (user: User | null) => Promise<void>
    restoreSession: () => Promise<void>
}>((set) => ({
    user: readSession()?.user || null,
    // Cache profile chỉ để dựng app trong lúc probe cookie; header không hiển thị tài khoản trước khi xác minh xong.
    sessionReady: false,
    setUser: async (user) => {
        if (!user) {
            await api('/auth/logout', { method: 'POST' }, false)
            saveSession(null)
        } else {
            // Lưu cả khi cache tab còn rỗng; profile có thể vừa được lấy từ login hoặc cookie hiện hữu.
            saveSession({ user })
        }
        set({ user, sessionReady: true })
    },
    /**
     * Khôi phục thông tin phiên người dùng khi component mount hoặc trở về từ cổng thanh toán VNPAY.
     * Luồng: Probe cookie tại Gateway; không có cookie thì xóa cache profile, có cookie mới gọi GET /users/me.
     * → nếu thành công lưu hồ sơ vào session store; nếu thất bại (401/400) client tự động dừng thử refresh tiếp.
     */
    restoreSession: () => {
        if (restoring) return restoring
        set({ sessionReady: false })
        // Kích hoạt cờ cho phép probe cookie HttpOnly một lần duy nhất khi khởi động ứng dụng
        setCanAttemptRefresh(true)
        const revision = sessionRevision
        // Tải một lần cho các component cùng mount; không ghi đè login/logout xảy ra khi request còn đang chạy.
        restoring = api<{ hasSessionCookies: boolean }>(
            '/auth/session',
            { cache: 'no-store' },
            false,
        )
            .then((status) => {
                // Không để probe cũ xóa phiên vừa login; lỗi mạng đi vào catch và giữ profile để thử lại.
                if (revision !== sessionRevision) return
                if (!status.hasSessionCookies) {
                    saveSession(null)
                    return
                }
                return api<ApiUser | ApiResponse<ApiUser>>('/users/me')
            })
            .then((payload) => {
                const profile = payload && 'data' in payload ? payload.data : payload
                if (profile?.id && revision === sessionRevision)
                    saveSession({ user: mapUser(profile) })
            })
            .catch(() => {
                // Profile cache không chứng minh được phiên; lỗi probe cũng phải hiển thị như Guest.
                if (revision === sessionRevision) saveSession(null)
            })
            .finally(() => {
                restoring = null
                set({ sessionReady: true })
            })
        return restoring
    },
}))

// Refresh trong HTTP client và logout cùng cập nhật store; revision chặn response khôi phục cũ ghi đè phiên mới.
let sessionRevision = 0
let restoring: Promise<void> | null = null
subscribeSession((session) => {
    sessionRevision++
    useSessionStore.setState({ user: session?.user ?? null })
})
