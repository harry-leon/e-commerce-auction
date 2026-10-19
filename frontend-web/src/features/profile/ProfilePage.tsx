import { useState, type FormEvent } from 'react'
import { api, post, mapUser, errorMessage, type ApiUser } from '../../api/client'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { Button, Input } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'

/**
 * Tải hồ sơ và 20 hoạt động gần đây của chính tài khoản qua JWT.
 * Lưu hồ sơ bằng PUT /users/me; đổi mật khẩu dùng endpoint riêng rồi xóa session để đăng nhập lại.
 */
export function ProfilePage() {
    const { setUser, showToast, navigate } = useApp()
    const { data, error, loading } = useLoad<ApiUser>('/users/me')
    const history = useLoad<{
        content: { id: string; action: string; description: string; createdAt: string }[]
    }>('/users/me/activity-history?size=20')
    const [saving, setSaving] = useState(false)
    const [failure, setFailure] = useState('')
    // Hai form dùng chung trạng thái lưu/lỗi; chỉ báo thành công sau khi API trả về thành công.
    const submit = async (event: FormEvent<HTMLFormElement>, password = false) => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        setSaving(true)
        setFailure('')
        try {
            if (password) {
                if (form.get('newPassword') !== form.get('confirmPassword'))
                    throw new Error('Mật khẩu xác nhận không khớp')
                await post('/auth/change-password', {
                    currentPassword: form.get('oldPassword'),
                    newPassword: form.get('newPassword'),
                })
                setUser(null)
                navigate('login')
            } else {
                const updated = await api<ApiUser>('/users/me', {
                    method: 'PUT',
                    body: JSON.stringify(Object.fromEntries(form)),
                })
                setUser(mapUser(updated))
            }
            showToast({ type: 'success', message: 'Đã lưu thay đổi' })
        } catch (error) {
            setFailure(errorMessage(error))
        } finally {
            setSaving(false)
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
            >
                <h2 className="font-bold">Đổi mật khẩu</h2>
                <Input label="Mật khẩu hiện tại" name="oldPassword" type="password" required />
                <Input
                    label="Mật khẩu mới"
                    name="newPassword"
                    type="password"
                    minLength={8}
                    required
                />
                <Input label="Xác nhận mật khẩu" name="confirmPassword" type="password" required />
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
