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
import { AddressSelector } from '../../components/AddressSelector'
import { useApp } from '../../context'
import { AdminLayout, SellerLayout, SupportLayout, BuyerLayout } from '../../components/Layout'
import { Button, Input, PasswordInput } from '../../components/UI'
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
    // Lưu trữ lỗi validation chi tiết theo từng trường (fullName, identifyNumber, phone) từ backend trả về
    const [profileErrors, setProfileErrors] = useState<Record<string, string>>({})
    const [savedProfile, setSavedProfile] = useState<ApiUser | null>(null)
    const [revision, setRevision] = useState(0)

    // State riêng cho form đổi mật khẩu để validate realtime và hiển thị lỗi theo từng trường
    const [oldPassword, setOldPassword] = useState('')
    const [newPassword, setNewPassword] = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({})

    // State riêng theo dõi trạng thái gửi request đổi mật khẩu (tránh xung đột spinner với nút lưu hồ sơ)
    const [changingPassword, setChangingPassword] = useState(false)

    // State lưu lỗi theo từng trường của form cập nhật hồ sơ cá nhân
    const [profileErrors, setProfileErrors] = useState<Record<string, string>>({})

    // State lưu địa chỉ do người dùng chọn/nhập tùy biến qua AddressSelector (null nghĩa là dùng địa chỉ gốc của user)
    const [customAddress, setCustomAddress] = useState<string | null>(null)

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

    // Địa chỉ hiển thị thực tế: ưu tiên địa chỉ người dùng vừa thay đổi, fallback ngay lập tức sang địa chỉ trong activeUser
    const currentAddress = customAddress ?? activeUser?.address ?? ''

    /**
     * Cập nhật thông tin cá nhân qua PUT /api/v1/users/me:
     * 1. Chức năng: Lưu thay đổi thông tin cá nhân của người dùng vào hệ thống.
     * 2. Đầu vào và kết quả: Nhận form data (fullName, phone, identifyNumber, dateOfBirth, gender, address từ AddressSelector);
     *    gửi PUT /users/me; cập nhật session store và cache tải dữ liệu.
     * 3. Luồng xử lý:
     *    - Validate client-side:
     *      + Họ tên: bắt buộc, 2-100 ký tự chữ cái Unicode có dấu, ít nhất 2 từ.
     *      + Số điện thoại: bắt buộc, chuẩn di động Việt Nam 10 chữ số ((0|+84)[3|5|7|8|9]...).
     *      + Số CCCD: bắt buộc, đúng 12 chữ số theo quy định định danh tài khoản sàn đấu giá.
     *      + Ngày sinh: bắt buộc ở quá khứ, kiểm tra đủ từ 15 tuổi trở lên (độ tuổi tối thiểu tham gia sàn) và không quá 120 tuổi.
     *      + Địa chỉ: bắt buộc, từ 5 đến 500 ký tự, được chọn theo Open API tỉnh thành hành chính 3 cấp hoặc nhập tự do.
     *    - Nếu lỗi client-side: Gán vào profileErrors để hiển thị đỏ dưới từng trường tương ứng, không gửi request.
     *    - Gửi API -> nếu thành công: lưu vào savedProfile & session store, thông báo toast thành công.
     *    - Nếu lỗi từ server (422): trích xuất extractFieldErrors(err) và gán vào profileErrors để khoanh vùng chính xác trường bị sai.
     * 4. Quy tắc và lý do:
     *    - Bắt buộc SĐT và CCCD 12 số để phục vụ giao nhận hàng hóa và thẩm định người mua/người bán trên sàn đấu giá.
     *    - Chặn độ tuổi dưới 15 nhằm tuân thủ quy định pháp luật về năng lực hành vi dân sự trong giao dịch thương mại điện tử.
     *    - Chuẩn hóa địa chỉ qua Open API hành chính giúp dữ liệu địa chỉ sạch, phục vụ tính cước vận chuyển chính xác.
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

        // Kiểm tra Số điện thoại: Bắt buộc, đúng định dạng di động Việt Nam (10 số)
        if (!phone) {
            clientErrors.phone =
                lang === 'en' ? 'Phone number is required' : 'Số điện thoại không được để trống'
        } else if (!/^(0|\+84)(3|5|7|8|9)[0-9]{8}$/.test(phone)) {
            clientErrors.phone =
                lang === 'en'
                    ? 'Invalid Vietnam phone number format (10 digits)'
                    : 'Số điện thoại không đúng định dạng di động Việt Nam (10 chữ số)'
        }

        // Kiểm tra CCCD: Bắt buộc, đúng 12 chữ số
        if (!identifyNumber) {
            clientErrors.identifyNumber =
                lang === 'en' ? 'National ID is required' : 'Số CCCD không được để trống'
        } else if (!/^\d{12}$/.test(identifyNumber)) {
            clientErrors.identifyNumber =
                lang === 'en'
                    ? 'National ID must be exactly 12 digits'
                    : 'Số CCCD phải gồm đúng 12 chữ số'
        }

        // Kiểm tra Ngày sinh: Phải ở quá khứ, tối thiểu 15 tuổi và không quá 120 năm trước
        const MIN_AGE = 15
        const MAX_AGE = 120
        if (dateOfBirth) {
            const dob = new Date(dateOfBirth + 'T00:00:00')
            const today = new Date()
            today.setHours(0, 0, 0, 0)
            const minAgeDate = new Date()
            minAgeDate.setFullYear(minAgeDate.getFullYear() - MIN_AGE)
            minAgeDate.setHours(0, 0, 0, 0)
            const maxAgeDate = new Date()
            maxAgeDate.setFullYear(maxAgeDate.getFullYear() - MAX_AGE)
            maxAgeDate.setHours(0, 0, 0, 0)

            if (isNaN(dob.getTime())) {
                clientErrors.dateOfBirth =
                    lang === 'en' ? 'Invalid date' : 'Ngày sinh không tồn tại trong lịch'
            } else if (dob >= today) {
                clientErrors.dateOfBirth =
                    lang === 'en'
                        ? 'Date of birth must be in the past'
                        : 'Ngày sinh phải ở trong quá khứ'
            } else if (dob > minAgeDate) {
                clientErrors.dateOfBirth =
                    lang === 'en'
                        ? `You must be at least ${MIN_AGE} years old to participate`
                        : `Người dùng phải đủ từ ${MIN_AGE} tuổi trở lên để tham gia giao dịch`
            } else if (dob < maxAgeDate) {
                clientErrors.dateOfBirth =
                    lang === 'en'
                        ? `Age cannot exceed ${MAX_AGE} years`
                        : `Ngày sinh không hợp lệ (tuổi tối đa ${MAX_AGE})`
            }
        }

        // Kiểm tra Địa chỉ: Bắt buộc, từ 5 đến 500 ký tự (lấy từ state currentAddress hoặc form data)
        const effectiveAddress = (currentAddress || (form.get('address') as string) || '').trim()
        if (!effectiveAddress) {
            clientErrors.address =
                lang === 'en' ? 'Address is required' : 'Địa chỉ không được để trống'
        } else if (effectiveAddress.length < 5 || effectiveAddress.length > 500) {
            clientErrors.address =
                lang === 'en'
                    ? 'Address must be between 5 and 500 characters'
                    : 'Địa chỉ phải từ 5 đến 500 ký tự'
        }

        if (Object.keys(clientErrors).length > 0) {
            setProfileErrors(clientErrors)
            setSaving(false)
            return
        }

        try {
            const payload = Object.fromEntries(form)
            payload.address = effectiveAddress
            const updated = await api<ApiResponse<ApiUser> | ApiUser>('/users/me', {
                method: 'PUT',
                body: JSON.stringify(payload),
            })
            const raw = (
                updated && typeof updated === 'object' && 'data' in updated && updated.data
                    ? (updated as ApiResponse<ApiUser>).data
                    : updated
            ) as ApiUser
            setSavedProfile(raw)
            setCustomAddress(null)
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
     * 1. Chức năng: Cho phép người dùng đổi mật khẩu tài khoản đang đăng nhập.
     * 2. Đầu vào và kết quả:
     *    - Đầu vào: oldPassword (mật khẩu hiện tại), newPassword (mật khẩu mới), confirmPassword.
     *    - Kết quả: Gửi POST /auth/change-password; nếu thành công xóa session và điều hướng về trang đăng nhập.
     * 3. Luồng xử lý:
     *    - Mật khẩu hiện tại: bắt buộc không để trống.
     *    - Mật khẩu mới: kiểm tra policy (8-100 ký tự, >= 1 chữ in hoa A-Z, >= 1 chữ số, >= 1 ký tự đặc biệt).
     *    - Quy tắc chống trùng: Mật khẩu mới không được giống hệt mật khẩu hiện tại.
     *    - Xác nhận mật khẩu: phải khớp chính xác từng ký tự với mật khẩu mới.
     *    - Quản lý loading độc lập qua changingPassword để không làm xoay spinner của form thông tin cá nhân.
     *    - Đọc chi tiết lỗi 422 từ backend (error.details[]) gán đúng từng field input.
     * 4. Quy tắc bảo mật: Sau khi đổi mật khẩu thành công, phiên đăng nhập hiện tại lập tức bị hủy để bảo vệ tài khoản.
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

        setChangingPassword(true)
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
        } else if (oldPass && newPass && oldPass === newPass) {
            errors.newPassword = 'Mật khẩu mới không được trùng với mật khẩu hiện tại'
        }

        if (newPass !== confirmPass) {
            errors.confirmPassword = 'Mật khẩu xác nhận không khớp'
        }

        if (Object.keys(errors).length > 0) {
            setPasswordErrors(errors)
            setChangingPassword(false)
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
            setOldPassword('')
            setNewPassword('')
            setConfirmPassword('')
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
            setChangingPassword(false)
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
            {/* Chức năng: Tiêu đề trang hồ sơ kèm nút điều hướng nhanh tới Điểm uy tín của tôi (My Reputation) */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-5">
                <h1 className="text-2xl font-bold">{title}</h1>
                <button
                    type="button"
                    onClick={() => navigate('my-reputation')}
                    className="self-start sm:self-auto flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-xs font-bold text-amber-900 bg-amber-100 hover:bg-amber-200 border border-amber-300 shadow-sm transition-all cursor-pointer"
                >
                    <span>
                        ⭐{' '}
                        {lang === 'vi'
                            ? 'Điểm uy tín & Đánh giá của tôi'
                            : 'My Reputation & Ratings'}
                    </span>
                </button>
            </div>
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
                    <div>
                        <Input
                            label="Số giấy tờ (CCCD)"
                            name="identifyNumber"
                            defaultValue={activeUser.identifyNumber}
                            error={profileErrors.identifyNumber}
                            placeholder="Ví dụ: 079203001234"
                            required
                        />
                        <p className="text-[11px] text-gray-500 mt-1">
                            {lang === 'en'
                                ? '12-digit Citizen ID required for identity verification and auction participation'
                                : 'CCCD 12 chữ số dùng cho định danh và tham gia đấu giá theo quy định'}
                        </p>
                    </div>
                    <Input
                        label="Ngày sinh"
                        type="date"
                        name="dateOfBirth"
                        max={
                            new Date(Date.now() - 15 * 365.25 * 24 * 60 * 60 * 1000)
                                .toISOString()
                                .split('T')[0]
                        }
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

                    {/*
                     * Khối Địa chỉ hành chính phân cấp tích hợp Open API (provinces.open-api.vn):
                     * - Chiếm trọn 2 cột (sm:col-span-2) để hiển thị thoáng 3 dropdown Tỉnh/Quận/Xã và ô số nhà.
                     * - Có fallback sang chế độ nhập tự do (manualMode) khi API lỗi.
                     * - Input ẩn name="address" đồng bộ giá trị với FormData khi submit và hỗ trợ DOM queries.
                     */}
                    <div className="sm:col-span-2">
                        <AddressSelector
                            value={currentAddress}
                            onChange={(val) => {
                                setCustomAddress(val)
                                if (profileErrors.address) {
                                    setProfileErrors((prev) => {
                                        const next = { ...prev }
                                        delete next.address
                                        return next
                                    })
                                }
                            }}
                            lang={lang}
                            error={profileErrors.address}
                        />
                        {/* Input type="text" ẩn khỏi thị giác (sr-only) đồng bộ giá trị với FormData và thỏa mãn DOM DisplayValue query */}
                        <input
                            type="text"
                            name="address"
                            value={currentAddress}
                            readOnly
                            className="sr-only"
                            tabIndex={-1}
                        />
                    </div>

                    {/* Nút lưu thông tin tách riêng ở hàng cuối căn phải, khắc phục lỗi layout bị co cụm */}
                    <div className="sm:col-span-2 flex justify-end pt-2">
                        <Button type="submit" loading={saving} className="min-w-[160px]">
                            Lưu thông tin
                        </Button>
                    </div>
                </form>
            )}

            {/* Chức năng: Thẻ hiển thị tóm tắt liên kết trực tiếp sang trang Điểm uy tín của tôi, giải quyết hoàn toàn vấn đề trang mồ côi */}
            <div className="nexus-card p-5 mt-6 bg-gradient-to-r from-amber-50/70 to-orange-50/70 border border-amber-200/90 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div className="space-y-1">
                    <h2 className="font-bold text-base text-amber-900 flex items-center gap-2">
                        <span>⭐</span>
                        <span>
                            {lang === 'vi'
                                ? 'Hồ sơ uy tín & Cấp độ tín nhiệm'
                                : 'Reputation & Trust Level'}
                        </span>
                    </h2>
                    <p className="text-xs text-amber-800/80">
                        {lang === 'vi'
                            ? 'Xem cấp độ uy tín (VIP/HIGH/STANDARD), tỷ lệ giao dịch thành công và danh sách nhận xét đánh giá từ cộng đồng.'
                            : 'View trust level, transaction stats and community feedback.'}
                    </p>
                </div>
                <button
                    type="button"
                    onClick={() => navigate('my-reputation')}
                    className="self-start sm:self-auto px-4 py-2 rounded-xl text-xs font-bold text-amber-900 bg-amber-300 hover:bg-amber-400 transition-colors shadow-sm cursor-pointer whitespace-nowrap"
                >
                    {lang === 'vi' ? 'Xem chi tiết uy tín →' : 'View Reputation →'}
                </button>
            </div>
            <form
                className="nexus-card p-5 mt-6 space-y-3"
                onSubmit={(event) => submit(event, true)}
                data-testid="change-password-form"
            >
                <h2 className="font-bold">Đổi mật khẩu</h2>
                <div>
                    <PasswordInput
                        label="Mật khẩu hiện tại"
                        name="oldPassword"
                        value={oldPassword}
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
                    <PasswordInput
                        label="Mật khẩu mới"
                        name="newPassword"
                        value={newPassword}
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
                    <PasswordRequirementsChecklist password={newPassword} lang={lang} />
                </div>
                <div>
                    <PasswordInput
                        label="Xác nhận mật khẩu"
                        name="confirmPassword"
                        value={confirmPassword}
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
                        lang={lang}
                    />
                </div>
                <Button type="submit" loading={changingPassword}>
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
