import { create } from 'zustand'
import {
    api,
    mapUser,
    readSession,
    saveSession,
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
    setUser: (user: User | null) => Promise<void>
    restoreSession: () => Promise<void>
}>((set) => ({
    user: readSession()?.user || null,
    setUser: async (user) => {
        if (!user) {
            await api('/auth/logout', { method: 'POST' }, false)
            saveSession(null)
        } else {
            // Lưu cả khi cache tab còn rỗng; profile có thể vừa được lấy từ login hoặc cookie hiện hữu.
            saveSession({ user })
        }
        set({ user })
    },
    restoreSession: () => {
        if (restoring) return restoring
        const revision = sessionRevision
        // Tải một lần cho các component cùng mount; không ghi đè login/logout xảy ra khi request còn đang chạy.
        restoring = api<ApiUser | ApiResponse<ApiUser>>('/users/me')
            .then((payload) => {
                const profile = payload && 'data' in payload ? payload.data : payload
                if (profile?.id && revision === sessionRevision)
                    saveSession({ user: mapUser(profile) })
            })
            .catch(() => {
                // Client đã xử lý refresh/hết phiên; lỗi mạng không xóa profile hoặc gửi logout.
            })
            .finally(() => {
                restoring = null
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
