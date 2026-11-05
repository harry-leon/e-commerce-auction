import { useState } from 'react'
import { useApp } from '../../context'
import { tr } from '../../i18n'
import {
    Input,
    PasswordInput,
    Button,
    Checkbox,
    AlertBanner,
    LanguageSwitcher,
    CheckCircle,
} from '../../components/UI'
import {
    post,
    saveSession,
    mapUser,
    errorMessage,
    extractFieldErrors,
    readRememberedEmail,
    saveRememberedEmail,
    type AuthPayload,
} from '../../api/client'
import {
    validatePasswordPolicy,
    PasswordRequirementsChecklist,
    PasswordConfirmIndicator,
} from './PasswordValidation'
import type { Page } from '../../types'
import { Gavel } from '../../components/UI'

/** Khung auth hiển thị tiêu đề/nội dung và bộ đổi ngôn ngữ; trạng thái đăng nhập do form con xử lý. */
function AuthCard({
    children,
    title,
    subtitle,
}: {
    children: React.ReactNode
    title: string
    subtitle?: string
}) {
    return (
        <div className="min-h-screen bg-page flex items-center justify-center p-4">
            <div className="absolute top-6 right-6">
                <LanguageSwitcher />
            </div>
            <div className="nexus-card p-8 w-full max-w-md">
                <div className="flex items-center gap-2 mb-8">
                    <div className="w-9 h-9 bg-primary rounded-xl flex items-center justify-center">
                        <Gavel size={18} className="text-white" />
                    </div>
                    <span className="text-2xl font-black text-gray-900">Nexus</span>
                </div>
                <h1 className="text-2xl font-bold text-gray-900 mb-1">{title}</h1>
                {subtitle && <p className="text-gray-500 text-sm mb-6">{subtitle}</p>}
                {children}
            </div>
        </div>
    )
}

// ─── Login Page ────────────────────────────────────────────────────────────────
/** Gửi tài khoản tới IAM, giữ token trong tab và chỉ ghi nhớ email nếu được chọn, sau đó điều hướng theo vai trò. */
export function LoginPage() {
    const { lang, navigate, setUser } = useApp()
    const [email, setEmail] = useState(readRememberedEmail)
    const [password, setPassword] = useState('')
    const [remember, setRemember] = useState(() => !!readRememberedEmail())
    const [attempts, setAttempts] = useState(0)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    // Chỉ tạo session sau khi API login thành công; số lần sai/khóa tài khoản thực tế do backend quyết định.
    const handleLogin = async (e: React.FormEvent) => {
        e.preventDefault()
        setLoading(true)
        setError('')
        try {
            // Chức năng: Gửi yêu cầu đăng nhập với email đã loại bỏ khoảng trắng thừa để tránh lỗi nhập liệu
            const rawResponse = await post<AuthPayload | { data: AuthPayload }>('/auth/login', {
                email: email.trim(),
                password,
            })
            const payload: AuthPayload = (rawResponse as any)?.data || rawResponse
            const user = mapUser(payload.user)
            saveSession({ ...payload, user })
            saveRememberedEmail(email, remember)
            setUser(user)
            const pages: Record<string, Page> = {
                buyer: 'home',
                seller: 'seller-dashboard',
                admin: 'admin-dashboard',
                support: 'support-dashboard',
            }
            navigate(pages[user.role])
        } catch (error) {
            setError(errorMessage(error))
            setAttempts((value) => value + 1)
        } finally {
            setLoading(false)
        }
    }

    return (
        <AuthCard
            title={tr(lang, 'login')}
            subtitle={
                lang === 'en'
                    ? 'Sign in to your Nexus account'
                    : 'Đăng nhập vào tài khoản Nexus của bạn'
            }
        >
            {error && (
                <div className="mb-4">
                    <AlertBanner
                        type={attempts >= 5 ? 'error' : 'error'}
                        message={error}
                        onClose={() => setError('')}
                    />
                </div>
            )}
            <form onSubmit={handleLogin} className="space-y-4">
                <Input
                    label={tr(lang, 'email')}
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    required
                    error={error && !email ? tr(lang, 'required') : ''}
                />
                <PasswordInput
                    label={tr(lang, 'password')}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                />
                <div className="flex items-center justify-between">
                    <Checkbox
                        label={tr(lang, 'rememberMe')}
                        checked={remember}
                        onChange={setRemember}
                    />
                    <button
                        type="button"
                        onClick={() => navigate('forgot-password')}
                        className="text-sm text-primary hover:underline"
                    >
                        {tr(lang, 'forgotPassword')}
                    </button>
                </div>
                <Button
                    type="submit"
                    variant="primary"
                    size="lg"
                    loading={loading}
                    className="w-full mt-2"
                >
                    {tr(lang, 'login')}
                </Button>
            </form>

            <p className="text-center text-sm text-gray-500 mt-5">
                {tr(lang, 'dontHaveAccount')}{' '}
                <button
                    onClick={() => navigate('register')}
                    className="text-primary font-semibold hover:underline"
                >
                    {tr(lang, 'register')}
                </button>
            </p>

            {/* Chức năng: Cung cấp nút bấm điền nhanh tài khoản mẫu Buyer cho người dùng thử nghiệm */}
            <div className="mt-5 pt-4 border-t border-gray-100">
                <p className="text-xs text-gray-500 mb-2 font-medium">
                    {lang === 'vi' ? 'Tài khoản mẫu để test nhanh:' : 'Demo accounts for testing:'}
                </p>
                <div className="flex flex-wrap gap-2">
                    <button
                        type="button"
                        onClick={() => {
                            setEmail('buyer@nexus.vn')
                            setPassword('Nexus123@')
                        }}
                        className="text-xs bg-primary/10 hover:bg-primary/20 text-primary font-medium px-2.5 py-1.5 rounded-lg border border-primary/20 transition-colors"
                    >
                        Buyer: buyer@nexus.vn
                    </button>
                </div>
            </div>
        </AuthCard>
    )
}

// ─── Register Page ─────────────────────────────────────────────────────────────
/** Tính thanh độ mạnh chỉ từ password đang nhập, không thay đổi session hoặc gọi API. */
function PasswordStrength({ password }: { password: string }) {
    const strength = !password
        ? 0
        : password.length >= 12 &&
            /[A-Z]/.test(password) &&
            /[0-9]/.test(password) &&
            /[^A-Za-z0-9]/.test(password)
          ? 4
          : password.length >= 8 && /[A-Z]/.test(password) && /[0-9]/.test(password)
            ? 3
            : password.length >= 8
              ? 2
              : 1
    const labels = ['', 'Weak', 'Fair', 'Strong', 'Very Strong']
    const colors = ['', 'bg-danger', 'bg-warning', 'bg-success', 'bg-primary']
    if (!password) return null
    return (
        <div className="mt-1.5">
            <div className="flex gap-1 mb-1">
                {[1, 2, 3, 4].map((i) => (
                    <div
                        key={i}
                        className={`h-1 flex-1 rounded-full ${i <= strength ? colors[strength] : 'bg-gray-200'} transition-colors`}
                    />
                ))}
            </div>
            <p
                className={`text-xs font-medium ${strength <= 1 ? 'text-danger' : strength === 2 ? 'text-warning' : 'text-success'}`}
            >
                {labels[strength]}
            </p>
        </div>
    )
}

/**
 * Form đăng ký ba bước; CCCD là chuỗi 12 chữ số để giữ số 0 đầu.
 * UI hướng dẫn sớm, còn backend vẫn là nơi kiểm tra và tạo tài khoản cuối cùng.
 */
export function RegisterPage() {
    const { lang, navigate, showToast } = useApp()
    const [step, setStep] = useState(1)
    const [loading, setLoading] = useState(false)
    const [form, setForm] = useState({
        email: '',
        phone: '',
        fullName: '',
        idNumber: '',
        gender: 'male',
        dateOfBirth: '',
        address: '',
        password: '',
        confirmPassword: '',
    })
    const [errors, setErrors] = useState<Partial<typeof form>>({})

    const set =
        (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
            setForm((prev) => ({ ...prev, [k]: e.target.value }))

    const validate1 = () => {
        const e: Partial<typeof form> = {}
        if (!form.email.includes('@')) e.email = tr(lang, 'invalidEmail')
        if (form.phone.length < 10) e.phone = tr(lang, 'fieldTooShort')
        setErrors(e)
        return Object.keys(e).length === 0
    }
    const validate2 = () => {
        const e: Partial<typeof form> = {}
        if (!form.fullName) e.fullName = tr(lang, 'required')
        if (!form.idNumber) e.idNumber = tr(lang, 'required')
        else if (!/^\d{12}$/.test(form.idNumber)) e.idNumber = tr(lang, 'cccdFormat')
        if (!form.dateOfBirth) e.dateOfBirth = tr(lang, 'required')
        setErrors(e)
        return Object.keys(e).length === 0
    }
    /**
     * Xác thực bước 3 (bảo mật):
     * 1. Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ hoa (A-Z), 1 số, 1 ký tự đặc biệt.
     *    Chữ in hoa chỉ tính A-Z (không tính chữ tiếng Việt như Đ, Â...).
     * 2. Khoảng trắng không tính là ký tự đặc biệt.
     * 3. Mật khẩu xác nhận phải trùng khớp hoàn toàn với mật khẩu mới.
     */
    const validate3 = () => {
        const e: Partial<typeof form> = {}
        const policy = validatePasswordPolicy(form.password)
        if (!policy.isValid) {
            e.password =
                lang === 'en'
                    ? 'Password must be 8–100 characters, include at least 1 uppercase letter (A-Z), 1 number, and 1 special character'
                    : 'Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ in hoa (A-Z), 1 chữ số, 1 ký tự đặc biệt'
            4
        }
        if (form.password !== form.confirmPassword) {
            e.confirmPassword = tr(lang, 'passwordMismatch')
        }
        setErrors(e)
        return Object.keys(e).length === 0
    }

    /**
     * Gửi đăng ký tới Gateway (POST /api/v1/auth/register).
     * Luồng: validate bước 3 → gửi API → nếu lỗi 422, phân tích error.details[] để hiển thị
     * lỗi cụ thể theo từng trường (field + message) và chuyển về đúng bước chứa trường lỗi.
     * Thành công thì điều hướng về trang đăng nhập.
     */
    const handleSubmit = async () => {
        if (!validate3()) return
        setLoading(true)
        try {
            await post<AuthPayload>('/auth/register', {
                ...form,
                identifyNumber: form.idNumber,
                gender: form.gender.toUpperCase(),
            })
        } catch (error) {
            // Khi BE trả 422, đọc error.details[], dùng field và message để hiển thị từng lỗi
            const fieldErrors = extractFieldErrors(error)
            if (Object.keys(fieldErrors).length > 0) {
                // API dùng identifyNumber, còn state của form dùng idNumber; đổi key để lỗi nằm cạnh ô CCCD.
                const normalizedFieldErrors = {
                    ...fieldErrors,
                    ...(fieldErrors.identifyNumber ? { idNumber: fieldErrors.identifyNumber } : {}),
                }
                setErrors((prev) => ({ ...prev, ...normalizedFieldErrors }))
                // Tự động chuyển về đúng bước chứa trường bị lỗi để người dùng dễ theo dõi
                if (fieldErrors.password) {
                    setStep(3)
                } else if (
                    fieldErrors.fullName ||
                    fieldErrors.identifyNumber ||
                    normalizedFieldErrors.idNumber ||
                    fieldErrors.gender ||
                    fieldErrors.dateOfBirth ||
                    fieldErrors.address
                ) {
                    setStep(2)
                } else if (fieldErrors.email || fieldErrors.phone) {
                    setStep(1)
                }
                const firstMsg = Object.values(fieldErrors)[0]
                showToast({ type: 'error', message: firstMsg })
            } else {
                showToast({ type: 'error', message: errorMessage(error) })
            }
            setLoading(false)
            return
        }
        showToast({
            type: 'success',
            message:
                lang === 'en'
                    ? 'Account created! Please log in.'
                    : 'Tài khoản đã được tạo! Vui lòng đăng nhập.',
        })
        navigate('login')
        setLoading(false)
    }

    const STEPS = [
        tr(lang, 'email') + ' & ' + tr(lang, 'phone'),
        lang === 'en' ? 'Personal Info' : 'Thông tin cá nhân',
        lang === 'en' ? 'Security' : 'Bảo mật',
    ]

    return (
        <AuthCard
            title={tr(lang, 'register')}
            subtitle={lang === 'en' ? 'Create your Nexus account' : 'Tạo tài khoản Nexus của bạn'}
        >
            {/* Step indicator */}
            <div className="flex items-center mb-6">
                {STEPS.map((_step, i) => (
                    <span key={i} className="flex items-center flex-1">
                        <span
                            className={`checkout-step ${i < step ? 'done' : i === step - 1 ? 'current' : 'pending'}`}
                        >
                            <span className="checkout-step-num">
                                {i < step - 1 ? <CheckCircle size={13} /> : i + 1}
                            </span>
                        </span>
                        {i < STEPS.length - 1 && (
                            <span className={`checkout-connector ${i < step - 1 ? 'done' : ''}`} />
                        )}
                    </span>
                ))}
            </div>

            {step === 1 && (
                <div className="space-y-4">
                    <Input
                        label={tr(lang, 'email')}
                        type="email"
                        value={form.email}
                        onChange={set('email')}
                        placeholder="you@example.com"
                        error={errors.email}
                    />
                    <Input
                        label={tr(lang, 'phone')}
                        type="tel"
                        value={form.phone}
                        onChange={set('phone')}
                        placeholder={lang === 'en' ? '09xxxxxxxx' : '09xxxxxxxx'}
                        error={errors.phone}
                    />
                    <Button
                        variant="primary"
                        size="lg"
                        className="w-full"
                        onClick={() => {
                            if (validate1()) setStep(2)
                        }}
                    >
                        {tr(lang, 'next')}
                    </Button>
                </div>
            )}
            {step === 2 && (
                <div className="space-y-4">
                    <Input
                        label={tr(lang, 'fullName')}
                        value={form.fullName}
                        onChange={set('fullName')}
                        error={errors.fullName}
                    />
                    <Input
                        label={tr(lang, 'idNumber')}
                        value={form.idNumber}
                        onChange={set('idNumber')}
                        placeholder="079203001234"
                        inputMode="numeric"
                        pattern="[0-9]{12}"
                        maxLength={12}
                        autoComplete="off"
                        error={errors.idNumber}
                    />
                    <div className="grid grid-cols-2 gap-4">
                        <div className="flex flex-col gap-1.5">
                            <label className="text-sm font-semibold text-gray-700">
                                {tr(lang, 'gender')}
                            </label>
                            <select
                                value={form.gender}
                                onChange={set('gender')}
                                className="nexus-input"
                            >
                                <option value="male">{tr(lang, 'male')}</option>
                                <option value="female">{tr(lang, 'female')}</option>
                                <option value="other">{tr(lang, 'other')}</option>
                            </select>
                        </div>
                        <Input
                            label={tr(lang, 'dateOfBirth')}
                            type="date"
                            value={form.dateOfBirth}
                            onChange={set('dateOfBirth')}
                            error={errors.dateOfBirth}
                        />
                    </div>
                    <Input
                        label={tr(lang, 'address')}
                        value={form.address}
                        onChange={set('address')}
                    />
                    <div className="flex gap-3">
                        <Button
                            variant="outline"
                            size="lg"
                            className="flex-1"
                            onClick={() => setStep(1)}
                        >
                            {tr(lang, 'back')}
                        </Button>
                        <Button
                            variant="primary"
                            size="lg"
                            className="flex-1"
                            onClick={() => {
                                if (validate2()) setStep(3)
                            }}
                        >
                            {tr(lang, 'next')}
                        </Button>
                    </div>
                </div>
            )}
            {step === 3 && (
                <div className="space-y-4">
                    <div>
                        <PasswordInput
                            label={tr(lang, 'password')}
                            value={form.password}
                            onChange={set('password')}
                            error={errors.password}
                        />
                        <PasswordRequirementsChecklist password={form.password} lang={lang} />
                        <PasswordStrength password={form.password} />
                    </div>
                    <div>
                        <PasswordInput
                            label={tr(lang, 'confirmPassword')}
                            value={form.confirmPassword}
                            onChange={set('confirmPassword')}
                            error={errors.confirmPassword}
                        />
                        <PasswordConfirmIndicator
                            password={form.password}
                            confirmPassword={form.confirmPassword}
                            lang={lang}
                        />
                    </div>
                    <div className="flex gap-3">
                        <Button
                            variant="outline"
                            size="lg"
                            className="flex-1"
                            onClick={() => setStep(2)}
                        >
                            {tr(lang, 'back')}
                        </Button>
                        <Button
                            variant="primary"
                            size="lg"
                            className="flex-1"
                            loading={loading}
                            onClick={handleSubmit}
                        >
                            {tr(lang, 'register')}
                        </Button>
                    </div>
                </div>
            )}

            <p className="text-center text-sm text-gray-500 mt-5">
                {tr(lang, 'alreadyHaveAccount')}{' '}
                <button
                    onClick={() => navigate('login')}
                    className="text-primary font-semibold hover:underline"
                >
                    {tr(lang, 'login')}
                </button>
            </p>
        </AuthCard>
    )
}

// ─── Forgot Password Page ──────────────────────────────────────────────────────
/**
 * Yêu cầu email UPDATE_PASSWORD từ Keycloak (POST /api/v1/auth/forgot-password).
 * Sau khi gửi thành công, người dùng được hướng dẫn mở liên kết trong email để đặt mật khẩu tại trang Keycloak.
 * Form và endpoint reset token cũ (/auth/reset-password) đã ngừng hỗ trợ và trả về HTTP 410 (AUTH_RESET_FLOW_CHANGED).
 */
export function ForgotPasswordPage() {
    const { lang, navigate } = useApp()
    const [email, setEmail] = useState('')
    const [sent, setSent] = useState(false)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState('')

    // Chỉ xác nhận gửi yêu cầu sau khi API thành công; response không tiết lộ tài khoản có tồn tại.
    const sendLink = async () => {
        setError('')
        if (!email.includes('@')) {
            setError(tr(lang, 'invalidEmail'))
            return
        }
        setLoading(true)
        try {
            await post('/auth/forgot-password', { email })
            setSent(true)
        } catch (failure) {
            setError(errorMessage(failure))
        } finally {
            setLoading(false)
        }
    }

    return (
        <AuthCard title={tr(lang, 'forgotPassword')}>
            {error && <AlertBanner type="error" message={error} onClose={() => setError('')} />}
            {sent ? (
                <div className="space-y-3 mb-5" role="status">
                    <p className="text-sm text-gray-700 bg-blue-50 border border-blue-200 rounded-lg p-3">
                        {lang === 'en'
                            ? 'If the account exists, follow the link in your email to set a new password on Keycloak.'
                            : 'Nếu tài khoản tồn tại, hãy mở liên kết trong email để đặt mật khẩu mới tại trang quản lý tài khoản Keycloak.'}
                    </p>
                    <p className="text-xs text-gray-500">
                        {lang === 'en'
                            ? 'Notice: Legacy reset password tokens (/auth/reset-password) are no longer supported (HTTP 410). Password updates are handled securely via Keycloak.'
                            : 'Lưu ý: Endpoint đặt lại mật khẩu cũ (/auth/reset-password) đã ngừng hỗ trợ (HTTP 410). Quá trình đặt lại được thực hiện an toàn qua Keycloak.'}
                    </p>
                </div>
            ) : (
                <div className="space-y-4">
                    <Input
                        label={tr(lang, 'email')}
                        type="email"
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        placeholder="you@example.com"
                    />
                    <Button
                        variant="primary"
                        size="lg"
                        className="w-full"
                        loading={loading}
                        onClick={sendLink}
                    >
                        {tr(lang, 'sendResetLink')}
                    </Button>
                </div>
            )}
            <Button variant="primary" className="w-full mt-4" onClick={() => navigate('login')}>
                {tr(lang, 'backToLogin')}
            </Button>
        </AuthCard>
    )
}
