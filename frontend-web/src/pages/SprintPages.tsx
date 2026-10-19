import { useEffect, useState, type FormEvent } from 'react'
import { api, post, mapUser, errorMessage, type ApiUser, type ApiProduct, type ApiCategory, type Envelope } from '../api'
import { useApp } from '../context'
import { BuyerLayout, AdminLayout } from '../components/Layout'
import { Button, Input, AlertBanner } from '../components/UI'

/**
 * Các màn Sprint 0 dùng API thật: catalog, chi tiết sản phẩm, hồ sơ và quản trị danh mục.
 * Dữ liệu DB chỉ đổi sau khi backend xác nhận; những màn demo của sprint sau nằm ở các module cũ.
 */

/** Hook tải lại khi URL API đổi; xóa dữ liệu cũ, báo loading/lỗi và hủy request khi rời màn hình. */
function useLoad<T>(path: string) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setData(null)
    api<T>(path, { signal: controller.signal }).then(setData).catch(error => {
      if (!controller.signal.aborted) setError(errorMessage(error))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [path])
  return { data, error, loading }
}

/** Dùng chung trạng thái tải/lỗi để không hiển thị lỗi mạng như một danh sách rỗng thành công. */
function LoadState({ error, loading }: { error: string; loading: boolean }) {
  if (error) return <AlertBanner type="error" message={error} />
  if (loading) return <p role="status" className="py-6">Đang tải…</p>
  return null
}

/** Trải cây danh mục thành danh sách chọn; vẫn giữ level để UI thụt lề đúng cấp. */
function flatten(categories: ApiCategory[]): ApiCategory[] {
  return categories.flatMap(category => [category, ...flatten(category.children)])
}

/**
 * Luồng catalog: lấy bộ lọc từ form/URL → GET /products → hiển thị data và meta.total.
 * Mỗi lần đổi bộ lọc quay về trang 0; giới hạn/sort/giá được backend kiểm tra lại.
 */
export function SearchPage() {
  const { navigate, pageParams } = useApp()
  const [q, setQ] = useState(String(pageParams.q || ''))
  const [category, setCategory] = useState(String(pageParams.category || ''))
  const [min, setMin] = useState('')
  const [max, setMax] = useState('')
  const [kind, setKind] = useState('')
  const [sort, setSort] = useState('createdAt,desc')
  const [page, setPage] = useState(0)
  // Backend dùng trang bắt đầu từ 0; UI hiển thị số trang bắt đầu từ 1 và 12 sản phẩm mỗi trang.
  const query = new URLSearchParams({ q, page: String(page), size: '12', sort })
  if (category) query.set('categoryId', category)
  if (min) query.set('minPrice', min)
  if (max) query.set('maxPrice', max)
  if (kind) query.set('auctionEnabled', kind)
  const { data, error, loading } = useLoad<Envelope<ApiProduct[]>>(`/products?${query}`)
  const categories = useLoad<Envelope<ApiCategory[]>>('/categories')
  const total = data?.meta?.total || 0
  return <BuyerLayout>
    <h1 className="text-2xl font-bold mb-5">Sản phẩm</h1>
    <form className="nexus-card p-4 grid sm:grid-cols-3 gap-3 mb-6" onSubmit={event => event.preventDefault()}>
      <Input label="Tìm kiếm" value={q} onChange={event => { setQ(event.target.value); setPage(0) }} />
      <label>Danh mục<select className="nexus-input w-full" value={category} onChange={event => { setCategory(event.target.value); setPage(0) }}>
        <option value="">Tất cả</option>{flatten(categories.data?.data || []).map(item => <option key={item.id} value={item.id}>{'—'.repeat(item.level - 1)} {item.nameVi || item.name}</option>)}
      </select></label>
      <label>Sắp xếp<select className="nexus-input w-full" value={sort} onChange={event => { setSort(event.target.value); setPage(0) }}>
        <option value="createdAt,desc">Mới nhất</option><option value="name,asc">Tên A–Z</option>
      </select></label>
      <Input label="Giá từ" type="number" min="0" value={min} onChange={event => { setMin(event.target.value); setPage(0) }} />
      <Input label="Giá đến" type="number" min="0" value={max} onChange={event => { setMax(event.target.value); setPage(0) }} />
      <label>Loại sản phẩm<select className="nexus-input w-full" value={kind} onChange={event => { setKind(event.target.value); setPage(0) }}>
        <option value="">Tất cả</option><option value="false">Mua ngay</option><option value="true">Có đấu giá</option>
      </select></label>
    </form>
    <LoadState error={error || categories.error} loading={loading} />
    {!loading && !error && <p className="mb-4">{total} sản phẩm</p>}
    <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-5">{data?.data.map(product => <button key={product.id} className="nexus-card overflow-hidden text-left" onClick={() => navigate('product-detail', { id: product.id })}>
      {product.images[0] && <img className="h-48 w-full object-cover" src={product.images[0].url} alt={product.name} />}
      <div className="p-4"><h2 className="font-semibold">{product.name}</h2><p>{price(product)}</p></div>
    </button>)}</div>
    {!loading && !error && total === 0 && <p>Không có sản phẩm phù hợp.</p>}
    <div className="flex items-center justify-center gap-4 mt-6">
      <Button disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>Trước</Button>
      <span>Trang {page + 1}</span><Button disabled={(page + 1) * 12 >= total || loading} onClick={() => setPage(page + 1)}>Sau</Button>
    </div>
  </BuyerLayout>
}

/** Giá đại diện là giá thấp nhất trong các SKU ACTIVE; không có SKU bán được thì báo chưa có giá. */
function price(product: ApiProduct) {
  const prices = product.skus.filter(sku => sku.status === 'ACTIVE').map(sku => Number(sku.price))
  return prices.length ? `${Math.min(...prices).toLocaleString('vi-VN')} ₫` : 'Chưa có giá bán'
}

/** Lấy UUID từ URL, tải chi tiết thật và hiển thị ảnh/SKU; không thay sản phẩm thiếu bằng dữ liệu demo. */
export function ProductDetailPage() {
  const { pageParams, navigate } = useApp()
  const { data, error, loading } = useLoad<Envelope<ApiProduct>>(`/products/${encodeURIComponent(String(pageParams.id || ''))}`)
  const [image, setImage] = useState(0)
  const product = data?.data
  return <BuyerLayout>
    <Button onClick={() => navigate('search')}>Quay lại sản phẩm</Button>
    <LoadState error={error} loading={loading} />
    {product && <article className="nexus-card p-6 mt-5 grid md:grid-cols-2 gap-8">
      <div>{product.images[image] && <img src={product.images[image].url} alt={product.name} className="w-full rounded-lg" />}
        <div className="flex gap-2 mt-3">{product.images.map((item, index) => <button key={item.id} onClick={() => setImage(index)} aria-label={`Ảnh ${index + 1}`}><img src={item.url} alt="" className="w-16 h-16 object-cover" /></button>)}</div>
      </div>
      <div><h1 className="text-2xl font-bold">{product.name}</h1><p className="text-xl my-4">{price(product)}</p>
        <p className="whitespace-pre-wrap">{product.description}</p>
        <h2 className="font-semibold mt-6">Phiên bản sản phẩm</h2>
        <ul>{product.skus.map(sku => <li className="py-2 border-b" key={sku.id}>{sku.skuCode} · {Number(sku.price).toLocaleString('vi-VN')} ₫ · {sku.status}</li>)}</ul>
      </div>
    </article>}
  </BuyerLayout>
}

/**
 * Tải hồ sơ và 20 hoạt động gần đây của chính tài khoản qua JWT.
 * Lưu hồ sơ bằng PUT /users/me; đổi mật khẩu dùng endpoint riêng rồi xóa session để đăng nhập lại.
 */
export function ProfilePage() {
  const { setUser, showToast, navigate } = useApp()
  const { data, error, loading } = useLoad<ApiUser>('/users/me')
  const history = useLoad<{ content: { id: string; action: string; description: string; createdAt: string }[] }>('/users/me/activity-history?size=20')
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState('')
  // Hai form dùng chung trạng thái lưu/lỗi; chỉ báo thành công sau khi API trả về thành công.
  const submit = async (event: FormEvent<HTMLFormElement>, password = false) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setSaving(true); setFailure('')
    try {
      if (password) {
        if (form.get('newPassword') !== form.get('confirmPassword')) throw new Error('Mật khẩu xác nhận không khớp')
        await post('/auth/change-password', { currentPassword: form.get('oldPassword'), newPassword: form.get('newPassword') })
        setUser(null)
        navigate('login')
      } else {
        const updated = await api<ApiUser>('/users/me', { method: 'PUT', body: JSON.stringify(Object.fromEntries(form)) })
        setUser(mapUser(updated))
      }
      showToast({ type: 'success', message: 'Đã lưu thay đổi' })
    } catch (error) { setFailure(errorMessage(error)) }
    finally { setSaving(false) }
  }
  return <BuyerLayout><h1 className="text-2xl font-bold mb-5">Thông tin cá nhân</h1>
    <LoadState error={failure || error} loading={loading} />
    {data && <form key={data.id} className="nexus-card p-5 grid sm:grid-cols-2 gap-4" onSubmit={event => submit(event)}>
      <Input label="Họ tên" name="fullName" defaultValue={data.fullName} required />
      <Input label="Email" value={data.email} disabled />
      <Input label="Số điện thoại" name="phone" defaultValue={data.phone} />
      <Input label="Số giấy tờ" name="identifyNumber" defaultValue={data.identifyNumber} />
      <Input label="Ngày sinh" type="date" name="dateOfBirth" defaultValue={data.dateOfBirth} />
      <label>Giới tính<select className="nexus-input w-full" name="gender" defaultValue={data.gender || 'OTHER'}><option>MALE</option><option>FEMALE</option><option>OTHER</option></select></label>
      <Input label="Địa chỉ" name="address" defaultValue={data.address} />
      <Button type="submit" loading={saving}>Lưu thông tin</Button>
    </form>}
    <form className="nexus-card p-5 mt-6 space-y-3" onSubmit={event => submit(event, true)}>
      <h2 className="font-bold">Đổi mật khẩu</h2>
      <Input label="Mật khẩu hiện tại" name="oldPassword" type="password" required />
      <Input label="Mật khẩu mới" name="newPassword" type="password" minLength={8} required />
      <Input label="Xác nhận mật khẩu" name="confirmPassword" type="password" required />
      <Button type="submit" loading={saving}>Đổi mật khẩu</Button>
    </form>
    <section className="nexus-card p-5 mt-6"><h2 className="font-bold">Lịch sử hoạt động gần đây</h2>
      <LoadState error={history.error} loading={history.loading} />
      <ul>{history.data?.content.map(item => <li className="py-2 border-b" key={item.id}>{new Date(item.createdAt).toLocaleString('vi-VN')} · {item.action} · {item.description}</li>)}</ul>
    </section>
  </BuyerLayout>
}

/**
 * Quản trị danh mục: GET cây → chọn tạo/sửa → POST/PUT → tải lại cây từ DB.
 * Xóa cần xác nhận rồi DELETE; backend là nơi chặn vượt cấp, vòng lặp và danh mục còn sản phẩm.
 */
export function AdminCategoriesPage() {
  // Tăng revision để useLoad lấy lại cây sau thao tác ghi; không tự giả định server đã lưu vào state cục bộ.
  const [revision, setRevision] = useState(0)
  const { data, error, loading } = useLoad<Envelope<ApiCategory[]>>(`/categories?revision=${revision}`)
  const [editing, setEditing] = useState<ApiCategory | null>(null)
  const [failure, setFailure] = useState('')
  const [saving, setSaving] = useState(false)
  const categories = flatten(data?.data || [])
  // Có editing thì sửa theo UUID; không có thì tạo mới. Chuỗi cha rỗng phải chuyển thành null cho DTO backend.
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const values = Object.fromEntries(new FormData(form))
    setFailure(''); setSaving(true)
    try {
      await api(`/categories${editing ? `/${editing.id}` : ''}`, {
        method: editing ? 'PUT' : 'POST', body: JSON.stringify({ ...values, parentId: values.parentId || null }),
      })
      form.reset(); setEditing(null); setRevision(value => value + 1)
    } catch (error) { setFailure(errorMessage(error)) }
    finally { setSaving(false) }
  }
  // Giữ dữ liệu cũ khi server từ chối xóa; lỗi nghiệp vụ được đưa lên form thay vì xóa giả trên giao diện.
  const remove = async (category: ApiCategory) => {
    if (!window.confirm(`Xóa danh mục “${category.name}”?`)) return
    setFailure(''); setSaving(true)
    try { await api(`/categories/${category.id}`, { method: 'DELETE' }); setRevision(value => value + 1) }
    catch (error) { setFailure(errorMessage(error)) }
    finally { setSaving(false) }
  }
  return <AdminLayout title="Quản lý danh mục">
    <LoadState error={failure || error} loading={loading} />
    <form key={editing?.id || 'new'} onSubmit={submit} className="nexus-card p-5 space-y-3 mb-6">
      <h2 className="font-bold">{editing ? 'Sửa danh mục' : 'Thêm danh mục'}</h2>
      <Input name="name" label="Tên tiếng Anh" defaultValue={editing?.name} maxLength={255} required />
      <Input name="nameVi" label="Tên tiếng Việt" defaultValue={editing?.nameVi} maxLength={255} required />
      <label>Danh mục cha<select className="nexus-input w-full" name="parentId" defaultValue={editing?.parentId || ''}>
        <option value="">Danh mục gốc</option>{categories.filter(item => item.level < 3 && item.id !== editing?.id).map(item => <option value={item.id} key={item.id}>{'—'.repeat(item.level - 1)} {item.nameVi || item.name}</option>)}
      </select></label>
      <label>Trạng thái<select className="nexus-input w-full" name="status" defaultValue={editing?.status || 'ACTIVE'}><option>ACTIVE</option><option>INACTIVE</option></select></label>
      <div className="flex gap-3"><Button type="submit" loading={saving}>Lưu</Button>{editing && <Button onClick={() => setEditing(null)}>Hủy</Button>}</div>
    </form>
    <div className="nexus-card p-4">{categories.map(item => <div className="flex justify-between items-center border-b py-3 gap-3" key={item.id}>
      <span style={{ paddingLeft: (item.level - 1) * 24 }}>{item.nameVi || item.name} · {item.productCount} sản phẩm · {item.status}</span>
      <div className="flex gap-2"><Button disabled={saving} onClick={() => setEditing(item)}>Sửa</Button><Button disabled={saving} onClick={() => remove(item)}>Xóa</Button></div>
    </div>)}</div>
  </AdminLayout>
}
