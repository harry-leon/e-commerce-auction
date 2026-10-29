import type { Lang } from '../../i18n'
import { CheckCircle } from '../../components/UI'

/**
 * Kết quả kiểm tra chính sách mật khẩu theo contract BE mới:
 * 1. Độ dài: từ 8 đến 100 ký tự.
 * 2. Chữ in hoa: ít nhất 1 chữ in hoa A–Z (chỉ tính [A-Z], không tính chữ tiếng Việt như Đ, Â...).
 * 3. Chữ số: ít nhất 1 chữ số (0-9).
 * 4. Ký tự đặc biệt: ít nhất 1 ký tự đặc biệt. Khoảng trắng không tính là ký tự đặc biệt.
 */
export interface PasswordValidationResult {
    length: boolean
    hasUppercase: boolean
    hasDigit: boolean
    hasSpecialChar: boolean
    isValid: boolean
}

/**
 * Kiểm tra mật khẩu theo các quy tắc bảo mật của hệ thống.
 * Hàm thuần túy (pure function) nhận chuỗi mật khẩu và trả về trạng thái chi tiết từng điều kiện.
 * Chữ in hoa chỉ tính chữ A–Z chuẩn (các chữ có dấu như Đ, Â không được tính là chữ in hoa).
 */
export function validatePasswordPolicy(password: string): PasswordValidationResult {
    const length = password.length >= 8 && password.length <= 100
    // Chỉ chấp nhận chữ in hoa chuẩn A-Z (không tính Đ, Â...)
    const hasUppercase = /[A-Z]/.test(password)
    // Ít nhất 1 chữ số 0–9
    const hasDigit = /[0-9]/.test(password)
    // Ký tự đặc biệt chuẩn, khoảng trắng không tính là ký tự đặc biệt
    const hasSpecialChar = /[!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?`~]/.test(password)

    return {
        length,
        hasUppercase,
        hasDigit,
        hasSpecialChar,
        isValid: length && hasUppercase && hasDigit && hasSpecialChar,
    }
}

interface CriteriaItem {
    id: keyof Omit<PasswordValidationResult, 'isValid'>
    labelVi: string
    labelEn: string
}

const CRITERIA_ITEMS: CriteriaItem[] = [
    {
        id: 'length',
        labelVi: 'Từ 8 đến 100 ký tự',
        labelEn: '8–100 characters',
    },
    {
        id: 'hasUppercase',
        labelVi: 'Ít nhất 1 chữ in hoa (A–Z)',
        labelEn: 'At least 1 uppercase letter (A–Z)',
    },
    {
        id: 'hasDigit',
        labelVi: 'Ít nhất 1 chữ số (0–9)',
        labelEn: 'At least 1 number (0–9)',
    },
    {
        id: 'hasSpecialChar',
        labelVi: 'Ít nhất 1 ký tự đặc biệt (ví dụ @, !, #; khoảng trắng không tính)',
        labelEn: 'At least 1 special character (e.g. @, !, #; space excluded)',
    },
]

/**
 * Component hiển thị danh sách 4 điều kiện mật khẩu ngay dưới ô nhập mật khẩu mới.
 * Tự động đánh dấu xanh khi đạt và xám khi chưa đạt trong thời gian thực khi người dùng gõ.
 */
export function PasswordRequirementsChecklist({
    password,
    lang = 'vi',
}: {
    password: string
    lang?: Lang
}) {
    const result = validatePasswordPolicy(password)

    return (
        <div
            className="mt-2 p-3 bg-gray-50/80 rounded-xl border border-gray-100 text-xs space-y-1.5"
            data-testid="password-requirements"
        >
            <p className="font-semibold text-gray-600 mb-1">
                {lang === 'en' ? 'Password requirements:' : 'Yêu cầu mật khẩu:'}
            </p>
            <ul className="space-y-1">
                {CRITERIA_ITEMS.map((item) => {
                    const met = result[item.id]
                    return (
                        <li
                            key={item.id}
                            className={`flex items-center gap-1.5 transition-colors ${
                                met ? 'text-emerald-700 font-medium' : 'text-gray-500'
                            }`}
                            data-testid={`criteria-${item.id}`}
                            data-met={met}
                        >
                            {met ? (
                                <CheckCircle size={13} className="text-emerald-500 shrink-0" />
                            ) : (
                                <span
                                    className="w-3.5 h-3.5 rounded-full border border-gray-300 shrink-0 inline-block bg-white"
                                    aria-hidden="true"
                                />
                            )}
                            <span>{lang === 'en' ? item.labelEn : item.labelVi}</span>
                        </li>
                    )
                })}
            </ul>
        </div>
    )
}

/**
 * Component hiển thị trạng thái khớp của mật khẩu xác nhận ngay dưới ô nhập xác nhận.
 */
export function PasswordConfirmIndicator({
    password,
    confirmPassword,
    lang = 'vi',
}: {
    password: string
    confirmPassword: string
    lang?: Lang
}) {
    if (!confirmPassword) return null

    const match = password === confirmPassword

    return (
        <div
            className={`mt-1.5 text-xs flex items-center gap-1.5 font-medium ${
                match ? 'text-emerald-600' : 'text-red-500'
            }`}
            data-testid="confirm-password-indicator"
            data-match={match}
        >
            {match ? (
                <>
                    <CheckCircle size={13} className="text-emerald-500 shrink-0" />
                    <span>
                        {lang === 'en' ? 'Passwords match' : 'Mật khẩu xác nhận trùng khớp'}
                    </span>
                </>
            ) : (
                <>
                    <span className="w-1.5 h-1.5 rounded-full bg-red-500 shrink-0 inline-block" />
                    <span>
                        {lang === 'en' ? 'Passwords do not match' : 'Mật khẩu xác nhận chưa khớp'}
                    </span>
                </>
            )}
        </div>
    )
}
