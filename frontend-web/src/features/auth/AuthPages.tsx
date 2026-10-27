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
    readRememberedEmail,
    saveRememberedEmail,
    type AuthPayload,
} from '../../api/client'
import type { Page } from '../../types'
import { Gavel } from '../../components/UI'

function AuthCard({
    children,
    title,
    subtitle,
}: {
    children: React.ReactNode
    title: string
    subtitle?: string
}) {
    const { lang, navigate } = useApp()
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
    const { lang, navigate, setUser, showToast } = useApp()
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
            const response = await post<AuthPayload>('/auth/login', { email, password })
            const user = mapUser(response.user)
            saveSession({ ...response, user })
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
        </AuthCard>
    )
}

// ─── Register Page ─────────────────────────────────────────────────────────────
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
    const { lang } = useApp()
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

/** Form đăng ký ba bước; validate UI để hướng dẫn nhập, backend vẫn kiểm tra dữ liệu trước khi tạo tài khoản. */
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
        if (!form.dateOfBirth) e.dateOfBirth = tr(lang, 'required')
        setErrors(e)
        return Object.keys(e).length === 0
    }
    const validate3 = () => {
        const e: Partial<typeof form> = {}
        if (form.password.length < 8) e.password = tr(lang, 'passwordRule')
        if (form.password !== form.confirmPassword) e.confirmPassword = tr(lang, 'passwordMismatch')
        setErrors(e)
        return Object.keys(e).length === 0
    }

    // Chuyển tên trường và enum theo DTO backend; đăng ký xong quay về login, không tự giả lập user đã lưu.
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
            showToast({ type: 'error', message: errorMessage(error) })
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
                {STEPS.map((s, i) => (
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
                        placeholder="079xxxxxxxxx"
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
                            helper={tr(lang, 'passwordRule')}
                        />
                        <PasswordStrength password={form.password} />
                    </div>
                    <PasswordInput
                        label={tr(lang, 'confirmPassword')}
                        value={form.confirmPassword}
                        onChange={set('confirmPassword')}
                        error={errors.confirmPassword}
                    />
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
 * Luồng quên mật khẩu: gửi email → nhận link chứa token → nhập mật khẩu mới → gọi reset-password.
 * Nếu mở trực tiếp link từ email, khởi tạo bước 2 và lấy token từ query string; lỗi API giữ người dùng ở form.
 */
/**
 * Yêu cầu email UPDATE_PASSWORD từ Keycloak; link trong email mở trang IAM để nhập mật khẩu mới.
 * Không nhận hoặc lưu reset token tại frontend. Token cũ cần yêu cầu lại email sau khi nâng cấp.
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
                <p role="status" className="text-sm mb-5">
                    {lang === 'en'
                        ? 'If the account exists, follow the link in your email to set a new password on the secure account page.'
                        : 'Nếu tài khoản tồn tại, hãy mở liên kết trong email để đặt mật khẩu mới tại trang quản lý tài khoản.'}
                </p>
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
