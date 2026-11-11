import { AlertBanner } from './UI'

/**
 * Chức năng: Spinner Bootstrap thuần với màu nhận diện thương hiệu Nexus (#ee4d2d).
 * Đầu vào:
 *   - size: 'sm' | 'md' | 'lg' hoặc số px.
 *   - color: màu viền chính (mặc định #ee4d2d).
 *   - className: chuỗi class bổ sung.
 * Kết quả: Phần tử div mang chuẩn class .spinner-border của Bootstrap 5.
 */
export function BootstrapSpinner({
    size = 'md',
    color = '#ee4d2d',
    className = '',
}: {
    size?: 'sm' | 'md' | 'lg' | number
    color?: string
    className?: string
}) {
    const sizeStyle =
        typeof size === 'number'
            ? { width: `${size}px`, height: `${size}px` }
            : size === 'lg'
              ? { width: '3rem', height: '3rem' }
              : size === 'sm'
                ? { width: '1rem', height: '1rem' }
                : { width: '2rem', height: '2rem' }

    return (
        <div
            className={`spinner-border text-primary ${size === 'sm' ? 'spinner-border-sm' : ''} ${className}`}
            style={{
                ...sizeStyle,
                color,
                borderTopColor: color,
            }}
            role="status"
        >
            <span className="visually-hidden">Đang tải...</span>
        </div>
    )
}

/**
 * Chức năng: Spinner inline Bootstrap-style dùng chung cho trạng thái tải dữ liệu (section-level).
 * Đầu vào: error (chuỗi lỗi), loading (đang tải).
 * Kết quả: Hiển thị spinner khi loading=true, AlertBanner khi có lỗi, null khi xong.
 * Màu spinner khớp với màu brand chính (#ee4d2d) của trang Nexus.
 */
export function LoadState({ error, loading }: { error: string; loading: boolean }) {
    if (error) return <AlertBanner type="error" message={error} />
    if (loading)
        return (
            <div
                role="status"
                className="flex flex-col items-center justify-center gap-3 py-10"
                aria-label="Đang tải dữ liệu"
            >
                {/* Spinner Bootstrap 5 chuẩn .spinner-border kết hợp màu nhận diện thương hiệu */}
                <div
                    className="spinner-border text-primary"
                    style={{ width: '2.5rem', height: '2.5rem', color: '#ee4d2d' }}
                    role="status"
                >
                    <span className="visually-hidden">Đang tải...</span>
                </div>
                <span className="text-sm text-gray-500 font-medium">Đang tải…</span>
            </div>
        )
    return null
}

/**
 * Chức năng: Spinner toàn trang (full-page overlay) Bootstrap-style dùng khi chuyển trang hoặc khởi động.
 * Đầu vào: label (nhãn hiển thị, tùy chọn), size (kích thước spinner, px).
 * Luồng: Hiển thị ở giữa viewport với backdrop mờ; không block tương tác (pointer-events: none).
 */
export function PageSpinner({ label = 'Đang tải…', size = 48 }: { label?: string; size?: number }) {
    return (
        <div
            role="status"
            aria-label={label}
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 9999,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 16,
                background: 'rgba(249,250,251,0.85)',
                backdropFilter: 'blur(2px)',
                pointerEvents: 'none',
            }}
        >
            {/* Bootstrap border-spinner – màu brand #ee4d2d */}
            <div
                className="spinner-border text-primary"
                style={{
                    width: `${size}px`,
                    height: `${size}px`,
                    color: '#ee4d2d',
                    borderWidth: `${Math.max(3, size / 10)}px`,
                }}
                role="status"
            >
                <span className="visually-hidden">Đang tải...</span>
            </div>
            {label && <span style={{ color: '#6b7280', fontSize: 14, fontWeight: 500 }}>{label}</span>}
        </div>
    )
}

/**
 * Chức năng: Spinner nhỏ inline dùng trong card/widget đang tải (không chiếm toàn trang).
 * Đầu vào: size (px, mặc định 24), color (màu spinner, mặc định brand #ee4d2d).
 */
export function InlineSpinner({ size = 24, color = '#ee4d2d' }: { size?: number; color?: string }) {
    return (
        <div
            className="spinner-border text-primary inline-block flex-shrink-0"
            style={{
                width: `${size}px`,
                height: `${size}px`,
                color,
                borderWidth: `${Math.max(2, size / 10)}px`,
            }}
            role="status"
        >
            <span className="visually-hidden">Đang tải...</span>
        </div>
    )
}
