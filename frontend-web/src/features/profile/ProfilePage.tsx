import { useState, type FormEvent } from 'react'
import { api, post, mapUser, errorMessage, extractFieldErrors, type ApiUser } from '../../api/client'
import {
    validatePasswordPolicy,
    PasswordRequirementsChecklist,
    PasswordConfirmIndicator,
} from '../auth/PasswordValidation'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { Button, Input } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'

/**
 * Tải hồ sơ và 20 hoạt động gần đây của chính tài khoản qua JWT.
 * Lưu hồ sơ bằng PUT /users/me; đổi mật khẩu dùng POST /api/v1/auth/change-password (yêu cầu Bearer token),
 * áp dụng validation mật khẩu mới và hiển thị lỗi 422 theo từng trường.
 */
export function ProfilePage() {
    const { setUser, showToast, navigate } = useApp()
    const { data, error, loading } = useLoad<ApiUser>('/users/me')
    const history = useLoad<{
        content: { id: string; action: string; description: string; createdAt: string }[]
    }>('/users/me/activity-history?size=20')
    const [saving, setSaving] = useState(false)
    const [failure, setFailure] = useState('')

    // State riêng cho form đổi mật khẩu để validate realtime và hiển thị lỗi theo từng trường
    const [oldPassword, setOldPassword] = useState('')
    const [newPassword, setNewPassword] = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({})

    /**
     * Cập nhật thông tin cá nhân qua PUT /api/v1/users/me.
     */
    const submitProfile = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        setSaving(true)
        setFailure('')
        try {
            const updated = await api<ApiUser>('/users/me', {
                method: 'PUT',
                body: JSON.stringify(Object.fromEntries(form)),
            })
            setUser(mapUser(updated))
            showToast({ type: 'success', message: 'Đã lưu thay đổi' })
        } catch (err) {
            setFailure(errorMessage(err))
        } finally {
            setSaving(false)
        }
    }

    /**
     * Đổi mật khẩu qua POST /api/v1/auth/change-password:
     * - Đầu vào: currentPassword, newPassword (gửi kèm Bearer token qua client.ts).
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
        const confirmPass = ((form.get('confirmPassword') as string) || confirmPassword || '').trim()

        setSaving(true)
        setFailure('')
        setPasswordErrors({})

        const errors: Record<string, string> = {}
        if (!oldPass) {
            errors.currentPassword = 'Mật khẩu hiện tại không được để trống'
        }

        const policy = validatePasswordPolicy(newPass)
        if (!policy.isValid) {
            errors.newPassword = 'Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ in hoa (A-Z), 1 chữ số, 1 ký tự đặc biệt'
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
            showToast({ type: 'success', message: 'Đổi mật khẩu thành công. Vui lòng đăng nhập lại.' })
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

    return (
        <BuyerLayout>
            <h1 className="text-2xl font-bold mb-5">Thông tin cá nhân</h1>
            <LoadState error={failure || error} loading={loading} />
            {data && (
                <form
                    key={data.id}
                    className="nexus-card p-5 grid sm:grid-cols-2 gap-4"
                    onSubmit={(event) => submit(event)}
                >
                    <Input label="Họ tên" name="fullName" defaultValue={data.fullName} required />
                    <Input label="Email" value={data.email} disabled />
                    <Input label="Số điện thoại" name="phone" defaultValue={data.phone} />
                    <Input
                        label="Số giấy tờ"
                        name="identifyNumber"
                        defaultValue={data.identifyNumber}
                    />
                    <Input
                        label="Ngày sinh"
                        type="date"
                        name="dateOfBirth"
                        defaultValue={data.dateOfBirth}
                    />
                    <label>
                        Giới tính
                        <select
                            className="nexus-input w-full"
                            name="gender"
                            defaultValue={data.gender || 'OTHER'}
                        >
                            <option>MALE</option>
                            <option>FEMALE</option>
                            <option>OTHER</option>
                        </select>
                    </label>
                    <Input label="Địa chỉ" name="address" defaultValue={data.address} />
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
                    {history.data?.content.map((item) => (
                        <li className="py-2 border-b" key={item.id}>
                            {new Date(item.createdAt).toLocaleString('vi-VN')} · {item.action} ·{' '}
                            {item.description}
                        </li>
                    ))}
                </ul>
            </section>
        </BuyerLayout>
    )
}
