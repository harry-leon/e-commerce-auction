/**
 * Quy chuẩn API Response & Error Models cho Client (Web & Mobile).
 * Đồng bộ 100% với Backend "QUY CHUẨN API RESPONSE & DANH MỤC MÃ LỖI (v2)".
 * 
 * Top-level Envelope bắt buộc gồm 4 key:
 * {
 *   "success": boolean,
 *   "data": T | null,
 *   "error": ErrorDetail | null,
 *   "meta": MetaInfo
 * }
 */

/**
 * Thông tin meta truy vết và ngữ cảnh của request
 */
export interface MetaInfo {
    /** Mã truy vết đồng bộ với header X-Request-Id (chuẩn req_xxxxxxxx) */
    requestId: string
    /** Thời gian máy chủ sinh phản hồi, chuẩn ISO-8601 UTC */
    timestamp: string
    /** Thông tin phân trang (chỉ xuất hiện khi response là danh sách phân trang) */
    pagination?: PaginationInfo
    /** Tiêu chí sắp xếp đã áp dụng (?sort=field,asc|desc) */
    sort?: string[]
    /** Bộ lọc đã áp dụng */
    filters?: Record<string, unknown>
    /** Danh sách ID không tìm thấy trong thao tác truy vấn hàng loạt */
    notFoundIds?: string[]
    [key: string]: unknown
}

/**
 * Cấu trúc phân trang chuẩn (Spring Data Pageable)
 */
export interface PaginationInfo {
    /** Trang hiện tại, bắt đầu từ 0 */
    page: number
    /** Số phần tử yêu cầu trên mỗi trang (mặc định 20, tối đa 100) */
    size: number
    /** Tổng số phần tử */
    totalElements: number
    /** Tổng số trang */
    totalPages: number
    /** Có trang kế tiếp hay không */
    hasNext: boolean
    /** Có trang trước đó hay không */
    hasPrevious: boolean
    /** Con trỏ trang tiếp theo khi dùng cursor-based / infinite scroll */
    nextCursor?: string
}

/**
 * Chi tiết lỗi kiểm tra tính hợp lệ dữ liệu đầu vào (HTTP 422 - VALIDATION_ERROR)
 */
export interface ValidationErrorDetail {
    /** Tên trường / field bị lỗi */
    field: string
    /** Mã validation chuẩn (NOT_BLANK, MUST_BE_POSITIVE, MIN_VALUE, v.v.) */
    code: string
    /** Thông báo lỗi hiển thị cho người dùng */
    message: string
    /** Tham số bổ sung của rule validation (ví dụ: { min: 1, max: 100 }) */
    params?: Record<string, unknown>
}

/**
 * Cấu trúc đối tượng lỗi trong Envelope
 */
export interface ErrorDetail<TDetails = unknown> {
    /** Mã lỗi nghiệp vụ UPPER_SNAKE_CASE (ví dụ: PRODUCT_NOT_FOUND, VALIDATION_ERROR) */
    code: string
    /** Thông điệp lỗi hiển thị / debug */
    message: string
    /**
     * Dữ liệu chi tiết kèm theo:
     * - null: lỗi thông thường không có dữ liệu kèm theo
     * - ValidationErrorDetail[]: khi lỗi là VALIDATION_ERROR (HTTP 422)
     * - Object: dữ liệu nghiệp vụ kèm theo (ví dụ: { requested: 5, available: 2 })
     */
    details: TDetails | null
}

/**
 * Envelope phản hồi cấp cao nhất từ Backend
 */
export interface ApiResponse<T = unknown, TDetails = unknown> {
    /** true cho HTTP 2xx, false cho HTTP 4xx/5xx */
    success: boolean
    /** Dữ liệu thành công; khi có lỗi luôn là null */
    data: T | null
    /** Thông tin lỗi; khi thành công luôn là null */
    error: ErrorDetail<TDetails> | null
    /** Thông tin metadata kèm theo */
    meta: MetaInfo
}

/**
 * Dữ liệu danh sách phân trang bọc cả dữ liệu và pagination meta
 */
export interface PaginatedResult<T> {
    items: T[]
    pagination: PaginationInfo
    meta: MetaInfo
}
