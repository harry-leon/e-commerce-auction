import { useState, useEffect, useRef, useId, type ReactNode, type InputHTMLAttributes } from 'react'
import type { Toast } from '../types'
import { useApp } from '../context'

// ─── Button ────────────────────────────────────────────────────────────────────
interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger' | 'auction'
    size?: 'sm' | 'md' | 'lg'
    loading?: boolean
    icon?: ReactNode
}
export function Button({
    variant = 'primary',
    size = 'md',
    loading,
    icon,
    children,
    className = '',
    ...props
}: ButtonProps) {
    const cls = `nexus-btn nexus-btn-${variant} ${size === 'sm' ? 'nexus-btn-sm' : size === 'lg' ? 'nexus-btn-lg' : ''} ${className}`
    return (
        <button {...props} className={cls} disabled={props.disabled || loading}>
            {loading ? (
                <Spinner
                    size={14}
                    color={variant === 'outline' || variant === 'ghost' ? '#374151' : '#fff'}
                />
            ) : (
                icon
            )}
            {children}
        </button>
    )
}

// ─── Spinner ───────────────────────────────────────────────────────────────────
export function Spinner({ size = 18, color = '#fff' }: { size?: number; color?: string }) {
    return (
        <svg
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            style={{ animation: 'spin 0.8s linear infinite' }}
        >
            <style>{`@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}`}</style>
            <circle cx="12" cy="12" r="10" stroke={color} strokeOpacity=".25" strokeWidth="3" />
            <path
                d="M22 12A10 10 0 0 0 12 2"
                stroke={color}
                strokeWidth="3"
                strokeLinecap="round"
            />
        </svg>
    )
}

// ─── Input ─────────────────────────────────────────────────────────────────────
interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
    label?: string
    error?: string
    helper?: string
    icon?: ReactNode
    rightIcon?: ReactNode
    suffix?: string
}
export function Input({
    label,
    error,
    helper,
    icon,
    rightIcon,
    suffix,
    className = '',
    ...props
}: InputProps) {
    // ID ổn định liên kết label với input để bấm nhãn/focus, trình đọc màn hình và test getByLabel hoạt động đúng.
    const generatedId = useId()
    const inputId = props.id || generatedId
    return (
        <div className="flex flex-col gap-1.5">
            {label && (
                <label htmlFor={inputId} className="text-sm font-semibold text-gray-700">
                    {label}
                </label>
            )}
            <div className="relative flex items-center">
                {icon && (
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 pointer-events-none flex items-center justify-center">
                        {icon}
                    </span>
                )}
                <input
                    {...props}
                    id={inputId}
                    aria-invalid={!!error}
                    className={`nexus-input ${error ? 'error' : ''} ${icon ? 'has-icon-left !pl-10' : ''} ${rightIcon || suffix ? 'has-icon-right !pr-10' : ''} ${className}`}
                />
                {(rightIcon || suffix) && (
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm">
                        {rightIcon || suffix}
                    </span>
                )}
            </div>
            {error && <p className="text-xs text-danger">{error}</p>}
            {!error && helper && <p className="text-xs text-gray-500">{helper}</p>}
        </div>
    )
}

// ─── PasswordInput ─────────────────────────────────────────────────────────────
export function PasswordInput({
    label,
    error,
    helper,
    ...props
}: Omit<InputProps, 'type' | 'rightIcon'>) {
    const [show, setShow] = useState(false)
    return (
        <Input
            type={show ? 'text' : 'password'}
            label={label}
            error={error}
            helper={helper}
            rightIcon={
                <button
                    type="button"
                    onClick={() => setShow((s) => !s)}
                    className="text-gray-400 hover:text-gray-600"
                >
                    {show ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
            }
            {...props}
        />
    )
}

// ─── Select ────────────────────────────────────────────────────────────────────
interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
    label?: string
    error?: string
    options: { value: string; label: string }[]
}
export function Select({ label, error, options, className = '', ...props }: SelectProps) {
    const generatedId = useId()
    const selectId = props.id || generatedId
    return (
        <div className="flex flex-col gap-1.5">
            {label && (
                <label htmlFor={selectId} className="text-sm font-semibold text-gray-700">
                    {label}
                </label>
            )}
            <select
                {...props}
                id={selectId}
                aria-invalid={!!error}
                className={`nexus-input appearance-none ${error ? 'error' : ''} ${className}`}
                style={{
                    backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'%3E%3Cpath d='M1 1l5 5 5-5' stroke='%236B7280' stroke-width='1.5' fill='none' stroke-linecap='round'/%3E%3C/svg%3E")`,
                    backgroundRepeat: 'no-repeat',
                    backgroundPosition: 'right 12px center',
                }}
            >
                {options.map((o) => (
                    <option key={o.value} value={o.value}>
                        {o.label}
                    </option>
                ))}
            </select>
            {error && <p className="text-xs text-danger">{error}</p>}
        </div>
    )
}

// ─── Textarea ─────────────────────────────────────────────────────────────────
interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
    label?: string
    error?: string
}
export function Textarea({ label, error, className = '', ...props }: TextareaProps) {
    return (
        <div className="flex flex-col gap-1.5">
            {label && <label className="text-sm font-semibold text-gray-700">{label}</label>}
            <textarea
                {...props}
                className={`nexus-input resize-y min-h-[80px] ${error ? 'error' : ''} ${className}`}
            />
            {error && <p className="text-xs text-danger">{error}</p>}
        </div>
    )
}

// ─── Checkbox ─────────────────────────────────────────────────────────────────
export function Checkbox({
    label,
    checked,
    onChange,
}: {
    label: ReactNode
    checked: boolean
    onChange: (v: boolean) => void
}) {
    // Checkbox native nằm trong label: bấm chữ hoặc Space đều đổi trạng thái; ô trang trí không xử lý click lần hai.
    return (
        <label className="flex items-center gap-2 cursor-pointer select-none">
            <input
                type="checkbox"
                checked={checked}
                onChange={(event) => onChange(event.target.checked)}
                className="sr-only"
            />
            <div
                aria-hidden="true"
                className={`w-4 h-4 rounded border-2 flex items-center justify-center transition-colors ${checked ? 'bg-primary border-primary' : 'border-gray-300 bg-white'}`}
            >
                {checked && (
                    <svg width="10" height="8" viewBox="0 0 10 8" fill="none">
                        <path
                            d="M1 4l3 3 5-6"
                            stroke="white"
                            strokeWidth="1.5"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    </svg>
                )}
            </div>
            <span className="text-sm text-gray-700">{label}</span>
        </label>
    )
}

// ─── Toggle ───────────────────────────────────────────────────────────────────
export function Toggle({
    checked,
    onChange,
    label,
    disabled,
}: {
    checked: boolean
    onChange: (v: boolean) => void
    label?: string
    disabled?: boolean
}) {
    return (
        <label
            className={`flex items-center gap-2 ${disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'} select-none`}
        >
            <button
                type="button"
                disabled={disabled}
                onClick={() => !disabled && onChange(!checked)}
                className={`w-10 h-5.5 rounded-full relative transition-colors ${checked ? 'bg-primary' : 'bg-gray-300'}`}
                style={{ width: 40, height: 22 }}
            >
                <span
                    className={`absolute top-0.5 w-[18px] h-[18px] bg-white rounded-full shadow transition-transform ${checked ? 'translate-x-[19px]' : 'translate-x-0.5'}`}
                    style={{ transition: 'transform 150ms' }}
                />
            </button>
            {label && <span className="text-sm text-gray-700">{label}</span>}
        </label>
    )
}

// Badge màu cho OrderStatus (UPPERCASE) đồng nhất với backend chuẩn V2
const STATUS_BADGE: Record<string, string> = {
    // OrderStatus UPPERCASE (chuẩn V2, đồng nhất với backend nexus_schema.sql)
    PENDING_PAYMENT: 'badge-yellow',
    PAID: 'badge-blue',
    PROCESSING: 'badge-indigo',
    SHIPPED: 'badge-blue',
    DELIVERED: 'badge-green',
    COMPLETED: 'badge-green',
    CANCELLED: 'badge-red',
    REFUNDED: 'badge-gray',
    // AuctionStatus (lowercase, không đổi)
    pending_approval: 'badge-yellow',
    scheduled: 'badge-blue',
    active: 'badge-orange',
    paused: 'badge-yellow',
    ended: 'badge-gray',
    settled: 'badge-green',
    failed: 'badge-red',
    rejected: 'badge-red',
    // ProductStatus (lowercase, không đổi)
    draft: 'badge-gray',
    inactive: 'badge-gray',
    // TrustLevel (lowercase)
    bronze: 'badge-yellow',
    silver: 'badge-slate',
    gold: 'badge-yellow',
    platinum: 'badge-indigo',
    // SystemService.status
    up: 'badge-green',
    down: 'badge-red',
    // PaymentStatus (lowercase, không đổi)
    held: 'badge-slate',
    protection_period: 'badge-blue',
    released: 'badge-green',
    on_hold: 'badge-yellow',
    partial_refund: 'badge-yellow',
}

export function StatusBadge({ status, label }: { status: string; label: string }) {
    return <span className={`badge ${STATUS_BADGE[status] || 'badge-gray'}`}>{label}</span>
}

export function Badge({
    children,
    color = 'indigo',
}: {
    children: ReactNode
    color?: 'indigo' | 'orange' | 'green' | 'yellow' | 'red' | 'blue' | 'gray' | 'slate'
}) {
    return <span className={`badge badge-${color}`}>{children}</span>
}

// ─── Modal ─────────────────────────────────────────────────────────────────────
export function Modal({
    open,
    onClose,
    title,
    children,
    footer,
    size = 'md',
}: {
    open: boolean
    onClose: () => void
    title: string
    children: ReactNode
    footer?: ReactNode
    size?: 'sm' | 'md' | 'lg' | 'xl'
}) {
    const sizeW = { sm: 400, md: 520, lg: 680, xl: 860 }[size]
    if (!open) return null
    return (
        // Overlay toàn màn hình; click vùng ngoài modal sẽ đóng (onClose)
        <div
            className="fixed inset-0 z-50 flex items-center justify-center p-4"
            onClick={(e) => e.target === e.currentTarget && onClose()}
        >
            <div className="absolute inset-0 bg-black/40" onClick={onClose} />
            {/* role="dialog" + aria-modal="true" đảm bảo screen reader nhận ra đây là dialog, hỗ trợ getByRole('dialog') trong test */}
            <div
                role="dialog"
                aria-modal="true"
                aria-label={title}
                className="relative bg-white rounded-xl shadow-xl flex flex-col max-h-[90vh] fade-in"
                style={{ width: '100%', maxWidth: sizeW }}
            >
                <div className="flex items-center justify-between px-6 py-4 border-b border-border">
                    <h2 className="text-lg font-bold text-gray-900">{title}</h2>
                    <button
                        onClick={onClose}
                        className="text-gray-400 hover:text-gray-600 p-1 rounded-lg hover:bg-gray-100"
                    >
                        <X size={18} />
                    </button>
                </div>
                <div className="flex-1 overflow-y-auto px-6 py-5">{children}</div>
                {footer && (
                    <div className="px-6 py-4 border-t border-border flex justify-end gap-3">
                        {footer}
                    </div>
                )}
            </div>
        </div>
    )
}

// ─── ConfirmModal ──────────────────────────────────────────────────────────────
export function ConfirmModal({
    open,
    onClose,
    onConfirm,
    title,
    message,
    danger,
}: {
    open: boolean
    onClose: () => void
    onConfirm: () => void
    title: string
    message: string
    danger?: boolean
}) {
    return (
        <Modal
            open={open}
            onClose={onClose}
            title={title}
            size="sm"
            footer={
                <>
                    <Button variant="outline" onClick={onClose}>
                        Cancel
                    </Button>
                    <Button
                        variant={danger ? 'danger' : 'primary'}
                        onClick={() => {
                            onConfirm()
                            onClose()
                        }}
                    >
                        Confirm
                    </Button>
                </>
            }
        >
            <p className="text-gray-600 text-sm">{message}</p>
        </Modal>
    )
}

// ─── Toast Container ───────────────────────────────────────────────────────────
function ToastIcon({ type }: { type: Toast['type'] }) {
    const Icon =
        type === 'success'
            ? CheckCircle
            : type === 'error'
              ? CircleX
              : type === 'warning'
                ? AlertTriangle
                : Info
    return <Icon size={14} />
}

export function ToastContainer({
    toasts = [],
    onDismiss = () => undefined,
}: {
    toasts?: Toast[]
    onDismiss?: (id: string) => void
}) {
    const safeToasts = Array.isArray(toasts) ? toasts : []
    return (
        <div className="toast-container">
            {safeToasts.map((t) => (
                <div
                    key={t.id}
                    className="flex items-start gap-3 bg-white rounded-xl shadow-lg border border-border px-4 py-3 fade-in"
                    style={{ maxWidth: 360 }}
                >
                    <div className="w-6 h-6 rounded-md flex items-center justify-center bg-gray-100 text-gray-600 shrink-0 mt-0.5">
                        <ToastIcon type={t.type} />
                    </div>
                    <p className="text-sm text-gray-700 flex-1">{t.message}</p>
                    <button
                        onClick={() => onDismiss(t.id)}
                        className="text-gray-400 hover:text-gray-600 shrink-0"
                    >
                        <X size={14} />
                    </button>
                </div>
            ))}
        </div>
    )
}

// ─── Tabs ──────────────────────────────────────────────────────────────────────
export function Tabs({
    tabs,
    active,
    onChange,
}: {
    tabs: { id: string; label: string; count?: number }[]
    active: string
    onChange: (id: string) => void
}) {
    return (
        <div className="flex gap-0 border-b border-border">
            {tabs.map((t) => (
                <button
                    key={t.id}
                    onClick={() => onChange(t.id)}
                    className={`px-5 py-3 text-sm font-semibold border-b-2 transition-colors whitespace-nowrap ${active === t.id ? 'border-primary text-primary' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
                >
                    {t.label}
                    {t.count !== undefined && (
                        <span
                            className={`ml-2 text-xs px-1.5 py-0.5 rounded-full font-bold ${active === t.id ? 'bg-primary/10 text-primary' : 'bg-gray-100 text-gray-500'}`}
                        >
                            {t.count}
                        </span>
                    )}
                </button>
            ))}
        </div>
    )
}

// ─── Pagination ───────────────────────────────────────────────────────────────
export function Pagination({
    page,
    total,
    perPage,
    onChange,
}: {
    page: number
    total: number
    perPage: number
    onChange: (p: number) => void
}) {
    const pages = Math.ceil(total / perPage)
    if (pages <= 1) return null
    return (
        <div className="flex items-center gap-1">
            <button
                onClick={() => onChange(page - 1)}
                disabled={page === 1}
                className="nexus-btn nexus-btn-outline nexus-btn-sm px-2.5 disabled:opacity-40"
            >
                <ChevronLeft size={14} />
            </button>
            {Array.from({ length: Math.min(pages, 7) }, (_, i) => {
                const p = i + 1
                return (
                    <button
                        key={p}
                        onClick={() => onChange(p)}
                        className={`nexus-btn nexus-btn-sm w-8 h-8 ${p === page ? 'nexus-btn-primary' : 'nexus-btn-outline'}`}
                    >
                        {p}
                    </button>
                )
            })}
            <button
                onClick={() => onChange(page + 1)}
                disabled={page === pages}
                className="nexus-btn nexus-btn-outline nexus-btn-sm px-2.5 disabled:opacity-40"
            >
                <ChevronRight size={14} />
            </button>
        </div>
    )
}

// ─── EmptyState ───────────────────────────────────────────────────────────────
export function EmptyState({
    icon,
    title,
    subtitle,
    action,
}: {
    icon?: ReactNode
    title: string
    subtitle?: string
    action?: ReactNode
}) {
    return (
        <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-11 h-11 mb-4 rounded-lg border border-border bg-gray-50 text-gray-500 flex items-center justify-center">
                {icon ?? <Inbox size={20} />}
            </div>
            <h3 className="text-lg font-semibold text-gray-700 mb-1">{title}</h3>
            {subtitle && <p className="text-sm text-gray-500 max-w-xs">{subtitle}</p>}
            {action && <div className="mt-5">{action}</div>}
        </div>
    )
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────
export function SkeletonCard() {
    return (
        <div className="nexus-card p-4 space-y-3">
            <div className="skeleton w-full h-40 rounded-lg" />
            <div className="skeleton w-3/4 h-4 rounded" />
            <div className="skeleton w-1/2 h-4 rounded" />
            <div className="skeleton w-1/4 h-6 rounded" />
        </div>
    )
}

export function SkeletonRow() {
    return (
        <tr>
            {Array.from({ length: 5 }).map((_, i) => (
                <td key={i} className="px-4 py-3">
                    <div
                        className="skeleton h-4 rounded"
                        style={{ width: `${60 + Math.random() * 40}%` }}
                    />
                </td>
            ))}
        </tr>
    )
}

// ─── StarRating ───────────────────────────────────────────────────────────────
export function StarRating({
    rating,
    max = 5,
    size = 16,
    interactive,
    onChange,
}: {
    rating: number
    max?: number
    size?: number
    interactive?: boolean
    onChange?: (r: number) => void
}) {
    const [hover, setHover] = useState(0)
    return (
        <div className="flex gap-0.5">
            {Array.from({ length: max }).map((_, i) => {
                const filled = (hover || rating) > i
                return (
                    <svg
                        key={i}
                        width={size}
                        height={size}
                        viewBox="0 0 24 24"
                        fill={filled ? 'currentColor' : 'none'}
                        stroke="currentColor"
                        strokeWidth="2"
                        className={`${filled ? 'text-gray-700' : 'text-gray-300'} ${interactive ? 'cursor-pointer' : ''}`}
                        onMouseEnter={() => interactive && setHover(i + 1)}
                        onMouseLeave={() => interactive && setHover(0)}
                        onClick={() => interactive && onChange?.(i + 1)}
                    >
                        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
                    </svg>
                )
            })}
        </div>
    )
}

// ─── Breadcrumb ───────────────────────────────────────────────────────────────
export function Breadcrumb({ items }: { items: { label: string; page?: string }[] }) {
    const { navigate } = useApp()
    return (
        <nav className="flex items-center gap-2 text-sm text-gray-500">
            {items.map((item, i) => (
                <span key={i} className="flex items-center gap-2">
                    {i > 0 && <ChevronRight size={14} className="text-gray-400" />}
                    {item.page ? (
                        <button
                            onClick={() => navigate(item.page as any)}
                            className="hover:text-primary transition-colors"
                        >
                            {item.label}
                        </button>
                    ) : (
                        <span className="text-gray-800 font-medium">{item.label}</span>
                    )}
                </span>
            ))}
        </nav>
    )
}

// ─── Alert Banner ─────────────────────────────────────────────────────────────
export function AlertBanner({
    type,
    message,
    onClose,
}: {
    type: 'success' | 'error' | 'warning' | 'info'
    message: string
    onClose?: () => void
}) {
    const styles = {
        success: 'bg-success-light border-success text-green-800',
        error: 'bg-danger-light border-danger text-red-800',
        warning: 'bg-warning-light border-warning text-yellow-800',
        info: 'bg-info-light border-info text-blue-800',
    }
    return (
        <div
            className={`flex items-center gap-3 px-4 py-3 rounded-xl border ${styles[type]} text-sm font-medium`}
        >
            <span className="flex-1">{message}</span>
            {onClose && (
                <button onClick={onClose}>
                    <X size={14} />
                </button>
            )}
        </div>
    )
}

// ─── CountdownTimer ───────────────────────────────────────────────────────────
/**
 * Component đếm ngược thời gian phiên đấu giá (CountdownTimer).
 * Chức năng: Đếm ngược từng giây đến mốc endTime; tự động cập nhật mốc đếm khi phiên được gia hạn
 *            (anti-sniping extension), dừng đếm khi hết giờ và kích hoạt callback onEnd.
 * Đầu vào:
 *   - endTime: Chuỗi ISO-8601 thời điểm kết thúc phiên đấu giá.
 *   - large: Hiển thị dạng thẻ số to (màn hình chi tiết) hay định dạng nhỏ gọn (danh sách).
 *   - onEnd: Callback kích hoạt khi thời gian còn lại chạm mốc 0.
 * Kết quả: Render khối thời gian trực quan (Ngày, Giờ, Phút, Giây) tự động đếm lùi mỗi 1000ms.
 * Luồng xử lý:
 *   1. Tính toán số giây còn lại: (endTime - Date.now()) / 1000.
 *   2. Chạy setInterval 1000ms để cập nhật state remaining.
 *   3. Khi remaining <= 0: Dừng interval, chốt 0 và kích hoạt onEnd duy nhất một lần.
 *   4. Khi prop endTime thay đổi: Hủy interval cũ, tính toán mốc mới và tiếp tục đếm lùi.
 * Quy tắc: Không hiển thị số âm (Math.max(0, ...)); tự động pad số có 2 chữ số.
 * Lỗi & giới hạn: Phụ thuộc vào đồng hồ hệ thống client; backend vẫn là nguồn chân lý chốt phiên.
 */
export function CountdownTimer({
    endTime,
    large,
    onEnd,
}: {
    endTime: string
    large?: boolean
    onEnd?: () => void
}) {
    const [remaining, setRemaining] = useState(() =>
        Math.max(0, Math.floor((new Date(endTime).getTime() - Date.now()) / 1000)),
    )
    const onEndRef = useRef(onEnd)
    onEndRef.current = onEnd
    const hasEndedRef = useRef(false)

    useEffect(() => {
        hasEndedRef.current = false
        const update = () => {
            const diff = Math.max(0, Math.floor((new Date(endTime).getTime() - Date.now()) / 1000))
            setRemaining(diff)
            if (diff <= 0) {
                if (!hasEndedRef.current) {
                    hasEndedRef.current = true
                    onEndRef.current?.()
                }
            }
        }
        update()
        const t = setInterval(update, 1000)
        return () => clearInterval(t)
    }, [endTime])

    const d = Math.floor(remaining / 86400)
    const h = Math.floor((remaining % 86400) / 3600)
    const m = Math.floor((remaining % 3600) / 60)
    const s = remaining % 60
    const pad = (n: number) => String(n).padStart(2, '0')

    if (large) {
        const units =
            d > 0
                ? [
                      { v: d, l: 'd' },
                      { v: h, l: 'h' },
                      { v: m, l: 'm' },
                      { v: s, l: 's' },
                  ]
                : [
                      { v: h, l: 'h' },
                      { v: m, l: 'm' },
                      { v: s, l: 's' },
                  ]
        return (
            <div className="flex items-center gap-2">
                {units.map(({ v, l }, i) => (
                    <span key={i} className="flex flex-col items-center">
                        <span className="countdown-digits text-auction bg-auction-light rounded-lg px-3 py-1 font-mono font-bold text-lg">
                            {pad(v)}
                        </span>
                        <span className="text-xs text-gray-500 mt-1">{l}</span>
                    </span>
                ))}
            </div>
        )
    }
    return (
        <span className="text-auction font-bold text-sm font-mono">
            {d > 0 ? `${d}d ` : ''}
            {pad(h)}:{pad(m)}:{pad(s)}
        </span>
    )
}

/** Thanh tiến độ dùng màu CSS rep-bar-fill; value/max quyết định độ rộng, không có prop màu bị bỏ qua. */
export function ProgressBar({ value, max, label }: { value: number; max: number; label?: string }) {
    const pct = Math.min(100, (value / max) * 100)
    return (
        <div>
            {label && (
                <div className="flex justify-between text-xs text-gray-600 mb-1.5">
                    <span>{label}</span>
                    <span>{Math.round(pct)}%</span>
                </div>
            )}
            <div className="rep-bar">
                <div className="rep-bar-fill" style={{ width: `${pct}%` }} />
            </div>
        </div>
    )
}

// ─── Reputation Bar ────────────────────────────────────────────────────────────
export function ReputationBar({ score }: { score: number }) {
    const level =
        score >= 90 ? 'Platinum' : score >= 70 ? 'Gold' : score >= 50 ? 'Silver' : 'Bronze'
    const cls =
        score >= 90
            ? 'trust-platinum'
            : score >= 70
              ? 'trust-gold'
              : score >= 50
                ? 'trust-silver'
                : 'trust-bronze'
    return (
        <div className="space-y-2">
            <div className="flex items-center justify-between">
                <span className={`font-bold text-lg ${cls}`}>{score} pts</span>
                <span className={`font-semibold text-sm ${cls}`}>{level}</span>
            </div>
            <ProgressBar value={score} max={100} />
            <div className="flex justify-between text-xs text-gray-400">
                <span>Bronze (0)</span>
                <span>Silver (50)</span>
                <span>Gold (70)</span>
                <span>Platinum (90)</span>
            </div>
        </div>
    )
}

// ─── OrderTimeline ─────────────────────────────────────────────────────────────
// Bước tiến trình theo đúng OrderStatus UPPERCASE (chuẩn V2, đồng nhất với backend).
const ORDER_STEPS = ['PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'COMPLETED']
export function OrderTimeline({ status }: { status: string }) {
    const cur = ORDER_STEPS.indexOf(status)
    const labels: Record<string, string> = {
        PENDING_PAYMENT: 'Order Placed',
        PAID: 'Payment Confirmed',
        PROCESSING: 'Processing',
        SHIPPED: 'Shipped',
        DELIVERED: 'Delivered',
        COMPLETED: 'Completed',
    }
    if (status === 'CANCELLED')
        return (
            <div className="flex items-center gap-3 p-4 bg-danger-light rounded-xl">
                <CircleX size={18} className="text-gray-600" />
                <span className="font-semibold text-danger">Order Cancelled</span>
            </div>
        )
    if (status === 'REFUNDED')
        return (
            <div className="flex items-center gap-3 p-4 bg-gray-100 rounded-xl">
                <CircleX size={18} className="text-gray-400" />
                <span className="font-semibold text-gray-600">Order Refunded</span>
            </div>
        )
    return (
        <div className="flex flex-col gap-0">
            {ORDER_STEPS.map((step, i) => {
                const done = i <= cur
                return (
                    <div key={step} className="flex gap-3">
                        <div className="flex flex-col items-center">
                            <div
                                className={`w-8 h-8 rounded-full border-2 flex items-center justify-center text-xs font-bold ${done ? 'bg-primary border-primary text-white' : 'border-gray-200 text-gray-300'}`}
                            >
                                {done ? <CheckCircle size={13} /> : i + 1}
                            </div>
                            {i < ORDER_STEPS.length - 1 && (
                                <div
                                    className={`w-0.5 h-8 ${done && i < cur ? 'bg-primary' : 'bg-gray-200'}`}
                                />
                            )}
                        </div>
                        <div className={`pb-6 pt-1 ${done ? 'text-gray-800' : 'text-gray-400'}`}>
                            <p className="font-semibold text-sm">{labels[step]}</p>
                        </div>
                    </div>
                )
            })}
        </div>
    )
}

// ─── DataTable ────────────────────────────────────────────────────────────────
interface Column<T> {
    key: string
    label: string
    render?: (row: T) => ReactNode
    sortable?: boolean
    width?: string
}
interface DataTableProps<T> {
    columns: Column<T>[]
    data: T[]
    loading?: boolean
    onRowClick?: (row: T) => void
    rowKey: (row: T) => string
}
export function DataTable<T>({ columns, data, loading, onRowClick, rowKey }: DataTableProps<T>) {
    return (
        <div className="overflow-x-auto">
            <table className="w-full border-collapse">
                <thead>
                    <tr className="border-b border-border">
                        {columns.map((c) => (
                            <th
                                key={c.key}
                                className={`text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap ${c.sortable ? 'sortable-header' : ''}`}
                                style={c.width ? { width: c.width } : {}}
                            >
                                {c.label}
                            </th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {loading ? (
                        Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} />)
                    ) : data.length === 0 ? (
                        <tr>
                            <td
                                colSpan={columns.length}
                                className="text-center py-12 text-gray-500 text-sm"
                            >
                                No data found
                            </td>
                        </tr>
                    ) : (
                        data.map((row) => (
                            <tr
                                key={rowKey(row)}
                                className={`border-b border-border table-row-hover ${onRowClick ? 'cursor-pointer' : ''}`}
                                onClick={() => onRowClick?.(row)}
                            >
                                {columns.map((c) => (
                                    <td key={c.key} className="px-4 py-3 text-sm text-gray-700">
                                        {c.render
                                            ? c.render(row)
                                            : String((row as any)[c.key] ?? '')}
                                    </td>
                                ))}
                            </tr>
                        ))
                    )}
                </tbody>
            </table>
        </div>
    )
}

// ─── NumberStepper ─────────────────────────────────────────────────────────────
export function NumberStepper({
    value,
    min = 1,
    max,
    onChange,
}: {
    value: number
    min?: number
    max?: number
    onChange: (v: number) => void
}) {
    return (
        <div className="flex items-center gap-0 border border-border rounded-lg overflow-hidden">
            <button
                onClick={() => onChange(Math.max(min, value - 1))}
                className="px-3 py-2 text-gray-600 hover:bg-gray-50 font-bold text-lg leading-none"
            >
                −
            </button>
            <span className="px-4 py-2 text-sm font-semibold border-x border-border min-w-[44px] text-center">
                {value}
            </span>
            <button
                onClick={() => onChange(Math.min(max ?? 99, value + 1))}
                className="px-3 py-2 text-gray-600 hover:bg-gray-50 font-bold text-lg leading-none"
            >
                +
            </button>
        </div>
    )
}

// ─── Minimal icon set (inline SVG helpers) ────────────────────────────────────
type IconProps = { size?: number; className?: string; style?: React.CSSProperties }
export const X = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        className={className}
    >
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
)
export const Eye = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
        <circle cx="12" cy="12" r="3" />
    </svg>
)
export const EyeOff = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
        <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
)
export const ChevronRight = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="9 18 15 12 9 6" />
    </svg>
)
export const ChevronLeft = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="15 18 9 12 15 6" />
    </svg>
)
export const ChevronDown = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="6 9 12 15 18 9" />
    </svg>
)
export const Search = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
)
export const Bell = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.73 21a2 2 0 0 1-3.46 0" />
    </svg>
)
export const ShoppingCart = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="9" cy="21" r="1" />
        <circle cx="20" cy="21" r="1" />
        <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
    </svg>
)
export const Globe = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="12" r="10" />
        <line x1="2" y1="12" x2="22" y2="12" />
        <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
    </svg>
)
export const User2 = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
    </svg>
)
export const Package = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <line x1="16.5" y1="9.4" x2="7.5" y2="4.21" />
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
    </svg>
)
export const Gavel = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="m14 13-7.5 7.5a2.12 2.12 0 0 1-3-3L11 10" />
        <path d="m16 16 6-6" />
        <path d="m8 8 6-6" />
        <path d="m9 7 8 8" />
        <path d="m21 11-8-8" />
    </svg>
)
export const BarChart2 = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <line x1="18" y1="20" x2="18" y2="10" />
        <line x1="12" y1="20" x2="12" y2="4" />
        <line x1="6" y1="20" x2="6" y2="14" />
        <line x1="2" y1="20" x2="22" y2="20" />
    </svg>
)
export const Settings2 = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
)
export const Shield = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </svg>
)
export const Truck = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="1" y="3" width="15" height="13" />
        <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
        <circle cx="5.5" cy="18.5" r="2.5" />
        <circle cx="18.5" cy="18.5" r="2.5" />
    </svg>
)
export const AlertTriangle = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
)
export const CheckCircle = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
        <polyline points="22 4 12 14.01 9 11.01" />
    </svg>
)
export const Plus = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <line x1="12" y1="5" x2="12" y2="19" />
        <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
)
export const Filter = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
    </svg>
)
export const Download = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
)
export const Pencil = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
    </svg>
)
export const Trash2 = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="3 6 5 6 21 6" />
        <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
        <path d="M10 11v6" />
        <path d="M14 11v6" />
        <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
    </svg>
)
export const MoreVertical = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="5" r="1" />
        <circle cx="12" cy="12" r="1" />
        <circle cx="12" cy="19" r="1" />
    </svg>
)
export const LogOut = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
        <polyline points="16 17 21 12 16 7" />
        <line x1="21" y1="12" x2="9" y2="12" />
    </svg>
)
export const Home = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
    </svg>
)
export const Users = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
)
export const Tag = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
        <line x1="7" y1="7" x2="7.01" y2="7" />
    </svg>
)
export const Activity = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
    </svg>
)
export const Star = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
    </svg>
)
export const Warehouse = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M22 8.35V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.35A2 2 0 0 1 3.26 6.5l8-3.2a2 2 0 0 1 1.48 0l8 3.2A2 2 0 0 1 22 8.35Z" />
        <path d="M6 18h12" />
        <path d="M6 14h12" />
        <rect width="8" height="6" x="8" y="18" />
    </svg>
)
export const FileText = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <line x1="16" y1="13" x2="8" y2="13" />
        <line x1="16" y1="17" x2="8" y2="17" />
        <polyline points="10 9 9 9 8 9" />
    </svg>
)
export const Server = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
        <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
        <line x1="6" y1="6" x2="6.01" y2="6" />
        <line x1="6" y1="18" x2="6.01" y2="18" />
    </svg>
)
export const TrendingUp = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="23 6 13.5 15.5 8.5 10.5 1 18" />
        <polyline points="17 6 23 6 23 12" />
    </svg>
)
export const HeartHandshake = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
        <path d="M12 5 9.04 7.96a2.17 2.17 0 0 0 0 3.08v0c.82.82 2.13.85 3 .07l2.07-1.9a2.82 2.82 0 0 1 3.79 0l2.96 2.66" />
        <path d="m18 15-2-2" />
        <path d="m15 18-2-2" />
    </svg>
)
export const Inbox = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
        <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
    </svg>
)
export const Camera = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
        <circle cx="12" cy="13" r="4" />
    </svg>
)
export const ClipboardList = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="4" y="4" width="16" height="18" rx="2" />
        <path d="M9 4V2h6v2" />
        <line x1="9" y1="10" x2="15" y2="10" />
        <line x1="9" y1="14" x2="15" y2="14" />
        <line x1="9" y1="18" x2="13" y2="18" />
    </svg>
)
export const CreditCard = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="2" y="5" width="20" height="14" rx="2" />
        <line x1="2" y1="10" x2="22" y2="10" />
    </svg>
)
export const Gift = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <rect x="3" y="8" width="18" height="13" rx="1" />
        <path d="M12 8v13M3 12h18M12 8H7.5a2.5 2.5 0 1 1 0-5C11 3 12 8 12 8ZM12 8h4.5a2.5 2.5 0 1 0 0-5C13 3 12 8 12 8Z" />
    </svg>
)
export const Trophy = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <path d="M8 21h8M12 17v4M7 4h10v4a5 5 0 0 1-10 0V4Z" />
        <path d="M7 6H3v2a4 4 0 0 0 4 4M17 6h4v2a4 4 0 0 1-4 4" />
    </svg>
)
export const Clock = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="12" r="9" />
        <polyline points="12 7 12 12 15 14" />
    </svg>
)
export const CircleX = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="12" r="9" />
        <line x1="9" y1="9" x2="15" y2="15" />
        <line x1="15" y1="9" x2="9" y2="15" />
    </svg>
)
export const Info = ({ size = 16, className = '' }: IconProps) => (
    <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
    >
        <circle cx="12" cy="12" r="9" />
        <line x1="12" y1="11" x2="12" y2="16" />
        <line x1="12" y1="8" x2="12.01" y2="8" />
    </svg>
)
export const Globe2 = Globe

// ─── LanguageSwitcher ─────────────────────────────────────────────────────────
export function LanguageSwitcher() {
    const { lang, setLang } = useApp()
    const [open, setOpen] = useState(false)
    const ref = useRef<HTMLDivElement>(null)
    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
        }
        document.addEventListener('mousedown', handler)
        return () => document.removeEventListener('mousedown', handler)
    }, [])
    return (
        <div ref={ref} className="relative">
            <button
                onClick={() => setOpen((o) => !o)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold text-gray-700 hover:bg-gray-100 border border-border transition-colors"
            >
                <Globe size={14} />
                {lang.toUpperCase()}
                <ChevronDown size={12} />
            </button>
            {open && (
                <div className="absolute right-0 top-full mt-1.5 bg-white border border-border rounded-xl shadow-lg py-1.5 min-w-[110px] z-50 slide-down">
                    {(['en', 'vi'] as const).map((l) => (
                        <button
                            key={l}
                            onClick={() => {
                                setLang(l)
                                setOpen(false)
                            }}
                            className={`flex items-center gap-2 w-full px-4 py-2 text-sm hover:bg-gray-50 ${lang === l ? 'text-primary font-semibold' : 'text-gray-700'}`}
                        >
                            <span className="w-6 font-mono text-[11px] text-gray-500">
                                {l.toUpperCase()}
                            </span>
                            {l === 'en' ? 'English' : 'Tiếng Việt'}
                            {lang === l && (
                                <CheckCircle size={14} className="ml-auto text-gray-500" />
                            )}
                        </button>
                    ))}
                </div>
            )}
        </div>
    )
}
