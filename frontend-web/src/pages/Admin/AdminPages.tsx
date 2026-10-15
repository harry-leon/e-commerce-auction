import { useState } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
import { AdminLayout } from '../../components/Layout'
import { Button, Input, Textarea, Select, Tabs, Modal, ConfirmModal, AlertBanner, EmptyState, StatusBadge, DataTable, Toggle, Breadcrumb, Plus, Pencil, Trash2, Search, Eye, ChevronRight, ChevronDown, AlertTriangle, CheckCircle, Server, TrendingUp, Package, Gavel, Users, User2, Settings2, Shield, ClipboardList, Info } from '../../components/UI'
import { USERS, PRODUCTS, AUCTIONS, ORDERS, ROLES, EVENT_LOGS, SYSTEM_SERVICES } from '../../mockData'
import { getEffectivePaymentStatus } from '../../payment'

// ─── Admin Dashboard ───────────────────────────────────────────────────────────
export function AdminDashboardPage() {
  const { lang, navigate } = useApp()
  const KPIs = [
    { icon: <TrendingUp size={20} />, label: 'Gross Merchandise Value', value: '$2.4M', change: '+23%' },
    { icon: <Package size={20} />, label: lang === 'en' ? 'Total Orders' : 'Tổng đơn hàng', value: '8,241', change: '+12%' },
    { icon: <Users size={20} />, label: lang === 'en' ? 'Active Users' : 'Người dùng hoạt động', value: '4,821', change: '+8%' },
    { icon: <Gavel size={20} />, label: lang === 'en' ? 'Running Auctions' : 'Đang đấu giá', value: '2', change: '' },
  ]
  const DOWN_SERVICES = SYSTEM_SERVICES.filter(s => s.status === 'down')
  return (
    <AdminLayout title={tr(lang, 'dashboard')}>
      {DOWN_SERVICES.length > 0 && (
        <div className="mb-5"><AlertBanner type="error" message={lang === 'en' ? `Service(s) down: ${DOWN_SERVICES.map(s => s.name).join(', ')}` : `Dịch vụ bị sập: ${DOWN_SERVICES.map(s => s.name).join(', ')}`} /></div>
      )}
      <div className="grid grid-cols-4 gap-5 mb-6">
        {KPIs.map(k => (
          <div key={k.label} className="nexus-card p-5">
            <div className="flex items-center gap-3 mb-3"><span className="text-gray-500">{k.icon}</span><span className="text-xs text-gray-500">{k.label}</span></div>
            <p className="text-2xl font-black text-gray-900">{k.value}</p>
            {k.change && <p className="text-xs text-success font-semibold mt-1">{k.change}</p>}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-[1fr_320px] gap-6">
        <div className="nexus-card p-5">
          <h3 className="font-bold mb-4">{lang === 'en' ? 'GMV Overview' : 'Tổng quan GMV'}</h3>
          <div className="flex items-end gap-1.5 h-40">
            {[30, 50, 40, 70, 55, 85, 65, 90, 75, 95, 80, 100].map((h, i) => (
              <div key={i} className="flex-1 rounded-t-sm" style={{ height: `${h}%`, background: i === 11 ? '#4F46E5' : '#EEF2FF' }} />
            ))}
          </div>
          <div className="flex justify-between text-xs text-gray-400 mt-2"><span>Jan</span><span>Jun</span><span>Dec</span></div>
        </div>
        <div className="nexus-card p-5">
          <div className="flex items-center justify-between mb-4"><h3 className="font-bold">{lang === 'en' ? 'System Health' : 'Trạng thái hệ thống'}</h3><button onClick={() => navigate('admin-system-health')} className="text-xs text-primary">{tr(lang, 'viewAll')}</button></div>
          <div className="space-y-2">
            {SYSTEM_SERVICES.slice(0, 5).map(s => (
              <div key={s.name} className="flex items-center justify-between">
                <span className="text-xs text-gray-700 truncate flex-1">{s.name}</span>
                <span className={`badge ml-2 ${s.status === 'up' ? 'badge-green' : 'badge-red'}`}>{s.status === 'up' ? tr(lang, 'serviceUp') : tr(lang, 'serviceDown')}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
      <div className="nexus-card mt-6">
        <div className="flex items-center justify-between p-5 pb-0"><h3 className="font-bold">{lang === 'en' ? 'Recent Events' : 'Sự kiện gần đây'}</h3><button onClick={() => navigate('admin-event-logs')} className="text-xs text-primary">{tr(lang, 'viewAll')}</button></div>
        <div className="p-5">
          {EVENT_LOGS.slice(0, 4).map(e => (
            <div key={e.id} className="flex gap-4 py-3 border-b border-border last:border-0">
              <div className="w-8 h-8 bg-gray-100 rounded-lg flex items-center justify-center text-sm">
                {e.type === 'AUCTION' ? <Gavel size={16} /> : e.type === 'ORDER' ? <Package size={16} /> : e.type === 'USER' ? <User2 size={16} /> : <Settings2 size={16} />}
              </div>
              <div className="flex-1">
                <p className="text-sm font-semibold text-gray-800">{e.action}</p>
                <p className="text-xs text-gray-500">{e.message}</p>
              </div>
              <div className="text-right">
                <p className="text-xs text-gray-400">{e.operator}</p>
                <p className="text-xs text-gray-300">{new Date(e.timestamp).toLocaleTimeString()}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </AdminLayout>
  )
}

// ─── Admin Users ────────────────────────────────────────────────────────────────
export function AdminUsersPage() {
  const { lang, navigate, showToast } = useApp()
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [deleteId, setDeleteId] = useState<string | null>(null)

  const filtered = USERS.filter(u =>
    (roleFilter === 'all' || u.role === roleFilter) &&
    (!search || u.fullName.toLowerCase().includes(search.toLowerCase()) || u.email.includes(search))
  )

  return (
    <AdminLayout title={tr(lang, 'userManagement')}>
      <div className="flex items-center justify-between mb-5">
        <div className="flex gap-3">
          <Input icon={<Search size={14} />} placeholder={lang === 'en' ? 'Search users...' : 'Tìm người dùng...'} value={search} onChange={e => setSearch(e.target.value)} className="w-64" />
          <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)} className="nexus-input text-sm py-2 w-36">
            {['all', 'buyer', 'seller', 'admin', 'support'].map(r => <option key={r} value={r}>{r === 'all' ? tr(lang, 'all') : r.charAt(0).toUpperCase() + r.slice(1)}</option>)}
          </select>
        </div>
        <Button variant="primary" icon={<Plus size={14} />} onClick={() => setCreateOpen(true)}>{tr(lang, 'createUser')}</Button>
      </div>
      <div className="nexus-card">
        {filtered.length === 0 ? (
          <div className="p-8"><EmptyState icon={<Users size={20} />} title={tr(lang, 'noData')} /></div>
        ) : (
          <DataTable
            columns={[
              { key: 'avatar', label: '', width: '48px', render: (r: any) => <img src={r.avatar} alt="" className="w-9 h-9 rounded-full object-cover" /> },
              { key: 'fullName', label: lang === 'en' ? 'Name' : 'Họ tên', render: (r: any) => <div><p className="font-semibold text-sm">{r.fullName}</p><p className="text-xs text-gray-400">{r.email}</p></div> },
              { key: 'phone', label: lang === 'en' ? 'Phone' : 'Điện thoại', render: (r: any) => <span className="text-xs">{r.phone}</span> },
              { key: 'role', label: lang === 'en' ? 'Role' : 'Vai trò', render: (r: any) => <span className="badge badge-indigo text-xs">{r.role}</span> },
              { key: 'trustLevel', label: lang === 'en' ? 'Trust' : 'Uy tín', render: (r: any) => <StatusBadge status={r.trustLevel} label={r.trustLevel.charAt(0).toUpperCase() + r.trustLevel.slice(1)} /> },
              { key: 'status', label: tr(lang, 'status'), render: (r: any) => <StatusBadge status={r.status === 'active' ? 'active' : 'cancelled'} label={r.status === 'active' ? (lang === 'en' ? 'Active' : 'Hoạt động') : (lang === 'en' ? 'Locked' : 'Bị khóa')} /> },
              {
                key: 'actions', label: tr(lang, 'actions'), render: (r: any) => (
                  <div className="flex gap-1">
                    <button onClick={e => { e.stopPropagation(); navigate('admin-user-detail', { id: r.id }) }} className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5"><Search size={13} /></button>
                    <button className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5"><Pencil size={13} /></button>
                    <button onClick={e => { e.stopPropagation(); setDeleteId(r.id) }} className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"><Trash2 size={13} /></button>
                  </div>
                )
              },
            ]}
            data={filtered}
            rowKey={r => r.id}
            onRowClick={r => navigate('admin-user-detail', { id: r.id })}
          />
        )}
      </div>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={tr(lang, 'createUser')} size="lg"
        footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setCreateOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'User created!' : 'Đã tạo người dùng!' }) }}>{tr(lang, 'create')}</Button></>}>
        <div className="grid grid-cols-2 gap-4">
          <Input label={tr(lang, 'fullName')} />
          <Input label={tr(lang, 'email')} type="email" />
          <Input label={tr(lang, 'phone')} type="tel" />
          <Input label={tr(lang, 'idNumber')} />
          <Select label={lang === 'en' ? 'Role' : 'Vai trò'} value="buyer" onChange={() => { }} options={['buyer', 'seller', 'admin', 'support'].map(r => ({ value: r, label: r.charAt(0).toUpperCase() + r.slice(1) }))} />
          <Select label={tr(lang, 'gender')} value="male" onChange={() => { }} options={[{ value: 'male', label: tr(lang, 'male') }, { value: 'female', label: tr(lang, 'female') }]} />
          <Input label={tr(lang, 'dateOfBirth')} type="date" />
        </div>
        <div className="mt-4"><Input label={tr(lang, 'address')} /></div>
      </Modal>

      <ConfirmModal open={!!deleteId} onClose={() => setDeleteId(null)} onConfirm={() => showToast({ type: 'success', message: lang === 'en' ? 'User deleted.' : 'Đã xóa người dùng.' })}
        title={tr(lang, 'confirmDeleteTitle')} message={lang === 'en' ? 'This will permanently delete the user account.' : 'Thao tác này sẽ xóa vĩnh viễn tài khoản người dùng.'} danger />
    </AdminLayout>
  )
}

// ─── Admin User Detail ─────────────────────────────────────────────────────────
export function AdminUserDetailPage() {
  const { lang, navigate, pageParams } = useApp()
  const user = USERS.find(u => u.id === pageParams?.id) || USERS[0]
  const [tab, setTab] = useState('info')
  return (
    <AdminLayout title={tr(lang, 'editUser')}>
      <Breadcrumb items={[{ label: tr(lang, 'userManagement'), page: 'admin-users' }, { label: user.fullName }]} />
      <div className="mt-5 nexus-card">
        <div className="p-5 flex items-center gap-5 border-b border-border">
          <img src={user.avatar} alt="" className="w-16 h-16 rounded-2xl object-cover" />
          <div><h2 className="text-xl font-bold">{user.fullName}</h2><p className="text-gray-500 text-sm">{user.email}</p><div className="flex gap-2 mt-1.5"><span className="badge badge-indigo">{user.role}</span><StatusBadge status={user.status === 'active' ? 'active' : 'cancelled'} label={user.status} /></div></div>
        </div>
        <Tabs tabs={[{ id: 'info', label: lang === 'en' ? 'Info' : 'Thông tin' }, { id: 'reputation', label: tr(lang, 'reputation') }, { id: 'activity', label: lang === 'en' ? 'Activity' : 'Hoạt động' }]} active={tab} onChange={setTab} />
        <div className="p-6">
          {tab === 'info' && (
            <div className="grid grid-cols-2 gap-4">
              {[['Full Name', user.fullName], ['Email', user.email], ['Phone', user.phone], ['ID Number', user.idNumber], ['Gender', user.gender], ['Date of Birth', user.dateOfBirth], ['Role', user.role], ['Status', user.status], ['Created', user.createdAt]].map(([k, v]) => (
                <div key={k} className="flex items-center p-3 bg-gray-50 rounded-lg">
                  <span className="text-xs font-semibold text-gray-500 w-32">{k}</span>
                  <span className="text-sm text-gray-800">{v}</span>
                </div>
              ))}
            </div>
          )}
          {tab === 'reputation' && (
            <div className="space-y-4">
              <div className="grid grid-cols-3 gap-4">
                {[['Score', user.reputationScore + ' pts'], ['Trust Level', user.trustLevel.charAt(0).toUpperCase() + user.trustLevel.slice(1)], ['Transactions', '24 successful']].map(([k, v]) => (
                  <div key={k} className="nexus-card p-4 text-center"><p className="text-xs text-gray-500">{k}</p><p className="text-xl font-black text-primary mt-1">{v}</p></div>
                ))}
              </div>
              <div className="flex gap-3">
                <Button variant="outline" size="sm"><AlertTriangle size={14} />{tr(lang, 'applyPenalty')}</Button>
                <Button variant="outline" size="sm">{tr(lang, 'adjustReputation')}</Button>
              </div>
            </div>
          )}
          {tab === 'activity' && (
            <div className="space-y-3">
              {EVENT_LOGS.slice(0, 5).map(e => (
                <div key={e.id} className="flex gap-3 p-3 bg-gray-50 rounded-xl">
                  <div className="text-gray-500">{e.type === 'ORDER' ? <Package size={18} /> : e.type === 'AUCTION' ? <Gavel size={18} /> : e.type === 'USER' ? <User2 size={18} /> : <Settings2 size={18} />}</div>
                  <div><p className="text-sm font-semibold">{e.action}</p><p className="text-xs text-gray-500">{e.message}</p><p className="text-xs text-gray-400">{new Date(e.timestamp).toLocaleString()}</p></div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </AdminLayout>
  )
}

// ─── Admin Roles ───────────────────────────────────────────────────────────────
const MODULES = ['USER', 'ROLE', 'PRODUCT', 'CATEGORY', 'ORDER', 'AUCTION', 'SHIPPING', 'REPUTATION', 'NOTIFICATION', 'SYSTEM']

export function AdminRolesPage() {
  const { lang, showToast } = useApp()
  const [createOpen, setCreateOpen] = useState(false)
  const [selectedRole, setSelectedRole] = useState<typeof ROLES[0] | null>(null)

  return (
    <AdminLayout title={tr(lang, 'roleManagement')}>
      <div className="grid grid-cols-[260px_1fr] gap-6">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-bold">{lang === 'en' ? 'Roles' : 'Vai trò'}</h3>
            <Button variant="primary" size="sm" icon={<Plus size={12} />} onClick={() => setCreateOpen(true)}>{tr(lang, 'create')}</Button>
          </div>
          <div className="space-y-2">
            {ROLES.map(role => (
              <button key={role.id} onClick={() => setSelectedRole(role)}
                className={`w-full p-3 rounded-xl border text-left transition-colors ${selectedRole?.id === role.id ? 'border-primary bg-primary-light' : 'border-border hover:border-gray-300'}`}>
                <div className="flex items-center justify-between">
                  <p className="font-semibold text-sm">{role.name}</p>
                  <span className="badge badge-gray text-xs">{role.userCount}</span>
                </div>
                <p className="text-xs text-gray-500 mt-0.5">{role.description}</p>
              </button>
            ))}
          </div>
        </div>
        <div>
          {selectedRole ? (
            <div className="nexus-card">
              <div className="p-5 border-b border-border flex items-center justify-between">
                <div><h2 className="font-bold text-lg">{selectedRole.name}</h2><p className="text-sm text-gray-500">{selectedRole.description}</p></div>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm"><Pencil size={14} />{tr(lang, 'edit')}</Button>
                  <Button variant="danger" size="sm"><Trash2 size={14} />{tr(lang, 'delete')}</Button>
                </div>
              </div>
              <div className="p-5 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-left py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide w-32">Module</th>
                      {['Read', 'Write', 'Delete', 'Admin'].map(p => <th key={p} className="text-center py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide w-16">{p}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {MODULES.map(mod => {
                      const perm = selectedRole.permissions.find(p => p.module === mod)
                      return (
                        <tr key={mod} className="border-b border-border">
                          <td className="py-3 font-semibold text-gray-700">{mod}</td>
                          {(['read', 'write', 'delete', 'admin'] as const).map(p => (
                            <td key={p} className="text-center py-3">
                              <input type="checkbox" defaultChecked={perm?.[p] || false} className="accent-primary" />
                            </td>
                          ))}
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
                <div className="mt-4"><Button variant="primary" onClick={() => showToast({ type: 'success', message: lang === 'en' ? 'Role updated!' : 'Đã cập nhật vai trò!' })}>{tr(lang, 'save')}</Button></div>
              </div>
            </div>
          ) : (
            <div className="nexus-card p-12 flex items-center justify-center h-full">
              <EmptyState icon={<Shield size={20} />} title={lang === 'en' ? 'Select a role to view permissions' : 'Chọn vai trò để xem phân quyền'} />
            </div>
          )}
        </div>
      </div>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={tr(lang, 'createRole')}
        footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setCreateOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Role created!' : 'Đã tạo vai trò!' }) }}>{tr(lang, 'create')}</Button></>}>
        <Input label={lang === 'en' ? 'Role Name' : 'Tên vai trò'} />
        <div className="mt-3"><Textarea label={lang === 'en' ? 'Description' : 'Mô tả'} rows={2} /></div>
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Categories ──────────────────────────────────────────────────────────
import { CATEGORIES as INITIAL_CATEGORIES } from '../../mockData'

interface CategoryItem {
  id: string
  name: string
  nameVi: string
  parentId?: string
  productCount?: number
  children?: CategoryItem[]
}

export function AdminCategoriesPage() {
  const { lang, showToast } = useApp()
  const [categories, setCategories] = useState<CategoryItem[]>(INITIAL_CATEGORIES as CategoryItem[])
  const [search, setSearch] = useState('')
  const [selectedParentId, setSelectedParentId] = useState<string>('root')

  // Modal states
  const [createOpen, setCreateOpen] = useState(false)
  const [editCategory, setEditCategory] = useState<CategoryItem | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<CategoryItem | null>(null)
  const [viewCategory, setViewCategory] = useState<CategoryItem | null>(null)
  const [collapsedIds, setCollapsedIds] = useState<Record<string, boolean>>({})

  const toggleCollapse = (id: string) => {
    setCollapsedIds(prev => ({ ...prev, [id]: !prev[id] }))
  }

  // Form states
  const [formNameEn, setFormNameEn] = useState('')
  const [formNameVi, setFormNameVi] = useState('')
  const [formParentId, setFormParentId] = useState<string>('root')
  const [formError, setFormError] = useState('')

  // Configurations
  const CATEGORY_MAX_LEVEL = 3
  const ALLOW_CATEGORY_DELETE_WITH_PRODUCT = false

  // Flatten categories with their depth/level for select options
  const getFlatOptions = (items: CategoryItem[], currentLevel = 1, prefix = ''): { id: string; label: string; level: number }[] => {
    let result: { id: string; label: string; level: number }[] = []
    for (const item of items) {
      const displayName = lang === 'vi' ? item.nameVi : item.name
      result.push({ id: item.id, label: `${prefix}${displayName}`, level: currentLevel })
      if (item.children && item.children.length > 0) {
        result = result.concat(getFlatOptions(item.children, currentLevel + 1, `${prefix}— `))
      }
    }
    return result
  }

  // Find category level in tree
  const findCategoryLevel = (items: CategoryItem[], targetId: string, currentLevel = 1): number => {
    for (const item of items) {
      if (item.id === targetId) return currentLevel
      if (item.children && item.children.length > 0) {
        const found = findCategoryLevel(item.children, targetId, currentLevel + 1)
        if (found !== -1) return found
      }
    }
    return -1
  }

  // Calculate total products recursively
  const getCategoryTotalProducts = (cat: CategoryItem): number => {
    const direct = cat.productCount || 0
    const childrenTotal = (cat.children || []).reduce((acc, child) => acc + getCategoryTotalProducts(child), 0)
    return direct + childrenTotal
  }

  // Check if category has any child with products
  const hasDescendantWithProducts = (cat: CategoryItem): boolean => {
    if ((cat.productCount || 0) > 0) return true
    if (!cat.children || cat.children.length === 0) return false
    return cat.children.some(c => hasDescendantWithProducts(c))
  }

  // Find category by ID
  const findCategoryById = (items: CategoryItem[], id: string): CategoryItem | null => {
    for (const item of items) {
      if (item.id === id) return item
      if (item.children) {
        const res = findCategoryById(item.children, id)
        if (res) return res
      }
    }
    return null
  }

  // Open Create Modal
  const openCreateModal = (presetParentId?: string) => {
    setFormNameEn('')
    setFormNameVi('')
    setFormParentId(presetParentId || 'root')
    setFormError('')
    setCreateOpen(true)
  }

  // Open Edit Modal
  const openEditModal = (cat: CategoryItem) => {
    setEditCategory(cat)
    setFormNameEn(cat.name)
    setFormNameVi(cat.nameVi)
    setFormParentId(cat.parentId || 'root')
    setFormError('')
  }

  // Handle Create Category with BR-CFG-11 Validation
  const handleCreateCategory = () => {
    if (!formNameEn.trim() || !formNameVi.trim()) {
      setFormError(lang === 'en' ? 'Both English and Vietnamese names are required.' : 'Vui lòng nhập cả tên tiếng Anh và tiếng Việt.')
      return
    }

    // Validate level
    let targetLevel = 1
    if (formParentId !== 'root') {
      const parentLevel = findCategoryLevel(categories, formParentId)
      targetLevel = parentLevel + 1
    }

    if (targetLevel > CATEGORY_MAX_LEVEL) {
      setFormError(
        lang === 'en'
          ? `Maximum category depth is ${CATEGORY_MAX_LEVEL} levels. Cannot add subcategory under Level ${targetLevel - 1}.`
          : `Độ sâu danh mục tối đa là ${CATEGORY_MAX_LEVEL} cấp. Không thể tạo danh mục con dưới cấp ${targetLevel - 1}.`
      )
      return
    }

    const newId = `cat-${Date.now().toString(36)}`
    const newCategory: CategoryItem = {
      id: newId,
      name: formNameEn.trim(),
      nameVi: formNameVi.trim(),
      parentId: formParentId === 'root' ? undefined : formParentId,
      productCount: 0,
      children: [],
    }

    if (formParentId === 'root') {
      setCategories(prev => [...prev, newCategory])
    } else {
      const addChild = (items: CategoryItem[]): CategoryItem[] => {
        return items.map(item => {
          if (item.id === formParentId) {
            return {
              ...item,
              children: [...(item.children || []), newCategory],
            }
          }
          if (item.children) {
            return { ...item, children: addChild(item.children) }
          }
          return item
        })
      }
      setCategories(prev => addChild(prev))
    }

    setCreateOpen(false)
    showToast({
      type: 'success',
      message: lang === 'en' ? `Category "${newCategory.name}" created successfully!` : `Đã thêm danh mục "${newCategory.nameVi}"!`,
    })
  }

  // Handle Edit Category
  const handleUpdateCategory = () => {
    if (!editCategory) return
    if (!formNameEn.trim() || !formNameVi.trim()) {
      setFormError(lang === 'en' ? 'Both English and Vietnamese names are required.' : 'Vui lòng nhập đầy đủ tên.')
      return
    }

    const updateItem = (items: CategoryItem[]): CategoryItem[] => {
      return items.map(item => {
        if (item.id === editCategory.id) {
          return {
            ...item,
            name: formNameEn.trim(),
            nameVi: formNameVi.trim(),
          }
        }
        if (item.children) {
          return { ...item, children: updateItem(item.children) }
        }
        return item
      })
    }

    setCategories(prev => updateItem(prev))
    setEditCategory(null)
    showToast({
      type: 'success',
      message: lang === 'en' ? 'Category updated successfully.' : 'Đã cập nhật danh mục thành công.',
    })
  }

  // Handle Delete Category
  const handleDeleteCategory = (cat: CategoryItem) => {
    // ALLOW_CATEGORY_DELETE_WITH_PRODUCT = false
    const totalProducts = getCategoryTotalProducts(cat)
    if (!ALLOW_CATEGORY_DELETE_WITH_PRODUCT && totalProducts > 0) {
      showToast({
        type: 'error',
        message:
          lang === 'en'
            ? `Category contains ${totalProducts} active products. Cannot delete category while products exist!`
            : `Danh mục đang chứa ${totalProducts} sản phẩm. Không cho phép xóa danh mục có sản phẩm!`,
      })
      setDeleteTarget(null)
      return
    }

    // Also check if it has subcategories
    if (cat.children && cat.children.length > 0) {
      const hasProductsInSub = cat.children.some(c => hasDescendantWithProducts(c))
      if (hasProductsInSub) {
        showToast({
          type: 'error',
          message:
            lang === 'en'
              ? 'Subcategories contain products. Please reassign or delete them first!'
              : 'Danh mục con đang chứa sản phẩm. Vui lòng chuyển hoặc xóa sản phẩm trước!',
        })
        setDeleteTarget(null)
        return
      }
    }

    // Perform deletion
    const removeItem = (items: CategoryItem[]): CategoryItem[] => {
      return items
        .filter(item => item.id !== cat.id)
        .map(item => ({
          ...item,
          children: item.children ? removeItem(item.children) : undefined,
        }))
    }

    setCategories(prev => removeItem(prev))
    setDeleteTarget(null)
    showToast({
      type: 'success',
      message: lang === 'en' ? `Category "${cat.name}" deleted.` : `Đã xóa danh mục "${cat.nameVi}".`,
    })
  }

  // Filter categories by search
  const filterCategoryTree = (items: CategoryItem[], query: string): CategoryItem[] => {
    if (!query) return items
    const lower = query.toLowerCase()
    return items
      .map(item => {
        const matchesSelf = item.name.toLowerCase().includes(lower) || item.nameVi.toLowerCase().includes(lower)
        const filteredChildren = item.children ? filterCategoryTree(item.children, query) : []
        if (matchesSelf || filteredChildren.length > 0) {
          return { ...item, children: filteredChildren }
        }
        return null
      })
      .filter(Boolean) as CategoryItem[]
  }

  const displayedCategories = filterCategoryTree(categories, search)
  const flatParentOptions = getFlatOptions(categories).filter(opt => opt.level < CATEGORY_MAX_LEVEL)
  const allFlatCategories = getFlatOptions(categories)

  const totalRootCategories = categories.length
  const totalAllCategories = allFlatCategories.length
  const totalProductsCatalog = categories.reduce((sum, c) => sum + getCategoryTotalProducts(c), 0)

  return (
    <AdminLayout title={tr(lang, 'categoryManagement')}>
      <div className="grid grid-cols-[1fr_320px] gap-6">
        {/* Category Tree Main Card */}
        <div className="nexus-card">
          <div className="p-5 border-b border-border flex items-center justify-between gap-4 flex-wrap">
            <div>
              <h3 className="font-bold text-base text-gray-900">{lang === 'en' ? 'Product Categories' : 'Danh mục & Phân loại'}</h3>
              <p className="text-xs text-gray-500 mt-0.5">{lang === 'en' ? 'Manage categories and subcategories' : 'Quản lý danh mục sản phẩm và phân loại trực thuộc'}</p>
            </div>
            <div className="flex items-center gap-2">
              <Input
                placeholder={lang === 'en' ? 'Search...' : 'Tìm kiếm...'}
                value={search}
                onChange={e => setSearch(e.target.value)}
                className="w-48 text-xs"
              />
              <Button variant="primary" size="sm" onClick={() => openCreateModal()}>
                {lang === 'en' ? 'Add Category' : 'Thêm danh mục'}
              </Button>
            </div>
          </div>

          <div className="p-5 space-y-3">
            {displayedCategories.length === 0 ? (
              <div className="py-16 text-center">
                <h3 className="text-lg font-semibold text-gray-700 mb-5">{lang === 'en' ? 'No categories found' : 'Không tìm thấy danh mục'}</h3>
                <Button variant="primary" size="sm" onClick={() => openCreateModal()}>
                  {lang === 'en' ? 'Create Category' : 'Tạo danh mục'}
                </Button>
              </div>
            ) : (
              displayedCategories.map(cat => {
                const totalCatProducts = getCategoryTotalProducts(cat)
                const canAddChild = 1 < CATEGORY_MAX_LEVEL
                const isCatCollapsed = !!collapsedIds[cat.id]
                const hasChildren = !!(cat.children && cat.children.length > 0)

                return (
                  <div key={cat.id} className="border border-border/80 rounded-xl overflow-hidden bg-white shadow-xs">
                    {/* Category (Root) */}
                    <div
                      onClick={() => hasChildren && toggleCollapse(cat.id)}
                      className={`flex items-center justify-between p-3.5 bg-gray-50/80 hover:bg-gray-100/70 transition-colors ${hasChildren ? 'cursor-pointer select-none' : ''}`}
                    >
                      <div className="flex items-center gap-2">
                        {hasChildren ? (
                          <span className="text-gray-400 transition-transform">
                            {isCatCollapsed ? <ChevronRight size={15} /> : <ChevronDown size={15} />}
                          </span>
                        ) : (
                          <span className="w-[15px]" />
                        )}
                        <span className="font-bold text-sm text-gray-900 w-36 truncate">{lang === 'vi' ? cat.nameVi : cat.name}</span>
                        <span className={`badge ${totalCatProducts > 0 ? 'badge-blue' : 'badge-gray'} text-[11px] font-semibold py-0.5 px-2`}>
                          {totalCatProducts} {lang === 'en' ? 'products' : 'sản phẩm'}
                        </span>
                      </div>

                      <div className="flex items-center gap-1" onClick={e => e.stopPropagation()}>
                        {canAddChild && (
                          <button
                            title={lang === 'en' ? 'Add subcategory' : 'Thêm phân loại'}
                            onClick={() => openCreateModal(cat.id)}
                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-primary hover:bg-primary-light"
                          >
                            <Plus size={14} />
                          </button>
                        )}
                        <button
                          title={lang === 'en' ? 'View details' : 'Xem chi tiết'}
                          onClick={() => setViewCategory(cat)}
                          className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                        >
                          <Eye size={13} />
                        </button>
                        <button
                          title={lang === 'en' ? 'Edit category' : 'Sửa'}
                          onClick={() => openEditModal(cat)}
                          className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                        >
                          <Pencil size={13} />
                        </button>
                        <button
                          title={lang === 'en' ? 'Delete category' : 'Xóa'}
                          onClick={() => setDeleteTarget(cat)}
                          className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger hover:bg-red-50"
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>

                    {/* Subcategories (Phân loại) */}
                    {!isCatCollapsed && hasChildren && (
                      <div className="divide-y divide-border/40 pl-6 pr-3 py-1 bg-white">
                        {cat.children!.map(child => {
                          const childProducts = getCategoryTotalProducts(child)
                          const canAddLeaf = 2 < CATEGORY_MAX_LEVEL
                          const isChildCollapsed = !!collapsedIds[child.id]
                          const hasLeafChildren = !!(child.children && child.children.length > 0)

                          return (
                            <div key={child.id} className="py-2">
                              <div
                                onClick={() => hasLeafChildren && toggleCollapse(child.id)}
                                className={`flex items-center justify-between rounded-lg p-2 hover:bg-gray-50/80 transition-colors ${hasLeafChildren ? 'cursor-pointer select-none' : ''}`}
                              >
                                <div className="flex items-center gap-2">
                                  {hasLeafChildren ? (
                                    <span className="text-gray-400">
                                      {isChildCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                                    </span>
                                  ) : (
                                    <span className="w-[14px]" />
                                  )}
                                  <span className="font-semibold text-sm text-gray-800 w-36 truncate">{lang === 'vi' ? child.nameVi : child.name}</span>
                                  <span className="text-[11px] font-medium px-2 py-0.5 rounded-md bg-indigo-50 text-indigo-700 border border-indigo-100">
                                    {childProducts} {lang === 'en' ? 'products' : 'sản phẩm'}
                                  </span>
                                </div>

                                <div className="flex items-center gap-1" onClick={e => e.stopPropagation()}>
                                  {canAddLeaf && (
                                    <button
                                      title={lang === 'en' ? 'Add group' : 'Thêm nhóm'}
                                      onClick={() => openCreateModal(child.id)}
                                      className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-primary hover:bg-primary-light"
                                    >
                                      <Plus size={14} />
                                    </button>
                                  )}
                                  <button
                                    title={lang === 'en' ? 'View details' : 'Xem chi tiết'}
                                    onClick={() => setViewCategory(child)}
                                    className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                                  >
                                    <Eye size={13} />
                                  </button>
                                  <button
                                    title={lang === 'en' ? 'Edit category' : 'Sửa'}
                                    onClick={() => openEditModal(child)}
                                    className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                                  >
                                    <Pencil size={13} />
                                  </button>
                                  <button
                                    title={lang === 'en' ? 'Delete category' : 'Xóa'}
                                    onClick={() => setDeleteTarget(child)}
                                    className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger hover:bg-red-50"
                                  >
                                    <Trash2 size={13} />
                                  </button>
                                </div>
                              </div>

                              {/* Third-level Subcategories (Nhóm con) */}
                              {!isChildCollapsed && hasLeafChildren && (
                                <div className="pl-7 pr-2 py-1 space-y-1">
                                  {child.children!.map(leaf => {
                                    const leafProducts = leaf.productCount || 0
                                    return (
                                      <div key={leaf.id} className="flex items-center justify-between p-1.5 rounded-lg bg-gray-50/50 hover:bg-gray-100/50 transition-colors">
                                        <div className="flex items-center gap-2">
                                          <span className="w-[14px]" />
                                          <span className="font-medium text-xs text-gray-700 w-36 truncate">{lang === 'vi' ? leaf.nameVi : leaf.name}</span>
                                          <span className="text-[11px] text-gray-500 font-medium px-1.5 py-0.5 rounded bg-gray-100">
                                            {leafProducts} {lang === 'en' ? 'products' : 'sản phẩm'}
                                          </span>
                                        </div>

                                        <div className="flex items-center gap-1">
                                          <button
                                            title={lang === 'en' ? 'View details' : 'Xem chi tiết'}
                                            onClick={() => setViewCategory(leaf)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                                          >
                                            <Eye size={13} />
                                          </button>
                                          <button
                                            title={lang === 'en' ? 'Edit category' : 'Sửa'}
                                            onClick={() => openEditModal(leaf)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500 hover:text-gray-800"
                                          >
                                            <Pencil size={13} />
                                          </button>
                                          <button
                                            title={lang === 'en' ? 'Delete category' : 'Xóa'}
                                            onClick={() => setDeleteTarget(leaf)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger hover:bg-red-50"
                                          >
                                            <Trash2 size={13} />
                                          </button>
                                        </div>
                                      </div>
                                    )
                                  })}
                                </div>
                              )}
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })
            )}
          </div>
        </div>

        {/* Sidebar Statistics & Rules Card */}
        <div className="space-y-5">
          <div className="nexus-card p-5">
            <h3 className="font-bold text-base text-gray-900 mb-4">{lang === 'en' ? 'Catalog Overview' : 'Tổng quan danh mục'}</h3>
            <div className="space-y-3">
              <div className="flex items-center justify-between p-3 bg-gray-50 rounded-xl">
                <span className="text-xs font-semibold text-gray-600">{lang === 'en' ? 'Categories' : 'Danh mục'}</span>
                <span className="font-black text-gray-900 text-sm">{totalRootCategories}</span>
              </div>
              <div className="flex items-center justify-between p-3 bg-gray-50 rounded-xl">
                <span className="text-xs font-semibold text-gray-600">{lang === 'en' ? 'Total with Subcategories' : 'Tổng gồm phân loại'}</span>
                <span className="font-black text-primary text-sm">{totalAllCategories}</span>
              </div>
              <div className="flex items-center justify-between p-3 bg-gray-50 rounded-xl">
                <span className="text-xs font-semibold text-gray-600">{lang === 'en' ? 'Total Linked Products' : 'Tổng sản phẩm'}</span>
                <span className="font-black text-success text-sm">{totalProductsCatalog}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Modal: Create Category */}
      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={lang === 'en' ? 'Add Category / Subcategory' : 'Thêm danh mục / phân loại'}

        footer={
          <>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>
              {tr(lang, 'cancel')}
            </Button>
            <Button variant="primary" onClick={handleCreateCategory}>
              {tr(lang, 'create')}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {formError && <AlertBanner type="error" message={formError} />}

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-semibold text-gray-700">{lang === 'en' ? 'Parent Category' : 'Trực thuộc danh mục'}</label>
            <select
              value={formParentId}
              onChange={e => {
                setFormParentId(e.target.value)
                setFormError('')
              }}
              className="nexus-input py-2 text-sm"
            >
              <option value="root">{lang === 'en' ? 'None' : 'Không có'}</option>
              {flatParentOptions.map(opt => (
                <option key={opt.id} value={opt.id}>
                  {opt.label}
                </option>
              ))}
            </select>
            <span className="text-[11px] text-gray-500">
              {lang === 'en'
                ? 'Select a categories to create as a subcategories (Max 3 levels).'
                : 'Chọn danh mục nếu muốn tạo phân loại trực thuộc.'}
            </span>
          </div>

          <Input
            label={`${tr(lang, 'name')} (EN)`}
            value={formNameEn}
            onChange={e => {
              setFormNameEn(e.target.value)
              setFormError('')
            }}
            placeholder="e.g. Smart Home Devices"
          />

          <Input
            label={`${tr(lang, 'name')} (VI)`}
            value={formNameVi}
            onChange={e => {
              setFormNameVi(e.target.value)
              setFormError('')
            }}
            placeholder="Ví dụ: Thiết bị nhà thông minh"
          />
        </div>
      </Modal>

      {/* Modal: Edit Category */}
      <Modal
        open={!!editCategory}
        onClose={() => setEditCategory(null)}
        title={lang === 'en' ? 'Edit Category' : 'Chỉnh sửa'}
        footer={
          <>
            <Button variant="outline" onClick={() => setEditCategory(null)}>
              {tr(lang, 'cancel')}
            </Button>
            <Button variant="primary" onClick={handleUpdateCategory}>
              {tr(lang, 'save')}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {formError && <AlertBanner type="error" message={formError} />}
          <Input
            label={`${tr(lang, 'name')} (EN)`}
            value={formNameEn}
            onChange={e => setFormNameEn(e.target.value)}
          />

          <Input
            label={`${tr(lang, 'name')} (VI)`}
            value={formNameVi}
            onChange={e => setFormNameVi(e.target.value)}
          />
        </div>
      </Modal>

      {/* Modal: View Category Details */}
      <Modal
        open={!!viewCategory}
        onClose={() => setViewCategory(null)}
        title={lang === 'en' ? 'Category Details' : 'Chi tiết'}
        footer={
          <Button variant="primary" onClick={() => setViewCategory(null)}>
            {tr(lang, 'close')}
          </Button>
        }
      >
        {viewCategory && (
          <div className="space-y-4 text-sm">
            <div className="p-3 bg-gray-50 rounded-xl">
              <div>
                <h4 className="font-bold text-base text-gray-900">{lang === 'vi' ? viewCategory.nameVi : viewCategory.name}</h4>
                <p className="text-xs text-gray-500">{lang === 'vi' ? viewCategory.name : viewCategory.nameVi}</p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 text-xs">
              <div className="p-3 bg-gray-50 rounded-lg">
                <span className="text-gray-400 block mb-0.5">{lang === 'en' ? 'Hierarchy' : 'Cấp phân loại'}</span>
                <span className="font-bold text-gray-900 text-sm">
                  {(() => {
                    const lvl = findCategoryLevel(categories, viewCategory.id)
                    if (lvl === 1) return lang === 'en' ? 'Category' : 'Danh mục'
                    if (lvl === 2) return lang === 'en' ? 'Subcategory' : 'Phân loại'
                    return lang === 'en' ? 'Group' : 'Nhóm hàng'
                  })()}
                </span>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <span className="text-gray-400 block mb-0.5">{lang === 'en' ? 'Direct Products' : 'Sản phẩm trực tiếp'}</span>
                <span className="font-bold text-primary text-sm">{viewCategory.productCount || 0}</span>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <span className="text-gray-400 block mb-0.5">{lang === 'en' ? 'Total Sub-tree Products' : 'Tổng sản phẩm'}</span>
                <span className="font-bold text-success text-sm">{getCategoryTotalProducts(viewCategory)}</span>
              </div>
              <div className="p-3 bg-gray-50 rounded-lg">
                <span className="text-gray-400 block mb-0.5">{lang === 'en' ? 'Sub-items Count' : 'Số mục trực thuộc'}</span>
                <span className="font-bold text-gray-900 text-sm">{viewCategory.children?.length || 0}</span>
              </div>
            </div>
          </div>
        )}
      </Modal>

      {/* Modal: Confirm Delete Target */}
      <ConfirmModal
        open={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => deleteTarget && handleDeleteCategory(deleteTarget)}
        title={lang === 'en' ? 'Confirm Delete' : 'Xác nhận xóa'}
        message={
          deleteTarget
            ? lang === 'en'
              ? `Are you sure you want to delete "${deleteTarget.name}"? This action cannot be undone.`
              : `Bạn có chắc muốn xóa "${deleteTarget.nameVi}"? Thao tác này không thể hoàn tác.`
            : ''
        }
        danger
      />

    </AdminLayout>
  )
}

// ─── Admin Orders ──────────────────────────────────────────────────────────────
export function AdminOrdersPage() {
  const { lang, showToast } = useApp()
  const [search, setSearch] = useState('')
  const [selectedOrder, setSelectedOrder] = useState<(typeof ORDERS)[number] | null>(null)
  const [paymentOverrides, setPaymentOverrides] = useState<Record<string, string>>({})
  const [withdrawalApproved, setWithdrawalApproved] = useState(false)
  const statusLabel = (s: string) => ({ pending: tr(lang, 'statusPending'), paid: tr(lang, 'statusPaid'), processing: tr(lang, 'statusProcessing'), shipped: tr(lang, 'statusShipped'), delivered: tr(lang, 'statusDelivered'), completed: tr(lang, 'statusCompleted'), cancelled: tr(lang, 'statusCancelled'), refunded: tr(lang, 'statusRefunded') }[s] || s)
  const paymentLabel = (s: string) => ({
    held: lang === 'en' ? 'Held by Nexus' : 'Nexus đang giữ',
    protection_period: lang === 'en' ? 'Buyer protection' : 'Bảo vệ Buyer',
    released: lang === 'en' ? 'Released' : 'Đã giải ngân',
    on_hold: lang === 'en' ? 'Dispute hold' : 'Giữ do khiếu nại',
    refunded: lang === 'en' ? 'Refunded' : 'Đã hoàn tiền',
    partial_refund: lang === 'en' ? 'Partial refund' : 'Hoàn một phần',
  }[s] || s)
  const heldTotal = ORDERS.filter(o => ['held', 'protection_period', 'on_hold'].includes(getEffectivePaymentStatus(o))).reduce((sum, order) => sum + order.total, 0)

  const resolvePayment = (status: 'refunded' | 'released' | 'partial_refund') => {
    if (!selectedOrder) return
    setPaymentOverrides(current => ({ ...current, [selectedOrder.id]: status }))
    setSelectedOrder(null)
    showToast({ type: 'success', message: lang === 'en' ? `Dispute resolved: ${paymentLabel(status)}.` : `Đã xử lý khiếu nại: ${paymentLabel(status)}.` })
  }

  return (
    <AdminLayout title={tr(lang, 'orders')}>
      <div className="grid grid-cols-3 gap-5 mb-5">
        <div className="nexus-card p-5"><p className="text-sm text-gray-500">{lang === 'en' ? 'Funds held by Nexus' : 'Tiền Nexus đang giữ'}</p><p className="text-2xl font-black mt-1">{formatCurrency(heldTotal, lang)}</p></div>
        <div className="nexus-card p-5"><p className="text-sm text-gray-500">{lang === 'en' ? 'Open disputes' : 'Khiếu nại đang xử lý'}</p><p className="text-2xl font-black mt-1">{ORDERS.filter(o => getEffectivePaymentStatus(o) === 'on_hold').length}</p></div>
        <div className="nexus-card p-5"><p className="text-sm text-gray-500">{lang === 'en' ? 'Protection period' : 'Đang trong thời gian bảo vệ'}</p><p className="text-2xl font-black mt-1">{ORDERS.filter(o => getEffectivePaymentStatus(o) === 'protection_period').length}</p></div>
      </div>
      <div className="flex gap-3 mb-5">
        <Input icon={<Search size={14} />} placeholder={lang === 'en' ? 'Order ID or buyer...' : 'Mã đơn hoặc người mua...'} value={search} onChange={e => setSearch(e.target.value)} className="w-72" />
        <select className="nexus-input text-sm py-2 w-40">{['all', 'pending', 'processing', 'shipped', 'cancelled'].map(s => <option key={s}>{s === 'all' ? tr(lang, 'all') : statusLabel(s)}</option>)}</select>
      </div>
      <div className="nexus-card">
        <DataTable
          columns={[
            { key: 'id', label: lang === 'en' ? 'Order' : 'Đơn hàng', render: (r: any) => <span className="font-mono text-xs font-semibold">{r.id}</span> },
            { key: 'buyerName', label: lang === 'en' ? 'Buyer' : 'Người mua' },
            { key: 'sellerName', label: lang === 'en' ? 'Seller' : 'Người bán' },
            { key: 'total', label: lang === 'en' ? 'Total' : 'Tổng', render: (r: any) => <span className="font-bold text-primary">{formatCurrency(r.total, lang)}</span> },
            { key: 'status', label: tr(lang, 'status'), render: (r: any) => <StatusBadge status={r.status} label={statusLabel(r.status)} /> },
            {
              key: 'paymentStatus', label: lang === 'en' ? 'Escrow' : 'Tiền giữ hộ', render: (r: any) => {
                const status = paymentOverrides[r.id] || getEffectivePaymentStatus(r)
                return <StatusBadge status={status} label={paymentLabel(status)} />
              }
            },
            { key: 'date', label: tr(lang, 'date'), render: (r: any) => <span className="text-xs">{formatDate(r.createdAt, lang)}</span> },
            { key: 'actions', label: '', render: (r: any) => <Button variant="outline" size="sm" onClick={e => { e.stopPropagation(); setSelectedOrder(r) }}>{lang === 'en' ? 'Review' : 'Kiểm tra'}</Button> },
          ]}
          data={ORDERS}
          rowKey={r => r.id}
          onRowClick={setSelectedOrder}
        />
      </div>

      <div className="nexus-card mt-6">
        <div className="p-5 border-b border-border font-bold">{lang === 'en' ? 'Withdrawal requests' : 'Yêu cầu rút tiền'}</div>
        <div className="p-5 flex items-center justify-between">
          <div>
            <div className="flex items-center gap-3"><span className="font-semibold">TechZone Store</span><StatusBadge status={withdrawalApproved ? 'released' : 'pending'} label={withdrawalApproved ? (lang === 'en' ? 'Approved' : 'Đã duyệt') : (lang === 'en' ? 'Pending review' : 'Chờ duyệt')} /></div>
            <p className="text-sm text-gray-500 mt-1">Vietcombank</p>
            <p className="text-xs text-gray-400 mt-0.5">{lang === 'en' ? 'Account number' : 'Số tài khoản'}: 0123456789</p>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-xl font-black">50.000.000 ₫</span>
            {!withdrawalApproved && <Button variant="primary" size="sm" onClick={() => { setWithdrawalApproved(true); showToast({ type: 'success', message: lang === 'en' ? 'Withdrawal approved and marked for bank transfer.' : 'Đã duyệt yêu cầu và chuyển sang bước chuyển khoản.' }) }}>{lang === 'en' ? 'Approve' : 'Duyệt'}</Button>}
          </div>
        </div>
      </div>

      <Modal open={!!selectedOrder} onClose={() => setSelectedOrder(null)} title={lang === 'en' ? 'Escrow and dispute review' : 'Kiểm tra tiền giữ hộ và khiếu nại'} size="lg">
        {selectedOrder && (
          <div className="space-y-5">
            <div className="grid grid-cols-3 gap-4">
              <div className="p-3 bg-gray-50 rounded-lg"><p className="text-xs text-gray-500">{lang === 'en' ? 'Order' : 'Đơn hàng'}</p><p className="font-mono font-semibold mt-1">{selectedOrder.id}</p></div>
              <div className="p-3 bg-gray-50 rounded-lg"><p className="text-xs text-gray-500">{lang === 'en' ? 'Amount held' : 'Số tiền đang giữ'}</p><p className="font-bold mt-1">{formatCurrency(selectedOrder.total, lang)}</p></div>
              <div className="p-3 bg-gray-50 rounded-lg"><p className="text-xs text-gray-500">{lang === 'en' ? 'Payment status' : 'Trạng thái thanh toán'}</p><div className="mt-1"><StatusBadge status={paymentOverrides[selectedOrder.id] || getEffectivePaymentStatus(selectedOrder)} label={paymentLabel(paymentOverrides[selectedOrder.id] || getEffectivePaymentStatus(selectedOrder))} /></div></div>
            </div>
            {selectedOrder.disputeStatus && selectedOrder.disputeStatus !== 'none' ? (
              <>
                <AlertBanner type="warning" message={lang === 'en' ? 'Funds must remain on hold until a dispute decision is recorded.' : 'Khoản tiền phải tiếp tục được giữ cho đến khi có quyết định xử lý khiếu nại.'} />
                <div><p className="text-sm font-semibold mb-1">{lang === 'en' ? 'Buyer reason' : 'Lý do của Buyer'}</p><p className="text-sm text-gray-600">{selectedOrder.disputeReason || (lang === 'en' ? 'No reason provided.' : 'Chưa cung cấp lý do.')}</p></div>
                <div className="p-4 border border-border rounded-xl bg-gray-50">
                  <div className="flex items-center justify-between mb-2">
                    <p className="text-sm font-semibold">{lang === 'en' ? 'Support review' : 'Đánh giá từ Support'}</p>
                    <StatusBadge status="pending" label={lang === 'en' ? 'Recommendation only' : 'Chỉ là đề xuất'} />
                  </div>
                  <p className="text-sm text-gray-600">{lang === 'en' ? 'Evidence from Buyer and carrier was reviewed. Support recommends a partial refund, pending Admin approval.' : 'Support đã kiểm tra bằng chứng từ Buyer và đơn vị vận chuyển, đồng thời đề xuất hoàn tiền một phần và chờ Admin phê duyệt.'}</p>
                </div>
                <Textarea label={lang === 'en' ? 'Admin notes' : 'Ghi chú của Admin'} rows={3} placeholder={lang === 'en' ? 'Record evidence and decision notes...' : 'Ghi lại bằng chứng và quyết định xử lý...'} />
                <div className="flex flex-wrap gap-2 pt-2 border-t border-border">
                  <Button variant="outline" onClick={() => resolvePayment('refunded')}>{lang === 'en' ? 'Refund Buyer' : 'Hoàn tiền Buyer'}</Button>
                  <Button variant="primary" onClick={() => resolvePayment('released')}>{lang === 'en' ? 'Release Seller' : 'Giải ngân Seller'}</Button>
                  <Button variant="outline" onClick={() => resolvePayment('partial_refund')}>{lang === 'en' ? 'Partial Refund' : 'Hoàn tiền một phần'}</Button>
                </div>
              </>
            ) : (
              <AlertBanner type="info" message={lang === 'en' ? 'No dispute is open. Payment will release automatically after the protection period.' : 'Không có khiếu nại. Khoản tiền sẽ tự động giải ngân sau thời gian bảo vệ.'} />
            )}
          </div>
        )}
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Auctions ────────────────────────────────────────────────────────────
export function AdminAuctionsPage() {
  const { lang, showToast, auctionReviews, reviewAuction, createdAuctions } = useApp()
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState('')
  const [rejectAuctionId, setRejectAuctionId] = useState<string | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const allAuctions = [...createdAuctions, ...AUCTIONS]
  const statusLabel = (s: string) => ({
    pending_approval: lang === 'en' ? 'Pending approval' : 'Chờ phê duyệt',
    scheduled: tr(lang, 'statusScheduled'), active: tr(lang, 'statusActive'), ended: tr(lang, 'statusEnded'),
    settled: tr(lang, 'statusSettled'), cancelled: tr(lang, 'statusCancelled'),
    rejected: lang === 'en' ? 'Rejected' : 'Bị từ chối',
  }[s] || s)
  const updateApproval = (id: string, status: 'scheduled' | 'rejected') => {
    reviewAuction(id, status, status === 'rejected' ? rejectReason : undefined)
    showToast({
      type: status === 'scheduled' ? 'success' : 'error',
      message: status === 'scheduled'
        ? (lang === 'en' ? 'Auction approved and scheduled.' : 'Đã phê duyệt và lên lịch phiên đấu giá.')
        : (lang === 'en' ? 'Auction rejected.' : 'Đã từ chối phiên đấu giá.'),
    })
    if (status === 'rejected') {
      setRejectAuctionId(null)
      setRejectReason('')
    }
  }
  return (
    <AdminLayout title={tr(lang, 'auctionManagement')}>
      <div className="mb-5">
        <AlertBanner type="info" message={lang === 'en' ? `${allAuctions.filter(auction => (auctionReviews[auction.id]?.status || auction.status) === 'pending_approval').length} auction(s) are waiting for approval.` : `${allAuctions.filter(auction => (auctionReviews[auction.id]?.status || auction.status) === 'pending_approval').length} phiên đấu giá đang chờ phê duyệt.`} />
      </div>
      <div className="nexus-card">
        <DataTable
          columns={[
            { key: 'id', label: 'ID', render: (r: any) => <span className="font-mono text-xs">{r.id}</span> },
            { key: 'product', label: lang === 'en' ? 'Product' : 'Sản phẩm', render: (r: any) => <span className="font-semibold text-sm">{lang === 'vi' ? r.product.nameVi : r.product.name}</span> },
            { key: 'seller', label: lang === 'en' ? 'Seller' : 'Người bán', render: (r: any) => <span>{r.sellerName}</span> },
            { key: 'currentBid', label: tr(lang, 'currentBid'), render: (r: any) => <span className="font-bold text-auction">{formatCurrency(r.currentBid, lang)}</span> },
            { key: 'bidCount', label: lang === 'en' ? 'Bids' : 'Lượt', render: (r: any) => <span>{r.bidCount}</span> },
            {
              key: 'status', label: tr(lang, 'status'), render: (r: any) => {
                const status = auctionReviews[r.id]?.status || r.status
                return <StatusBadge status={status} label={statusLabel(status)} />
              }
            },
            {
              key: 'actions', label: '', render: (r: any) => {
                const status = auctionReviews[r.id]?.status || r.status
                return status === 'pending_approval' ? (
                  <div className="flex gap-2">
                    <Button variant="primary" size="sm" onClick={e => { e.stopPropagation(); updateApproval(r.id, 'scheduled') }}>{lang === 'en' ? 'Approve' : 'Phê duyệt'}</Button>
                    <Button variant="outline" size="sm" onClick={e => { e.stopPropagation(); setRejectAuctionId(r.id) }}>{lang === 'en' ? 'Reject' : 'Từ chối'}</Button>
                  </div>
                ) : ['scheduled', 'active'].includes(status) ? (
                  <Button variant="danger" size="sm" onClick={e => { e.stopPropagation(); setCancelOpen(true) }}>{tr(lang, 'forceCancelAuction')}</Button>
                ) : null
              }
            },
          ]}
          data={allAuctions}
          rowKey={r => r.id}
        />
      </div>
      <Modal open={cancelOpen} onClose={() => setCancelOpen(false)} title={tr(lang, 'forceCancelAuction')} size="sm"
        footer={<><Button variant="outline" onClick={() => setCancelOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="danger" onClick={() => { if (!cancelReason) return; setCancelOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Auction force-cancelled.' : 'Đã buộc hủy phiên đấu giá.' }) }}>Force Cancel</Button></>}>
        <AlertBanner type="warning" message={lang === 'en' ? 'This will immediately end the auction and refund all bidders.' : 'Thao tác này sẽ kết thúc ngay phiên đấu giá và hoàn tiền cho tất cả người đặt giá.'} />
        <div className="mt-4"><Textarea label={`${tr(lang, 'reason')} *`} value={cancelReason} onChange={e => setCancelReason(e.target.value)} rows={3} placeholder={lang === 'en' ? 'Reason is required...' : 'Lý do là bắt buộc...'} /></div>
      </Modal>
      <Modal open={!!rejectAuctionId} onClose={() => { setRejectAuctionId(null); setRejectReason('') }} title={lang === 'en' ? 'Reject auction' : 'Từ chối phiên đấu giá'} size="sm"
        footer={<><Button variant="outline" onClick={() => { setRejectAuctionId(null); setRejectReason('') }}>{tr(lang, 'cancel')}</Button><Button variant="danger" disabled={!rejectReason.trim()} onClick={() => rejectAuctionId && updateApproval(rejectAuctionId, 'rejected')}>{lang === 'en' ? 'Reject Auction' : 'Từ chối đấu giá'}</Button></>}>
        <AlertBanner type="warning" message={lang === 'en' ? 'The Seller will see this decision and rejection reason.' : 'Seller sẽ nhìn thấy quyết định và lý do từ chối này.'} />
        <div className="mt-4"><Textarea label={`${tr(lang, 'reason')} *`} value={rejectReason} onChange={e => setRejectReason(e.target.value)} rows={3} placeholder={lang === 'en' ? 'Explain what the Seller needs to change...' : 'Giải thích nội dung Seller cần chỉnh sửa...'} /></div>
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Reputation ──────────────────────────────────────────────────────────
export function AdminReputationPage() {
  const { lang, showToast } = useApp()
  const [penaltyOpen, setPenaltyOpen] = useState(false)
  const [adjustOpen, setAdjustOpen] = useState(false)
  return (
    <AdminLayout title={tr(lang, 'reputationManagement')}>
      <div className="grid grid-cols-2 gap-6">
        <div className="nexus-card">
          <div className="p-4 border-b border-border font-bold">{lang === 'en' ? 'User Reputation Scores' : 'Điểm uy tín người dùng'}</div>
          <DataTable
            columns={[
              { key: 'user', label: lang === 'en' ? 'User' : 'Người dùng', render: (r: any) => <div className="flex items-center gap-2"><img src={r.avatar} alt="" className="w-7 h-7 rounded-full object-cover" /><span className="font-semibold text-sm">{r.fullName}</span></div> },
              { key: 'score', label: lang === 'en' ? 'Score' : 'Điểm', render: (r: any) => <span className="font-bold text-primary">{r.reputationScore}</span> },
              { key: 'trust', label: lang === 'en' ? 'Level' : 'Cấp', render: (r: any) => <StatusBadge status={r.trustLevel} label={r.trustLevel.charAt(0).toUpperCase() + r.trustLevel.slice(1)} /> },
              {
                key: 'actions', label: '', render: () => (
                  <div className="flex gap-1">
                    <button onClick={() => setPenaltyOpen(true)} className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"><AlertTriangle size={13} /></button>
                    <button onClick={() => setAdjustOpen(true)} className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5"><Pencil size={13} /></button>
                  </div>
                )
              },
            ]}
            data={USERS}
            rowKey={r => r.id}
          />
        </div>
        <div className="nexus-card">
          <div className="p-4 border-b border-border font-bold">{lang === 'en' ? 'Recent Penalties' : 'Hình phạt gần đây'}</div>
          <div className="p-4">
            {[
              { user: 'User#12345', reason: 'Non-payment after winning auction', points: -10, date: '2024-02-20' },
              { user: 'User#67890', reason: 'Order cancellation × 3', points: -5, date: '2024-02-18' },
            ].map((p, i) => (
              <div key={i} className="py-3 border-b border-border last:border-0">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-semibold text-sm">{p.user}</span>
                  <span className="font-bold text-danger">{p.points} pts</span>
                </div>
                <p className="text-xs text-gray-500">{p.reason}</p>
                <p className="text-xs text-gray-400 mt-0.5">{p.date}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
      <Modal open={penaltyOpen} onClose={() => setPenaltyOpen(false)} title={tr(lang, 'applyPenalty')} size="sm"
        footer={<><Button variant="outline" onClick={() => setPenaltyOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="danger" onClick={() => { setPenaltyOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Penalty applied.' : 'Đã áp dụng hình phạt.' }) }}>{tr(lang, 'applyPenalty')}</Button></>}>
        <Input label={lang === 'en' ? 'Points to deduct' : 'Số điểm trừ'} type="number" placeholder="-10" />
        <div className="mt-3"><Textarea label={tr(lang, 'reason')} rows={2} /></div>
      </Modal>
      <Modal open={adjustOpen} onClose={() => setAdjustOpen(false)} title={tr(lang, 'adjustReputation')} size="sm"
        footer={<><Button variant="outline" onClick={() => setAdjustOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setAdjustOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Reputation adjusted.' : 'Đã điều chỉnh điểm uy tín.' }) }}>{tr(lang, 'save')}</Button></>}>
        <Input label={lang === 'en' ? 'Adjust by (±)' : 'Điều chỉnh (±)'} type="number" placeholder="+5 or -5" />
        <div className="mt-3"><Textarea label={tr(lang, 'reason')} rows={2} /></div>
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Event Logs ──────────────────────────────────────────────────────────
export function AdminEventLogsPage() {
  const { lang } = useApp()
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('all')
  const filtered = EVENT_LOGS.filter(e => (typeFilter === 'all' || e.type === typeFilter) && (!search || e.message.toLowerCase().includes(search.toLowerCase()) || e.operator.includes(search)))
  return (
    <AdminLayout title={tr(lang, 'eventLogs')}>
      <div className="flex gap-3 mb-5">
        <Input icon={<Search size={14} />} placeholder={lang === 'en' ? 'Search events...' : 'Tìm sự kiện...'} value={search} onChange={e => setSearch(e.target.value)} className="w-72" />
        <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)} className="nexus-input text-sm py-2 w-32">
          {['all', 'AUCTION', 'ORDER', 'USER', 'SYSTEM'].map(t => <option key={t} value={t}>{t === 'all' ? tr(lang, 'all') : t}</option>)}
        </select>
      </div>
      <div className="nexus-card">
        {filtered.length === 0 ? (
          <div className="p-8"><EmptyState icon={<ClipboardList size={20} />} title={tr(lang, 'noData')} /></div>
        ) : (
          <DataTable
            columns={[
              { key: 'type', label: lang === 'en' ? 'Type' : 'Loại', render: (r: any) => <span className="badge badge-indigo text-xs">{r.type}</span> },
              { key: 'action', label: tr(lang, 'action'), render: (r: any) => <span className="font-semibold text-sm">{r.action}</span> },
              { key: 'message', label: tr(lang, 'message'), render: (r: any) => <span className="text-xs text-gray-600">{r.message}</span> },
              { key: 'operator', label: tr(lang, 'operator'), render: (r: any) => <span className="text-xs">{r.operator}</span> },
              { key: 'timestamp', label: tr(lang, 'date'), render: (r: any) => <span className="text-xs text-gray-400">{new Date(r.timestamp).toLocaleString()}</span> },
            ]}
            data={filtered}
            rowKey={r => r.id}
          />
        )}
      </div>
    </AdminLayout>
  )
}

// ─── Admin Audit Logs ──────────────────────────────────────────────────────────
export function AdminAuditLogsPage() {
  const { lang } = useApp()
  return (
    <AdminLayout title={tr(lang, 'auditLogs')}>
      <div className="nexus-card">
        <div className="p-4 border-b border-border text-sm text-gray-500">{lang === 'en' ? 'Read-only audit trail of all system changes.' : 'Nhật ký kiểm tra chỉ đọc của tất cả thay đổi hệ thống.'}</div>
        <DataTable
          columns={[
            { key: 'type', label: lang === 'en' ? 'Type' : 'Loại', render: (r: any) => <span className="badge badge-slate text-xs">{r.type}</span> },
            { key: 'action', label: tr(lang, 'action'), render: (r: any) => <span className="font-semibold text-sm">{r.action}</span> },
            { key: 'message', label: tr(lang, 'message'), render: (r: any) => <span className="text-xs text-gray-600">{r.message}</span> },
            { key: 'operator', label: tr(lang, 'operator') },
            { key: 'timestamp', label: tr(lang, 'date'), render: (r: any) => <span className="text-xs text-gray-400">{new Date(r.timestamp).toLocaleString()}</span> },
          ]}
          data={EVENT_LOGS}
          rowKey={r => r.id}
        />
      </div>
    </AdminLayout>
  )
}

// ─── Admin System Config ────────────────────────────────────────────────────────
export function AdminSystemConfigPage() {
  const { lang, showToast } = useApp()
  const CONFIG_GROUPS = [
    { title: lang === 'en' ? 'Security' : 'Bảo mật', items: [{ key: 'maxLoginAttempts', label: lang === 'en' ? 'Max login attempts' : 'Số lần đăng nhập tối đa', type: 'number', value: '5' }, { key: 'sessionTimeout', label: lang === 'en' ? 'Session timeout (min)' : 'Hết phiên (phút)', type: 'number', value: '60' }] },
    { title: lang === 'en' ? 'Auction' : 'Đấu giá', items: [{ key: 'minDuration', label: lang === 'en' ? 'Min duration (min)' : 'Thời gian tối thiểu (phút)', type: 'number', value: '60' }, { key: 'maxDuration', label: lang === 'en' ? 'Max duration (hours)' : 'Thời gian tối đa (giờ)', type: 'number', value: '168' }, { key: 'antiSniping', label: lang === 'en' ? 'Anti-sniping extension (min)' : 'Gia hạn chống sniping (phút)', type: 'number', value: '5' }] },
    { title: lang === 'en' ? 'Commerce' : 'Thương mại', items: [{ key: 'minReputation', label: lang === 'en' ? 'Min reputation to bid' : 'Điểm uy tín tối thiểu để đặt giá', type: 'number', value: '30' }, { key: 'paymentDeadline', label: lang === 'en' ? 'Auction payment deadline (hours)' : 'Hạn thanh toán (giờ)', type: 'number', value: '24' }] },
    { title: 'Feature Flags', items: [{ key: 'enableAuctions', label: lang === 'en' ? 'Auctions enabled' : 'Cho phép đấu giá', type: 'toggle', value: 'true' }, { key: 'enableSellers', label: lang === 'en' ? 'New sellers allowed' : 'Cho phép người bán mới', type: 'toggle', value: 'true' }, { key: 'maintenanceMode', label: lang === 'en' ? 'Maintenance mode' : 'Chế độ bảo trì', type: 'toggle', value: 'false' }] },
  ]
  return (
    <AdminLayout title={tr(lang, 'systemConfig')}>
      <div className="max-w-3xl space-y-5">
        {CONFIG_GROUPS.map(group => (
          <div key={group.title} className="nexus-card">
            <div className="p-4 border-b border-border font-bold">{group.title}</div>
            <div className="p-5 space-y-4">
              {group.items.map(item => (
                <div key={item.key} className="flex items-center justify-between">
                  <label className="text-sm font-medium text-gray-700">{item.label}</label>
                  {item.type === 'toggle' ? (
                    <Toggle checked={item.value === 'true'} onChange={() => { }} />
                  ) : (
                    <input type={item.type} defaultValue={item.value} className="nexus-input w-28 text-right" />
                  )}
                </div>
              ))}
            </div>
          </div>
        ))}
        <Button variant="primary" size="lg" onClick={() => showToast({ type: 'success', message: lang === 'en' ? 'Configuration saved!' : 'Đã lưu cấu hình!' })}>{tr(lang, 'save')}</Button>
      </div>
    </AdminLayout>
  )
}

// ─── Admin System Health ───────────────────────────────────────────────────────
export function AdminSystemHealthPage() {
  const { lang } = useApp()
  return (
    <AdminLayout title={tr(lang, 'systemHealth')}>
      <div className="grid grid-cols-2 gap-5 mb-6">
        {SYSTEM_SERVICES.map(s => (
          <div key={s.name} className={`nexus-card p-4 border-l-4 ${s.status === 'up' ? 'border-success' : 'border-danger'}`}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Server size={18} className={s.status === 'up' ? 'text-success' : 'text-danger'} />
                <div><p className="font-semibold text-sm">{s.name}</p>{s.latency && <p className="text-xs text-gray-400">{s.latency}ms</p>}</div>
              </div>
              <StatusBadge status={s.status === 'up' ? 'delivered' : 'cancelled'} label={s.status === 'up' ? tr(lang, 'serviceUp') : tr(lang, 'serviceDown')} />
            </div>
          </div>
        ))}
      </div>
      <div className="nexus-card">
        <div className="p-4 border-b border-border font-bold">{lang === 'en' ? 'Recent Incidents' : 'Sự cố gần đây'}</div>
        <div className="p-5">
          <div className="flex items-start gap-4 py-3 border-b border-border">
            <AlertTriangle size={18} className="text-gray-500 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-sm">{lang === 'en' ? 'Email Service Degradation' : 'Dịch vụ email bị suy giảm'}</p>
              <p className="text-xs text-gray-500">{lang === 'en' ? 'Email delivery delayed by 5-10 minutes. Engineers investigating.' : 'Gửi email bị chậm 5-10 phút. Kỹ sư đang điều tra.'}</p>
              <p className="text-xs text-gray-400 mt-1">2024-02-22 14:30 UTC</p>
            </div>
          </div>
          <div className="flex items-start gap-4 py-3">
            <CheckCircle size={18} className="text-gray-500 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-sm">{lang === 'en' ? 'Database maintenance completed' : 'Bảo trì cơ sở dữ liệu hoàn tất'}</p>
              <p className="text-xs text-gray-400 mt-1">2024-02-20 02:00–03:30 UTC</p>
            </div>
          </div>
        </div>
      </div>
    </AdminLayout>
  )
}

// ─── Admin Products (Moderation) ───────────────────────────────────────────────
export function AdminProductsPage() {
  const { lang, showToast } = useApp()
  const statusLabel = (s: string) => ({ draft: tr(lang, 'draft'), active: tr(lang, 'active'), inactive: tr(lang, 'inactive') }[s] || s)
  return (
    <AdminLayout title={tr(lang, 'productModeration')}>
      <div className="nexus-card">
        <DataTable
          columns={[
            { key: 'img', label: '', width: '56px', render: (r: any) => <img src={r.images[0]} alt="" className="w-10 h-10 object-cover rounded-lg" /> },
            { key: 'name', label: lang === 'en' ? 'Product' : 'Sản phẩm', render: (r: any) => <div><p className="font-semibold text-sm">{lang === 'vi' ? r.nameVi : r.name}</p><p className="text-xs text-gray-400">{r.sku}</p></div> },
            { key: 'price', label: tr(lang, 'price'), render: (r: any) => <span className="font-bold">{formatCurrency(r.price, lang)}</span> },
            { key: 'sellerName', label: lang === 'en' ? 'Seller' : 'Người bán' },
            { key: 'status', label: tr(lang, 'status'), render: (r: any) => <StatusBadge status={r.status} label={statusLabel(r.status)} /> },
            {
              key: 'actions', label: '', render: (r: any) => (
                <div className="flex gap-1">
                  <Button variant="outline" size="sm" onClick={() => showToast({ type: 'success', message: 'Product approved.' })}>{lang === 'en' ? 'Approve' : 'Duyệt'}</Button>
                  <Button variant="danger" size="sm" onClick={() => showToast({ type: 'error', message: 'Product rejected.' })}>{lang === 'en' ? 'Reject' : 'Từ chối'}</Button>
                </div>
              )
            },
          ]}
          data={PRODUCTS}
          rowKey={r => r.id}
        />
      </div>
    </AdminLayout>
  )
}

// ─── Admin Warehouses ──────────────────────────────────────────────────────────
export function AdminWarehousesPage() {
  const { lang, showToast } = useApp()
  const [adjustOpen, setAdjustOpen] = useState(false)
  return (
    <AdminLayout title={tr(lang, 'warehouseManagement')}>
      <div className="nexus-card mb-5">
        <DataTable
          columns={[
            { key: 'sku', label: 'SKU', render: (r: any) => <span className="font-mono text-xs">{r.sku}</span> },
            { key: 'name', label: lang === 'en' ? 'Product' : 'Sản phẩm', render: (r: any) => <span className="font-semibold text-sm">{lang === 'vi' ? r.nameVi : r.name}</span> },
            { key: 'stock', label: lang === 'en' ? 'Total' : 'Tổng', render: (r: any) => <span className="font-bold">{r.stock}</span> },
            { key: 'available', label: tr(lang, 'totalAvailable'), render: (r: any) => <span className="text-success font-semibold">{r.stock - 2}</span> },
            { key: 'reserved', label: tr(lang, 'totalReserved'), render: () => <span className="text-warning">2</span> },
            { key: 'actions', label: '', render: () => <Button variant="outline" size="sm" onClick={() => setAdjustOpen(true)}>{lang === 'en' ? 'Adjust Stock' : 'Điều chỉnh kho'}</Button> },
          ]}
          data={PRODUCTS}
          rowKey={r => r.id}
        />
      </div>
      <Modal open={adjustOpen} onClose={() => setAdjustOpen(false)} title={lang === 'en' ? 'Adjust Stock' : 'Điều chỉnh kho'} size="sm"
        footer={<><Button variant="outline" onClick={() => setAdjustOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setAdjustOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Stock adjusted.' : 'Đã điều chỉnh kho.' }) }}>{tr(lang, 'save')}</Button></>}>
        <Input label={lang === 'en' ? 'Adjustment (±)' : 'Điều chỉnh (±)'} type="number" placeholder="+10 or -5" />
        <div className="mt-3"><Textarea label={tr(lang, 'reason')} rows={2} /></div>
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Shipments ───────────────────────────────────────────────────────────
export function AdminShipmentsPage() {
  const { lang, showToast } = useApp()
  const [overrideOpen, setOverrideOpen] = useState(false)
  const SHIPMENTS = [
    { id: 'SHP-001', orderId: 'ORD-20240215-001', carrier: 'GHN', tracking: 'GHN123456789VN', status: 'delivered', buyer: 'Minh Tran', date: '2024-02-18' },
    { id: 'SHP-002', orderId: 'ORD-20240220-002', carrier: 'GHTK', tracking: 'GHTK987654321VN', status: 'in_transit', buyer: 'Minh Tran', date: '2024-02-22' },
  ]
  const statusLabel = (s: string) => ({ pending: 'Pending', picked_up: 'Picked Up', in_transit: 'In Transit', out_for_delivery: 'Out for Delivery', delivered: 'Delivered', failed: 'Failed', returned: 'Returned' }[s] || s)
  return (
    <AdminLayout title={tr(lang, 'shipmentManagement')}>
      <div className="nexus-card mb-5">
        <DataTable
          columns={[
            { key: 'id', label: 'Shipment ID', render: (r: any) => <span className="font-mono text-xs">{r.id}</span> },
            { key: 'orderId', label: lang === 'en' ? 'Order' : 'Đơn hàng', render: (r: any) => <span className="font-mono text-xs">{r.orderId}</span> },
            { key: 'carrier', label: tr(lang, 'carrier') },
            { key: 'tracking', label: tr(lang, 'trackingNumber'), render: (r: any) => <span className="font-mono text-xs">{r.tracking}</span> },
            { key: 'status', label: tr(lang, 'status'), render: (r: any) => <StatusBadge status={r.status === 'delivered' ? 'delivered' : r.status === 'in_transit' ? 'shipped' : 'pending'} label={statusLabel(r.status)} /> },
            { key: 'buyer', label: lang === 'en' ? 'Buyer' : 'Người mua' },
            { key: 'actions', label: '', render: () => <Button variant="outline" size="sm" onClick={() => setOverrideOpen(true)}>{tr(lang, 'manualOverride')}</Button> },
          ]}
          data={SHIPMENTS}
          rowKey={r => r.id}
        />
      </div>
      <Modal open={overrideOpen} onClose={() => setOverrideOpen(false)} title={tr(lang, 'manualOverride')} size="sm"
        footer={<><Button variant="outline" onClick={() => setOverrideOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="primary" onClick={() => { setOverrideOpen(false); showToast({ type: 'success', message: lang === 'en' ? 'Status overridden.' : 'Đã ghi đè trạng thái.' }) }}>{tr(lang, 'save')}</Button></>}>
        <Select label={lang === 'en' ? 'New Status' : 'Trạng thái mới'} value="in_transit" onChange={() => { }} options={[{ value: 'in_transit', label: 'In Transit' }, { value: 'delivered', label: 'Delivered' }, { value: 'failed', label: 'Failed' }]} />
        <div className="mt-3"><Textarea label={tr(lang, 'reason')} rows={2} /></div>
      </Modal>
    </AdminLayout>
  )
}

// ─── Admin Profile ─────────────────────────────────────────────────────────────
export function AdminProfilePage() {
  const { lang, user, showToast } = useApp()
  const [saving, setSaving] = useState(false)
  if (!user) return null

  const save = async () => {
    setSaving(true)
    await new Promise(resolve => setTimeout(resolve, 600))
    setSaving(false)
    showToast({ type: 'success', message: lang === 'en' ? 'Admin profile updated.' : 'Đã cập nhật hồ sơ Admin.' })
  }

  return (
    <AdminLayout title={lang === 'en' ? 'Profile' : 'Hồ sơ'}>
      <div className="max-w-3xl">
        <div className="flex items-center gap-5 mb-6">
          <img src={user.avatar} alt="" className="w-16 h-16 rounded-xl object-cover" />
          <div>
            <h2 className="text-xl font-bold">{user.fullName}</h2>
            <p className="text-sm text-gray-500">{user.email}</p>
            <div className="mt-2"><StatusBadge status="active" label={lang === 'en' ? 'Administrator' : 'Quản trị viên'} /></div>
          </div>
        </div>
        <div className="nexus-card p-6">
          <h3 className="font-bold mb-5">{lang === 'en' ? 'Account information' : 'Thông tin tài khoản'}</h3>
          <div className="grid grid-cols-2 gap-4">
            <Input label={tr(lang, 'fullName')} defaultValue={user.fullName} />
            <Input label={tr(lang, 'email')} type="email" defaultValue={user.email} />
            <Input label={tr(lang, 'phone')} defaultValue={user.phone} />
            <Input label={lang === 'en' ? 'Role' : 'Vai trò'} defaultValue="Admin" disabled />
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
    </AdminLayout>
  )
}
