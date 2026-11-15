import { useState, type FormEvent } from 'react'
import { api, errorMessage, type ApiCategory, type Envelope } from '../../api/client'
import { AdminLayout } from '../../components/Layout'
import { Button, Input } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { flatten } from '../catalog/catalog'
import { useApp } from '../../context'
import { tr } from '../../i18n'

/**
 * Quản trị danh mục: GET cây → chọn tạo/sửa → POST/PUT → tải lại cây từ DB.
 * Form lưu đồng thời tên EN/VI để khách có thể chuyển ngôn ngữ mà không dùng category mock hoặc alias.
 * Xóa cần xác nhận rồi DELETE; backend là nơi chặn vượt cấp, vòng lặp và danh mục còn sản phẩm.
 */
export function AdminCategoriesPage() {
    const { lang } = useApp()
    // Tăng revision để useLoad lấy lại cây sau thao tác ghi; không tự giả định server đã lưu vào state cục bộ.
    const [revision, setRevision] = useState(0)
    const { data, error, loading } = useLoad<Envelope<ApiCategory[]>>(
        `/categories?revision=${revision}`,
    )
    const [editing, setEditing] = useState<ApiCategory | null>(null)
    const [failure, setFailure] = useState('')
    const [saving, setSaving] = useState(false)
    const categories = flatten(data?.data || [])
    // Có editing thì sửa theo UUID; không có thì tạo mới. Chuỗi cha rỗng phải chuyển thành null cho DTO backend.
    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const form = event.currentTarget
        const values = Object.fromEntries(new FormData(form))
        setFailure('')
        setSaving(true)
        try {
            await api(`/categories${editing ? `/${editing.id}` : ''}`, {
                method: editing ? 'PUT' : 'POST',
                body: JSON.stringify({ ...values, parentId: values.parentId || null }),
            })
            form.reset()
            setEditing(null)
            setRevision((value) => value + 1)
        } catch (error) {
            setFailure(errorMessage(error))
        } finally {
            setSaving(false)
        }
    }
    // Giữ dữ liệu cũ khi server từ chối xóa; lỗi nghiệp vụ được đưa lên form thay vì xóa giả trên giao diện.
    const remove = async (category: ApiCategory) => {
        if (!window.confirm(`Xóa danh mục “${category.name}”?`)) return
        setFailure('')
        setSaving(true)
        try {
            await api(`/categories/${category.id}`, { method: 'DELETE' })
            setRevision((value) => value + 1)
        } catch (error) {
            setFailure(errorMessage(error))
        } finally {
            setSaving(false)
        }
    }
    return (
        <AdminLayout title="Quản lý danh mục">
            {/* Giữ form tạo/sửa ở đầu nội dung; spinner tải cây danh mục đặt phía dưới để form không nhảy vị trí. */}
            <form
                key={editing?.id || 'new'}
                onSubmit={submit}
                className="nexus-card p-5 space-y-3 mb-6"
            >
                <h2 className="font-bold">{editing ? 'Sửa danh mục' : 'Thêm danh mục'}</h2>
                <Input
                    name="name"
                    label={tr(lang, 'categoryNameEnglish')}
                    defaultValue={editing?.name}
                    maxLength={255}
                    required
                />
                <Input
                    name="nameVi"
                    label={tr(lang, 'categoryNameVietnamese')}
                    defaultValue={editing?.nameVi}
                    maxLength={255}
                />
                <label>
                    Danh mục cha
                    <select
                        className="nexus-input w-full"
                        name="parentId"
                        defaultValue={editing?.parentId || ''}
                    >
                        <option value="">Danh mục gốc</option>
                        {categories
                            .filter((item) => item.level < 3 && item.id !== editing?.id)
                            .map((item) => (
                                <option value={item.id} key={item.id}>
                                    {'—'.repeat(item.level - 1)} {item.nameVi || item.name}
                                </option>
                            ))}
                    </select>
                </label>
                <label>
                    Trạng thái
                    <select
                        className="nexus-input w-full"
                        name="status"
                        defaultValue={editing?.status || 'ACTIVE'}
                    >
                        <option>ACTIVE</option>
                        <option>INACTIVE</option>
                    </select>
                </label>
                <div className="flex gap-3">
                    <Button type="submit" loading={saving}>
                        Lưu
                    </Button>
                    {editing && <Button onClick={() => setEditing(null)}>Hủy</Button>}
                </div>
            </form>
            <LoadState error={failure || error} loading={loading} />
            <div className="nexus-card p-4">
                {categories.map((item) => (
                    <div
                        className="flex justify-between items-center border-b py-3 gap-3"
                        key={item.id}
                    >
                        <span style={{ paddingLeft: (item.level - 1) * 24 }}>
                            {item.nameVi || item.name} · {item.productCount} sản phẩm ·{' '}
                            {item.status}
                        </span>
                        <div className="flex gap-2">
                            <Button disabled={saving} onClick={() => setEditing(item)}>
                                Sửa
                            </Button>
                            <Button disabled={saving} onClick={() => remove(item)}>
                                Xóa
                            </Button>
                        </div>
                    </div>
                ))}
            </div>
        </AdminLayout>
    )
}
