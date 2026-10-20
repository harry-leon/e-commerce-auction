import type { User, UserRole } from '../types'

/**
 * Cổng gọi backend dùng chung: đọc session → gắn Bearer token → gọi API → chuẩn hóa lỗi.
 * Khi API nghiệp vụ trả 401, thử refresh rồi gửi lại tối đa một lần; không tự giả lập dữ liệu.
 */
const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const tokenKey = 'nexus.session'
export interface Session {
    accessToken: string
    refreshToken: string
    user: User
}

/** Chỉ khôi phục token từ sessionStorage của tab; xóa bản localStorage cũ khi nâng cấp để không giữ token lâu dài. */
export function readSession(): Session | null {
    try {
        localStorage.removeItem(tokenKey)
        return JSON.parse(sessionStorage.getItem(tokenKey) || 'null')
    } catch {
        return null
    }
}

/** Token chỉ sống trong tab; logout xóa cả session và bản token localStorage của phiên bản cũ. */
export function saveSession(session: Session | null) {
    localStorage.removeItem(tokenKey)
    sessionStorage.removeItem(tokenKey)
    if (session) sessionStorage.setItem(tokenKey, JSON.stringify(session))
}

/** Ghi nhớ email chỉ để điền sẵn form; không lưu mật khẩu/token hoặc khôi phục đăng nhập sau khi đóng tab. */
export function readRememberedEmail(): string {
    try {
        return localStorage.getItem('nexus.remembered-email') || ''
    } catch {
        return ''
    }
}

/** Bỏ chọn ghi nhớ thì xóa email cũ; localStorage tuyệt đối không nhận payload session. */
export function saveRememberedEmail(email: string, remember: boolean) {
    if (remember) localStorage.setItem('nexus.remembered-email', email)
    else localStorage.removeItem('nexus.remembered-email')
}

/** Lấy thông báo an toàn để hiển thị trên form khi Promise gọi API bị reject. */
export function errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : 'Request failed'
}

// Các request cùng gặp 401 chờ chung một lần refresh, tránh phát sinh nhiều yêu cầu cấp token đồng thời.
let refreshing: Promise<string> | null = null

/**
 * Trả nguyên payload backend (User DTO hoặc Catalog envelope tùy endpoint).
 * HTTP 204 không parse JSON; lỗi HTTP/envelope bị throw để màn hình hiển thị thất bại.
 * RequestInit được chuyển tiếp, bao gồm AbortSignal để component có thể hủy request khi unmount.
 */
export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
    const session = readSession()
    const headers = new Headers(init.headers)
    if (init.body) headers.set('Content-Type', 'application/json')
    // Không gắn token cũ lên login/register/refresh: Bearer hết hạn có thể khiến public endpoint bị chặn trước controller.
    if (session && (!path.startsWith('/auth/') || path === '/auth/change-password')) {
        headers.set('Authorization', `Bearer ${session.accessToken}`)
    }
    const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
    if (response.status === 401 && session && retry && !path.startsWith('/auth/')) {
        // retry=false giới hạn vòng lặp; refresh thất bại xóa session, finally mở lại khả năng refresh cho lần sau.
        refreshing ??= api<AuthPayload>(
            '/auth/refresh',
            {
                method: 'POST',
                body: JSON.stringify({ refreshToken: session.refreshToken }),
            },
            false,
        )
            .then((payload) => {
                saveSession({ ...payload, user: mapUser(payload.user) })
                return payload.accessToken
            })
            .catch((error) => {
                saveSession(null)
                throw error
            })
            .finally(() => {
                refreshing = null
            })
        await refreshing
        return api<T>(path, init, false)
    }
    if (response.status === 204) return undefined as T
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload.success === false) {
        throw new Error(
            payload.error?.message ||
            payload.message ||
            payload.detail ||
            `Request failed (${response.status})`,
        )
    }
    return payload as T
}

/** Gói dữ liệu thành JSON cho các thao tác POST; vẫn dùng cùng cơ chế xác thực và xử lý lỗi của api(). */
export const post = <T>(path: string, body: unknown) =>
    api<T>(path, { method: 'POST', body: JSON.stringify(body) })

/** Hợp đồng User Service: giữ tên field/role của backend, chuyển sang model giao diện bằng mapUser(). */
export interface ApiUser {
    id: string
    email: string
    fullName: string
    phone?: string
    identifyNumber?: string
    gender?: string
    dateOfBirth?: string
    address?: string
    status: string
    createdAt: string
    roles: string[]
}
export interface AuthPayload {
    accessToken: string
    refreshToken: string
    user: ApiUser
}
/**
 * Chuyển DTO backend sang User của UI; chọn vai trò điều hướng theo thứ tự admin → support → seller → buyer.
 * Avatar/reputation dùng giá trị mặc định vì UserResponse hiện chưa cung cấp các trường này.
 * Vai trò trong UI chỉ phục vụ hiển thị; backend vẫn là nơi kiểm tra quyền truy cập thực sự.
 */
export function mapUser(user: ApiUser): User {
    const roles = user.roles.map((role) => role.toLowerCase())
    const role: UserRole = roles.includes('admin')
        ? 'admin'
        : roles.includes('support')
            ? 'support'
            : roles.includes('seller')
                ? 'seller'
                : 'buyer'
    return {
        id: user.id,
        fullName: user.fullName,
        email: user.email,
        phone: user.phone || '',
        idNumber: user.identifyNumber || '',
        gender: (user.gender?.toLowerCase() || 'other') as User['gender'],
        dateOfBirth: user.dateOfBirth || '',
        address: user.address || '',
        role,
        status: user.status === 'ACTIVE' ? 'active' : 'locked',
        createdAt: user.createdAt,
        avatar: '/avatar.svg',
        trustLevel: 'bronze',
        reputationScore: 0,
    }
}

/** Catalog bọc kết quả trong data, kèm meta phân trang zero-based; khác với DTO trả trực tiếp của User Service. */
export interface Envelope<T> {
    success: boolean
    data: T
    meta?: { total: number; page: number; pageSize: number }
}
export interface ApiCategory {
    id: string
    name: string
    nameVi: string
    parentId: string | null
    level: number
    status: 'ACTIVE' | 'INACTIVE'
    productCount: number
    children: ApiCategory[]
}
export interface ApiProduct {
    id: string
    name: string
    description: string
    categoryId: string
    sellerId: string
    status: string
    auctionEnabled: boolean
    createdAt: string
    skus: {
        id: string
        skuCode: string
        price: number
        originalPrice?: number
        status: string
        attributes?: string
    }[]
    images: { id: string; url: string; sortOrder: number }[]
}
