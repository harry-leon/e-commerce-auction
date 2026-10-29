import { api, type ApiResponse } from './client'

/** Hộp thư từ Notification service; không dùng mock/localStorage khi lỗi API hoặc hết phiên. */
export interface NotificationItem {
    id: string
    orderId: string | null
    type: string | null
    title: string
    content: string
    read: boolean
    createdAt: string
}
export interface Inbox {
    content: NotificationItem[]
    totalElements: number
    unreadCount: number
}

/** Server tự lấy userId từ JWT; signal hủy response khi chuyển trang hoặc đổi tài khoản. */
export async function getNotifications(page: number, signal?: AbortSignal): Promise<Inbox> {
    const response = await api<ApiResponse<Inbox>>(`/notifications?page=${page}&size=20`, {
        signal,
    })
    if (!response.data) throw new Error('Không thể tải thông báo')
    return response.data
}

/** Chỉ cập nhật UI sau khi backend xác nhận quyền sở hữu và ghi trạng thái READ. */
export async function readNotification(id?: string): Promise<void> {
    await api(`/notifications/${id ? `${encodeURIComponent(id)}/read` : 'read-all'}`, {
        method: 'POST',
    })
}
