import type { User, UserRole } from '../types'
import type {
    ApiResponse,
    ErrorDetail,
    ValidationErrorDetail,
    MetaInfo,
    PaginationInfo,
    PaginatedResult,
} from './types'

export type {
    ApiResponse,
    ErrorDetail,
    ValidationErrorDetail,
    MetaInfo,
    PaginationInfo,
    PaginatedResult,
}

/**
 * Lớp lỗi chuẩn hóa phía Client khi gọi API thất bại.
 * Đóng gói đầy đủ HTTP status, mã lỗi nghiệp vụ, message và chi tiết validation.
 */
export class ApiClientError<TDetails = unknown> extends Error {
    readonly code: string
    readonly status: number
    readonly details: TDetails | null
    readonly requestId?: string
    readonly timestamp?: string

    constructor(
        message: string,
        code = 'INTERNAL_SERVER_ERROR',
        status = 500,
        details: TDetails | null = null,
        requestId?: string,
        timestamp?: string,
    ) {
        super(message)
        this.name = 'ApiClientError'
        this.code = code
        this.status = status
        this.details = details
        this.requestId = requestId
        this.timestamp = timestamp
    }

    /** Kiểm tra xem có phải lỗi validation đầu vào (HTTP 422) hay không */
    isValidationError(): this is ApiClientError<ValidationErrorDetail[]> {
        return this.status === 422 && Array.isArray(this.details)
    }

    /**
     * Lấy map lỗi theo từng field để bind nhanh vào form validation.
     * Khi một field có nhiều lỗi (ví dụ mật khẩu vừa thiếu chữ hoa vừa thiếu số),
     * gộp các message phân tách bằng dấu chấm để không bỏ sót thông báo.
     */
    getFieldErrors(): Record<string, string> {
        if (!Array.isArray(this.details)) return {}
        const record: Record<string, string> = {}
        for (const item of this.details as ValidationErrorDetail[]) {
            if (item.field) {
                if (record[item.field]) {
                    record[item.field] += '. ' + item.message
                } else {
                    record[item.field] = item.message
                }
            }
        }
        return record
    }

    /** Lấy danh sách toàn bộ message chi tiết từ details */
    getAllDetailMessages(): string[] {
        if (!Array.isArray(this.details)) return []
        return (this.details as ValidationErrorDetail[]).map((d) => d.message).filter(Boolean)
    }
}

/**
 * Trích xuất map lỗi theo field từ ApiClientError hoặc error object bất kỳ.
 * Hỗ trợ đọc cấu trúc error.details[] hoặc details[] từ response 422 của Backend/Gateway.
 */
export function extractFieldErrors(error: unknown): Record<string, string> {
    if (error instanceof ApiClientError) {
        return error.getFieldErrors()
    }
    const anyErr = error as {
        details?: ValidationErrorDetail[]
        error?: { details?: ValidationErrorDetail[] }
    }
    const details = anyErr?.details || anyErr?.error?.details
    if (Array.isArray(details)) {
        const record: Record<string, string> = {}
        for (const item of details) {
            if (item?.field) {
                if (record[item.field]) {
                    record[item.field] += '. ' + item.message
                } else {
                    record[item.field] = item.message
                }
            }
        }
        return record
    }
    return {}
}

/**
 * Cổng gọi backend dùng chung: gửi cookie HttpOnly → gọi API → chuẩn hóa lỗi.
 * Khi API nghiệp vụ trả 401, thử refresh rồi gửi lại tối đa một lần; không tự giả lập dữ liệu.
 */
const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const tokenKey = 'nexus.session'
export interface Session {
    user: User
}

// Profile là cache giao diện, cookie mới là phiên thật; thông báo thay đổi để Zustand cập nhật cả sau refresh.
const sessionListeners = new Set<(session: Session | null) => void>()
let sessionVersion = 0

/** Theo dõi profile trong cùng tab; callback không nhận token, trả cleanup cho nơi đăng ký. */
export function subscribeSession(listener: (session: Session | null) => void) {
    sessionListeners.add(listener)
    return () => sessionListeners.delete(listener)
}

/** Chỉ khôi phục profile UI; xóa phiên cũ có token để chuyển sang cookie HttpOnly. */
export function readSession(): Session | null {
    try {
        localStorage.removeItem(tokenKey)
        const stored = JSON.parse(sessionStorage.getItem(tokenKey) || 'null')
        if (stored && ('accessToken' in stored || 'refreshToken' in stored)) {
            sessionStorage.removeItem(tokenKey)
            return null
        }
        return stored
    } catch {
        return null
    }
}

/** Chỉ lưu profile, không token; đồng bộ người dùng đang hiển thị sau login/refresh/logout.
 * Storage bị chặn không được biến một phiên cookie hợp lệ thành lỗi đăng nhập.
 */
export function saveSession(session: Session | null) {
    sessionVersion++
    const profile = session ? { user: session.user } : null
    try {
        localStorage.removeItem(tokenKey)
        sessionStorage.removeItem(tokenKey)
        if (profile) sessionStorage.setItem(tokenKey, JSON.stringify(profile))
    } catch {
        // Cookie vẫn do browser giữ; lần tải trang sau có thể nạp lại profile từ server.
    }
    sessionListeners.forEach((listener) => listener(profile))
}

/** Ghi nhớ email chỉ điền sẵn form; không lưu mật khẩu/token. Khôi phục phiên phụ thuộc cookie, không phụ thuộc lựa chọn này. */
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
let refreshing: Promise<void> | null = null

/**
 * Trả nguyên payload backend (User DTO hoặc Catalog envelope tùy endpoint).
 * HTTP 204 không parse JSON; lỗi HTTP/envelope bị throw để màn hình hiển thị thất bại.
 * RequestInit được chuyển tiếp, bao gồm AbortSignal để component có thể hủy request khi unmount.
 */
export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
    const headers = new Headers(init.headers)
    if (init.body) headers.set('Content-Type', 'application/json')
    // Browser quản lý cookie; header riêng chống CSRF, JS không đọc hoặc gửi token.
    headers.set('X-Nexus-Request', '1')
    const response = await fetch(`${baseUrl}${path}`, { ...init, headers, credentials: 'include' })
    if (response.status === 401 && retry && !path.startsWith('/auth/')) {
        // Cookie refresh có thể còn dù tab/origin mới không có profile; dùng chung Promise và chỉ gửi lại tối đa một lần.
        const refreshVersion = sessionVersion
        refreshing ??= api<AuthPayload>(
            '/auth/refresh',
            {
                method: 'POST',
                body: JSON.stringify({}),
            },
            false,
        )
            .then((payload) => {
                const auth = (payload as any)?.data || payload
                // Login/logout mới có thể hoàn tất trong lúc refresh cũ còn chạy; không ghi đè profile mới.
                if (refreshVersion === sessionVersion) saveSession({ user: mapUser(auth.user) })
            })
            .catch((error) => {
                // Chỉ hết phiên đã được server xác nhận mới xóa profile; lỗi mạng/5xx cho phép thử lại.
                if (
                    error instanceof ApiClientError &&
                    refreshVersion === sessionVersion &&
                    (error.status === 401 || error.code === 'AUTH_REFRESH_TOKEN_EXPIRED')
                ) {
                    saveSession(null)
                }
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
    // Nguyên tắc Fail-Fast: Kiểm tra status/success trước tiên.
    // Nếu false hoặc HTTP status >= 400: dừng ngay lập tức và ném lỗi, không format hay xử lý tiếp để giảm tải hệ thống.
    const isSuccess = response.ok && payload.success !== false && payload.status !== false
    if (!isSuccess) {
        const errorObj: Partial<ErrorDetail> = payload.error || {}
        /**
         * Chức năng: Chuẩn hóa thông báo lỗi phản hồi từ API Backend.
         * Đầu vào: Dữ liệu payload JSON lỗi từ server hoặc mã trạng thái HTTP.
         * Kết quả: Chuỗi thông báo lỗi rõ ràng, có ý nghĩa cho người dùng UI.
         * Quy tắc: Nếu server trả 401 mà không có body JSON (ví dụ Spring Security chặn trực tiếp ở Gateway),
         *          hiển thị thông báo phiên hết hạn kèm mã (401) thay vì chuỗi kỹ thuật "Request failed (401)".
         */
        const defaultMsg =
            response.status === 401
                ? 'Phiên đăng nhập đã hết hạn hoặc chưa xác thực (401)'
                : `Request failed (${response.status})`
        const msg =
            errorObj.message ||
            payload.message ||
            payload.detail ||
            defaultMsg
        const code =
            errorObj.code ||
            (response.status === 401 ? 'AUTH_UNAUTHORIZED' : 'INTERNAL_SERVER_ERROR')
        throw new ApiClientError(
            msg,
            code,
            response.status,
            errorObj.details ?? payload.details ?? null,
            payload.meta?.requestId,
            payload.meta?.timestamp,
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
    user: ApiUser
}

/** Vai trò và danh sách quyền từ User Service (RoleController) theo SRS §3.1.1 */
export interface ApiRole {
    id: string
    code: string
    name: string
    description: string
    privileges: string[]
}
/**
 * Chuyển DTO backend sang User của UI; chọn vai trò điều hướng theo thứ tự admin → support → seller → buyer.
 * Avatar/reputation dùng giá trị mặc định vì UserResponse hiện chưa cung cấp các trường này.
 * Vai trò trong UI chỉ phục vụ hiển thị; backend vẫn là nơi kiểm tra quyền truy cập thực sự.
 */
export function mapUser(user?: ApiUser | { data: ApiUser } | null): User {
    const raw: any = user && typeof user === 'object' && 'data' in user ? (user as any).data : user
    if (!raw) {
        return {
            id: '',
            fullName: '',
            email: '',
            phone: '',
            idNumber: '',
            gender: 'other',
            dateOfBirth: '',
            address: '',
            role: 'buyer',
            trustLevel: 'bronze',
            reputationScore: 100,
            avatar: 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=100&h=100&fit=crop&auto=format',
            status: 'active',
            createdAt: new Date().toISOString(),
        }
    }
    const roles: string[] = ((raw.roles || []) as string[]).map((role: string) =>
        role.replace(/^ROLE_/, '').toLowerCase(),
    )
    const role: UserRole = roles.includes('admin')
        ? 'admin'
        : roles.includes('support')
          ? 'support'
          : roles.includes('seller')
            ? 'seller'
            : 'buyer'
    return {
        id: raw.id || '',
        fullName: raw.fullName || '',
        email: raw.email || '',
        phone: raw.phone || '',
        idNumber: raw.identifyNumber || '',
        gender: (raw.gender?.toLowerCase() || 'other') as User['gender'],
        dateOfBirth: raw.dateOfBirth || '',
        address: raw.address || '',
        role,
        status:
            raw.status?.toUpperCase() === 'ACTIVE' || raw.status === 'active' ? 'active' : 'locked',
        createdAt: raw.createdAt || new Date().toISOString(),
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
    minPrice?: number
    maxPrice?: number
    skus: {
        id: string
        skuCode?: string | null
        price: number
        originalPrice?: number
        status: string
        attributes?: string
    }[]
    images: { id: string; url: string; sortOrder: number }[]
}

/** Kết quả xem trước giá bán động từ Catalog /api/v1/pricing-rules/preview. */
export interface ApiPricePreview {
    originalPrice: number
    sellingPrice: number
    appliedRuleId?: string | null
    appliedRuleName?: string | null
    marginRate?: number
    seasonFactor?: number
    marketAdjustmentRate?: number
    effectiveRate?: number
}

/**
 * Chuẩn hóa mã SKU hiển thị trên UI sau HG-S2-6:
 * ưu tiên skuCode (do Fulfillment cấp khi nhập kho), nếu chưa có thì hiển thị tiền tố từ Catalog skuId.
 */
export function formatSkuDisplay(skuId?: string | null, skuCode?: string | null): string {
    if (skuCode && skuCode.trim().length > 0) {
        return skuCode.trim()
    }
    if (skuId && skuId.trim().length > 0) {
        return `SKU-${skuId
            .replace(/[^a-zA-Z0-9]/g, '')
            .slice(0, 8)
            .toUpperCase()}`
    }
    return ''
}

/**
 * Chức năng: Lưu trữ và tra cứu tồn kho (stock) cho SKU hoặc Product.
 * Đầu vào: skuCode hoặc productId (chuỗi định danh), số lượng tồn kho (number).
 * Kết quả: Số lượng tồn kho được lưu trữ hoặc null nếu chưa có thông tin.
 * Luồng xử lý: Tra cứu trong localStorage 'nexus.product-stock-cache.v1'.
 * Quy tắc & lý do: Backend Catalog Service hiện chỉ lưu thông tin sản phẩm và SKU,
 *                  không lưu trường stock (tồn kho thuộc về Fulfillment Service).
 *                  Để tránh tình trạng hardcode '10' làm sai lệch dữ liệu người dùng,
 *                  hàm này giúp lưu trữ và tra cứu đúng số lượng mà người bán đã nhập khi tạo sản phẩm.
 * Lỗi & giới hạn: Dữ liệu cache lưu trên localStorage của trình duyệt hiện tại.
 */
export function getProductStock(skuCode?: string, productId?: string): number | null {
    if (!skuCode && !productId) return null
    try {
        const raw = localStorage.getItem('nexus.product-stock-cache.v1')
        if (raw) {
            const map = JSON.parse(raw)
            if (skuCode && map[skuCode] !== undefined) return Number(map[skuCode])
            if (productId && map[productId] !== undefined) return Number(map[productId])
        }
    } catch {}
    return null
}

export function setProductStock(key: string, stock: number) {
    if (!key) return
    try {
        const raw = localStorage.getItem('nexus.product-stock-cache.v1')
        const map = raw ? JSON.parse(raw) : {}
        map[key] = stock
        localStorage.setItem('nexus.product-stock-cache.v1', JSON.stringify(map))
    } catch {}
}
