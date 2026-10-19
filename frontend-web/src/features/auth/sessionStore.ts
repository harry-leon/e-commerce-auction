import { create } from 'zustand'
import { readSession, saveSession } from '../../api/client'
import type { User } from '../../types'

/**
 * Zustand giữ user đang đăng nhập cho các màn hình; khởi tạo từ session đã lưu khi reload.
 * setUser đồng bộ profile vào sessionStorage nhưng giữ token; null là logout và xóa token.
 */
export const useSessionStore = create<{ user: User | null; setUser: (user: User | null) => void }>(
    (set) => ({
        user: readSession()?.user || null,
        setUser: (user) => {
            if (!user) saveSession(null)
            else {
                const session = readSession()
                if (session) saveSession({ ...session, user })
            }
            set({ user })
        },
    }),
)
