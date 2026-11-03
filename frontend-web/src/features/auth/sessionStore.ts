import { create } from 'zustand'
import { api, readSession, saveSession } from '../../api/client'
import type { User } from '../../types'

/**
 * Zustand giữ user đang đăng nhập cho các màn hình; khởi tạo từ session đã lưu khi reload.
 * Chỉ lưu profile; logout chờ server xóa cookie, lỗi giữ phiên để thử lại.
 */
export const useSessionStore = create<{
    user: User | null
    setUser: (user: User | null) => Promise<void>
}>((set) => ({
    user: readSession()?.user || null,
    setUser: async (user) => {
        if (!user) {
            await api('/auth/logout', { method: 'POST' }, false)
            saveSession(null)
        } else {
            const session = readSession()
            if (session) saveSession({ ...session, user })
        }
        set({ user })
    },
}))
