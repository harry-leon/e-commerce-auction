import { useState, type FormEvent } from 'react'
import {
    api,
    post,
    mapUser,
    errorMessage,
    extractFieldErrors,
    type ApiUser,
    type ApiResponse,
} from '../../api/client'
import {
    validatePasswordPolicy,
    PasswordRequirementsChecklist,
    PasswordConfirmIndicator,
} from '../auth/PasswordValidation'
import { useApp } from '../../context'
import { AdminLayout, SellerLayout, SupportLayout, BuyerLayout } from '../../components/Layout'
import { Button, Input } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad, clearLoadCache } from '../../hooks/useLoad'

/**
 * Định dạng ngày sinh sang chuỗi YYYY-MM-DD để gán vào input[type="date"].
 * Hỗ trợ chuyển đổi an toàn khi backend trả về chuỗi ISO chứa 'T' (ví dụ 1995-10-25T00:00:00).
 */
function formatDateForInput(dob?: string): string {
    if (!dob) return ''
    return dob.includes('T') ? dob.split('T')[0] : dob
}

/**
 * Tải hồ sơ và 20 hoạt động gần đây của chính tài khoản qua JWT.
 * Lưu hồ sơ bằng PUT /users/me; đổi mật khẩu dùng POST /api/v1/auth/change-password (yêu cầu cookie HttpOnly),
 * áp dụng validation mật khẩu mới và hiển thị lỗi 422 theo từng trường.
 */
export function ProfilePage() {
    const { setUser, showToast, navigate, user, page, lang } = useApp()
    const {
        data: userProfile,
        error,
        loading,
    } = useLoad<ApiResponse<ApiUser> | ApiUser>('/users/me')
    // User Service bọc trang lịch sử trong data; đọc đúng envelope trước khi duyệt content.
    const history = useLoad<
        ApiResponse<{
            content: { id: string; action: string; description: string; createdAt: string }[]
        }>
    >('/users/me/activity-history?size=20')
    const activities = Array.isArray(history.data?.data?.content) ? history.data.data.content : []
    const [saving, setSaving] = useState(false)
    const [failure, setFailure] = useState('')
    const [savedProfile, setSavedProfile] = useState<ApiUser | null>(null)
    const [revision, setRevision] = useState(0)

    // State riêng cho form đổi mật khẩu để validate realtime và hiển thị lỗi theo từng trường
    const [oldPassword, setOldPassword] = useState('')
    const [newPassword, setNewPassword] = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({})

    // State lưu lỗi theo từng trường của form cập nhật hồ sơ cá nhân
    const [profileErrors, setProfileErrors] = useState<Record<string, string>>({})

    /**
     * Chức năng: Trích xuất và hợp nhất dữ liệu người dùng để điền sẵn vào form hồ sơ.
     * Đầu vào: Dữ liệu tải từ BE (/users/me), dữ liệu vừa lưu thành công (savedProfile), và user hiện tại trong session context.
     * Kết quả: Đối tượng hồ sơ đầy đủ thông tin (họ tên, email, sđt, CCCD, ngày sinh, giới tính, địa chỉ).
     * Luồng xử lý:
     * 1. Đọc envelope ApiResponse<ApiUser> nếu có (payload.data), hoặc dùng trực tiếp nếu là DTO thô.
     * 2. Ưu tiên dữ liệu vừa cập nhật (savedProfile) -> dữ liệu server vừa tải (/users/me) -> thông tin đăng nhập trong session (user).
     * Quy tắc và lý do:
     * - Khắc phục lỗi form bị trống trơn do backend bọc dữ liệu trong ApiResponse { success: true, data: { ... } }.
     * - Khi vừa vào trang profile, hiển thị ngay thông tin từ session (0ms latency), không để form bị trống chờ mạng.
     */
    const rawUserProfile = (
        userProfile && typeof userProfile === 'object' && 'data' in userProfile && userProfile.data
            ? (userProfile as ApiResponse<ApiUser>).data
            : userProfile
    ) as ApiUser | null

    const profile = savedProfile || rawUserProfile

    const activeUser = profile
        ? {
              ...profile,
              fullName: profile.fullName || user?.fullName || '',
              email: profile.email || user?.email || '',
              phone: profile.phone || user?.phone || '',
              identifyNumber: profile.identifyNumber || user?.idNumber || '',
              dateOfBirth: formatDateForInput(profile.dateOfBirth || user?.dateOfBirth),
              gender: (profile.gender || user?.gender || 'OTHER').toUpperCase(),
              address: profile.address || user?.address || '',
          }
        : user
          ? {
                id: user.id,
                fullName: user.fullName || '',
                email: user.email || '',
                phone: user.phone || '',
                identifyNumber: user.idNumber || '',
                dateOfBirth: formatDateForInput(user.dateOfBirth),
                gender: (user.gender || 'OTHER').toUpperCase(),
                address: user.address || '',
                status: user.status,
                createdAt: user.createdAt,
                roles: [user.role],
            }
          : null

    /**
     * Cập nhật thông tin cá nhân qua PUT /api/v1/users/me:
     * 1. Chức năng: Lưu thay đổi thông tin cá nhân của người dùng vào hệ thống.
     * 2. Đầu vào và kết quả: Nhận form data (fullName, phone, identifyNumber, dateOfBirth, gender, address);
     *    gửi PUT /users/me; cập nhật session store và cache tải dữ liệu.
     * 3. Luồng xử lý:
     *    - Validate client-side: Họ tên (chữ cái/khoảng trắng, >= 2 từ), CCCD (12 số), Ngày sinh (trong quá khứ, <= 120 tuổi).
     *    - Nếu lỗi client-side: Gán vào profileErrors để hiển thị đỏ dưới từng trường tương ứng, không gửi request.
     *    - Gửi API -> nếu thành công: lưu vào savedProfile & session store, thông báo toast thành công.
     *    - Nếu lỗi từ server (422): trích xuất extractFieldErrors(err) và gán vào profileErrors để khoanh vùng chính xác trường bị sai.
     * 4. Quy tắc và lý do:
     *    - Ngăn chặn người dùng nhập ngày sinh ở tương lai hoặc ký tự đặc biệt ở họ tên.
     *    - Tránh tình trạng thông báo lỗi chung chung mập mờ; người dùng biết ngay ô nào cần sửa.
     */
    const submitProfile = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        const fullName = ((form.get('fullName') as string) || '').trim()
        const phone = ((form.get('phone') as string) || '').trim()
        const identifyNumber = ((form.get('identifyNumber') as string) || '').trim()
        const dateOfBirth = ((form.get('dateOfBirth') as string) || '').trim()

        setSaving(true)
        setFailure('')
        setProfileErrors({})

        // 1. Client-side validation:
        const clientErrors: Record<string, string> = {}

        // Kiểm tra Họ và tên: Bắt buộc, chỉ chứa chữ cái Unicode (có dấu) và khoảng trắng, ít nhất 2 từ
        if (!fullName) {
            clientErrors.fullName =
                lang === 'en' ? 'Full name is required' : 'Họ và tên không được để trống'
        } else if (!/^[\p{L}\s']{2,100}$/u.test(fullName)) {
            clientErrors.fullName =
                lang === 'en'
                    ? 'Full name must contain only letters and spaces (2–100 characters)'
                    : 'Họ và tên chỉ được chứa chữ cái và khoảng trắng (2–100 ký tự)'
        } else if (fullName.split(/\s+/).filter(Boolean).length < 2) {
            clientErrors.fullName =
                lang === 'en'
                    ? 'Full name must include both first and last name (at least 2 words)'
                    : 'Họ và tên phải bao gồm cả họ và tên (ít nhất 2 từ)'
        }

        // Kiểm tra CCCD: Đúng 12 chữ số
        if (!identifyNumber) {
            clientErrors.identifyNumber =
                lang === 'en' ? 'National ID is required' : 'Số CCCD không được để trống'
        } else if (!/^\d{12}$/.test(identifyNumber)) {
            clientErrors.identifyNumber =
                lang === 'en'
                    ? 'National ID must be exactly 12 digits'
                    : 'Số CCCD phải gồm đúng 12 chữ số'
        }

        // Kiểm tra SĐT (nếu có nhập)
        if (phone && !/^(0|\+84)(3|5|7|8|9)[0-9]{8}$/.test(phone)) {
            clientErrors.phone =
                lang === 'en'
                    ? 'Invalid Vietnam phone number format'
                    : 'Số điện thoại không đúng định dạng di động Việt Nam'
        }

        // Kiểm tra Ngày sinh: Phải ở quá khứ và không quá 120 năm trước
        if (dateOfBirth) {
            const dob = new Date(dateOfBirth + 'T00:00:00')
            const today = new Date()
            today.setHours(0, 0, 0, 0)
            const minDate = new Date()
            minDate.setFullYear(minDate.getFullYear() - 120)
            minDate.setHours(0, 0, 0, 0)

            if (isNaN(dob.getTime())) {
                clientErrors.dateOfBirth =
                    lang === 'en' ? 'Invalid date' : 'Ngày sinh không tồn tại trong lịch'
            } else if (dob >= today) {
                clientErrors.dateOfBirth =
                    lang === 'en'
                        ? 'Date of birth must be in the past'
                        : 'Ngày sinh phải ở trong quá khứ'
            } else if (dob < minDate) {
                clientErrors.dateOfBirth =
                    lang === 'en'
                        ? 'Age cannot exceed 120 years'
                        : 'Ngày sinh không hợp lệ (tuổi tối đa 120)'
            }
        }

        if (Object.keys(clientErrors).length > 0) {
            setProfileErrors(clientErrors)
            setSaving(false)
            return
        }

        try {
            const updated = await api<ApiResponse<ApiUser> | ApiUser>('/users/me', {
                method: 'PUT',
                body: JSON.stringify(Object.fromEntries(form)),
            })
            const raw = (
                updated && typeof updated === 'object' && 'data' in updated && updated.data
                    ? (updated as ApiResponse<ApiUser>).data
                    : updated
            ) as ApiUser
            setSavedProfile(raw)
            setRevision((r) => r + 1)
            setUser(mapUser(raw))
            clearLoadCache()
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Profile updated successfully' : 'Đã lưu thay đổi hồ sơ',
            })
        } catch (err) {
            const fieldErrors = extractFieldErrors(err)
            if (Object.keys(fieldErrors).length > 0) {
                setProfileErrors(fieldErrors)
            } else {
                setFailure(errorMessage(err))
            }
        } finally {
            setSaving(false)
        }
    }

    /**
     * Đổi mật khẩu qua POST /api/v1/auth/change-password:
     * - Đầu vào: currentPassword, newPassword (gửi kèm cookie HttpOnly qua client.ts).
     * - Validation:
     *   + Mật khẩu hiện tại: chỉ kiểm tra không để trống, KHÔNG áp dụng policy mới.
     *   + Mật khẩu mới: 8–100 ký tự, ít nhất 1 chữ hoa, 1 số, 1 ký tự đặc biệt (\p{P}\p{S}); khoảng trắng không tính.
     *   + Xác nhận mật khẩu: phải trùng khớp với mật khẩu mới.
     * - Xử lý 422: trích xuất error.details[] từ response BE, ánh xạ đúng field (currentPassword, newPassword) để hiển thị.
     * - Thành công: xóa phiên đăng nhập (setUser(null)) và điều hướng về trang đăng nhập.
     */
    const submitChangePassword = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        const oldPass = ((form.get('oldPassword') as string) || oldPassword || '').trim()
        const newPass = ((form.get('newPassword') as string) || newPassword || '').trim()
        const confirmPass = (
            (form.get('confirmPassword') as string) ||
            confirmPassword ||
            ''
        ).trim()

        setSaving(true)
        setFailure('')
        setPasswordErrors({})

        const errors: Record<string, string> = {}
        if (!oldPass) {
            errors.currentPassword = 'Mật khẩu hiện tại không được để trống'
        }

        const policy = validatePasswordPolicy(newPass)
        if (!policy.isValid) {
            errors.newPassword =
                'Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ in hoa (A-Z), 1 chữ số, 1 ký tự đặc biệt'
        }

        if (newPass !== confirmPass) {
            errors.confirmPassword = 'Mật khẩu xác nhận không khớp'
        }

        if (Object.keys(errors).length > 0) {
            setPasswordErrors(errors)
            setSaving(false)
            return
        }

        try {
            await post('/auth/change-password', {
                currentPassword: oldPass,
                newPassword: newPass,
            })
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Password changed successfully! For security reasons, your current session has ended. Please log in again.'
                        : 'Đổi mật khẩu thành công! Vì lý do bảo mật, phiên làm việc hiện tại đã kết thúc. Vui lòng đăng nhập lại bằng mật khẩu mới.',
            })
            setUser(null)
            navigate('login')
        } catch (err) {
            // Khi BE trả 422, đọc error.details[], dùng field và message để hiển thị từng lỗi
            const fieldErrors = extractFieldErrors(err)
            if (Object.keys(fieldErrors).length > 0) {
                setPasswordErrors(fieldErrors)
                showToast({ type: 'error', message: Object.values(fieldErrors).join('. ') })
            } else {
                setFailure(errorMessage(err))
            }
        } finally {
            setSaving(false)
        }
    }

    // Hàm submit giữ tương thích cho form hồ sơ
    const submit = async (event: FormEvent<HTMLFormElement>, password = false) => {
        if (password) {
            await submitChangePassword(event)
        } else {
            await submitProfile(event)
        }
    }

    const title = lang === 'en' ? 'Profile' : 'Thông tin cá nhân'

    const profileBody = (
        <div className="max-w-4xl">
            <h1 className="text-2xl font-bold mb-5">{title}</h1>
            <LoadState error={failure || error} loading={loading && !activeUser} />
            {activeUser && (
                <form
                    key={`${activeUser.id}-${profile ? 'server' : 'session'}-${revision}`}
                    className="nexus-card p-5 grid sm:grid-cols-2 gap-4"
                    onSubmit={(event) => submit(event)}
                >
                    <Input
                        label="Họ tên"
                        name="fullName"
                        defaultValue={activeUser.fullName}
                        error={profileErrors.fullName}
                        required
                    />
                    <Input label="Email" value={activeUser.email} disabled />
                    <Input
                        label="Số điện thoại"
                        name="phone"
                        defaultValue={activeUser.phone}
                        error={profileErrors.phone}
                    />
                    <Input
                        label="Số giấy tờ"
                        name="identifyNumber"
                        defaultValue={activeUser.identifyNumber}
                        error={profileErrors.identifyNumber}
                    />
                    <Input
                        label="Ngày sinh"
                        type="date"
                        name="dateOfBirth"
                        max={new Date().toISOString().split('T')[0]}
                        defaultValue={activeUser.dateOfBirth}
                        error={profileErrors.dateOfBirth}
                    />
                    <label>
                        Giới tính
                        <select
                            className="nexus-input w-full"
                            name="gender"
                            defaultValue={activeUser.gender || 'OTHER'}
                        >
                            <option>MALE</option>
                            <option>FEMALE</option>
                            <option>OTHER</option>
                        </select>
                    </label>
                    <Input
                        label="Địa chỉ"
                        name="address"
                        defaultValue={activeUser.address}
                        error={profileErrors.address}
                    />
                    <Button type="submit" loading={saving}>
                        Lưu thông tin
                    </Button>
                </form>
            )}
            <form
                className="nexus-card p-5 mt-6 space-y-3"
                onSubmit={(event) => submit(event, true)}
                data-testid="change-password-form"
            >
                <h2 className="font-bold">Đổi mật khẩu</h2>
                <div>
                    <Input
                        label="Mật khẩu hiện tại"
                        name="oldPassword"
                        type="password"
                        onChange={(e) => {
                            setOldPassword(e.target.value)
                            if (passwordErrors.currentPassword || passwordErrors.oldPassword) {
                                setPasswordErrors((prev) => {
                                    const next = { ...prev }
                                    delete next.currentPassword
                                    delete next.oldPassword
                                    return next
                                })
                            }
                        }}
                        error={passwordErrors.currentPassword || passwordErrors.oldPassword}
                        required
                    />
                </div>
                <div>
                    <Input
                        label="Mật khẩu mới"
                        name="newPassword"
                        type="password"
                        onChange={(e) => {
                            setNewPassword(e.target.value)
                            if (passwordErrors.newPassword) {
                                setPasswordErrors((prev) => {
                                    const next = { ...prev }
                                    delete next.newPassword
                                    return next
                                })
                            }
                        }}
                        error={passwordErrors.newPassword}
                        required
                    />
                    <PasswordRequirementsChecklist password={newPassword} lang="vi" />
                </div>
                <div>
                    <Input
                        label="Xác nhận mật khẩu"
                        name="confirmPassword"
                        type="password"
                        onChange={(e) => {
                            setConfirmPassword(e.target.value)
                            if (passwordErrors.confirmPassword) {
                                setPasswordErrors((prev) => {
                                    const next = { ...prev }
                                    delete next.confirmPassword
                                    return next
                                })
                            }
                        }}
                        error={passwordErrors.confirmPassword}
                        required
                    />
                    <PasswordConfirmIndicator
                        password={newPassword}
                        confirmPassword={confirmPassword}
                        lang="vi"
                    />
                </div>
                <Button type="submit" loading={saving}>
                    Đổi mật khẩu
                </Button>
            </form>
            <section className="nexus-card p-5 mt-6">
                <h2 className="font-bold">Lịch sử hoạt động gần đây</h2>
                <LoadState error={history.error} loading={history.loading} />
                <ul>
                    {activities.map((item) => (
                        <li className="py-2 border-b" key={item.id}>
                            {new Date(item.createdAt).toLocaleString('vi-VN')} · {item.action} ·{' '}
                            {item.description}
                        </li>
                    ))}
                </ul>
            </section>
        </div>
    )

    // Chức năng: Điều hướng layout tương ứng theo vai trò (role) và route hiện tại để không làm mất sidebar của Admin, Seller, Support.
    // Quy tắc: Khi ở trang hồ sơ của Admin, Seller hoặc Support, render layout tương ứng giữ nguyên sidebar; Buyer dùng BuyerLayout.
    if (page === 'admin-profile' || (user?.role === 'admin' && page === 'profile')) {
        return <AdminLayout title={title}>{profileBody}</AdminLayout>
    }
    if (page === 'seller-profile' || (user?.role === 'seller' && page === 'profile')) {
        return <SellerLayout title={title}>{profileBody}</SellerLayout>
    }
    if (page === 'support-profile' || (user?.role === 'support' && page === 'profile')) {
        return <SupportLayout title={title}>{profileBody}</SupportLayout>
    }
    if (user?.role === 'admin') {
        return <AdminLayout title={title}>{profileBody}</AdminLayout>
    }
    if (user?.role === 'seller') {
        return <SellerLayout title={title}>{profileBody}</SellerLayout>
    }
    if (user?.role === 'support') {
        return <SupportLayout title={title}>{profileBody}</SupportLayout>
    }
    return <BuyerLayout>{profileBody}</BuyerLayout>
}
