import { useState } from 'react'
import { useApp } from '../../context'
import { tr } from '../../i18n'
import { AdminLayout, SellerLayout, SupportLayout } from '../../components/Layout'
import { Button, Input, StatusBadge } from '../../components/UI'

function RoleProfile({ role }: { role: 'admin' | 'seller' | 'support' }) {
  const { lang, user, showToast } = useApp()
  const [saving, setSaving] = useState(false)
  if (!user) return null

  const isAdmin = role === 'admin'
  const isSeller = role === 'seller'
  const roleLabel = isAdmin
    ? (lang === 'en' ? 'Administrator' : 'Quản trị viên')
    : isSeller
      ? (lang === 'en' ? 'Seller' : 'Người bán')
    : (lang === 'en' ? 'Support Staff' : 'Nhân viên hỗ trợ')

  const save = async () => {
    setSaving(true)
    await new Promise(resolve => setTimeout(resolve, 600))
    setSaving(false)
    showToast({
      type: 'success',
      message: isAdmin
        ? (lang === 'en' ? 'Admin profile updated.' : 'Đã cập nhật hồ sơ Admin.')
        : isSeller
          ? (lang === 'en' ? 'Seller profile updated.' : 'Đã cập nhật hồ sơ người bán.')
          : (lang === 'en' ? 'Support profile updated.' : 'Đã cập nhật hồ sơ nhân viên hỗ trợ.'),
    })
  }

  const content = (
    <div className="max-w-3xl">
      <div className="flex items-center gap-5 mb-6">
        <img src={user.avatar} alt="" className="w-16 h-16 rounded-xl object-cover" />
        <div>
          <h2 className="text-xl font-bold">{user.fullName}</h2>
          <p className="text-sm text-gray-500">{user.email}</p>
          <div className="mt-2"><StatusBadge status="active" label={roleLabel} /></div>
        </div>
      </div>
      <div className="nexus-card p-6">
        <h3 className="font-bold mb-5">{lang === 'en' ? 'Account information' : 'Thông tin tài khoản'}</h3>
        <div className="grid grid-cols-2 gap-4">
          <Input label={tr(lang, 'fullName')} defaultValue={user.fullName} />
          <Input label={tr(lang, 'email')} type="email" defaultValue={user.email} />
          <Input label={tr(lang, 'phone')} defaultValue={user.phone} />
          <Input label={lang === 'en' ? 'Role' : 'Vai trò'} defaultValue={roleLabel} disabled />
        </div>
        <div className="mt-4"><Input label={tr(lang, 'address')} defaultValue={user.address} /></div>
        <div className="mt-5"><Button variant="primary" loading={saving} onClick={save}>{tr(lang, 'save')}</Button></div>
      </div>
      <div className="nexus-card p-6 mt-5">
        <h3 className="font-bold mb-5">{lang === 'en' ? 'Change password' : 'Đổi mật khẩu'}</h3>
        <div className="grid grid-cols-2 gap-4">
          <Input label={tr(lang, 'newPassword')} type="password" placeholder="••••••••" />
          <Input label={tr(lang, 'confirmPassword')} type="password" placeholder="••••••••" />
        </div>
        <div className="mt-5"><Button variant="outline" onClick={save}>{lang === 'en' ? 'Update Password' : 'Cập nhật mật khẩu'}</Button></div>
      </div>
    </div>
  )

  if (isAdmin) return <AdminLayout title={lang === 'en' ? 'Profile' : 'Hồ sơ'}>{content}</AdminLayout>
  if (isSeller) return <SellerLayout title={lang === 'en' ? 'Profile' : 'Hồ sơ'}>{content}</SellerLayout>
  return <SupportLayout title={lang === 'en' ? 'Profile' : 'Hồ sơ'}>{content}</SupportLayout>
}

export function AdminProfilePage() {
  return <RoleProfile role="admin" />
}

export function SellerProfilePage() {
  return <RoleProfile role="seller" />
}

export function SupportProfilePage() {
  return <RoleProfile role="support" />
}
