import { useState } from 'react'
import { useApp } from '../../context'
import { tr } from '../../i18n'
import { Input, PasswordInput, Button, Checkbox, AlertBanner, LanguageSwitcher, CheckCircle } from '../../components/UI'
import { DEMO_BUYER, DEMO_SELLER, DEMO_ADMIN, DEMO_SUPPORT } from '../../mockData'
import type { User } from '../../types'
import { Gavel } from '../../components/UI'

function AuthCard({ children, title, subtitle }: { children: React.ReactNode; title: string; subtitle?: string }) {
  const { lang, navigate } = useApp()
  return (
    <div className="min-h-screen bg-page flex items-center justify-center p-4">
      <div className="absolute top-6 right-6"><LanguageSwitcher /></div>
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
export function LoginPage() {
  const { lang, navigate, setUser, showToast } = useApp()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [remember, setRemember] = useState(false)
  const [attempts, setAttempts] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const DEMO_USERS: [string, User, string][] = [
    ['buyer@nexus.vn', DEMO_BUYER, 'Buyer'],
    ['seller@nexus.vn', DEMO_SELLER, 'Seller'],
    ['admin@nexus.vn', DEMO_ADMIN, 'Admin'],
    ['support@nexus.vn', DEMO_SUPPORT, 'Support'],
  ]

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    if (attempts >= 5) { setError(tr(lang, 'accountLocked')); return }
    setLoading(true)
    await new Promise(r => setTimeout(r, 700))
    const found = DEMO_USERS.find(([em]) => em === email.toLowerCase())
    if (found && password === 'password') {
      setUser(found[1])
      showToast({ type: 'success', message: lang === 'en' ? `Welcome back, ${found[1].fullName}!` : `Chào mừng trở lại, ${found[1].fullName}!` })
      const rolePages: Record<string, string> = { buyer: 'home', seller: 'seller-dashboard', admin: 'admin-dashboard', support: 'support-dashboard' }
      navigate(rolePages[found[1].role] as any)
    } else {
      const newAttempts = attempts + 1
      setAttempts(newAttempts)
      setError(newAttempts >= 5 ? tr(lang, 'accountLocked') : tr(lang, 'loginError'))
    }
    setLoading(false)
  }

  return (
    <AuthCard title={tr(lang, 'login')} subtitle={lang === 'en' ? 'Sign in to your Nexus account' : 'Đăng nhập vào tài khoản Nexus của bạn'}>
      {error && <div className="mb-4"><AlertBanner type={attempts >= 5 ? 'error' : 'error'} message={error} onClose={() => setError('')} /></div>}
      <form onSubmit={handleLogin} className="space-y-4">
        <Input label={tr(lang, 'email')} type="email" value={email} onChange={e => setEmail(e.target.value)}
          placeholder="you@example.com" required error={error && !email ? tr(lang, 'required') : ''} />
        <PasswordInput label={tr(lang, 'password')} value={password} onChange={e => setPassword(e.target.value)} required />
        <div className="flex items-center justify-between">
          <Checkbox label={tr(lang, 'rememberMe')} checked={remember} onChange={setRemember} />
          <button type="button" onClick={() => navigate('forgot-password')} className="text-sm text-primary hover:underline">{tr(lang, 'forgotPassword')}</button>
        </div>
        <Button type="submit" variant="primary" size="lg" loading={loading} className="w-full mt-2">{tr(lang, 'login')}</Button>
      </form>

      {/* Demo accounts */}
      <div className="mt-6 pt-5 border-t border-border">
        <p className="text-xs text-gray-500 mb-3 text-center">{lang === 'en' ? 'Demo accounts (password: password)' : 'Tài khoản demo (mật khẩu: password)'}</p>
        <div className="grid grid-cols-2 gap-2">
          {DEMO_USERS.map(([em, user, role]) => (
            <button key={em} onClick={() => { setEmail(em); setPassword('password') }}
              className="flex items-center gap-2 p-2 border border-border rounded-lg text-xs hover:border-primary hover:bg-primary-light transition-colors">
              <img src={user.avatar} alt="" className="w-6 h-6 rounded-full object-cover" />
              <div className="text-left">
                <p className="font-semibold text-gray-800">{role}</p>
                <p className="text-gray-400 text-[10px] truncate">{em}</p>
              </div>
            </button>
          ))}
        </div>
      </div>

      <p className="text-center text-sm text-gray-500 mt-5">
        {tr(lang, 'dontHaveAccount')}{' '}
        <button onClick={() => navigate('register')} className="text-primary font-semibold hover:underline">{tr(lang, 'register')}</button>
      </p>
    </AuthCard>
  )
}

// ─── Register Page ─────────────────────────────────────────────────────────────
function PasswordStrength({ password }: { password: string }) {
  const strength = !password ? 0 : password.length >= 12 && /[A-Z]/.test(password) && /[0-9]/.test(password) && /[^A-Za-z0-9]/.test(password) ? 4 : password.length >= 8 && /[A-Z]/.test(password) && /[0-9]/.test(password) ? 3 : password.length >= 8 ? 2 : 1
  const labels = ['', 'Weak', 'Fair', 'Strong', 'Very Strong']
  const colors = ['', 'bg-danger', 'bg-warning', 'bg-success', 'bg-primary']
  const { lang } = useApp()
  if (!password) return null
  return (
    <div className="mt-1.5">
      <div className="flex gap-1 mb-1">{[1,2,3,4].map(i => <div key={i} className={`h-1 flex-1 rounded-full ${i <= strength ? colors[strength] : 'bg-gray-200'} transition-colors`} />)}</div>
      <p className={`text-xs font-medium ${strength <= 1 ? 'text-danger' : strength === 2 ? 'text-warning' : 'text-success'}`}>{labels[strength]}</p>
    </div>
  )
}

export function RegisterPage() {
  const { lang, navigate, showToast } = useApp()
  const [step, setStep] = useState(1)
  const [loading, setLoading] = useState(false)
  const [form, setForm] = useState({ email: '', phone: '', fullName: '', idNumber: '', gender: 'male', dateOfBirth: '', address: '', password: '', confirmPassword: '' })
  const [errors, setErrors] = useState<Partial<typeof form>>({})

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm(prev => ({ ...prev, [k]: e.target.value }))

  const validate1 = () => {
    const e: Partial<typeof form> = {}
    if (!form.email.includes('@')) e.email = tr(lang, 'invalidEmail')
    if (form.phone.length < 10) e.phone = tr(lang, 'fieldTooShort')
    setErrors(e); return Object.keys(e).length === 0
  }
  const validate2 = () => {
    const e: Partial<typeof form> = {}
    if (!form.fullName) e.fullName = tr(lang, 'required')
    if (!form.idNumber) e.idNumber = tr(lang, 'required')
    if (!form.dateOfBirth) e.dateOfBirth = tr(lang, 'required')
    setErrors(e); return Object.keys(e).length === 0
  }
  const validate3 = () => {
    const e: Partial<typeof form> = {}
    if (form.password.length < 8) e.password = tr(lang, 'passwordRule')
    if (form.password !== form.confirmPassword) e.confirmPassword = tr(lang, 'passwordMismatch')
    setErrors(e); return Object.keys(e).length === 0
  }

  const handleSubmit = async () => {
    if (!validate3()) return
    setLoading(true)
    await new Promise(r => setTimeout(r, 1000))
    showToast({ type: 'success', message: lang === 'en' ? 'Account created! Please log in.' : 'Tài khoản đã được tạo! Vui lòng đăng nhập.' })
    navigate('login')
    setLoading(false)
  }

  const STEPS = [tr(lang, 'email') + ' & ' + tr(lang, 'phone'), lang === 'en' ? 'Personal Info' : 'Thông tin cá nhân', lang === 'en' ? 'Security' : 'Bảo mật']

  return (
    <AuthCard title={tr(lang, 'register')} subtitle={lang === 'en' ? 'Create your Nexus account' : 'Tạo tài khoản Nexus của bạn'}>
      {/* Step indicator */}
      <div className="flex items-center mb-6">
        {STEPS.map((s, i) => (
          <span key={i} className="flex items-center flex-1">
            <span className={`checkout-step ${i < step ? 'done' : i === step - 1 ? 'current' : 'pending'}`}>
              <span className="checkout-step-num">{i < step - 1 ? <CheckCircle size={13} /> : i + 1}</span>
            </span>
            {i < STEPS.length - 1 && <span className={`checkout-connector ${i < step - 1 ? 'done' : ''}`} />}
          </span>
        ))}
      </div>

      {step === 1 && (
        <div className="space-y-4">
          <Input label={tr(lang, 'email')} type="email" value={form.email} onChange={set('email')} placeholder="you@example.com" error={errors.email} />
          <Input label={tr(lang, 'phone')} type="tel" value={form.phone} onChange={set('phone')} placeholder={lang === 'en' ? '09xxxxxxxx' : '09xxxxxxxx'} error={errors.phone} />
          <Button variant="primary" size="lg" className="w-full" onClick={() => { if (validate1()) setStep(2) }}>{tr(lang, 'next')}</Button>
        </div>
      )}
      {step === 2 && (
        <div className="space-y-4">
          <Input label={tr(lang, 'fullName')} value={form.fullName} onChange={set('fullName')} error={errors.fullName} />
          <Input label={tr(lang, 'idNumber')} value={form.idNumber} onChange={set('idNumber')} placeholder="079xxxxxxxxx" error={errors.idNumber} />
          <div className="grid grid-cols-2 gap-4">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-semibold text-gray-700">{tr(lang, 'gender')}</label>
              <select value={form.gender} onChange={set('gender')} className="nexus-input">
                <option value="male">{tr(lang, 'male')}</option>
                <option value="female">{tr(lang, 'female')}</option>
                <option value="other">{tr(lang, 'other')}</option>
              </select>
            </div>
            <Input label={tr(lang, 'dateOfBirth')} type="date" value={form.dateOfBirth} onChange={set('dateOfBirth')} error={errors.dateOfBirth} />
          </div>
          <Input label={tr(lang, 'address')} value={form.address} onChange={set('address')} />
          <div className="flex gap-3">
            <Button variant="outline" size="lg" className="flex-1" onClick={() => setStep(1)}>{tr(lang, 'back')}</Button>
            <Button variant="primary" size="lg" className="flex-1" onClick={() => { if (validate2()) setStep(3) }}>{tr(lang, 'next')}</Button>
          </div>
        </div>
      )}
      {step === 3 && (
        <div className="space-y-4">
          <div>
            <PasswordInput label={tr(lang, 'password')} value={form.password} onChange={set('password')} error={errors.password} helper={tr(lang, 'passwordRule')} />
            <PasswordStrength password={form.password} />
          </div>
          <PasswordInput label={tr(lang, 'confirmPassword')} value={form.confirmPassword} onChange={set('confirmPassword')} error={errors.confirmPassword} />
          <div className="flex gap-3">
            <Button variant="outline" size="lg" className="flex-1" onClick={() => setStep(2)}>{tr(lang, 'back')}</Button>
            <Button variant="primary" size="lg" className="flex-1" loading={loading} onClick={handleSubmit}>{tr(lang, 'register')}</Button>
          </div>
        </div>
      )}

      <p className="text-center text-sm text-gray-500 mt-5">
        {tr(lang, 'alreadyHaveAccount')}{' '}
        <button onClick={() => navigate('login')} className="text-primary font-semibold hover:underline">{tr(lang, 'login')}</button>
      </p>
    </AuthCard>
  )
}

// ─── Forgot Password Page ──────────────────────────────────────────────────────
export function ForgotPasswordPage() {
  const { lang, navigate, showToast } = useApp()
  const [step, setStep] = useState(1)
  const [email, setEmail] = useState('')
  const [newPw, setNewPw] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const sendLink = async () => {
    if (!email.includes('@')) { setError(tr(lang, 'invalidEmail')); return }
    setLoading(true)
    await new Promise(r => setTimeout(r, 800))
    setLoading(false)
    setStep(2)
  }

  const resetPw = async () => {
    if (newPw !== confirmPw) { setError(tr(lang, 'passwordMismatch')); return }
    if (newPw.length < 8) { setError(tr(lang, 'passwordRule')); return }
    setLoading(true)
    await new Promise(r => setTimeout(r, 800))
    setLoading(false)
    setStep(3)
  }

  return (
    <AuthCard title={tr(lang, 'forgotPassword')}>
      {step === 1 && (
        <div>
          <p className="text-gray-500 text-sm mb-5">{lang === 'en' ? "Enter your email address and we'll send you a reset link." : "Nhập email của bạn và chúng tôi sẽ gửi liên kết đặt lại mật khẩu."}</p>
          {error && <div className="mb-4"><AlertBanner type="error" message={error} onClose={() => setError('')} /></div>}
          <div className="space-y-4">
            <Input label={tr(lang, 'email')} type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" />
            <Button variant="primary" size="lg" className="w-full" loading={loading} onClick={sendLink}>{tr(lang, 'sendResetLink')}</Button>
            <button onClick={() => navigate('login')} className="w-full text-center text-sm text-gray-500 hover:text-primary">{tr(lang, 'backToLogin')}</button>
          </div>
        </div>
      )}
      {step === 2 && (
        <div>
          <div className="text-center mb-6 p-4 bg-primary-light rounded-xl">
            <p className="text-sm text-primary font-medium">{tr(lang, 'resetEmailSent')}</p>
            <p className="text-xs text-gray-500 mt-1">{email}</p>
          </div>
          <p className="text-gray-500 text-sm mb-5">{lang === 'en' ? 'Enter your new password:' : 'Nhập mật khẩu mới của bạn:'}</p>
          {error && <div className="mb-4"><AlertBanner type="error" message={error} onClose={() => setError('')} /></div>}
          <div className="space-y-4">
            <PasswordInput label={tr(lang, 'newPassword')} value={newPw} onChange={e => setNewPw(e.target.value)} />
            <PasswordInput label={tr(lang, 'confirmPassword')} value={confirmPw} onChange={e => setConfirmPw(e.target.value)} />
            <Button variant="primary" size="lg" className="w-full" loading={loading} onClick={resetPw}>{tr(lang, 'resetPassword')}</Button>
          </div>
        </div>
      )}
      {step === 3 && (
        <div className="text-center py-4">
          <div className="w-16 h-16 bg-success-light rounded-full flex items-center justify-center mx-auto mb-4">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#16A34A" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" /></svg>
          </div>
          <h2 className="text-xl font-bold text-gray-900 mb-2">{tr(lang, 'passwordReset')}</h2>
          <p className="text-gray-500 text-sm mb-6">{lang === 'en' ? 'You can now log in with your new password.' : 'Bạn có thể đăng nhập bằng mật khẩu mới.'}</p>
          <Button variant="primary" size="lg" className="w-full" onClick={() => navigate('login')}>{tr(lang, 'backToLogin')}</Button>
        </div>
      )}
    </AuthCard>
  )
}
