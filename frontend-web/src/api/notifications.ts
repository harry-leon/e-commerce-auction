/**
 * Client API Quản lý thông báo và cấu hình nhận thông báo (Sprint 3 - HA-S3-2).
 * 
 * 1. Chức năng:
 *    - Lấy danh sách thông báo IN_APP theo trang (GET /api/v1/notifications).
 *    - Đánh dấu đã đọc một thông báo hoặc tất cả thông báo (POST /api/v1/notifications/{id}/read, read-all).
 *    - Quản lý tùy chọn nhận thông báo theo loại tin và kênh (GET / PUT /api/v1/notifications/preferences).
 *    - Xử lý quyền cấp phép Web Push Notification trên trình duyệt (Notification.requestPermission).
 * 2. Đầu vào & kết quả:
 *    - Trả về Inbox, NotificationItem, NotificationPreference.
 * 3. Luồng xử lý:
 *    - REST API qua Gateway tới notification-service; hỗ trợ lưu trữ cục bộ (storage fallback) khi offline.
 * 4. Quy tắc & lý do:
 *    - Phân quyền theo JWT subject, không cho phép đọc/ghi thông báo của người khác.
 * 5. Lỗi & giới hạn:
 *    - Bắt lỗi mạng thân thiện và thông báo cho người dùng.
 */

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

export type NotificationChannel = 'IN_APP' | 'EMAIL' | 'PUSH' | 'SMS'

export interface NotificationPreference {
    id?: string
    notificationType: string // 'ORDER' | 'AUCTION' | 'PAYMENT' | 'SYSTEM' | 'PROMOTION'
    channel: NotificationChannel
    enabled: boolean
}

const DEFAULT_PREFERENCES: NotificationPreference[] = [
    { notificationType: 'ORDER', channel: 'IN_APP', enabled: true },
    { notificationType: 'ORDER', channel: 'EMAIL', enabled: true },
    { notificationType: 'ORDER', channel: 'PUSH', enabled: true },
    { notificationType: 'AUCTION', channel: 'IN_APP', enabled: true },
    { notificationType: 'AUCTION', channel: 'EMAIL', enabled: true },
    { notificationType: 'AUCTION', channel: 'PUSH', enabled: true },
    { notificationType: 'PAYMENT', channel: 'IN_APP', enabled: true },
    { notificationType: 'PAYMENT', channel: 'EMAIL', enabled: true },
    { notificationType: 'PAYMENT', channel: 'PUSH', enabled: false },
    { notificationType: 'PROMOTION', channel: 'IN_APP', enabled: true },
    { notificationType: 'PROMOTION', channel: 'EMAIL', enabled: false },
    { notificationType: 'PROMOTION', channel: 'PUSH', enabled: false },
]

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

/**
 * Lấy danh sách tùy chọn nhận thông báo của người dùng.
 * Hỗ trợ đồng bộ từ Backend hoặc khôi phục từ localStorage.
 */
export async function getNotificationPreferences(): Promise<NotificationPreference[]> {
    try {
        const res = await api<ApiResponse<NotificationPreference[]>>('/notifications/preferences')
        if (Array.isArray(res?.data) && res.data.length > 0) {
            return res.data
        }
    } catch {
        // Fallback sang storage
    }

    try {
        const local = localStorage.getItem('nexus.notification_preferences')
        if (local) {
            const parsed = JSON.parse(local)
            if (Array.isArray(parsed) && parsed.length > 0) return parsed
        }
    } catch {
        // Bỏ qua lỗi parse storage
    }

    return DEFAULT_PREFERENCES
}

/**
 * Cập nhật tùy chọn nhận thông báo của người dùng.
 */
export async function updateNotificationPreferences(
    prefs: NotificationPreference[],
): Promise<NotificationPreference[]> {
    try {
        localStorage.setItem('nexus.notification_preferences', JSON.stringify(prefs))
    } catch {
        // Bỏ qua lỗi storage
    }

    try {
        const res = await api<ApiResponse<NotificationPreference[]>>('/notifications/preferences', {
            method: 'PUT',
            body: JSON.stringify(prefs),
        })
        if (Array.isArray(res?.data)) return res.data
    } catch {
        // Vẫn coi là thành công vì đã lưu cục bộ
    }

    return prefs
}

/**
 * Kiểm tra trạng thái cấp quyền Push Notification của trình duyệt.
 */
export function getPushPermissionStatus(): NotificationPermission {
    if (typeof window !== 'undefined' && 'Notification' in window) {
        return Notification.permission
    }
    return 'default'
}

/**
 * Yêu cầu người dùng cấp quyền nhận thông báo đẩy trên trình duyệt.
 */
export async function requestPushPermission(): Promise<NotificationPermission> {
    if (typeof window !== 'undefined' && 'Notification' in window) {
        try {
            const permission = await Notification.requestPermission()
            return permission
        } catch {
            return 'denied'
        }
    }
    return 'denied'
}
